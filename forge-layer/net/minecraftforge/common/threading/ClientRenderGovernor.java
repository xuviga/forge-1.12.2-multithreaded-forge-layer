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

import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Adaptive client render distance.
 *
 * <p>RD32 on integrated graphics is fill-rate/geometry bound: the far ring costs far more than the frame budget, and
 * neither per-section nor merged far-ring LOD produced an acceptable picture on the reference Intel client. This
 * governor instead treats render distance as a dynamic quality setting, the same way console games scale resolution:
 *
 * <ul>
 *   <li>it measures the real client frame time (after the FPS limiter);</li>
 *   <li>when the rolling average is over budget it jumps directly to a distance that should hold the target (one
 *       renderer reload instead of a staircase of reloads);</li>
 *   <li>after a drop it refuses to grow for a minute, so looking at the sky for half a second cannot undo the drop
 *       and start an oscillation;</li>
 *   <li>it grows one chunk at a time only after a long uninterrupted good window.</li>
 * </ul>
 *
 * <p>The integrated server already mirrors {@code gameSettings.renderDistanceChunks} into the player-list view
 * distance every tick, so both the client renderer and the server chunk view shrink/grow together. The far ring
 * fades into normal fog instead of coarse LOD geometry.
 *
 * <p>Disable with {@code -Dforge.threading.clientRenderGovernor=false}. Tunables:
 * {@code clientRenderGovernorMinRd=6}, {@code ...LowMs=8}, {@code ...RecoverMs=5},
 * {@code ...LowSeconds=0.75}, {@code ...RecoverSeconds=15}, {@code ...PenaltySeconds=30}.
 */
@SideOnly(Side.CLIENT)
public final class ClientRenderGovernor
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("forge.threading.clientRenderGovernor", "true"));
    private static final int MIN_RD = Math.max(2, Integer.getInteger(
            "forge.threading.clientRenderGovernorMinRd", 6).intValue());
    private static final double LOW_MS = Double.parseDouble(
            System.getProperty("forge.threading.clientRenderGovernorLowMs", "8"));
    private static final double RECOVER_MS = Double.parseDouble(
            System.getProperty("forge.threading.clientRenderGovernorRecoverMs", "5"));
    private static final long LOW_NANOS = TimeUnit.MILLISECONDS.toNanos(Math.max(100L,
            (long)(Double.parseDouble(System.getProperty("forge.threading.clientRenderGovernorLowSeconds", "0.75"))
                    * 1000.0D)));
    private static final long RECOVER_NANOS = TimeUnit.SECONDS.toNanos(Math.max(1L,
            Long.getLong("forge.threading.clientRenderGovernorRecoverSeconds", 15L).longValue()));
    private static final long PENALTY_NANOS = TimeUnit.SECONDS.toNanos(Math.max(0L,
            Long.getLong("forge.threading.clientRenderGovernorPenaltySeconds", 30L).longValue()));
    private static final long COOLDOWN_NANOS = TimeUnit.MILLISECONDS.toNanos(1500L);

    private static double averageMs = 16.0D;
    private static int baseRd = -1;
    private static int currentRd = -1;
    private static long lowNanos;
    private static long recoverNanos;
    private static long cooldownUntil;
    private static long penaltyUntil;

    private ClientRenderGovernor()
    {
    }

    public static void onClientFrame(long nanos)
    {
        if (!ENABLED)
        {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();

        if (mc == null || mc.gameSettings == null || mc.world == null || mc.currentScreen != null
                || !mc.inGameHasFocus)
        {
            return;
        }

        double frameMs = (double)nanos / 1000000.0D;

        if (baseRd <= 0)
        {
            baseRd = mc.gameSettings.renderDistanceChunks;
            currentRd = baseRd;
            averageMs = frameMs;
            return;
        }

        // The user changed the setting manually (or another mod did): accept it as the new ceiling and forget the
        // previous penalty.
        if (mc.gameSettings.renderDistanceChunks != currentRd)
        {
            baseRd = mc.gameSettings.renderDistanceChunks;
            currentRd = baseRd;
            lowNanos = 0L;
            recoverNanos = 0L;
            penaltyUntil = 0L;
            cooldownUntil = System.nanoTime() + COOLDOWN_NANOS;
            averageMs = frameMs;
            return;
        }

        averageMs = averageMs * 0.85D + frameMs * 0.15D;

        long now = System.nanoTime();

        if (now < cooldownUntil)
        {
            return;
        }

        if (averageMs > LOW_MS)
        {
            lowNanos += Math.max(0L, nanos);
            recoverNanos = 0L;
        }
        else if (averageMs < RECOVER_MS)
        {
            recoverNanos += Math.max(0L, nanos);
            lowNanos = 0L;
        }
        else
        {
            lowNanos = 0L;
            recoverNanos = 0L;
        }

        if (lowNanos >= LOW_NANOS)
        {
            int target = targetFor(averageMs);

            if (target < currentRd)
            {
                apply(mc, target);
                penaltyUntil = now + PENALTY_NANOS;
            }

            lowNanos = 0L;
            return;
        }

        if (recoverNanos >= RECOVER_NANOS && now >= penaltyUntil && currentRd < baseRd)
        {
            apply(mc, Math.min(baseRd, currentRd + 1));
            recoverNanos = 0L;
        }
    }

    /**
     * The server must keep sending chunks up to the user's configured distance even while the client renders a
     * smaller adaptive distance. Otherwise the server unloads the far chunks, the client raises its render distance
     * again (e.g. while looking at the sky) and the far ring renders as empty holes until it reloads. Keeping the
     * server view at the ceiling pre-fills the client chunk cache, so a later render-distance increase is hole-free.
     */
    public static int getServerViewDistance(int clientRenderDistance)
    {
        int base = baseRd;
        return base > 0 ? Math.max(base, clientRenderDistance) : clientRenderDistance;
    }

    /** Maps a too-high frame time directly to a distance that should fit the budget. */
    private static int targetFor(double frameMs)
    {
        if (frameMs > 14.0D)
        {
            return Math.max(MIN_RD, 8);
        }
        if (frameMs > 11.0D)
        {
            return Math.max(MIN_RD, 10);
        }
        if (frameMs > 8.0D)
        {
            return Math.max(MIN_RD, 12);
        }
        if (frameMs > 6.0D)
        {
            return Math.max(MIN_RD, 14);
        }
        if (frameMs > 5.0D)
        {
            return Math.max(MIN_RD, 16);
        }
        return Math.max(MIN_RD, 20);
    }

    private static void apply(Minecraft mc, int target)
    {
        int clamped = Math.max(MIN_RD, Math.min(baseRd, target));

        if (clamped == currentRd)
        {
            cooldownUntil = System.nanoTime() + COOLDOWN_NANOS;
            return;
        }

        int previous = currentRd;
        currentRd = clamped;
        mc.gameSettings.renderDistanceChunks = clamped;
        cooldownUntil = System.nanoTime() + COOLDOWN_NANOS;
        LOGGER.info("[render-governor] renderDistance {} -> {} (avg {} ms)", Integer.valueOf(previous),
                Integer.valueOf(clamped), String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(averageMs)));
    }
}

