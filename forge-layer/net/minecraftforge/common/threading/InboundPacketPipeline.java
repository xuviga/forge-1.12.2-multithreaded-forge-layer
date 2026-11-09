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

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Inbound packet processing pipeline.
 *
 * Netty decodes packets on its IO event loops, but a slow packet handler on one channel can stall every other channel
 * assigned to that loop. This pipeline moves packet-handler execution to a per-connection ordered queue on a shared
 * worker pool. Per-connection order is preserved; cross-connection handlers run in parallel.
 *
 * Handlers that need the game thread call PacketThreadUtil.checkThreadAndEnqueue as usual, so world/player work is
 * still re-submitted to the owning server/client thread. Handlers that are thread-safe run directly on the inbound
 * worker. Local (single-player) channels keep the vanilla path.
 *
 * -Dforge.threading.inboundPipeline=false disables it; singleThreaded also disables it.
 */
public final class InboundPacketPipeline
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");
    private static final boolean ENABLED = !Threading.isSingleThreaded() &&
            Boolean.parseBoolean(System.getProperty("forge.threading.inboundPipeline", "true"));
    private static final int THREADS = Math.max(1, Math.min(8, Integer.getInteger(
            "forge.threading.inboundPipelineThreads",
            Math.max(1, Runtime.getRuntime().availableProcessors() / 2)).intValue()));
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final AtomicLong PROCESSED = new AtomicLong();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, new ThreadFactory()
    {
        @Override
        public Thread newThread(Runnable runnable)
        {
            Thread thread = new Thread(net.minecraftforge.fml.common.thread.SidedThreadGroups.SERVER,
                    runnable, "Inbound Packet #" + IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    static
    {
        ThreadingTelemetry.registerPool("packetIn", POOL);
    }

    private InboundPacketPipeline()
    {
    }

    public static boolean isEnabled()
    {
        return ENABLED;
    }

    public static Serial newSerial()
    {
        return new Serial();
    }

    public static void recordProcessed()
    {
        ThreadingTelemetry.increment("net.in");
        long count = PROCESSED.incrementAndGet();
        if (count == 1L || count % 5000L == 0L)
        {
            LOGGER.info("[inbound-pipeline] processed {} packets on {} threads", Long.valueOf(count),
                    Integer.valueOf(THREADS));
        }
    }

    /**
     * Per-connection serial executor. Tasks for one channel run in arrival order on the shared pool.
     */
    public static final class Serial
    {
        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<Runnable>();
        private final AtomicBoolean draining = new AtomicBoolean();

        private Serial()
        {
        }

        public void execute(Runnable task)
        {
            this.tasks.add(task);
            if (this.draining.compareAndSet(false, true))
            {
                POOL.execute(this::drain);
            }
        }

        public int pending()
        {
            return this.tasks.size();
        }

        private void drain()
        {
            try
            {
                Runnable task;
                while ((task = this.tasks.poll()) != null)
                {
                    try
                    {
                        task.run();
                    }
                    catch (Throwable throwable)
                    {
                        LOGGER.error("[inbound-pipeline] task failed", throwable);
                    }
                }
            }
            finally
            {
                this.draining.set(false);
                if (!this.tasks.isEmpty() && this.draining.compareAndSet(false, true))
                {
                    POOL.execute(this::drain);
                }
            }
        }
    }
}
