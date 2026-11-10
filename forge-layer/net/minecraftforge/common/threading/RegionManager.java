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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityBeacon;
import net.minecraft.tileentity.TileEntityBrewingStand;
import net.minecraft.tileentity.TileEntityEnchantmentTable;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Experimental Phase 3 regionized entity ticking.
 *
 * The main/dimension owner still performs the WORLD and BLOCK_ENTITIES phases (weather, blocks, tile entities,
 * player list). Entities are bucketed by Region and ticked concurrently; block/tile-entity mutations made by entity
 * code go through {@link DeferredMutationQueue} and are committed by the owner. A barrier joins all regions before
 * tracking/output work resumes.
 *
 * Enabled by default on machines with enough cores; override with -Dforge.threading.regions=N
 * (N &gt;= 2, 0 disables) and -Dforge.threading.regionSizeChunks=16.
 */
public final class RegionManager
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static volatile ExecutorService[] workers;
    private static volatile ExecutorService pool;
    private static volatile boolean logged;
    private static volatile boolean loggedTiles;
    private static volatile boolean loggedBlocks;
    private static volatile boolean loggedScheduled;
    private static final long SLOW_ENTITY_NANOS = Long.getLong("forge.threading.slowEntityMs", 2L).longValue() * 1000000L;
    private static final long SLOW_REGION_NANOS = Long.getLong("forge.threading.slowRegionMs", 50L).longValue() * 1000000L;
    private static final boolean REGION_ERRORS_FATAL = Boolean.parseBoolean(System.getProperty("forge.threading.regionErrorsFatal", "false"));
    private static final java.util.concurrent.atomic.AtomicLong REGION_ERRORS = new java.util.concurrent.atomic.AtomicLong();
    private static final boolean ENTITY_LOD = Boolean.parseBoolean(System.getProperty("forge.threading.entityLod", "true"));
    private static volatile int LOD_NEAR = Integer.getInteger("forge.threading.entityLodNear", 48).intValue();
    private static volatile int LOD_MID = Integer.getInteger("forge.threading.entityLodMid", 96).intValue();
    private static volatile int LOD_FAR = Integer.getInteger("forge.threading.entityLodFar", 160).intValue();
    /** Reduced entity rate during the first seconds after server start, while spawn chunks/entities are loading. */
    private static final int STARTUP_GRACE_TICKS = Math.max(0,
            Integer.getInteger("forge.threading.startupEntityGraceTicks", 200).intValue());
    private static final int STARTUP_INTERVAL = Math.max(1,
            Integer.getInteger("forge.threading.startupEntityInterval", 2).intValue());
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> SLOW_ENTITY_COUNTS =
            new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>();
    /** Per-world entity -> region ownership maps, published before workers start and read by the audit. */
    private static final java.util.concurrent.ConcurrentHashMap<net.minecraft.world.World, java.util.Map<Entity, Region>> ENTITY_OWNERS =
            new java.util.concurrent.ConcurrentHashMap<net.minecraft.world.World, java.util.Map<Entity, Region>>();

    private RegionManager()
    {
    }

    public static boolean isEnabled()
    {
        return Threading.isRegionTickingEnabled();
    }

    public static void tickEntities(WorldServer world)
    {
        if (!isEnabled())
        {
            return;
        }

        if (world.playerEntities.isEmpty() && world.getPersistentChunks().isEmpty())
        {
            // Vanilla freezes entity updates on an empty server. Do not spend region workers on the spawn-area
            // population while the server is booting or nobody is online.
            return;
        }

        int size = Threading.getRegionSizeChunks();
        int dimension = world.provider.getDimension();
        TreeMap<Integer, Bucket> buckets = new TreeMap<Integer, Bucket>();
        java.util.IdentityHashMap<Entity, Region> owners = new java.util.IdentityHashMap<Entity, Region>();

        for (int index = 0; index < world.loadedEntityList.size(); index++)
        {
            Entity entity = world.loadedEntityList.get(index);
            if (entity.isDead || entity instanceof EntityPlayerMP || entity.getRidingEntity() != null
                    || !world.shouldTickEntity(entity))
            {
                continue;
            }

            Region region = Region.forChunk(regionId(entity.chunkCoordX, entity.chunkCoordZ, size),
                    dimension, entity.chunkCoordX, entity.chunkCoordZ, size);
            owners.put(entity, region);

            if (shouldSkipForEntityLod(world, entity))
            {
                ThreadingTelemetry.increment("entity.lod.skipped");
                continue;
            }

            Bucket bucket = buckets.get(Integer.valueOf(region.getId()));
            if (bucket == null)
            {
                bucket = new Bucket(region);
                buckets.put(Integer.valueOf(region.getId()), bucket);
            }
            bucket.entities.add(entity);
        }

        ENTITY_OWNERS.put(world, owners);

        if (buckets.isEmpty())
        {
            return;
        }

        if (!logged)
        {
            logged = true;
            LOGGER.info("Regionized entity tick: {} regions across {} workers", Integer.valueOf(buckets.size()),
                    Integer.valueOf(Threading.getRegionThreads()));
        }

        List<Future<?>> futures = new ArrayList<Future<?>>(buckets.size());
        final long tickId = world.getTotalWorldTime();

        for (Bucket bucket : buckets.values())
        {
            final Bucket task = bucket;
            futures.add(submit(task.region, new Runnable()
            {
                @Override
                public void run()
                {
                    TickContext context = TickContext.getOrCreate();
                    context.setWorld(world);
                    context.setRegion(task.region);
                    context.setPhase(TickPhase.ENTITIES);
                    context.setTickId(tickId);

                    long taskStart = System.nanoTime();
                    try
                    {
                        for (int index = 0; index < task.entities.size(); index++)
                        {
                            Entity entity = task.entities.get(index);
                            if (!entity.isDead)
                            {
                                context.setRngKey(entityRngKey(entity));
                                long entityStart = System.nanoTime();
                                try
                                {
                                    world.updateEntity(entity);
                                }
                                finally
                                {
                                    long entityElapsed = System.nanoTime() - entityStart;
                                    if (entityElapsed > SLOW_ENTITY_NANOS)
                                    {
                                        logSlowEntity(entity, dimension, task.region.getId(), entityElapsed);
                                    }
                                    context.clearRngKey();
                                }
                            }
                        }
                    }
                    finally
                    {
                        long taskElapsed = System.nanoTime() - taskStart;
                        if (taskElapsed > SLOW_REGION_NANOS)
                        {
                            LOGGER.warn("[slow-region] dim={} region={} entities={} took {} ms",
                                    Integer.valueOf(dimension), Integer.valueOf(task.region.getId()),
                                    Integer.valueOf(task.entities.size()), Double.valueOf(taskElapsed / 1.0E6D));
                        }
                        ThreadingMetrics.recordRegionTask("entity", taskElapsed, task.entities.size());
                        TickContext.clear();
                    }
                }
            }));
        }

        await(futures, "region entity tick");
        cleanupDead(world);
    }

    /**
     * Deterministic spatial AI-LOD. Entities within 48 blocks of a player tick every tick; the next rings tick every
     * 2/4/8 ticks. Skipping is based on (entityId + worldTime) so it is replay-stable and does not depend on worker
     * count. This cuts distant mob farm/item load without changing visible near-player behaviour.
     */
    private static boolean shouldSkipForEntityLod(net.minecraft.world.World world, Entity entity)
    {
        if (!ENTITY_LOD)
        {
            return false;
        }

        int interval = entityLodInterval(world, entity);
        net.minecraft.server.MinecraftServer server = world.getMinecraftServer();
        if (STARTUP_GRACE_TICKS > 0 && server != null && server.getTickCounter() < STARTUP_GRACE_TICKS)
        {
            // Spawn chunks populate a few hundred animals at boot; ticking all of them at full rate is what created
            // the 5-second TPS dip right after "Done". Halve the rate for the first 10 seconds.
            interval = Math.max(interval, STARTUP_INTERVAL);
        }
        if (interval <= 1)
        {
            return false;
        }

        long tick = world.getTotalWorldTime() & 0x7fffffffL;
        return Math.floorMod(entity.getEntityId() + (int)tick, interval) != 0;
    }

    public static int getLodNear()
    {
        return LOD_NEAR;
    }

    public static int getLodMid()
    {
        return LOD_MID;
    }

    public static int getLodFar()
    {
        return LOD_FAR;
    }

    public static void setLodRadii(int near, int mid, int far)
    {
        LOD_NEAR = Math.max(16, Math.min(64, near));
        LOD_MID = Math.max(LOD_NEAR + 8, Math.min(128, mid));
        LOD_FAR = Math.max(LOD_MID + 8, Math.min(256, far));
    }

    private static int entityLodInterval(net.minecraft.world.World world, Entity entity)
    {
        java.util.List<? extends Entity> players = world.playerEntities;
        if (players.isEmpty())
        {
            return 1;
        }

        double nearest = Double.MAX_VALUE;
        for (int index = 0; index < players.size(); index++)
        {
            Entity player = players.get(index);
            double dx = entity.posX - player.posX;
            double dz = entity.posZ - player.posZ;
            double distanceSq = dx * dx + dz * dz;
            if (distanceSq < nearest)
            {
                nearest = distanceSq;
            }
        }

        if (nearest <= (double)LOD_NEAR * (double)LOD_NEAR)
        {
            return 1;
        }
        if (nearest <= (double)LOD_MID * (double)LOD_MID)
        {
            return 2;
        }
        if (nearest <= (double)LOD_FAR * (double)LOD_FAR)
        {
            return 4;
        }
        return 8;
    }

    private static void logSlowEntity(Entity entity, int dimension, int regionId, long elapsedNanos)
    {
        String key = entity.getClass().getName();
        java.util.concurrent.atomic.AtomicLong counter = SLOW_ENTITY_COUNTS.get(key);
        if (counter == null)
        {
            java.util.concurrent.atomic.AtomicLong created = new java.util.concurrent.atomic.AtomicLong();
            java.util.concurrent.atomic.AtomicLong previous = SLOW_ENTITY_COUNTS.putIfAbsent(key, created);
            counter = previous == null ? created : previous;
        }

        long hits = counter.incrementAndGet();
        if (hits <= 5L || hits % 100L == 0L)
        {
            LOGGER.warn("[slow-entity] {} id={} dim={} region={} took {} ms (hit #{})",
                    key, Integer.valueOf(entity.getEntityId()), Integer.valueOf(dimension), Integer.valueOf(regionId),
                    Double.valueOf(elapsedNanos / 1.0E6D), Long.valueOf(hits));
        }
    }

    /**
     * Ticks the safe subset of tile entities per region. Everything else (hoppers, pistons, spawners, chests,
     * shulkers, ...) stays serial on the owner thread after the region barrier.
     */
    public static void tickBlockEntities(WorldServer world)
    {
        if (!isEnabled())
        {
            return;
        }

        int size = Threading.getRegionSizeChunks();
        int dimension = world.provider.getDimension();
        TreeMap<Integer, TileBucket> buckets = new TreeMap<Integer, TileBucket>();
        List<TileEntity> serial = new ArrayList<TileEntity>();
        List<TileEntity> snapshot = new ArrayList<TileEntity>(world.tickableTileEntities);

        for (int index = 0; index < snapshot.size(); index++)
        {
            TileEntity tileEntity = snapshot.get(index);
            if (tileEntity == null || tileEntity.isInvalid() || !tileEntity.hasWorld()
                    || !world.shouldTickTileEntity(tileEntity))
            {
                continue;
            }

            if (!isWhitelisted(tileEntity))
            {
                serial.add(tileEntity);
                continue;
            }

            BlockPos pos = tileEntity.getPos();
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;
            Region region = Region.forChunk(regionId(chunkX, chunkZ, size), dimension, chunkX, chunkZ, size);
            TileBucket bucket = buckets.get(Integer.valueOf(region.getId()));
            if (bucket == null)
            {
                bucket = new TileBucket(region);
                buckets.put(Integer.valueOf(region.getId()), bucket);
            }
            bucket.tileEntities.add(tileEntity);
        }

        if (!buckets.isEmpty())
        {
            if (!loggedTiles)
            {
                loggedTiles = true;
                LOGGER.info("Regionized block entities: {} regions across {} workers, {} serial",
                        Integer.valueOf(buckets.size()), Integer.valueOf(Threading.getRegionThreads()),
                        Integer.valueOf(serial.size()));
            }

                List<Future<?>> futures = new ArrayList<Future<?>>(buckets.size());
            final long tickId = world.getTotalWorldTime();

            for (TileBucket bucket : buckets.values())
            {
                final TileBucket task = bucket;
                futures.add(submit(task.region, new Runnable()
                {
                    @Override
                    public void run()
                    {
                        TickContext context = TickContext.getOrCreate();
                        context.setWorld(world);
                        context.setRegion(task.region);
                        context.setPhase(TickPhase.BLOCK_ENTITIES);
                        context.setTickId(tickId);

                        long taskStart = System.nanoTime();
                        try
                        {
                            for (int index = 0; index < task.tileEntities.size(); index++)
                            {
                                TileEntity tileEntity = task.tileEntities.get(index);
                                context.setRngKey(tileEntity.getPos().toLong());
                                try
                                {
                                    world.tickTileEntity(tileEntity);
                                }
                                finally
                                {
                                    context.clearRngKey();
                                }
                            }
                        }
                        finally
                        {
                            ThreadingMetrics.recordRegionTask("te", System.nanoTime() - taskStart, task.tileEntities.size());
                            TickContext.clear();
                        }
                    }
                }));
            }

            await(futures, "region block-entity tick");
        }

        for (int index = 0; index < serial.size(); index++)
        {
            world.tickTileEntity(serial.get(index));
        }
    }

    /**
     * Parallel random block ticks. Weather/lightning/ice/snow stay on the owner thread; only the
     * {@code block.randomTick} work is regionized. Each region gets a deterministic RNG seeded from world seed, tick
     * and region id. Chunks are processed in (x, z) order and cross-region writes go through the deterministic
     * (regionId, phase, seq) commit queue, so repeated runs of the same save stay stable.
     */
    public static void tickRandomBlocks(WorldServer world, List<Chunk> chunks, int randomTickSpeed)
    {
        if (!isEnabled() || chunks.isEmpty() || randomTickSpeed <= 0)
        {
            return;
        }

        int size = Threading.getRegionSizeChunks();
        int dimension = world.provider.getDimension();
        TreeMap<Integer, ChunkBucket> buckets = new TreeMap<Integer, ChunkBucket>();
        List<Chunk> orderedChunks = new ArrayList<Chunk>(chunks);
        orderedChunks.sort(new java.util.Comparator<Chunk>()
        {
            @Override
            public int compare(Chunk first, Chunk second)
            {
                int x = Integer.compare(first.x, second.x);
                return x != 0 ? x : Integer.compare(first.z, second.z);
            }
        });

        for (int index = 0; index < orderedChunks.size(); index++)
        {
            Chunk chunk = orderedChunks.get(index);
            Region region = Region.forChunk(regionId(chunk.x, chunk.z, size), dimension, chunk.x, chunk.z, size);
            ChunkBucket bucket = buckets.get(Integer.valueOf(region.getId()));
            if (bucket == null)
            {
                bucket = new ChunkBucket(region);
                buckets.put(Integer.valueOf(region.getId()), bucket);
            }
            bucket.chunks.add(chunk);
        }

        if (!loggedBlocks)
        {
            loggedBlocks = true;
            LOGGER.info("Regionized random block ticks: {} regions across {} workers, speed {}",
                    Integer.valueOf(buckets.size()), Integer.valueOf(Threading.getRegionThreads()),
                    Integer.valueOf(randomTickSpeed));
        }

        List<Future<?>> futures = new ArrayList<Future<?>>(buckets.size());
        final long tickId = world.getTotalWorldTime();
        final int ticksPerChunk = randomTickSpeed;

        for (ChunkBucket bucket : buckets.values())
        {
            final ChunkBucket task = bucket;
            futures.add(submit(task.region, new Runnable()
            {
                @Override
                public void run()
                {
                    TickContext context = TickContext.getOrCreate();
                    context.setWorld(world);
                    context.setRegion(task.region);
                    context.setPhase(TickPhase.WORLD);
                    context.setTickId(tickId);

                    long taskStart = System.nanoTime();
                    try
                    {
                        for (int index = 0; index < task.chunks.size(); index++)
                        {
                            Chunk chunk = task.chunks.get(index);
                            int originX = chunk.x * 16;
                            int originZ = chunk.z * 16;
                            // Per-chunk RNG namespace: identical block tick results regardless of region size,
                            // worker count or which worker happens to execute this chunk.
                            long chunkKey = net.minecraft.util.math.ChunkPos.asLong(chunk.x, chunk.z);
                            context.setRngKey(chunkKey);
                            Random random = new Random(world.getSeed() ^ (tickId * 0x9e3779b97f4a7c15L) ^
                                    (chunkKey * 0x94d049bb133111ebL));
                            int lcg = (int)(world.getSeed() ^ (tickId * 0x9e3779b9L) ^ chunkKey);
                            try
                            {
                                for (ExtendedBlockStorage storage : chunk.getBlockStorageArray())
                                {
                                    if (storage == Chunk.NULL_BLOCK_STORAGE || !storage.needsRandomTick())
                                    {
                                        continue;
                                    }

                                    for (int tick = 0; tick < ticksPerChunk; tick++)
                                    {
                                        lcg = lcg * 3 + 1013904223;
                                        int value = lcg >> 2;
                                        int x = value & 15;
                                        int z = value >> 8 & 15;
                                        int y = value >> 16 & 15;
                                        IBlockState state = storage.get(x, y, z);
                                        Block block = state.getBlock();

                                        if (block.getTickRandomly())
                                        {
                                            block.randomTick(world, new BlockPos(originX + x,
                                                    storage.getYLocation() + y, originZ + z), state, random);
                                        }
                                    }
                                }
                            }
                            finally
                            {
                                context.clearRngKey();
                            }
                        }
                    }
                    finally
                    {
                        ThreadingMetrics.recordRegionTask("block", System.nanoTime() - taskStart, task.chunks.size());
                        TickContext.clear();
                    }
                }
            }));
        }

        MainThreadDispatcher.awaitAll(futures, "region random block tick");
    }

    /**
     * Parallel scheduled block/fluid updates. The owner has already extracted due NextTickListEntry objects from the
     * tick tree; this method buckets them by region and runs {@link Block#updateTick} on workers. Per-position RNG
     * keys keep results independent of region grouping, and cross-region writes go through World's deferred queue.
     *
     * @return true when the list was handled; false to let the vanilla sequential path run.
     */
    public static boolean tickScheduledUpdates(WorldServer world, List<net.minecraft.world.NextTickListEntry> entries)
    {
        if (!isEnabled() || !Threading.isParallelBlockUpdatesEnabled() || entries.isEmpty())
        {
            return false;
        }

        int size = Threading.getRegionSizeChunks();
        int dimension = world.provider.getDimension();
        TreeMap<Integer, UpdateBucket> buckets = new TreeMap<Integer, UpdateBucket>();
        List<net.minecraft.world.NextTickListEntry> unloaded = new ArrayList<net.minecraft.world.NextTickListEntry>();
        List<net.minecraft.world.NextTickListEntry> snapshot = new ArrayList<net.minecraft.world.NextTickListEntry>(entries);

        for (int index = 0; index < snapshot.size(); index++)
        {
            net.minecraft.world.NextTickListEntry entry = snapshot.get(index);

            // Modded updateTick implementations are not audited for region-worker execution. They may read
            // neighbouring chunks (which region workers now see as empty placeholders) or wait on main-thread
            // state; that is exactly how a "Timed out waiting for region scheduled block tick" happens. Let the
            // vanilla sequential path run the whole batch on the server thread instead.
            if (!entry.getBlock().getClass().getName().startsWith("net.minecraft."))
            {
                ThreadingTelemetry.increment("region.scheduled.moddedFallback");
                return false;
            }

            BlockPos pos = entry.position;
            if (!world.isAreaLoaded(pos, pos))
            {
                unloaded.add(entry);
                continue;
            }
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;
            Region region = Region.forChunk(regionId(chunkX, chunkZ, size), dimension, chunkX, chunkZ, size);
            UpdateBucket bucket = buckets.get(Integer.valueOf(region.getId()));
            if (bucket == null)
            {
                bucket = new UpdateBucket(region);
                buckets.put(Integer.valueOf(region.getId()), bucket);
            }
            bucket.entries.add(entry);
        }

        if (!buckets.isEmpty())
        {
            if (!loggedScheduled)
            {
                loggedScheduled = true;
                LOGGER.info("Regionized scheduled block updates: {} regions across {} workers, {} entries, {} unloaded",
                        Integer.valueOf(buckets.size()), Integer.valueOf(Threading.getRegionThreads()),
                        Integer.valueOf(snapshot.size()), Integer.valueOf(unloaded.size()));
                ThreadingTelemetry.add("region.scheduled.regions", buckets.size());
                ThreadingTelemetry.add("region.scheduled.entries", snapshot.size());
                ThreadingTelemetry.add("region.scheduled.unloaded", unloaded.size());
            }

            List<Future<?>> futures = new ArrayList<Future<?>>(buckets.size());
            final long tickId = world.getTotalWorldTime();

            for (final UpdateBucket bucket : buckets.values())
            {
                futures.add(submit(bucket.region, new Runnable()
                {
                    @Override
                    public void run()
                    {
                        TickContext context = TickContext.getOrCreate();
                        context.setWorld(world);
                        context.setRegion(bucket.region);
                        context.setPhase(TickPhase.WORLD);
                        context.setTickId(tickId);
                        long taskStart = System.nanoTime();
                        try
                        {
                            for (int index = 0; index < bucket.entries.size(); index++)
                            {
                                net.minecraft.world.NextTickListEntry entry = bucket.entries.get(index);
                                BlockPos pos = entry.position;
                                IBlockState state = world.getBlockState(pos);
                                if (state.getMaterial() != net.minecraft.block.material.Material.AIR &&
                                        Block.isEqualTo(state.getBlock(), entry.getBlock()))
                                {
                                    context.setRngKey(pos.toLong());
                                    Random random = new Random(world.getSeed() ^ (tickId * 0x9e3779b97f4a7c15L) ^
                                            (pos.toLong() * 0x94d049bb133111ebL));
                                    try
                                    {
                                        state.getBlock().updateTick(world, pos, state, random);
                                    }
                                    catch (Throwable throwable)
                                    {
                                        net.minecraft.crash.CrashReport report = net.minecraft.crash.CrashReport.makeCrashReport(throwable, "Exception while ticking a block");
                                        net.minecraft.crash.CrashReportCategory category = report.makeCategory("Block being ticked");
                                        net.minecraft.crash.CrashReportCategory.addBlockInfo(category, pos, state);
                                        throw new net.minecraft.util.ReportedException(report);
                                    }
                                    finally
                                    {
                                        context.clearRngKey();
                                    }
                                }
                            }
                        }
                        finally
                        {
                            ThreadingMetrics.recordRegionTask("scheduled", System.nanoTime() - taskStart, bucket.entries.size());
                            TickContext.clear();
                        }
                    }
                }));
            }

            await(futures, "region scheduled block tick");
        }

        for (int index = 0; index < unloaded.size(); index++)
        {
            net.minecraft.world.NextTickListEntry entry = unloaded.get(index);
            world.scheduleUpdate(entry.position, entry.getBlock(), 0);
        }

        world.flushDeferred();
        return true;
    }

    private static boolean isWhitelisted(TileEntity tileEntity)
    {
        return tileEntity instanceof TileEntityFurnace ||
                tileEntity instanceof TileEntityBrewingStand ||
                tileEntity instanceof TileEntityBeacon ||
                tileEntity instanceof TileEntityEnchantmentTable;
    }

    private static void await(List<Future<?>> futures, String what)
    {
        // Pumps forwarded MAIN-thread event handlers while waiting, so legacy mods keep synchronous semantics.
        MainThreadDispatcher.awaitAll(futures, what);
    }

    /** Stable RNG namespace for an entity, independent of region assignment. */
    private static long entityRngKey(Entity entity)
    {
        java.util.UUID id = entity.getUniqueID();
        return id.getMostSignificantBits() * 0x9E3779B97F4A7C15L ^ id.getLeastSignificantBits();
    }

    private static void cleanupDead(WorldServer world)
    {
        for (int index = 0; index < world.loadedEntityList.size(); )
        {
            Entity entity = world.loadedEntityList.get(index);
            if (entity.isDead)
            {
                int chunkX = entity.chunkCoordX;
                int chunkZ = entity.chunkCoordZ;
                if (entity.addedToChunk && world.getChunkProvider().chunkExists(chunkX, chunkZ))
                {
                    world.getChunk(chunkX, chunkZ).removeEntity(entity);
                }
                world.loadedEntityList.remove(index);
                world.onEntityRemoved(entity);
            }
            else
            {
                index++;
            }
        }
    }

    /**
     * @return the region that owns the entity for the current tick, or null for main-owned entities (players,
     *         passengers) and when regionized ticking is disabled.
     */
    public static Region getEntityOwner(Entity entity)
    {
        if (entity == null)
        {
            return null;
        }
        net.minecraft.world.World world = entity.world;
        java.util.Map<Entity, Region> owners = world == null ? null : ENTITY_OWNERS.get(world);
        return owners == null ? null : owners.get(entity);
    }

    /** Drops the per-world entity ownership map. Called on WorldEvent.Unload so an unloaded dimension can be GC'd. */
    public static int clearEntityOwners(net.minecraft.world.World world)
    {
        return world != null && ENTITY_OWNERS.remove(world) != null ? 1 : 0;
    }

    public static int getTrackedWorldCount()
    {
        return ENTITY_OWNERS.size();
    }

    public static void shutdown()
    {
        ENTITY_OWNERS.clear();
        ExecutorService[] current = workers;
        if (current != null)
        {
            for (int index = 0; index < current.length; index++)
            {
                if (current[index] != null)
                {
                    current[index].shutdownNow();
                }
            }
            workers = null;
        }

        ExecutorService executor = pool;
        if (executor != null)
        {
            executor.shutdownNow();
            pool = null;
        }
    }

    /**
     * Submits a task to the single worker owning its region. This is the static region assignment: the same region
     * always runs on the same thread, and an entity that crosses regions is naturally picked up by the new region's
     * worker on the next tick.
     */
    private static Future<?> submit(final Region region, final Runnable task)
    {
        Runnable safeTask = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    task.run();
                }
                catch (Throwable throwable)
                {
                    if (REGION_ERRORS_FATAL)
                    {
                        if (throwable instanceof RuntimeException)
                        {
                            throw (RuntimeException)throwable;
                        }
                        if (throwable instanceof Error)
                        {
                            throw (Error)throwable;
                        }
                        throw new RuntimeException(throwable);
                    }

                    long count = REGION_ERRORS.incrementAndGet();
                    if (count <= 5L || count % 100L == 0L)
                    {
                        ThreadingTelemetry.increment("region.errors");
                        LOGGER.error("[region-error] {} region={} task failed; continuing (hit #{})",
                                Thread.currentThread().getName(), Integer.valueOf(region.getId()), Long.valueOf(count), throwable);
                    }
                }
            }
        };

        if (Threading.isRegionAffinityEnabled())
        {
            ExecutorService[] current = getWorkers();
            int slot = Math.floorMod(region.getId(), current.length);
            return current[slot].submit(safeTask);
        }
        return getPool().submit(safeTask);
    }

    private static ExecutorService getPool()
    {
        ExecutorService executor = pool;
        if (executor == null)
        {
            synchronized (RegionManager.class)
            {
                executor = pool;
                if (executor == null)
                {
                    executor = Executors.newFixedThreadPool(Threading.getRegionThreads(), new ThreadFactory()
                    {
                        @Override
                        public Thread newThread(Runnable runnable)
                        {
                            Thread thread = new Thread(net.minecraftforge.fml.common.thread.SidedThreadGroups.SERVER,
                                    runnable, "Server Region Thread #" + THREAD_IDS.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        }
                    });
                    pool = executor;
                    ThreadingTelemetry.registerPool("region", executor);
                }
            }
        }
        return executor;
    }

    private static ExecutorService[] getWorkers()
    {
        ExecutorService[] current = workers;
        if (current == null)
        {
            synchronized (RegionManager.class)
            {
                current = workers;
                if (current == null)
                {
                    int count = Threading.getRegionThreads();
                    current = new ExecutorService[count];
                    for (int index = 0; index < count; index++)
                    {
                        current[index] = Executors.newSingleThreadExecutor(new ThreadFactory()
                        {
                            @Override
                            public Thread newThread(Runnable runnable)
                            {
                                Thread thread = new Thread(net.minecraftforge.fml.common.thread.SidedThreadGroups.SERVER,
                                        runnable, "Server Region Thread #" + THREAD_IDS.incrementAndGet());
                                thread.setDaemon(true);
                                return thread;
                            }
                        });
                    }
                    workers = current;
                }
            }
        }
        return current;
    }

    private static int regionId(int chunkX, int chunkZ, int size)
    {
        int regionX = Math.floorDiv(chunkX, size);
        int regionZ = Math.floorDiv(chunkZ, size);
        return (regionX & 0xffff) << 16 | (regionZ & 0xffff);
    }

    private static final class Bucket
    {
        private final Region region;
        private final List<Entity> entities = new ArrayList<Entity>();

        private Bucket(Region region)
        {
            this.region = region;
        }
    }

    private static final class TileBucket
    {
        private final Region region;
        private final List<TileEntity> tileEntities = new ArrayList<TileEntity>();

        private TileBucket(Region region)
        {
            this.region = region;
        }
    }

    private static final class ChunkBucket
    {
        private final Region region;
        private final List<Chunk> chunks = new ArrayList<Chunk>();

        private ChunkBucket(Region region)
        {
            this.region = region;
        }
    }

    private static final class UpdateBucket
    {
        private final Region region;
        private final List<net.minecraft.world.NextTickListEntry> entries =
                new ArrayList<net.minecraft.world.NextTickListEntry>();

        private UpdateBucket(Region region)
        {
            this.region = region;
        }
    }
}
