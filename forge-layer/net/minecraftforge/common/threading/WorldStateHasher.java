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
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Deterministic replay aid. When enabled, logs a stable block-state hash per dimension per tick:
 *
 *   -Dforge.threading.hashTicks=true
 *   -Dforge.threading.hashTicksMax=40
 *
 * Chunks are sorted by (x,z) before hashing and a fixed block-stride sample is used, so the result must be identical
 * for the same save at 1 vs N dimension threads. Compare the [thread-hash] lines from two runs.
 */
public final class WorldStateHasher
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("forge.threading.hashTicks", "false"));
    private static final int MAX_TICKS = Integer.getInteger("forge.threading.hashTicksMax", 40).intValue();
    /** 0 = hash every loaded chunk; &gt;0 = only chunks within this radius around (0,0), for stable comparisons. */
    private static final int CHUNK_RADIUS = Integer.getInteger("forge.threading.hashChunkRadius", 0).intValue();
    private static final int CHUNK_X = Integer.getInteger("forge.threading.hashChunkX", Integer.MIN_VALUE).intValue();
    private static final int CHUNK_Z = Integer.getInteger("forge.threading.hashChunkZ", Integer.MIN_VALUE).intValue();
    private static final int Y_STRIDE = 16;
    private static final int XZ_STRIDE = 8;
    private static final ConcurrentHashMap<Integer, AtomicInteger> SEEN = new ConcurrentHashMap<Integer, AtomicInteger>();

    private WorldStateHasher()
    {
    }

    public static boolean isEnabled()
    {
        return ENABLED;
    }

    public static void hash(WorldServer world)
    {
        if (!ENABLED)
        {
            return;
        }

        int dimension = world.provider.getDimension();
        AtomicInteger counter = SEEN.get(Integer.valueOf(dimension));
        if (counter == null)
        {
            AtomicInteger created = new AtomicInteger();
            counter = SEEN.putIfAbsent(Integer.valueOf(dimension), created);
            if (counter == null)
            {
                counter = created;
            }
        }
        int sequence = counter.incrementAndGet();
        if (sequence > MAX_TICKS)
        {
            return;
        }

        long tick = world.getTotalWorldTime();

        IChunkProvider provider = world.getChunkProvider();
        if (!(provider instanceof ChunkProviderServer))
        {
            return;
        }

        Collection<Chunk> loaded = ((ChunkProviderServer)provider).getLoadedChunks();
        List<Chunk> chunks = new ArrayList<Chunk>(loaded);
        chunks.sort(Comparator.comparingInt((Chunk chunk) -> chunk.x).thenComparingInt(chunk -> chunk.z));

        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, dimension);
        hash = mix(hash, tick);

        int hashedChunks = 0;
        for (Chunk chunk : chunks)
        {
            if (CHUNK_X != Integer.MIN_VALUE && (chunk.x != CHUNK_X || chunk.z != CHUNK_Z))
            {
                continue;
            }
            if (CHUNK_RADIUS > 0 && (Math.abs(chunk.x) > CHUNK_RADIUS || Math.abs(chunk.z) > CHUNK_RADIUS))
            {
                continue;
            }
            hashedChunks++;
            hash = mix(hash, chunk.x);
            hash = mix(hash, chunk.z);

            for (int y = 0; y < 256; y += Y_STRIDE)
            {
                for (int x = 0; x < 16; x += XZ_STRIDE)
                {
                    for (int z = 0; z < 16; z += XZ_STRIDE)
                    {
                        IBlockState state = chunk.getBlockState(new BlockPos((chunk.x << 4) + x, y, (chunk.z << 4) + z));
                        hash = mix(hash, Block.getStateId(state));
                    }
                }
            }
        }

        LOGGER.info("[thread-hash] dim={} seq={} tick={} chunks={} hash={}",
                Integer.valueOf(dimension), Integer.valueOf(sequence), Long.valueOf(tick), Integer.valueOf(hashedChunks),
                String.format("%016x", Long.valueOf(hash)));
    }

    private static long mix(long hash, long value)
    {
        hash ^= value;
        hash *= 0x100000001b3L;
        hash ^= hash >>> 32;
        return hash;
    }
}
