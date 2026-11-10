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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * MSPT airbag.
 *
 * <p>The 1.12.2 client and the integrated server share one JVM. A client frame that spends a lot of CPU inside
 * the chunk compiler, the render-prep thread or the packet pipeline does not only drop frames: it steals the CPU
 * slice that the server thread needs, and the next server tick is reported as a multi-hundred-millisecond spike.
 * The usual reaction is to lower the number of background threads, which permanently reduces throughput.
 *
 * <p>This class implements a feedback loop instead. Game threads publish the duration of every tick/frame. When a
 * game thread recently overshot its budget, background pools voluntarily park for a very short slice before starting
 * the next work item. The background work is not cancelled and no queue is dropped - it simply yields the CPU
 * exactly while the game thread needs it, then resumes at full speed. In steady state (no over-budget tick) the
 * check is one volatile read and has no measurable cost.
 *
 * <p>Disable with {@code -Dforge.threading.pacer=false}. Tunables:
 * <ul>
 *   <li>{@code -Dforge.threading.pacer.serverBudgetMs} (default 35)</li>
 *   <li>{@code -Dforge.threading.pacer.clientBudgetMs} (default 50)</li>
 *   <li>{@code -Dforge.threading.pacer.holdMs} (default 200)</li>
 *   <li>{@code -Dforge.threading.pacer.sliceUs} (default 250)</li>
 * </ul>
 */
public final class BackgroundPacer
{
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("forge.threading.pacer", "true"));
    private static final long SERVER_BUDGET_NANOS = millisToNanos(
            Long.getLong("forge.threading.pacer.serverBudgetMs", 35L).longValue());
    private static final long CLIENT_BUDGET_NANOS = millisToNanos(
            Long.getLong("forge.threading.pacer.clientBudgetMs", 50L).longValue());
    private static final long HOLD_NANOS = millisToNanos(
            Long.getLong("forge.threading.pacer.holdMs", 200L).longValue());
    private static final long SLICE_NANOS = Math.max(50000L, Long.getLong("forge.threading.pacer.sliceUs", 250L).longValue() * 1000L);
    /** Upper bound for the feedback hold; an ancient stall must not throttle the rest of the session. */
    private static final long MAX_HOLD_NANOS = millisToNanos(500L);

    private static volatile long serverHoldUntil;
    private static volatile long clientHoldUntil;
    private static volatile long lastServerTickNanos;
    private static volatile long lastClientFrameNanos;

    private static final AtomicLong YIELDS = new AtomicLong();
    private static final AtomicLong YIELD_NANOS = new AtomicLong();
    private static final AtomicLong SERVER_OVERRUNS = new AtomicLong();
    private static final AtomicLong CLIENT_OVERRUNS = new AtomicLong();

    private BackgroundPacer()
    {
    }

    public static boolean isEnabled()
    {
        return ENABLED;
    }

    private static long millisToNanos(long millis)
    {
        return Math.max(0L, millis) * 1000000L;
    }

    /** Called from the server thread after every measured world tick. */
    public static void noteServerTick(long nanos)
    {
        if (!ENABLED || nanos < 0L)
        {
            return;
        }
        lastServerTickNanos = nanos;
        if (nanos > SERVER_BUDGET_NANOS)
        {
            SERVER_OVERRUNS.incrementAndGet();
            serverHoldUntil = System.nanoTime() + Math.min(MAX_HOLD_NANOS, HOLD_NANOS);
        }
    }

    /** Called from the client thread after every rendered frame. */
    public static void noteClientFrame(long nanos)
    {
        if (!ENABLED || nanos < 0L)
        {
            return;
        }
        lastClientFrameNanos = nanos;
        if (nanos > CLIENT_BUDGET_NANOS)
        {
            CLIENT_OVERRUNS.incrementAndGet();
            clientHoldUntil = System.nanoTime() + Math.min(MAX_HOLD_NANOS, HOLD_NANOS);
        }
    }

    public static boolean isHolding()
    {
        if (!ENABLED)
        {
            return false;
        }
        long now = System.nanoTime();
        return now < serverHoldUntil || now < clientHoldUntil;
    }

    /**
     * Called by background workers immediately before a work item. Returns immediately unless a game thread recently
     * overran its budget; then parks for one short slice so the scheduler has a chance to run the game thread.
     */
    public static void yieldIfNeeded()
    {
        yieldIfNeeded(false);
    }

    /**
     * @param ignoreServerHold when true, a server-thread overrun does not park this worker. Client chunk builders
     *                         must keep building/uploading visible chunks even while the integrated server is
     *                         briefly over budget: the server TPS is already 20 and the renderer is the actual
     *                         bottleneck. The client hold is still honoured.
     */
    public static void yieldIfNeeded(boolean ignoreServerHold)
    {
        if (!ENABLED)
        {
            return;
        }
        long now = System.nanoTime();
        long until = ignoreServerHold ? clientHoldUntil : Math.max(serverHoldUntil, clientHoldUntil);
        if (until <= now)
        {
            return;
        }
        long slice = Math.min(until - now, SLICE_NANOS);
        if (slice > 0L)
        {
            LockSupport.parkNanos(slice);
            YIELDS.incrementAndGet();
            YIELD_NANOS.addAndGet(slice);
        }
    }

    public static void appendOverlayLines(List<String> lines)
    {
        if (!ENABLED)
        {
            return;
        }
        lines.add(String.format(java.util.Locale.ROOT,
                "[Pacer] hold=%s serverLast=%.2fms clientLast=%.2fms yields=%d serverOver=%d clientOver=%d",
                Boolean.valueOf(isHolding()), Double.valueOf(lastServerTickNanos / 1000000.0D),
                Double.valueOf(lastClientFrameNanos / 1000000.0D), Long.valueOf(YIELDS.get()),
                Long.valueOf(SERVER_OVERRUNS.get()), Long.valueOf(CLIENT_OVERRUNS.get())));
    }

    public static long getYieldCount()
    {
        return YIELDS.get();
    }

    public static long getYieldNanos()
    {
        return YIELD_NANOS.get();
    }
}
