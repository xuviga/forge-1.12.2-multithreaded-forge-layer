/*
 * Minecraft Forge
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 */

package net.minecraftforge.common.threading;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Legacy compatibility bridge for region workers.
 *
 * Existing mods use @SubscribeEvent without a thread type (MAIN). When a region worker ticks an entity that fires
 * such an event, the handler is forwarded to the owning main/server thread and the worker blocks until it completes.
 * The main thread pumps this queue while it waits at a region/dimension barrier, so synchronous semantics (cancel,
 * result) are preserved without running untrusted handlers concurrently.
 */
public final class MainThreadDispatcher
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");
    private static final ConcurrentLinkedQueue<Task> QUEUE = new ConcurrentLinkedQueue<Task>();
    private static final long DISPATCH_TIMEOUT_SECONDS = 30L;
    private static final long FORWARD_WAIT_MILLIS =
            Long.getLong("forge.threading.forwardWaitMs", 200L).longValue();
    private static final java.util.concurrent.atomic.AtomicLong INLINE_FALLBACKS = new java.util.concurrent.atomic.AtomicLong();
    private static volatile Thread owner;

    private MainThreadDispatcher()
    {
    }

    public static void markOwner()
    {
        owner = Thread.currentThread();
    }

    public static boolean hasOwner()
    {
        return owner != null;
    }

    public static boolean isOwnerThread()
    {
        return Thread.currentThread() == owner;
    }

    public static void dispatch(Runnable runnable)
    {
        Thread thread = owner;
        if (thread == null || Thread.currentThread() == thread)
        {
            runnable.run();
            return;
        }

        Task task = new Task(runnable);
        QUEUE.add(task);
        ThreadingTelemetry.increment("dispatch.tasks");
        if (!task.await(FORWARD_WAIT_MILLIS, TimeUnit.MILLISECONDS))
        {
            // The owner is inside a region barrier and may be slow to pump; run the handler inline once. The Task's
            // atomic guard makes this safe if the owner takes it concurrently, and prevents region stalls.
            QUEUE.remove(task);
            ThreadingTelemetry.increment("dispatch.fallbacks");
            long count = INLINE_FALLBACKS.incrementAndGet();
            if (count <= 5L || count % 1000L == 0L)
            {
                LOGGER.warn("[thread-dispatch] main thread did not run a forwarded handler in {} ms; running it on {} (fallback #{})",
                        Long.valueOf(FORWARD_WAIT_MILLIS), Thread.currentThread().getName(), Long.valueOf(count));
            }
            task.run();
            task.rethrow();
        }
        else
        {
            ThreadingTelemetry.increment("dispatch.forwarded");
        }
    }

    public static int getQueueSize()
    {
        return QUEUE.size();
    }

    public static int runPending()
    {
        // Only the owner may execute forwarded MAIN handlers. A dimension worker or region worker that pumps this
        // queue would run another world's handler on the wrong thread and break the single-thread contract.
        if (owner == null || Thread.currentThread() != owner)
        {
            return 0;
        }

        int executed = 0;
        Task task;
        while ((task = QUEUE.poll()) != null)
        {
            task.run();
            executed++;
        }
        return executed;
    }

    /**
     * Waits for futures while pumping forwarded MAIN-thread handlers. Used by every region/dimension barrier.
     */
    public static void awaitAll(List<Future<?>> futures, String what)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DISPATCH_TIMEOUT_SECONDS);

        while (true)
        {
            runPending();

            boolean allDone = true;
            for (int index = 0; index < futures.size(); index++)
            {
                if (!futures.get(index).isDone())
                {
                    allDone = false;
                    break;
                }
            }

            if (allDone)
            {
                break;
            }

            if (System.nanoTime() > deadline)
            {
                throw new RuntimeException("Timed out waiting for " + what);
            }

            try
            {
                Thread.sleep(0L, 200000);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for " + what, interrupted);
            }
        }

        for (int index = 0; index < futures.size(); index++)
        {
            try
            {
                futures.get(index).get();
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for " + what, interrupted);
            }
            catch (java.util.concurrent.CancellationException cancelled)
            {
                // A path/AI task was cancelled (e.g. PathNavigate.clearPath); it is already done.
            }
            catch (java.util.concurrent.ExecutionException execution)
            {
                Throwable cause = execution.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException)cause;
                if (cause instanceof Error) throw (Error)cause;
                throw new RuntimeException(cause);
            }
        }
    }

    /**
     * Joins a helper thread while pumping forwarded handlers. Used by the self-test.
     */
    public static void join(Thread thread, long timeoutMillis)
    {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (thread.isAlive() && System.nanoTime() < deadline)
        {
            runPending();
            try
            {
                Thread.sleep(1L);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static final class Task implements Runnable
    {
        private final Runnable delegate;
        private final CountDownLatch done = new CountDownLatch(1);
        private final java.util.concurrent.atomic.AtomicBoolean executed = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile Throwable error;

        private Task(Runnable delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public void run()
        {
            if (!executed.compareAndSet(false, true))
            {
                return;
            }
            try
            {
                delegate.run();
            }
            catch (Throwable throwable)
            {
                error = throwable;
            }
            finally
            {
                done.countDown();
            }
        }

        private boolean await(long timeout, TimeUnit unit)
        {
            try
            {
                if (!done.await(timeout, unit))
                {
                    return false;
                }
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                return false;
            }

            rethrow();
            return true;
        }

        private void await()
        {
            if (!await(DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            {
                LOGGER.error("[thread-dispatch] timed out waiting for the main thread to run a forwarded handler");
            }
        }

        private void rethrow()
        {
            Throwable throwable = error;
            if (throwable != null)
            {
                if (throwable instanceof RuntimeException) throw (RuntimeException)throwable;
                if (throwable instanceof Error) throw (Error)throwable;
                throw new RuntimeException(throwable);
            }
        }
    }
}
