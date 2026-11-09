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

/**
 * Global switches for the multithreaded fork.
 *
 * World ticking uses all practical CPU parallelism by default: dimensions tick on a fixed pool, each dimension's
 * entity/TE/random-block/scheduled-update work is split into regions, lighting is sharded, pathfinding runs
 * asynchronously and PLAY packets are serialized per player off the game thread. The owner thread still performs the
 * truly global phases (weather, time, chunk load/unload, tracking, global registries).
 *
 * This is the maximum practical parallelism for 1.12.2 short of rewriting the world/network/render contracts.
 *
 *   -Dforge.threading=audit                       enable ThreadAudit logging
 *   -Dforge.threading.audit=true                  same
 *   -Dforge.threading.parallelDimensions=N        tick up to N dimensions concurrently (default auto, 1 = vanilla)
 *   -Dforge.threading.regions=N                   region workers per world (default auto, 0 = disable)
 *   -Dforge.threading.parallelBlockUpdates=false  disable parallel scheduled block/fluid ticks
 *   -Dforge.threading.parallelLighting=false      disable sharded light propagation
 *   -Dforge.threading.packetSerialization=false   disable per-player packet serialization
 *   -Dforge.threading.inboundPipeline=false       disable off-event-loop inbound packet handlers
 *   -Dforge.threading.renderPipeline=false        disable the client render-prep thread
 *   -Dforge.threading.singleThreaded=true          force the old single-threaded world tick (vanilla escape hatch)
 *
 * Loading phases that are independent by construction (mod jar/class scanning and recipe JSON construction) also
 * use multiple cores. Lifecycle events themselves stay serial: mods share global registries and the event bus, so
 * running their preInit/init/postInit concurrently would corrupt that state.
 *
 *   -Dforge.threading.parallelLoading=false        disable all load-time parallelism
 *   -Dforge.threading.loadThreads=N                worker count for loading (default min(cores, 8))
 *   -Dforge.threading.parallelModDiscovery=false   scan mod jars/directories on one thread
 *   -Dforge.threading.parallelRecipeLoading=false  parse/build recipe JSON on one thread
 *   -Dforge.threading.parallelModelLoading=false   resolve block/item/blockstate models on one thread
 */
public final class Threading
{
    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("ForgeThreading");
    public static final String PROP_PARALLEL_DIMENSIONS = "forge.threading.parallelDimensions";
    public static final String PROP_SINGLE_THREADED = "forge.threading.singleThreaded";
    public static final String PROP_PARALLEL_LOADING = "forge.threading.parallelLoading";
    private static final int MAX_PARALLEL_DIMENSIONS = 16;
    private static volatile int dynamicSimulationDistance = -1;
    private static final int MAX_LOAD_THREADS = 16;
    /** Auto default: one worker per usable core, but never more than the normal vanilla dimension count. */
    private static final int DEFAULT_PARALLEL_DIMENSIONS = defaultParallelDimensions();
    private static volatile boolean summaryLogged;

