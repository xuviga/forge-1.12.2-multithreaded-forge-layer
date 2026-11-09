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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Autonomic region-size scheduler.
 *
 * The game measures its own world-tick cost and tries a small set of region sizes. A candidate is kept only when it
 * beats the current best by a configurable margin. Block ticks, entity ticks and tile-entity ticks use per-object
 * RNG namespaces (see TickContext#setRngKey), so resizing regions at runtime does not change the simulation stream.
 *
 * Enable/disable with -Dforge.threading.autopilot=true|false (default true). The window is
 * -Dforge.threading.autopilotWindow ticks (default 200), candidates are 8/16/32 chunks. Explicit
 * -Dforge.threading.regionSizeChunks always wins and disables tuning.
 */
public final class ThreadingAutopilot
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");
    private static final boolean ENABLED = !Threading.isSingleThreaded() &&
            Boolean.parseBoolean(System.getProperty("forge.threading.autopilot", "true"));
    private static final int WINDOW_TICKS = Math.max(40, Integer.getInteger("forge.threading.autopilotWindow", 200).intValue());
    private static final int[] CANDIDATES = { 8, 16, 32 };
    private static final double IMPROVEMENT_THRESHOLD;
    static
    {
        double threshold = 0.03D;
        try
        {
            threshold = Double.parseDouble(System.getProperty("forge.threading.autopilotThreshold", "0.03"));
        }
        catch (NumberFormatException ignored)
        {
        }
        IMPROVEMENT_THRESHOLD = Math.max(0.0D, Math.min(0.25D, threshold));
    }

    private static volatile int overrideSize = -1;
    private static long windowNanos;
    private static int windowTicks;
    private static boolean initialized;
    private static boolean finished;
    private static int trialSize = -1;
    private static int trialCursor;
    private static int bestSize = -1;
    private static double bestAverageMs = Double.MAX_VALUE;

    private ThreadingAutopilot()
    {
    }

    public static boolean isEnabled()
    {
        return ENABLED;
    }

    public static int getRegionSizeOverride()
    {
        return overrideSize;
    }

    public static void recordTick(long tickId, long tickNanos)
    {
        if (!ENABLED || finished || !Threading.isRegionTickingEnabled() || System.getProperty("forge.threading.regionSizeChunks") != null)
        {
            return;
        }

        windowNanos += tickNanos;
        windowTicks++;
        if (windowTicks < WINDOW_TICKS)
        {
            return;
        }

        double averageMs = (double)windowNanos / (double)windowTicks / 1000000.0D;
        windowNanos = 0L;
        windowTicks = 0;
        evaluate(tickId, averageMs);
    }

    private static synchronized void evaluate(long tickId, double averageMs)
    {
        int current = Threading.getRegionSizeChunks();

        if (!initialized)
        {
            initialized = true;
            bestSize = current;
            bestAverageMs = averageMs;
            LOGGER.info("[autopilot] baseline regionSize={} avgMs={} window={} ticks",
                    Integer.valueOf(bestSize), format(averageMs), Integer.valueOf(WINDOW_TICKS));
            scheduleNextTrial(tickId);
            return;
        }

        if (trialSize > 0)
        {
            if (averageMs < bestAverageMs * (1.0D - IMPROVEMENT_THRESHOLD))
            {
                LOGGER.info("[autopilot] accept regionSize={} avgMs={} (previous best {} @ {} ms)",
                        Integer.valueOf(trialSize), format(averageMs), Integer.valueOf(bestSize),
                        format(bestAverageMs));
                ThreadingTelemetry.increment("autopilot.accept");
                bestSize = trialSize;
                bestAverageMs = averageMs;
            }
            else
            {
                LOGGER.info("[autopilot] reject regionSize={} avgMs={} (best {} @ {} ms)",
                        Integer.valueOf(trialSize), format(averageMs), Integer.valueOf(bestSize),
                        format(bestAverageMs));
                ThreadingTelemetry.increment("autopilot.reject");
            }

            trialSize = -1;
            overrideSize = bestSize;
        }

        scheduleNextTrial(tickId);
    }

    private static void scheduleNextTrial(long tickId)
    {
        if (finished || trialSize > 0)
        {
            return;
        }

        while (trialCursor < CANDIDATES.length)
        {
            int candidate = CANDIDATES[trialCursor++];
            if (candidate == bestSize)
            {
                continue;
            }
            trialSize = candidate;
            overrideSize = candidate;
            LOGGER.info("[autopilot] trial regionSize={} startTick={} best={} bestAvgMs={}",
                    Integer.valueOf(candidate), Long.valueOf(tickId),
                    Integer.valueOf(bestSize), format(bestAverageMs));
            ThreadingTelemetry.increment("autopilot.trial");
            return;
        }

        finished = true;
        overrideSize = bestSize;
        LOGGER.info("[autopilot] settled regionSize={} bestAvgMs={} after {} ticks",
                Integer.valueOf(bestSize), format(bestAverageMs),
                Integer.valueOf(CANDIDATES.length * WINDOW_TICKS));
    }

    public static synchronized void reset()
    {
        overrideSize = -1;
        windowNanos = 0L;
        windowTicks = 0;
        initialized = false;
        finished = false;
        trialSize = -1;
        trialCursor = 0;
        bestSize = -1;
        bestAverageMs = Double.MAX_VALUE;
    }

    private static String format(double value)
    {
        return String.format(java.util.Locale.ROOT, "%.3f", Double.valueOf(value));
    }
}
