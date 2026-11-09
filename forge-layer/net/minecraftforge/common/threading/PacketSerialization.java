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
 * Per-player ordered packet serialization off the game thread.
 *
 * Every NetworkManager owns one {@link Serial} queue. The queue runs serially per player (packet order is preserved)
 * on a shared daemon pool. Eligible PLAY-state packets are written to a ByteBuf on the pool and then handed to
 * Netty, which only has to compress/encrypt/frame them. FML proxy packets and state transitions are dispatched
 * through the same serial queue so they cannot overtake already-queued packets.
 *
 * Local (single-player) channels bypass this path entirely: they transfer Packet objects in memory and have no
 * encoder pipeline.
 *
 * -Dforge.threading.packetSerialization=false disables it; singleThreaded also disables it.
 */
public final class PacketSerialization
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");
    private static final boolean ENABLED = !Threading.isSingleThreaded() &&
            Boolean.parseBoolean(System.getProperty("forge.threading.packetSerialization", "true"));
    private static final int THREADS = Math.max(1, Math.min(8, Integer.getInteger(
            "forge.threading.packetSerializationThreads",
            Math.max(1, Runtime.getRuntime().availableProcessors() / 2)).intValue()));
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final AtomicLong SERIALIZED = new AtomicLong();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, new ThreadFactory()
    {
        @Override
        public Thread newThread(Runnable runnable)
        {
            Thread thread = new Thread(net.minecraftforge.fml.common.thread.SidedThreadGroups.SERVER,
                    runnable, "Packet Serializer #" + IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    static
    {
        ThreadingTelemetry.registerPool("packetOut", POOL);
    }

    private PacketSerialization()
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

    public static void recordSerialized()
    {
        ThreadingTelemetry.increment("net.out");
        long count = SERIALIZED.incrementAndGet();
        if (count % 1000L == 0L)
        {
            LOGGER.info("[packet-serialization] serialized {} packets on {} threads", Long.valueOf(count),
                    Integer.valueOf(THREADS));
        }
    }

    /**
     * A serial executor: tasks for one player run in submission order on the shared pool, and only one worker
     * drains a player's queue at a time.
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
                        LOGGER.error("[packet-serialization] task failed", throwable);
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
