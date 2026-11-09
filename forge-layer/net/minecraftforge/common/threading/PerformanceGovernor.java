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
 * Self-tuning server governor.
 *
 * It watches the rolling MSPT window produced by {@link ThreadingMetrics}. When the server is consistently over
 * budget it shrinks the deterministic AI-LOD radii and the simulation distance; when it has been comfortably under
 * budget for a while it expands them back. Near players always remain at full tick rate.
 *
 * This is intentionally pack-agnostic: no per-modpack configuration is required. Hysteresis (2 bad windows to
 * shrink, 10 good windows to expand) and a 10-window warm-up prevent the initial world-load spike from triggering it.
 *
 * -Dforge.threading.governor=false disables it.
 */
public final class PerformanceGovernor
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("forge.threading.governor", "true"));
    private static final double HIGH_MSPT = Double.parseDouble(System.getProperty("forge.threading.governorHighMspt", "40"));
    private static final double HIGH_MAX = Double.parseDouble(System.getProperty("forge.threading.governorHighMaxMspt", "120"));
    private static final double LOW_MSPT = Double.parseDouble(System.getProperty("forge.threading.governorLowMspt", "20"));
    private static final double LOW_MAX = Double.parseDouble(System.getProperty("forge.threading.governorLowMaxMspt", "60"));
    private static final int WARMUP_WINDOWS = Integer.getInteger("forge.threading.governorWarmupWindows", 10).intValue();
    private static final int SHRINK_AFTER = Math.max(1, Integer.getInteger("forge.threading.governorShrinkAfter", 2).intValue());
    private static final int EXPAND_AFTER = Math.max(1, Integer.getInteger("forge.threading.governorExpandAfter", 10).intValue());
    private static final int DEFAULT_NEAR = 48;
    private static final int DEFAULT_MID = 96;
    private static final int DEFAULT_FAR = 160;
    private static final int DEFAULT_SIM = 12;
    private static final int MIN_NEAR = 24;
    private static final int MIN_MID = 48;
    private static final int MIN_FAR = 80;
    private static final int MIN_SIM = 8;
    private static volatile int windows;
    private static volatile int badWindows;
    private static volatile int goodWindows;
    private static volatile long actions;

    private PerformanceGovernor()
    {
    }

    public static void onMetricsWindow(double avgMspt, double maxMspt)
    {
        if (!ENABLED || !Threading.isRegionTickingEnabled()
                || !Boolean.parseBoolean(System.getProperty("forge.threading.entityLod", "true")))
        {
            return;
        }

        windows++;
        if (windows <= WARMUP_WINDOWS)
        {
            return;
        }

        boolean high = avgMspt > HIGH_MSPT || maxMspt > HIGH_MAX;
        boolean low = avgMspt < LOW_MSPT && maxMspt < LOW_MAX;

        if (high)
        {
            badWindows++;
            goodWindows = 0;
        }
        else if (low)
        {
            goodWindows++;
            badWindows = 0;
        }
        else
        {
            badWindows = 0;
            goodWindows = 0;
        }

        if (badWindows >= SHRINK_AFTER)
        {
            badWindows = 0;
            shrink(avgMspt, maxMspt);
        }
        else if (goodWindows >= EXPAND_AFTER)
        {
            goodWindows = 0;
            expand(avgMspt, maxMspt);
        }
    }

    private static void shrink(double avgMspt, double maxMspt)
    {
        int oldNear = RegionManager.getLodNear();
        int oldMid = RegionManager.getLodMid();
        int oldFar = RegionManager.getLodFar();
        int oldSim = Threading.getDynamicSimulationDistance();
        int near = Math.max(MIN_NEAR, oldNear * 3 / 4);
        int mid = Math.max(near + 16, oldMid * 3 / 4);
        int far = Math.max(mid + 16, oldFar * 3 / 4);
        int sim = Math.max(MIN_SIM, oldSim - 1);

        if (near == oldNear && mid == oldMid && far == oldFar && sim == oldSim)
        {
            return;
        }

        RegionManager.setLodRadii(near, mid, far);
        Threading.setDynamicSimulationDistance(sim);
        actions++;
        LOGGER.warn("[governor] mspt avg={} max={} -> LOD {}/{}/{} sim {} (action #{})",
                Double.valueOf(avgMspt), Double.valueOf(maxMspt), Integer.valueOf(near), Integer.valueOf(mid),
                Integer.valueOf(far), Integer.valueOf(sim), Long.valueOf(actions));
    }

    private static void expand(double avgMspt, double maxMspt)
    {
        int oldNear = RegionManager.getLodNear();
        int oldMid = RegionManager.getLodMid();
        int oldFar = RegionManager.getLodFar();
        int oldSim = Threading.getDynamicSimulationDistance();
        int near = Math.min(DEFAULT_NEAR, oldNear + 2);
        int mid = Math.min(DEFAULT_MID, oldMid + 4);
        int far = Math.min(DEFAULT_FAR, oldFar + 8);
        int sim = Math.min(DEFAULT_SIM, oldSim + 1);

        if (near == oldNear && mid == oldMid && far == oldFar && sim == oldSim)
        {
            return;
        }

        RegionManager.setLodRadii(near, mid, far);
        Threading.setDynamicSimulationDistance(sim);
        actions++;
        LOGGER.info("[governor] mspt avg={} max={} -> LOD {}/{}/{} sim {} (action #{})",
                Double.valueOf(avgMspt), Double.valueOf(maxMspt), Integer.valueOf(near), Integer.valueOf(mid),
                Integer.valueOf(far), Integer.valueOf(sim), Long.valueOf(actions));
    }
}