    private static int defaultParallelDimensions()
    {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(4, cores));
    }

    /**
     * Master escape hatch. Returns true when the user explicitly wants the old single-threaded world tick. In this
     * mode all world/pathfinding/region parallelism and the non-vanilla gameplay throttles are disabled as well.
     */
    public static boolean isSingleThreaded()
    {
        return Boolean.parseBoolean(System.getProperty(PROP_SINGLE_THREADED, "false")) ||
                "off".equalsIgnoreCase(System.getProperty("forge.threading", ""));
    }

    /**
     * Simulation distance is independent from the client's render/view distance. Chunks outside it stay loaded and
     * rendered, but entities, tile entities and random block ticks in them are not updated.
     * -Dforge.threading.simulationDistance=N (0 or >= view distance = vanilla behaviour)
     */
    public static int getSimulationDistance(int viewDistance)
    {
        if (isSingleThreaded())
        {
            return viewDistance;
        }
        if (isLowMemory())
        {
            return Math.min(viewDistance, 4);
        }
        int dynamic = dynamicSimulationDistance;
        int value = dynamic > 0 ? dynamic : Integer.getInteger("forge.threading.simulationDistance", 12).intValue();
        if (value <= 0 || value >= viewDistance)
        {
            return viewDistance;
        }
        return value;
    }

    /** Current governor-controlled simulation distance (or the configured base value when the governor has no override). */
    public static int getDynamicSimulationDistance()
    {
        int dynamic = dynamicSimulationDistance;
        return dynamic > 0 ? dynamic : Integer.getInteger("forge.threading.simulationDistance", 12).intValue();
    }

    public static void setDynamicSimulationDistance(int value)
    {
        dynamicSimulationDistance = Math.max(4, Math.min(32, value));
    }

    public static void resetDynamicSimulationDistance()
    {
        dynamicSimulationDistance = -1;
    }

    /** Player entity tracker range in blocks. Lower values reduce N^2 fanout on crowded servers. */
    public static int getPlayerTrackerRange()
    {
        if (isSingleThreaded())
        {
            return 512;
        }
        int value = Integer.getInteger("forge.threading.playerTrackerRange", 128).intValue();
        return Math.max(32, Math.min(512, value));
    }

    /** Ticks between full player tracker entry updates. Player movement still uses the normal update path. */
    public static int getPlayerTrackerInterval()
    {
        if (isSingleThreaded())
        {
            return 2;
        }
        int value = Integer.getInteger("forge.threading.playerTrackerInterval", 4).intValue();
        return Math.max(1, Math.min(20, value));
    }

    static
    {
        applyPreset();
    }

    private Threading()
    {
    }

    /**
     * Optional one-switch presets. Explicit -Dforge.threading.* properties always win.
     *
     *   auto       - every verified parallel path: dimensions, regions, block ticks, async paths, client sim (default)
     *   safe       - only CPU-side client parallelism; world ticking vanilla
     *   balanced   - parallel dimensions + region entity/TE ticks + async pathfinding
     *   aggressive - balanced + region random block ticks (new deterministic RNG model)
     *   maximum    - auto with explicit maximum pool sizes
     */
    private static void applyPreset()
    {
        String preset = System.getProperty("forge.threading.preset", "auto");
        if ("auto".equalsIgnoreCase(preset))
        {
            // The default profile uses every parallel phase that has been verified: larger dimension pooling,
            // regionized entity/TE/block ticks, async pathfinding and the client simulation thread. Random block
            // ticks use the per-region deterministic RNG model; repeated runs with the same loaded chunk set produce
            // the same block hash. They are still not bit-identical to vanilla's single global RNG, so safe/balanced
            // disable them explicitly.
            setDefault("forge.threading.regionBlockTicks", "true");
            setDefault("forge.threading.clientSimThread", "true");
            setDefault("forge.threading.clientSimEntities", "true");
        }
        else if ("safe".equalsIgnoreCase(preset))
        {
            setDefault("forge.threading.parallelDimensions", "1");
            setDefault("forge.threading.regions", "0");
            setDefault("forge.threading.regionBlockTicks", "false");
            setDefault("forge.threading.asyncPathfinding", "false");
            setDefault("forge.threading.clientSimThread", "false");
            setDefault("forge.threading.clientSimEntities", "false");
            setDefault("forge.threading.parallelBlockUpdates", "false");
            setDefault("forge.threading.parallelLighting", "false");
            setDefault("forge.threading.packetSerialization", "false");
            setDefault("forge.threading.inboundPipeline", "false");
            setDefault("forge.threading.renderPipeline", "false");
            setDefault("forge.threading.parallelLoading", "false");
            setDefault("forge.threading.parallelModelLoading", "false");
            setDefault("forge.threading.entityLod", "false");
        }
        else if ("balanced".equalsIgnoreCase(preset))
        {
            setDefault("forge.threading.parallelDimensions", "1");
            // Leave regions on auto so autoRegionThreads() scales workers with the CPU instead of pinning 2.
            setDefault("forge.threading.regionSizeChunks", "4");
            setDefault("forge.threading.regionBlockTicks", "false");
            setDefault("forge.threading.asyncPathfinding", "true");
        }
        else if ("aggressive".equalsIgnoreCase(preset))
        {
            setDefault("forge.threading.parallelDimensions", "2");
            // Auto worker count: 8 on a 16-thread CPU. Smaller regions balance heavy entity clusters.
            setDefault("forge.threading.regionSizeChunks", "4");
            setDefault("forge.threading.regionBlockTicks", "true");
            setDefault("forge.threading.asyncPathfinding", "true");
            setDefault("forge.threading.pathfindingThreads", "16");
        }
        else if ("maximum".equalsIgnoreCase(preset))
        {
            int cores = Runtime.getRuntime().availableProcessors();
            setDefault("forge.threading.parallelDimensions", Integer.toString(Math.max(2, Math.min(4, cores))));
            setDefault("forge.threading.regions", Integer.toString(Math.max(2, Math.min(8, cores / 2))));
            setDefault("forge.threading.regionSizeChunks", "4");
            setDefault("forge.threading.regionBlockTicks", "true");
            setDefault("forge.threading.asyncPathfinding", "true");
            setDefault("forge.threading.pathfindingThreads", Integer.toString(Math.max(2, Math.min(16, cores / 2))));
            setDefault("forge.threading.clientSimThread", "true");
            setDefault("forge.threading.clientSimEntities", "true");
        }
    }

    private static void setDefault(String key, String value)
    {
        if (System.getProperty(key) == null)
        {
            System.setProperty(key, value);
        }
    }

    public static String getPreset()
    {
        return System.getProperty("forge.threading.preset", "auto");
    }

    public static void logSummaryOnce()
    {
        if (summaryLogged)
        {
            return;
        }
        summaryLogged = true;
        LOGGER.info("[threading] preset={} singleThreaded={} parallelDimensions={} regions={} regionSizeChunks={} regionBlockTicks={} parallelBlockUpdates={} asyncPathfinding={} autopilot={} parallelLighting={} packetSerialization={} inboundPipeline={} simulationDistance={} parallelModelBaking={} parallelTextureDecoding={} parallelLoading={} loadThreads={} parallelModDiscovery={} parallelRecipeLoading={} parallelModelLoading={} metrics={} audit={}",
                getPreset(), Boolean.valueOf(isSingleThreaded()), Integer.valueOf(getParallelDimensions()),
                Integer.valueOf(getRegionThreads()),
                Integer.valueOf(getRegionSizeChunks()), Boolean.valueOf(isRegionBlockTicksEnabled()),
                Boolean.valueOf(isParallelBlockUpdatesEnabled()),
                Boolean.valueOf(isAsyncPathfindingEnabled()), Boolean.valueOf(ThreadingAutopilot.isEnabled()),
                Boolean.valueOf(LightingExecutor.isEnabled()), Boolean.valueOf(PacketSerialization.isEnabled()),
                Boolean.valueOf(InboundPacketPipeline.isEnabled()),
                Integer.valueOf(getSimulationDistance(32)),
                Boolean.valueOf(isParallelModelBakingEnabled()),
                Boolean.valueOf(isParallelTextureDecodingEnabled()),
                Boolean.valueOf(isParallelLoadingEnabled()), Integer.valueOf(getLoadThreads()),
                Boolean.valueOf(isParallelModDiscoveryEnabled()), Boolean.valueOf(isParallelRecipeLoadingEnabled()),
                Boolean.valueOf(isParallelModelLoadingEnabled()),
                Boolean.valueOf(Boolean.parseBoolean(System.getProperty("forge.threading.metrics", "false"))),
                Boolean.valueOf(isAuditEnabled()));
    }

    public static boolean isAuditEnabled()
    {
        return ThreadAudit.isEnabled();
    }

    public static int getParallelDimensions()
    {
        if (isSingleThreaded())
        {
            return 1;
        }
        return clamp(Integer.getInteger(PROP_PARALLEL_DIMENSIONS, DEFAULT_PARALLEL_DIMENSIONS).intValue());
    }

    public static boolean isLowMemory()
    {
        return MemoryTrim.isLowMemory();
    }

    public static boolean isParallelDimensionsEnabled()
    {
        return !isLowMemory() && getParallelDimensions() > 1;
    }

    /**
     * Client model baking is CPU-only after the sprite map has been stitched, so it can run before the render
     * thread uploads textures. Enabled by default; disable with -Dforge.threading.parallelModelBaking=false.
     */
    public static boolean isParallelModelBakingEnabled()
    {
        return !isSingleThreaded() && !isLowMemory() && Boolean.parseBoolean(System.getProperty("forge.threading.parallelModelBaking", "true"));
    }

    public static int getModelBakingThreads()
    {
        if (isSingleThreaded() || isLowMemory())
        {
            return 1;
        }
        int value = Integer.getInteger("forge.threading.modelBakingThreads",
                Runtime.getRuntime().availableProcessors()).intValue();
        return clamp(value);
    }

    /**
     * Independent sprite PNG decode + mipmap generation is CPU-only and can run before stitching/GL upload.
     * Disable with -Dforge.threading.parallelTextureDecoding=false.
     */
    public static boolean isParallelTextureDecodingEnabled()
    {
        return !isSingleThreaded() && !isLowMemory() && Boolean.parseBoolean(System.getProperty("forge.threading.parallelTextureDecoding", "true"));
    }

    public static int getTextureDecodingThreads()
    {
        if (isSingleThreaded() || isLowMemory())
        {
            return 1;
        }
        int value = Integer.getInteger("forge.threading.textureDecodingThreads",
                Runtime.getRuntime().availableProcessors()).intValue();
        return clamp(value);
    }

    /**
     * Master switch for load-time parallelism. Mod discovery and recipe JSON construction are independent work
     * items, so they run on a short-lived pool while the loader thread waits and merges the results in the original
     * order. Mod lifecycle events (construction/preInit/init/postInit) are deliberately never parallelized.
     * Disable with -Dforge.threading.parallelLoading=false; singleThreaded forces it off.
     */
    public static boolean isParallelLoadingEnabled()
    {
        return !isSingleThreaded() && !isLowMemory() && Boolean.parseBoolean(System.getProperty(PROP_PARALLEL_LOADING, "true"));
    }

    /**
     * Worker count for the load-time pool. Default is min(cores, 8): loading is I/O + short CPU bursts, so more
     * threads than that mostly adds contention on the disk and the class loader.
     */
    public static int getLoadThreads()
    {
        if (isSingleThreaded() || !isParallelLoadingEnabled())
        {
            return 1;
        }
        int automatic = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        int value = Integer.getInteger("forge.threading.loadThreads", automatic).intValue();
        if (value < 1)
        {
            return 1;
        }
        return value > MAX_LOAD_THREADS ? MAX_LOAD_THREADS : value;
    }

    /** Scan mod jars/directories for @Mod metadata on the load pool. -Dforge.threading.parallelModDiscovery=false disables. */
    public static boolean isParallelModDiscoveryEnabled()
    {
        return isParallelLoadingEnabled() && getLoadThreads() > 1 &&
                Boolean.parseBoolean(System.getProperty("forge.threading.parallelModDiscovery", "true"));
    }

    /** Read + Gson parse + build recipe objects on the load pool. -Dforge.threading.parallelRecipeLoading=false disables. */
    public static boolean isParallelRecipeLoadingEnabled()
    {
        return isParallelLoadingEnabled() && getLoadThreads() > 1 &&
                Boolean.parseBoolean(System.getProperty("forge.threading.parallelRecipeLoading", "true"));
    }

    /**
     * Resolve blockstate/model/item models concurrently. This is the client startup phase that parses the whole
     * models/ and blockstates/ trees. It is independent work per block/item, but it mutates shared caches, so the
     * caches are made concurrent before this is enabled. Disable with -Dforge.threading.parallelModelLoading=false.
     */
    public static boolean isParallelModelLoadingEnabled()
    {
        return !isSingleThreaded() && !isLowMemory() && Boolean.parseBoolean(System.getProperty("forge.threading.parallelModelLoading", "true"));
    }

    public static int getModelLoadingThreads()
    {
        if (isSingleThreaded() || isLowMemory() || !isParallelModelLoadingEnabled())
        {
            return 1;
        }
        int automatic = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        int value = Integer.getInteger("forge.threading.modelLoadingThreads", automatic).intValue();
        if (value < 1)
        {
            return 1;
        }
        return value > MAX_LOAD_THREADS ? MAX_LOAD_THREADS : value;
    }

    /**
     * Regionized entity/TE ticking. 0/1 = disabled (vanilla), 2..16 = number of shared region workers.
     * Default "auto": enabled on machines with at least 4 cores, with 2..4 workers. Explicit -Dforge.threading.regions=N
     * overrides the policy (0 disables it).
     */
    public static int getRegionThreads()
    {
        if (isSingleThreaded())
        {
            return 0;
        }

        String raw = System.getProperty("forge.threading.regions", "auto");
        if (raw == null || raw.trim().isEmpty() || "auto".equalsIgnoreCase(raw))
        {
            return autoRegionThreads();
        }

        int value;
        try
        {
            value = Integer.parseInt(raw.trim());
        }
        catch (NumberFormatException invalid)
        {
            return 0;
        }
        if (value <= 1)
        {
            return 0;
        }
        return value > MAX_PARALLEL_DIMENSIONS ? MAX_PARALLEL_DIMENSIONS : value;
    }

    private static int autoRegionThreads()
    {
        int cores = Runtime.getRuntime().availableProcessors();
        if (cores < 4)
        {
            return 0;
        }
        return Math.min(16, Math.max(2, cores / 2));
    }

    public static boolean isRegionTickingEnabled()
    {
        return getRegionThreads() > 1;
    }

    /** Region edge in chunks when regionized ticking is enabled. The autopilot may tune this at runtime. */
    public static int getRegionSizeChunks()
    {
        if (System.getProperty("forge.threading.regionSizeChunks") == null)
        {
            int automatic = ThreadingAutopilot.getRegionSizeOverride();
            if (automatic > 0)
            {
                return clamp(automatic);
            }
        }
        return clamp(Integer.getInteger("forge.threading.regionSizeChunks", 4).intValue());
    }

    /**
     * Random block ticks executed per Region with a deterministic per-region RNG. This is a new deterministic model
     * (like Folia); it is not bit-identical to vanilla's single global RNG. Enabled by the auto/aggressive presets,
     * disabled by safe/balanced; -Dforge.threading.regionBlockTicks=false overrides.
     */
    public static boolean isRegionBlockTicksEnabled()
    {
        return !isSingleThreaded() && Boolean.parseBoolean(System.getProperty("forge.threading.regionBlockTicks", "true"));
    }

    /**
     * Parallel scheduled block/fluid updates (WorldServer#tickUpdates). Entries are bucketed by region, run with
     * per-position RNG keys, and committed through the same deferred-mutation barrier as random block ticks.
     * -Dforge.threading.parallelBlockUpdates=false disables it.
     */
    public static boolean isParallelBlockUpdatesEnabled()
    {
        return !isSingleThreaded() && Boolean.parseBoolean(System.getProperty("forge.threading.parallelBlockUpdates", "true"));
    }

    /** Asynchronous PathNavigate searches with results picked up on a later AI update. On by default. */
    public static boolean isAsyncPathfindingEnabled()
    {
        return !isSingleThreaded() && Boolean.parseBoolean(System.getProperty("forge.threading.asyncPathfinding", "true"));
    }

    public static int getPathfindingThreads()
    {
        if (isSingleThreaded())
        {
            return 1;
        }
        int value = Integer.getInteger("forge.threading.pathfindingThreads",
                Math.max(1, Runtime.getRuntime().availableProcessors() / 2)).intValue();
        return clamp(value);
    }

    /**
     * Static region affinity (one region always on the same worker). Off by default because the shared pool balances
     * uneven region load better; enable for cache-locality experiments.
     */
    public static boolean isRegionAffinityEnabled()
    {
        return !isSingleThreaded() && Boolean.parseBoolean(System.getProperty("forge.threading.regionAffinity", "false"));
    }

    private static int clamp(int value)
    {
        if (value < 1)
        {
            return 1;
        }
        return value > MAX_PARALLEL_DIMENSIONS ? MAX_PARALLEL_DIMENSIONS : value;
    }
}

