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

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkGeneratorOverworld;
import net.minecraft.world.gen.ChunkProviderServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Single-worker, out-of-tick chunk generation for the overworld generator.
 *
 * <p>The 1.12.2 overworld generator keeps mutable scratch state ({@code rand}, height/depth buffers,
 * {@code biomesForGeneration}) and its structure generators keep caches. Running two generations at once corrupts
 * that state, and running a generation on the server thread turns a chunk border into a 50-200 ms tick. This class
 * therefore serialises every generator call behind {@link #worldGenLock()}:
 *
 * <ul>
 *   <li>one background worker generates {@link net.minecraft.world.gen.IChunkGenerator#generateChunk(int, int)};</li>
 *   <li>the main thread commits the finished chunk and posts its {@code populate} to a bounded per-tick pump;</li>
 *   <li>the pump uses {@code tryLock}: if the worker is mid-generation the populate is retried next tick instead of
 *       blocking the server thread;</li>
 *   <li>the old synchronous path still takes the same lock, so a {@code World#getChunk} request can never race the
 *       worker.</li>
 * </ul>
 *
 * <p>Only the vanilla overworld generator is offloaded. Modded/replaced generators keep the synchronous path
 * because their thread-safety cannot be verified from here.
 *
 * <p>Disable with {@code -Dforge.threading.asyncChunkGen=false}. Tunables:
 * {@code forge.threading.chunkGenThreads} (default 1), {@code forge.threading.chunkGenQueue} (512),
 * {@code forge.threading.chunkPostBudgetMs} (8), {@code forge.threading.chunkPostPerTick} (2).
 */
public final class ChunkGenExecutor
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");

    private static final boolean ENABLED = !Threading.isSingleThreaded() &&
            Boolean.parseBoolean(System.getProperty("forge.threading.asyncChunkGen", "true"));
    private static final int THREADS = Math.max(1, Integer.getInteger("forge.threading.chunkGenThreads", 1).intValue());
    private static final int MAX_PENDING = Math.max(64, Integer.getInteger("forge.threading.chunkGenQueue", 512).intValue());
    private static final long POST_BUDGET_NS = TimeUnit.MILLISECONDS.toNanos(
            Math.max(1L, Long.getLong("forge.threading.chunkPostBudgetMs", 8L).longValue()));
    private static final int POST_PER_TICK = Math.max(1, Integer.getInteger("forge.threading.chunkPostPerTick", 2).intValue());
    private static final int HARVEST_PER_TICK = Math.max(1, Integer.getInteger("forge.threading.chunkHarvestPerTick", 4).intValue());

    /** Guards every call into the overworld generator (generation and populate). */
    private static final ReentrantLock WORLDGEN = new ReentrantLock();

    private static final ConcurrentHashMap<GenKey, GenTask> TASKS = new ConcurrentHashMap<GenKey, GenTask>();
    private static final ConcurrentLinkedQueue<PostTask> POSTS = new ConcurrentLinkedQueue<PostTask>();

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicLong SUBMITTED = new AtomicLong();
    private static final AtomicLong GENERATED = new AtomicLong();
    private static final AtomicLong POSTED = new AtomicLong();
    private static final AtomicLong POST_DONE = new AtomicLong();
    private static final AtomicLong POST_DEFERRED = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong FAILED = new AtomicLong();

    private static final ThreadPoolExecutor POOL;

    static
    {
        ThreadFactory factory = new ThreadFactory()
        {
            private final AtomicInteger count = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable)
            {
                Thread thread = new Thread(runnable, "Chunk Gen Worker-" + this.count.getAndIncrement());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            }
        };
        POOL = new ThreadPoolExecutor(THREADS, THREADS, 30L, TimeUnit.SECONDS,
                new PriorityBlockingQueue<Runnable>(), factory);
        POOL.allowCoreThreadTimeOut(true);
        ThreadingTelemetry.registerPool("chunkgen", POOL);
    }

    private ChunkGenExecutor()
    {
    }

    public static boolean isEnabled()
    {
        return ENABLED;
    }

    /**
     * True when this provider can be generated on the worker. The vanilla overworld generator is the only one whose
     * state model has been audited; everything else must keep the synchronous path.
     */
    public static boolean supports(ChunkProviderServer provider)
    {
        return ENABLED && provider != null && provider.world != null && !provider.world.isRemote
                && provider.chunkGenerator != null
                && provider.chunkGenerator.getClass() == ChunkGeneratorOverworld.class;
    }

    public static boolean isPending(ChunkProviderServer provider, int x, int z)
    {
        return ENABLED && provider != null && TASKS.containsKey(new GenKey(provider, x, z));
    }

    public static void addCallback(ChunkProviderServer provider, int x, int z, Runnable callback)
    {
        if (callback == null || provider == null)
        {
            return;
        }
        GenTask task = TASKS.get(new GenKey(provider, x, z));
        if (task != null)
        {
            task.callbacks.add(callback);
        }
    }

    /**
     * Queues a chunk for out-of-tick generation. Returns true when the chunk is now owned by the executor (queued,
     * already generating or already queued). Returns false when async generation is unavailable and the caller must
     * use the synchronous path.
     */
    public static boolean submit(ChunkProviderServer provider, int x, int z, Runnable callback, int priority, boolean unloadWhenDone)
    {
        if (!supports(provider))
        {
            return false;
        }

        GenKey key = new GenKey(provider, x, z);
        GenTask existing = TASKS.get(key);
        if (existing != null)
        {
            if (callback != null)
            {
                existing.callbacks.add(callback);
            }
            return true;
        }

        // Live player requests (priority 0) are never dropped: the player chunk map already rate-limits submissions.
        // The cap only protects against a runaway pregen queue.
        if (priority >= 50 && TASKS.size() >= MAX_PENDING)
        {
            DROPPED.incrementAndGet();
            return false;
        }

        GenTask task = new GenTask(key, x, z, priority, unloadWhenDone);
        if (callback != null)
        {
            task.callbacks.add(callback);
        }

        GenTask raced = TASKS.putIfAbsent(key, task);
        if (raced != null)
        {
            if (callback != null)
            {
                raced.callbacks.add(callback);
            }
            return true;
        }

        SUBMITTED.incrementAndGet();
        try
        {
            POOL.execute(task);
        }
        catch (Throwable throwable)
        {
            TASKS.remove(key, task);
            FAILED.incrementAndGet();
            LOGGER.error("[chunkgen] could not queue chunk ({}, {})", Integer.valueOf(x), Integer.valueOf(z), throwable);
            return false;
        }
        return true;
    }

    /** Removes one callback; the task is cancelled when nobody is waiting for it anymore. */
    public static void drop(ChunkProviderServer provider, int x, int z, Runnable callback)
    {
        if (provider == null)
        {
            return;
        }
        GenKey key = new GenKey(provider, x, z);
        GenTask task = TASKS.get(key);
        if (task == null)
        {
            return;
        }
        if (callback != null)
        {
            task.callbacks.remove(callback);
        }
        if (task.callbacks.isEmpty() && !task.unloadWhenDone)
        {
            task.cancelled = true;
            if (POOL.remove(task))
            {
                TASKS.remove(key, task);
                DROPPED.incrementAndGet();
            }
        }
    }

    /**
     * Takes ownership of a queued/running generation for the synchronous path. If the task has not started yet it is
     * run inline; otherwise the caller waits for the worker. The caller must commit the chunk and run populate under
     * {@link #lockWorldGen()}.
     */
    public static Chunk awaitPending(ChunkProviderServer provider, int x, int z)
    {
        if (!ENABLED || provider == null)
        {
            return null;
        }

        GenKey key = new GenKey(provider, x, z);
        GenTask task = TASKS.get(key);
        if (task == null)
        {
            return null;
        }

        if (POOL.remove(task))
        {
            task.runInline();
        }

        synchronized (task)
        {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60L);
            while (!task.done)
            {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L)
                {
                    break;
                }
                try
                {
                    task.wait(Math.max(1L, Math.min(25L, TimeUnit.NANOSECONDS.toMillis(remaining) + 1L)));
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (!task.done || !TASKS.remove(key, task))
        {
            return null;
        }
        if (task.cancelled)
        {
            task.runCallbacks();
            return null;
        }
        if (task.error != null)
        {
            FAILED.incrementAndGet();
            LOGGER.error("[chunkgen] chunk ({}, {}) failed", Integer.valueOf(x), Integer.valueOf(z), task.error);
            task.runCallbacks();
            return null;
        }

        // The synchronous caller owns finalisation (commit + populate); release the entry/request callbacks now.
        task.runCallbacks();
        return task.chunk;
    }

    /** Posts the world-touching half (recreate structures + populate) of a loaded or generated chunk. */
    public static void post(ChunkProviderServer provider, Chunk chunk, boolean recreateStructures)
    {
        post(provider, chunk, recreateStructures, null);
    }

    /**
     * Same as {@link #post(ChunkProviderServer, Chunk, boolean)} and runs {@code after} once the chunk has been
     * fully populated. Used to delay the player-map callback until the chunk is safe to send.
     */
    public static void post(ChunkProviderServer provider, Chunk chunk, boolean recreateStructures, Runnable after)
    {
        if (provider == null || chunk == null)
        {
            if (after != null)
            {
                after.run();
            }
            return;
        }
        if (!ENABLED)
        {
            provider.postProcessChunk(chunk, recreateStructures);
            if (after != null)
            {
                after.run();
            }
            return;
        }
        POSTS.add(new PostTask(provider, chunk, recreateStructures, after));
        POSTED.incrementAndGet();
    }

    /** Called once per server tick, on the server thread, before the world tick. */
    public static void tick()
    {
        if (!ENABLED)
        {
            return;
        }

        // 1. Commit completed generations: loadedChunks.put + onLoad on the owning thread.
        int harvested = 0;
        for (Iterator<Map.Entry<GenKey, GenTask>> iterator = TASKS.entrySet().iterator(); iterator.hasNext(); )
        {
            Map.Entry<GenKey, GenTask> entry = iterator.next();
            GenTask task = entry.getValue();
            if (!task.done || !TASKS.remove(entry.getKey(), task))
            {
                continue;
            }

            if (task.error != null)
            {
                FAILED.incrementAndGet();
                LOGGER.error("[chunkgen] chunk ({}, {}) failed", Integer.valueOf(task.x), Integer.valueOf(task.z), task.error);
            }
            else if (!task.cancelled)
            {
                try
                {
                    task.key.provider.acceptAsyncGeneratedChunk(task.x, task.z, task.chunk);
                    if (task.unloadWhenDone)
                    {
                        // Pregen: persist the terrain and drop it immediately. Running populate here would spawn
                        // world-gen animals and generate features for chunks nobody is near yet, which then tick
                        // in the region workers until the unload budget catches up.
                        task.key.provider.unloadGeneratedChunk(task.chunk);
                    }
                    else
                    {
                        // Notify the requester only after populate; otherwise a loaded chunk could be sent before
                        // recreateStructures/generateStructures has run.
                        post(task.key.provider, task.chunk, false, task::runCallbacks);
                    }
                    GENERATED.incrementAndGet();
                }
                catch (Throwable throwable)
                {
                    FAILED.incrementAndGet();
                    LOGGER.error("[chunkgen] could not commit chunk ({}, {})", Integer.valueOf(task.x), Integer.valueOf(task.z), throwable);
                }
            }

            if (task.error != null || task.cancelled || task.unloadWhenDone)
            {
                task.runCallbacks();
            }
            if (++harvested >= HARVEST_PER_TICK)
            {
                break;
            }
        }

        // 2. Bounded populate pump. Never blocks the server thread on the generator lock.
        long deadline = System.nanoTime() + POST_BUDGET_NS;
        int done = 0;
        while (done < POST_PER_TICK)
        {
            PostTask post = POSTS.peek();
            if (post == null)
            {
                break;
            }
            if (System.nanoTime() >= deadline)
            {
                POST_DEFERRED.incrementAndGet();
                break;
            }
            if (!WORLDGEN.tryLock())
            {
                POST_DEFERRED.incrementAndGet();
                break;
            }
            boolean processed = false;
            try
            {
                if (!POSTS.remove(post))
                {
                    continue;
                }
                try
                {
                    post.provider.postProcessChunk(post.chunk, post.recreateStructures);
                    POST_DONE.incrementAndGet();
                    ++done;
                    processed = true;
                }
                catch (Throwable throwable)
                {
                    FAILED.incrementAndGet();
                    LOGGER.error("[chunkgen] post-process failed", throwable);
                }
            }
            finally
            {
                WORLDGEN.unlock();
            }
            if (processed && post.after != null)
            {
                try
                {
                    post.after.run();
                }
                catch (Throwable throwable)
                {
                    FAILED.incrementAndGet();
                    LOGGER.error("[chunkgen] post callback failed", throwable);
                }
            }
        }
    }

    /** The caller must release the lock in a finally block. */
    public static void lockWorldGen()
    {
        if (ENABLED)
        {
            WORLDGEN.lock();
        }
    }

    public static void unlockWorldGen()
    {
        if (ENABLED)
        {
            WORLDGEN.unlock();
        }
    }

    public static boolean isWorldGenLockedByCurrentThread()
    {
        return ENABLED && WORLDGEN.isHeldByCurrentThread();
    }

    public static int purgeWorld(World world)
    {
        if (world == null)
        {
            return 0;
        }

        int removed = 0;
        for (Iterator<Map.Entry<GenKey, GenTask>> iterator = TASKS.entrySet().iterator(); iterator.hasNext(); )
        {
            Map.Entry<GenKey, GenTask> entry = iterator.next();
            if (entry.getKey().world() == world)
            {
                GenTask task = entry.getValue();
                task.cancelled = true;
                iterator.remove();
                POOL.remove(task);
                ++removed;
            }
        }
        for (PostTask post : POSTS)
        {
            if (post.provider.world == world)
            {
                POSTS.remove(post);
                ++removed;
            }
        }
        return removed;
    }

    public static int getPendingCount()
    {
        return TASKS.size();
    }

    public static int getPostCount()
    {
        return POSTS.size();
    }

    public static long getGeneratedCount()
    {
        return GENERATED.get();
    }

    public static long getPostDeferredCount()
    {
        return POST_DEFERRED.get();
    }

    public static long getDroppedCount()
    {
        return DROPPED.get();
    }

    // ------------------------------------------------------------------

    private static final class GenKey
    {
        private final ChunkProviderServer provider;
        private final int x;
        private final int z;
        private final int hash;

        private GenKey(ChunkProviderServer provider, int x, int z)
        {
            this.provider = provider;
            this.x = x;
            this.z = z;
            this.hash = System.identityHashCode(provider) * 31 + (int)(ChunkPos.asLong(x, z) * 13L);
        }

        private World world()
        {
            return this.provider.world;
        }

        @Override
        public int hashCode()
        {
            return this.hash;
        }

        @Override
        public boolean equals(Object other)
        {
            if (!(other instanceof GenKey))
            {
                return false;
            }
            GenKey key = (GenKey)other;
            return key.provider == this.provider && key.x == this.x && key.z == this.z;
        }
    }

    private static final class GenTask implements Runnable, Comparable<GenTask>
    {
        private final GenKey key;
        private final int x;
        private final int z;
        private final int priority;
        private final boolean unloadWhenDone;
        private final long sequence = SEQUENCE.incrementAndGet();
        private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<Runnable>();
        private volatile Chunk chunk;
        private volatile Throwable error;
        private volatile boolean done;
        private volatile boolean cancelled;
        private boolean inline;

        private GenTask(GenKey key, int x, int z, int priority, boolean unloadWhenDone)
        {
            this.key = key;
            this.x = x;
            this.z = z;
            this.priority = priority;
            this.unloadWhenDone = unloadWhenDone;
        }

        @Override
        public void run()
        {
            if (!this.inline)
            {
                // Give the server thread a chance to grab the lock between two chunks.
                try
                {
                    Thread.sleep(1L);
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                }
            }

            try
            {
                if (!this.cancelled)
                {
                    WORLDGEN.lock();
                    try
                    {
                        if (!this.cancelled)
                        {
                            this.chunk = this.key.provider.chunkGenerator.generateChunk(this.x, this.z);
                        }
                    }
                    finally
                    {
                        WORLDGEN.unlock();
                    }
                }
            }
            catch (Throwable throwable)
            {
                this.error = throwable;
            }
            finally
            {
                this.done = true;
                synchronized (this)
                {
                    this.notifyAll();
                }
            }
        }

        private void runInline()
        {
            this.inline = true;
            this.run();
        }

        private void runCallbacks()
        {
            for (Runnable callback : this.callbacks)
            {
                try
                {
                    callback.run();
                }
                catch (Throwable throwable)
                {
                    LOGGER.error("[chunkgen] callback failed", throwable);
                }
            }
            this.callbacks.clear();
        }

        @Override
        public int compareTo(GenTask other)
        {
            int byPriority = Integer.compare(this.priority, other.priority);
            return byPriority != 0 ? byPriority : Long.compare(this.sequence, other.sequence);
        }
    }

    private static final class PostTask
    {
        private final ChunkProviderServer provider;
        private final Chunk chunk;
        private final boolean recreateStructures;
        private final Runnable after;

        private PostTask(ChunkProviderServer provider, Chunk chunk, boolean recreateStructures, Runnable after)
        {
            this.provider = provider;
            this.chunk = chunk;
            this.recreateStructures = recreateStructures;
            this.after = after;
        }
    }
}
