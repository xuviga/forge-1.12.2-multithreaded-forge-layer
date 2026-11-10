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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Budgeted pre-generation.
 *
 * <p>Two sources feed the queue:
 * <ul>
 *   <li>{@code /forgepregen <radius>} - a square around the command sender;</li>
 *   <li>flight prediction - when a player moves faster than {@code forge.threading.flightPregenSpeed} blocks/tick,
 *       a small patch of chunks is queued along the extrapolated velocity vector. The player therefore flies into
 *       terrain that is already generated instead of generating it at the view edge.</li>
 * </ul>
 *
 * <p>When the async chunk generator is available the work is queued on it (low priority, so live player requests
 * always win) and the chunks are unloaded after generation, which persists them to disk. Otherwise the old
 * server-thread path is used with the same per-tick budget.
 *
 * <p>Disable flight prediction with {@code -Dforge.threading.flightPregen=false}. Tunables:
 * {@code flightPregenSpeed} (0.6 blocks/tick), {@code flightPregenLookahead} (80 ticks),
 * {@code flightPregenRadius} (2 chunks), {@code flightPregenPerTick} (4 submissions),
 * {@code flightPregenMaxQueue} (256), {@code pregenBudgetMs} (12).
 */
public final class PregenManager
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");

    private static final Deque<ChunkPos> QUEUE = new ArrayDeque<ChunkPos>();
    private static WorldServer world;
    private static int total;
    private static int done;

    private static final Deque<FlightTarget> FLIGHT_QUEUE = new ArrayDeque<FlightTarget>();
    private static final Set<FlightTarget> FLIGHT_QUEUED = new HashSet<FlightTarget>();
    private static final Map<Integer, double[]> FLIGHT_LAST = new HashMap<Integer, double[]>();
    private static long flightTicks;

    private static final boolean FLIGHT_ENABLED = Boolean.parseBoolean(
            System.getProperty("forge.threading.flightPregen", "true"));
    private static final double FLIGHT_SPEED = parseDouble("forge.threading.flightPregenSpeed", 0.6D);
    private static final int FLIGHT_LOOKAHEAD = Math.max(5, Integer.getInteger("forge.threading.flightPregenLookahead", 80).intValue());
    private static final int FLIGHT_RADIUS = Math.max(0, Math.min(4, Integer.getInteger("forge.threading.flightPregenRadius", 2).intValue()));
    private static final int FLIGHT_PER_TICK = Math.max(1, Integer.getInteger("forge.threading.flightPregenPerTick", 4).intValue());
    private static final int FLIGHT_MAX_QUEUE = Math.max(16, Integer.getInteger("forge.threading.flightPregenMaxQueue", 256).intValue());
    private static final int FLIGHT_ADD_PER_TICK = Math.max(1, Integer.getInteger("forge.threading.flightPregenAddPerTick", 24).intValue());

    private PregenManager()
    {
    }

    public static synchronized int start(WorldServer target, int centerChunkX, int centerChunkZ, int radius)
    {
        QUEUE.clear();
        world = target;
        total = 0;
        done = 0;

        List<ChunkPos> positions = new ArrayList<ChunkPos>();
        for (int dx = -radius; dx <= radius; ++dx)
        {
            for (int dz = -radius; dz <= radius; ++dz)
            {
                positions.add(new ChunkPos(centerChunkX + dx, centerChunkZ + dz));
            }
        }
        positions.sort(new Comparator<ChunkPos>()
        {
            @Override
            public int compare(ChunkPos a, ChunkPos b)
            {
                long da = (long)(a.x - centerChunkX) * (a.x - centerChunkX) + (long)(a.z - centerChunkZ) * (a.z - centerChunkZ);
                long db = (long)(b.x - centerChunkX) * (b.x - centerChunkX) + (long)(b.z - centerChunkZ) * (b.z - centerChunkZ);
                return Long.compare(da, db);
            }
        });
        QUEUE.addAll(positions);
        total = positions.size();
        LOGGER.info("[ForgeThreading] pregen started: center=({}, {}) radius={} chunks={}",
                Integer.valueOf(centerChunkX), Integer.valueOf(centerChunkZ), Integer.valueOf(radius), Integer.valueOf(total));
        return total;
    }

    public static synchronized boolean isRunning()
    {
        return !QUEUE.isEmpty() || !FLIGHT_QUEUE.isEmpty();
    }

    public static synchronized int getRemaining()
    {
        return QUEUE.size() + FLIGHT_QUEUE.size();
    }

    /** Aborts pre-generation and drops the static WorldServer reference when that world unloads. */
    public static synchronized int clearWorld(World target)
    {
        if (target != null && world != null && target != world)
        {
            return 0;
        }
        boolean cleared = world != null || !QUEUE.isEmpty();
        QUEUE.clear();
        world = null;
        total = 0;
        done = 0;

        for (java.util.Iterator<FlightTarget> iterator = FLIGHT_QUEUE.iterator(); iterator.hasNext(); )
        {
            FlightTarget flight = iterator.next();
            if (target == null || flight.world == target)
            {
                iterator.remove();
                FLIGHT_QUEUED.remove(flight);
            }
        }

        if (target == null)
        {
            FLIGHT_QUEUED.clear();
            FLIGHT_LAST.clear();
        }

        return cleared ? 1 : 0;
    }

    public static void tick(MinecraftServer server)
    {
        if (server == null)
        {
            return;
        }

        if (FLIGHT_ENABLED)
        {
            scheduleFlight(server);
        }

        long budgetMs = Math.max(1L, Long.getLong("forge.threading.pregenBudgetMs", 12L).longValue());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);

        processManualQueue(deadline);
        processFlightQueue(server, deadline);

        boolean finished;
        int remaining;
        synchronized (PregenManager.class)
        {
            finished = QUEUE.isEmpty();
            remaining = QUEUE.size();
            if (finished && world != null)
            {
                world = null;
            }
        }

        if (finished && done > 0 && (done & 63) == 0)
        {
            LOGGER.info("[ForgeThreading] pregen progress: {}/{} remaining={}", Integer.valueOf(done),
                    Integer.valueOf(total), Integer.valueOf(remaining));
        }
    }

    private static void processManualQueue(long deadline)
    {
        while (true)
        {
            WorldServer target;
            ChunkPos pos;

            synchronized (PregenManager.class)
            {
                if (QUEUE.isEmpty() || world == null)
                {
                    break;
                }
                pos = QUEUE.poll();
                target = world;
                ++done;
            }

            if (System.nanoTime() >= deadline)
            {
                synchronized (PregenManager.class)
                {
                    QUEUE.addFirst(pos);
                    --done;
                }
                break;
            }

            generate(target, pos.x, pos.z, 50, true);
        }
    }

    private static void processFlightQueue(MinecraftServer server, long deadline)
    {
        int submitted = 0;

        while (submitted < FLIGHT_PER_TICK)
        {
            FlightTarget target;

            synchronized (PregenManager.class)
            {
                target = FLIGHT_QUEUE.poll();
                if (target == null)
                {
                    break;
                }
                FLIGHT_QUEUED.remove(target);
            }

            if (target.world.getMinecraftServer() != server)
            {
                continue;
            }
            if (target.world.getChunkProvider().getLoadedChunk(target.x, target.z) != null)
            {
                continue;
            }
            if (target.world.getChunkProvider().isChunkGeneratedAt(target.x, target.z))
            {
                continue;
            }

            generate(target.world, target.x, target.z, 100, true);
            ++submitted;

            if (System.nanoTime() >= deadline)
            {
                break;
            }
        }
    }

    private static void generate(WorldServer target, int x, int z, int priority, boolean unloadWhenDone)
    {
        ChunkProviderServer provider = target.getChunkProvider();

        if (provider.getLoadedChunk(x, z) != null || provider.isChunkGeneratedAt(x, z))
        {
            return;
        }

        if (ChunkGenExecutor.isEnabled() && ChunkGenExecutor.supports(provider))
        {
            if (!ChunkGenExecutor.submit(provider, x, z, null, priority, unloadWhenDone))
            {
                // Queue is full: retry later instead of stalling the server thread.
                synchronized (PregenManager.class)
                {
                    if (priority >= 100)
                    {
                        FLIGHT_QUEUE.addFirst(new FlightTarget(target, x, z));
                        FLIGHT_QUEUED.add(new FlightTarget(target, x, z));
                    }
                    else
                    {
                        QUEUE.addFirst(new ChunkPos(x, z));
                    }
                }
            }
            return;
        }

        try
        {
            Chunk chunk = provider.provideChunk(x, z);
            if (chunk != null)
            {
                provider.queueUnload(chunk);
            }
        }
        catch (Throwable throwable)
        {
            LOGGER.error("[ForgeThreading] pregen failed at chunk ({}, {})", Integer.valueOf(x), Integer.valueOf(z), throwable);
        }
    }

    private static void scheduleFlight(MinecraftServer server)
    {
        if (server.getPlayerList() == null)
        {
            return;
        }

        List<EntityPlayerMP> players = server.getPlayerList().getPlayers();
        int added = 0;
        ++flightTicks;

        synchronized (PregenManager.class)
        {
            if ((flightTicks & 255L) == 0L)
            {
                Set<Integer> online = new HashSet<Integer>();
                for (EntityPlayerMP player : players)
                {
                    online.add(Integer.valueOf(player.getEntityId()));
                }
                FLIGHT_LAST.keySet().retainAll(online);
            }

            for (EntityPlayerMP player : players)
            {
                WorldServer target = player.getServerWorld();
                if (target == null)
                {
                    continue;
                }

                double[] last = FLIGHT_LAST.get(Integer.valueOf(player.getEntityId()));
                if (last == null)
                {
                    FLIGHT_LAST.put(Integer.valueOf(player.getEntityId()), new double[] {player.posX, player.posZ});
                    continue;
                }

                double dx = player.posX - last[0];
                double dz = player.posZ - last[1];
                last[0] = player.posX;
                last[1] = player.posZ;

                if (dx * dx + dz * dz < FLIGHT_SPEED * FLIGHT_SPEED)
                {
                    continue;
                }

                double targetX = player.posX + dx * FLIGHT_LOOKAHEAD;
                double targetZ = player.posZ + dz * FLIGHT_LOOKAHEAD;
                int centerX = MathHelper.floor(targetX) >> 4;
                int centerZ = MathHelper.floor(targetZ) >> 4;

                outer:
                for (int ax = -FLIGHT_RADIUS; ax <= FLIGHT_RADIUS; ++ax)
                {
                    for (int az = -FLIGHT_RADIUS; az <= FLIGHT_RADIUS; ++az)
                    {
                        if (FLIGHT_QUEUE.size() >= FLIGHT_MAX_QUEUE || added >= FLIGHT_ADD_PER_TICK)
                        {
                            break outer;
                        }

                        int x = centerX + ax;
                        int z = centerZ + az;
                        if (target.getChunkProvider().getLoadedChunk(x, z) != null
                                || target.getChunkProvider().isChunkGeneratedAt(x, z))
                        {
                            continue;
                        }

                        FlightTarget flight = new FlightTarget(target, x, z);
                        if (FLIGHT_QUEUED.add(flight))
                        {
                            FLIGHT_QUEUE.add(flight);
                            ++added;
                        }
                    }
                }
            }
        }
    }

    private static double parseDouble(String property, double fallback)
    {
        try
        {
            return Double.parseDouble(System.getProperty(property, Double.toString(fallback)));
        }
        catch (NumberFormatException ignored)
        {
            return fallback;
        }
    }

    private static final class FlightTarget
    {
        private final WorldServer world;
        private final int x;
        private final int z;

        private FlightTarget(WorldServer world, int x, int z)
        {
            this.world = world;
            this.x = x;
            this.z = z;
        }

        @Override
        public int hashCode()
        {
            return System.identityHashCode(this.world) * 31 + (int)(ChunkPos.asLong(this.x, this.z) * 13L);
        }

        @Override
        public boolean equals(Object other)
        {
            if (!(other instanceof FlightTarget))
            {
                return false;
            }
            FlightTarget flight = (FlightTarget)other;
            return flight.world == this.world && flight.x == this.x && flight.z == this.z;
        }
    }
}
