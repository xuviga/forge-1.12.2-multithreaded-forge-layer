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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Dedicated client simulation thread.
 *
 * In weather-only mode (-Dforge.threading.clientSimThread=true) it free-runs World.tick() every 50 ms.
 * In entity mode (-Dforge.threading.clientSimEntities=true) it is request-driven: the client tick submits a task and
 * waits for it, so entity interpolation stays aligned with partialTicks and render never races the tick. Only
 * World.updateEntities() runs here; WorldClient.tick() (chunk spawn queue, updateBlocks) remains on the client thread.
 *
 * Phase 4C uses the same task queue to run animateTick/particle CPU work on this thread.
 */
public final class ClientSimulationThread
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");
    private static final boolean ENABLED = !Threading.isSingleThreaded() &&
            Boolean.parseBoolean(System.getProperty("forge.threading.clientSimThread", "false"));
    private static final boolean ENTITIES = ENABLED && Boolean.parseBoolean(System.getProperty("forge.threading.clientSimEntities", "false"));
    private static volatile Thread thread;
    private static volatile World world;
    private static volatile boolean running;
    private static volatile boolean logged;
    private static final AtomicLong TICKS = new AtomicLong();
    private static final Semaphore TICK_REQUESTS = new Semaphore(0);
    private static final ConcurrentLinkedQueue<SimTask> TASKS = new ConcurrentLinkedQueue<SimTask>();
    private static final ConcurrentLinkedQueue<Entity> PENDING_REMOVALS = new ConcurrentLinkedQueue<Entity>();
    private static volatile ClientRenderSnapshot renderSnapshot;

    private ClientSimulationThread()
    {
    }

    public static boolean isEnabled()
    {
        return ENABLED;
    }

    public static boolean isSimulationThread()
    {
        return Thread.currentThread() == thread;
    }

    public static boolean isEntityTickingEnabled()
    {
        return ENTITIES;
    }

    public static ClientRenderSnapshot getRenderSnapshot()
    {
        return renderSnapshot;
    }

    public static void appendOverlayLines(List<String> lines)
    {
        if (!ENABLED)
        {
            return;
        }
        ClientRenderSnapshot snapshot = renderSnapshot;
        lines.add("[ClientSim] running=" + (thread != null && thread.isAlive()) + " ticks=" + TICKS.get()
                + " entities=" + (snapshot == null ? 0 : snapshot.size()));
    }

    /**
     * Requests exactly one entity tick and waits for it. Called from the client tick loop.
     */
    public static void requestEntityTick(final World target)
    {
        if (!ENTITIES)
        {
            return;
        }

        ensureStarted(target);
        if (world == null)
        {
            return;
        }

        requestTaskInternal(new Runnable()
        {
            @Override
            public void run()
            {
                World current = target;
                if (current == null)
                {
                    return;
                }
                processPendingRemovals(current);
                current.updateEntities();
                processPendingRemovals(current);
                renderSnapshot = ClientRenderSnapshot.capture(current);
                // WorldClient.tick() stays on the client/main thread. It touches chunk spawn queues and block
                // update/render state; running it here raced block-change packets and left ghost blocks/drops.
                // The expensive entity tick remains on this thread.
            }
        }, true);
    }

    /**
     * Queues an entity for forced removal on the client simulation thread. Packet handlers run on the client thread
     * and can only mark the entity dead; updateEntities normally removes it, but explosion drop ordering and chunk
     * state can leave it visible. Removing it explicitly on the owning thread closes that gap.
     */
    public static void requestEntityRemoval(Entity entity)
    {
        if (!ENTITIES || entity == null)
        {
            return;
        }
        PENDING_REMOVALS.add(entity);
    }

    private static void processPendingRemovals(World current)
    {
        final boolean debugPickup = Boolean.getBoolean("forge.threading.debugPickup");
        Entity entity;
        while ((entity = PENDING_REMOVALS.poll()) != null)
        {
            if (current == null || entity.world != current)
            {
                continue;
            }

            entity.setDead();
            int chunkX = entity.chunkCoordX;
            int chunkZ = entity.chunkCoordZ;
            current.getChunk(chunkX, chunkZ).removeEntity(entity);
            boolean removed = current.loadedEntityList.remove(entity);
            if (removed)
            {
                current.onEntityRemoved(entity);
            }

            if (debugPickup)
            {
                LOGGER.info("[pickup-debug] forced client removal: itemId={} dead={} addedToChunk={} chunk=({},{}) removedFromLoaded={}",
                        Integer.valueOf(entity.getEntityId()), Boolean.valueOf(entity.isDead),
                        Boolean.valueOf(entity.addedToChunk), Integer.valueOf(chunkX), Integer.valueOf(chunkZ),
                        Boolean.valueOf(removed));
            }
        }
    }

    /**
     * Runs an arbitrary CPU-only client task on the simulation thread and waits for completion. Used for
     * animateTick/particle updates so the render thread does not execute that work (Phase 4C).
     */
    public static void requestTask(Runnable action)
    {
        if (!ENTITIES)
        {
            action.run();
            return;
        }

        ensureStarted(world);
        if (world == null)
        {
            action.run();
            return;
        }

        requestTaskInternal(action, false);
    }

    private static void requestTaskInternal(Runnable action, boolean entityTick)
    {
        SimTask task = new SimTask(action, entityTick);
        TASKS.add(task);
        TICK_REQUESTS.release();

        try
        {
            if (!task.done.await(2L, TimeUnit.SECONDS))
            {
                LOGGER.warn("[client-sim] timed out waiting for a simulation task");
            }
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
        }
    }

    // Retained compatibility helpers; the handshake now makes coarse locks unnecessary.
    public static void readLock()
    {
    }

    public static void readUnlock()
    {
    }

    public static void writeLock()
    {
    }

    public static void writeUnlock()
    {
    }

    public static void ensureStarted(World target)
    {
        if (!ENABLED)
        {
            return;
        }
        world = target;
        if (target == null)
        {
            stop();
            return;
        }
        if (thread == null || !thread.isAlive())
        {
            running = true;
            thread = new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    loop();
                }
            }, "Client Simulation Thread");
            thread.setDaemon(true);
            thread.start();
            if (!logged)
            {
                logged = true;
                LOGGER.info(ENTITIES
                        ? "Client simulation thread started (weather/time + entity tick)"
                        : "Client simulation thread started (weather/time phase)");
            }
        }
    }

    public static void stop()
    {
        running = false;
        world = null;
        renderSnapshot = null;
        TICK_REQUESTS.release(8);
        SimTask task;
        while ((task = TASKS.poll()) != null)
        {
            task.done.countDown();
        }
        Thread current = thread;
        if (current != null)
        {
            current.interrupt();
            thread = null;
        }
    }

    /**
     * Releases the static world/snapshot/per-entity references when a client world unloads. Without this the
     * simulation thread's {@code world} and last render snapshot keep the whole WorldClient reachable after a
     * disconnect or a switch back to the main menu.
     */
    public static int clearWorld(World target)
    {
        if (target != null && world != null && target != world)
        {
            return 0;
        }
        boolean cleared = world != null || renderSnapshot != null;
        PENDING_REMOVALS.clear();
        stop();
        return cleared ? 1 : 0;
    }

    private static void loop()
    {
        while (running)
        {
            if (ENTITIES)
            {
                try
                {
                    if (!TICK_REQUESTS.tryAcquire(100L, TimeUnit.MILLISECONDS))
                    {
                        continue;
                    }
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                    break;
                }

                SimTask task = TASKS.poll();
                if (task == null)
                {
                    continue;
                }

                try
                {
                    BackgroundPacer.yieldIfNeeded();
                    task.action.run();
                }
                catch (Throwable throwable)
                {
                    LOGGER.error("[client-sim] exception in client simulation thread", throwable);
                    running = false;
                }
                finally
                {
                    task.done.countDown();
                }

                if (!task.entityTick)
                {
                    continue;
                }

                long ticks = TICKS.incrementAndGet();
                if (ticks % 100L == 0L)
                {
                    World loggedWorld = world;
                    ClientRenderSnapshot snapshot = renderSnapshot;
                    LOGGER.info("[client-sim] ticks={} entities={} snapshot={}", Long.valueOf(ticks),
                            Integer.valueOf(loggedWorld == null ? 0 : loggedWorld.loadedEntityList.size()),
                            Integer.valueOf(snapshot == null ? 0 : snapshot.size()));
                }
                continue;
            }

            World current = world;
            if (current == null)
            {
                break;
            }

            long started = System.nanoTime();
            try
            {
                current.tick();
            }
            catch (Throwable throwable)
            {
                LOGGER.error("[client-sim] exception in client simulation thread", throwable);
                running = false;
                break;
            }

            long elapsedNanos = System.nanoTime() - started;
            long sleepMillis = 50L - elapsedNanos / 1000000L;
            if (sleepMillis > 0L)
            {
                try
                {
                    Thread.sleep(sleepMillis);
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private static final class SimTask
    {
        private final Runnable action;
        private final boolean entityTick;
        private final CountDownLatch done = new CountDownLatch(1);

        private SimTask(Runnable action, boolean entityTick)
        {
            this.action = action;
            this.entityTick = entityTick;
        }
    }
}
