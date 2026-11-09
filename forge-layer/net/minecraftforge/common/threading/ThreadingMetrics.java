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

import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Lightweight opt-in metrics for the multithreaded fork.
 *
 *   -Dforge.threading.metrics=true
 *
 * Logs a rolling window of server tick time (MSPT/TPS) and per-phase region work. Disabled by default; the
 * counters are atomics only touched by tick/region threads.
 */
public final class ThreadingMetrics
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("forge.threading.metrics", "false"));
    private static final int WINDOW_TICKS = Integer.getInteger("forge.threading.metricsWindow", 100).intValue();

    private static final AtomicLong windowTicks = new AtomicLong();
    private static final AtomicLong windowNanos = new AtomicLong();
    private static final AtomicLong maxTickNanos = new AtomicLong();

    private static final AtomicLong entityNanos = new AtomicLong();
    private static final AtomicLong entityTasks = new AtomicLong();
    private static final AtomicLong entityCount = new AtomicLong();
    private static final AtomicLong teNanos = new AtomicLong();
    private static final AtomicLong teTasks = new AtomicLong();
    private static final AtomicLong teCount = new AtomicLong();
    private static final AtomicLong blockNanos = new AtomicLong();
    private static final AtomicLong blockTasks = new AtomicLong();
    private static final AtomicLong blockChunks = new AtomicLong();
    private static final AtomicLong pathSubmitted = new AtomicLong();
    private static final AtomicLong pathCompleted = new AtomicLong();
    private static final AtomicLong clientFrames = new AtomicLong();
    private static final AtomicLong clientNanos = new AtomicLong();
    private static final AtomicLong clientMaxNanos = new AtomicLong();

    private ThreadingMetrics()
    {
    }

    public static boolean isEnabled()
    {
        return ENABLED;
    }

    public static void recordServerTick(long nanos)
    {
        if (!ENABLED)
        {
            return;
        }

        long ticks = windowTicks.incrementAndGet();
        long total = windowNanos.addAndGet(nanos);
        long previousMax = maxTickNanos.get();
        while (nanos > previousMax && !maxTickNanos.compareAndSet(previousMax, nanos))
        {
            previousMax = maxTickNanos.get();
        }

        if (ticks < WINDOW_TICKS)
        {
            return;
        }

        long window = windowTicks.getAndSet(0);
        long sum = windowNanos.getAndSet(0);
        long max = maxTickNanos.getAndSet(0);
        double avgMs = (double)sum / (double)window / 1000000.0D;
        double maxMs = (double)max / 1000000.0D;
        double tps = Math.min(20.0D, 1000.0D / Math.max(0.001D, avgMs));

        long eNanos = entityNanos.getAndSet(0);
        long eTasks = entityTasks.getAndSet(0);
        long eCount = entityCount.getAndSet(0);
        long tNanos = teNanos.getAndSet(0);
        long tTasks = teTasks.getAndSet(0);
        long tCount = teCount.getAndSet(0);
        long bNanos = blockNanos.getAndSet(0);
        long bTasks = blockTasks.getAndSet(0);
        long bChunks = blockChunks.getAndSet(0);
        long pSubmitted = pathSubmitted.getAndSet(0);
        long pCompleted = pathCompleted.getAndSet(0);

        LOGGER.info("[thread-metrics] ticks={} msptAvg={} msptMax={} tps={} entityMs/task={} entities/tick={} teMs/task={} tes/tick={} blockMs/task={} chunks/tick={} pathSubmitted={} pathCompleted={}",
                Long.valueOf(window), String.format("%.3f", Double.valueOf(avgMs)), String.format("%.3f", Double.valueOf(maxMs)),
                String.format("%.2f", Double.valueOf(tps)),
                String.format("%.4f", Double.valueOf(eTasks == 0 ? 0.0D : (double)eNanos / (double)eTasks / 1000000.0D)),
                String.format("%.1f", Double.valueOf((double)eCount / (double)Math.max(1L, window))),
                String.format("%.4f", Double.valueOf(tTasks == 0 ? 0.0D : (double)tNanos / (double)tTasks / 1000000.0D)),
                String.format("%.1f", Double.valueOf((double)tCount / (double)Math.max(1L, window))),
                String.format("%.4f", Double.valueOf(bTasks == 0 ? 0.0D : (double)bNanos / (double)bTasks / 1000000.0D)),
                String.format("%.1f", Double.valueOf((double)bChunks / (double)Math.max(1L, window))),
                Long.valueOf(pSubmitted), Long.valueOf(pCompleted));
    }

    public static void recordClientFrame(long nanos)
    {
        if (!ENABLED)
        {
            return;
        }

        long frames = clientFrames.incrementAndGet();
        long total = clientNanos.addAndGet(nanos);
        long previousMax = clientMaxNanos.get();
        while (nanos > previousMax && !clientMaxNanos.compareAndSet(previousMax, nanos))
        {
            previousMax = clientMaxNanos.get();
        }

        if (frames < WINDOW_TICKS)
        {
            return;
        }

        long window = clientFrames.getAndSet(0);
        long sum = clientNanos.getAndSet(0);
        long max = clientMaxNanos.getAndSet(0);
        double avgMs = (double)sum / (double)window / 1000000.0D;
        double maxMs = (double)max / 1000000.0D;
        double fps = 1000.0D / Math.max(0.001D, avgMs);
        LOGGER.info("[client-metrics] frames={} frameMsAvg={} frameMsMax={} fps={}",
                Long.valueOf(window), String.format("%.3f", Double.valueOf(avgMs)),
                String.format("%.3f", Double.valueOf(maxMs)), String.format("%.2f", Double.valueOf(fps)));
    }

    public static void recordPathfindingSubmit()
    {
        if (ENABLED)
        {
            pathSubmitted.incrementAndGet();
        }
    }

    public static void recordPathfindingComplete()
    {
        if (ENABLED)
        {
            pathCompleted.incrementAndGet();
        }
    }

    public static void recordRegionTask(String kind, long nanos, int units)
    {
        ThreadingTelemetry.increment("region." + kind + ".tasks");
        ThreadingTelemetry.add("region." + kind + ".units", units);

        if (!ENABLED)
        {
            return;
        }

        if ("entity".equals(kind))
        {
            entityNanos.addAndGet(nanos);
            entityTasks.incrementAndGet();
            entityCount.addAndGet(units);
        }
        else if ("te".equals(kind))
        {
            teNanos.addAndGet(nanos);
            teTasks.incrementAndGet();
            teCount.addAndGet(units);
        }
        else if ("block".equals(kind))
        {
            blockNanos.addAndGet(nanos);
            blockTasks.incrementAndGet();
            blockChunks.addAndGet(units);
        }
    }
}
