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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Phase 0 developer aid for the multithreaded fork.
 *
 * It records the client and server game threads and logs accesses that happen from any other thread. The checks are
 * cheap no-ops unless auditing is enabled, so they can stay in production builds:
 *
 *   -Dforge.threading=audit
 *   -Dforge.threading.audit=true
 *
 * This class deliberately does not throw. Phase 0 only measures how many mods and vanilla paths assume a single
 * thread; hard failures become available in a later phase once the compatibility reports are in.
 */
public final class ThreadAudit
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");
    private static final boolean ENABLED =
            "audit".equalsIgnoreCase(System.getProperty("forge.threading", "")) ||
            Boolean.parseBoolean(System.getProperty("forge.threading.audit", "false"));
    private static final int LOG_BURST = 20;
    private static final long LOG_EVERY = 1000L;

    private static volatile Thread clientThread;
    private static volatile Thread serverThread;

    private static final AtomicLong violations = new AtomicLong();
    private static final AtomicLong inlineMainHandlers = new AtomicLong();
    private static final ConcurrentHashMap<String, AtomicLong> perSite = new ConcurrentHashMap<String, AtomicLong>();

    private ThreadAudit()
    {
    }

    public static boolean isEnabled()
    {
        return ENABLED;
    }

    public static void markClientThread()
    {
        clientThread = Thread.currentThread();
    }

    public static void markClientThread(Thread thread)
    {
        clientThread = thread;
    }

    public static void markServerThread()
    {
        serverThread = Thread.currentThread();
    }

    public static void markServerThread(Thread thread)
    {
        serverThread = thread;
    }

    public static Thread getClientThread()
    {
        return clientThread;
    }

    public static Thread getServerThread()
    {
        return serverThread;
    }

    public static long getViolationCount()
    {
        return violations.get();
    }

    /**
     * Verifies that the current thread owns the given world. Client worlds are owned by the client thread, server
     * worlds by the server thread.
     */
    public static void assertWorldThread(World world, String action)
    {
        if (!ENABLED || world == null)
        {
            return;
        }

        // A thread that published this world as its TickContext owns it, even if it is a dimension or region worker.
        TickContext context = TickContext.current();
        if (context != null && context.getWorld() == world)
        {
            return;
        }

        // The client simulation thread legitimately owns the client world for weather/entity ticking.
        if (world.isRemote && ClientSimulationThread.isSimulationThread())
        {
            return;
        }

        Thread expected = world.isRemote ? clientThread : serverThread;
        if (expected != null && Thread.currentThread() != expected)
        {
            report(expected, world.isRemote ? "client" : "server", action);
        }
    }

    /**
     * Records an event listener that was skipped because its declared thread type did not match the current thread.
     */
    /**
     * Records a legacy MAIN handler executed inline on a worker because forwarding to the owner was disabled.
     */
    public static void recordInlineMainHandler(String listener)
    {
        ThreadingTelemetry.increment("event.inline");
        if (!ENABLED)
        {
            return;
        }
        long count = inlineMainHandlers.incrementAndGet();
        if (count <= LOG_BURST || count % LOG_EVERY == 0L)
        {
            LOGGER.warn("[thread-audit] MAIN handler executed inline on {} (inline #{}, audit enabled): {}",
                    Thread.currentThread().getName(), Long.valueOf(count), listener);
        }
    }

    public static void recordSkippedListener(String listener)
    {
        ThreadingTelemetry.increment("event.skipped");
        if (!ENABLED)
        {
            return;
        }
        record("event:" + listener);
    }

    private static void record(String action)
    {
        long count = violations.incrementAndGet();
        AtomicLong siteCount = perSite.computeIfAbsent(action, k -> new AtomicLong());
        long n = siteCount.incrementAndGet();
        if (n <= LOG_BURST || n % LOG_EVERY == 0)
        {
            LOGGER.warn("[thread-audit] skipped/denied {} (violation #{}, site hit #{})", action, count, n,
                    new Throwable("thread-audit stack"));
        }
    }

    public static void assertServerThread(String action)
    {
        if (!ENABLED)
        {
            return;
        }

        Thread expected = serverThread;
        if (expected != null && Thread.currentThread() != expected)
        {
            report(expected, "server", action);
        }
    }

    public static void assertClientThread(String action)
    {
        if (!ENABLED)
        {
            return;
        }

        Thread expected = clientThread;
        if (expected != null && Thread.currentThread() != expected)
        {
            report(expected, "client", action);
        }
    }

    /**
     * Logs when a region worker touches an entity owned by a different region (cross-region AI/targeting race).
     */
    public static void assertEntityOwner(net.minecraft.entity.Entity entity, String action)
    {
        if (!ENABLED || entity == null)
        {
            return;
        }

        TickContext context = currentContext();
        if (context == null || context.getRegion() == null)
        {
            return;
        }

        Region owner = RegionManager.getEntityOwner(entity);
        if (owner != null && !owner.equals(context.getRegion()))
        {
            record("entity:" + action);
        }
    }

    /**
     * @return true when the current region worker does not own the given entity.
     */
    public static boolean isCrossRegion(net.minecraft.entity.Entity entity)
    {
        TickContext context = TickContext.current();
        if (context == null || context.getRegion() == null || entity == null)
        {
            return false;
        }
        Region owner = RegionManager.getEntityOwner(entity);
        return owner != null && !owner.equals(context.getRegion());
    }

    public static void recordDeferredCrossRegion(String action)
    {
        ThreadingTelemetry.increment("event.deferred");
        if (ENABLED)
        {
            record("deferred:" + action);
        }
    }

    private static TickContext currentContext()
    {
        return TickContext.current();
    }

    private static void report(Thread expected, String side, String action)
    {
        long count = violations.incrementAndGet();
        AtomicLong siteCount = perSite.computeIfAbsent(action, k -> new AtomicLong());
        long n = siteCount.incrementAndGet();

        if (n <= LOG_BURST || n % LOG_EVERY == 0)
        {
            LOGGER.warn("[thread-audit] {} called off the {} thread (expected '{}', actual '{}', violation #{}, site hit #{})",
                    action, side, expected.getName(), Thread.currentThread().getName(), count, n,
                    new Throwable("thread-audit stack"));
        }
    }
}
