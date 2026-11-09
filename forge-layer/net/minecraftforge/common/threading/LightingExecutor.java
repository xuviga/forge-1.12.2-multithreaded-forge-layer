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

import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Sharded light-propagation locks.
 *
 * Vanilla's light engine uses a single 32768-entry scratch buffer and a global BFS. The buffer is now thread-local
 * (World#lightBufferLocal) and the light arrays are guarded by:
 *
 *   - 16 shard locks for interior updates (a 256-block region, at least 36 blocks from an edge),
 *   - a global read lock shared by all interior updates,
 *   - a global write lock for boundary updates and for non-finite safety.
 *
 * Interior updates in different shards touch disjoint light arrays (BFS radius is 17; margin is 36), so they run in
 * parallel. Any update that can reach a shard edge takes the write lock, which excludes every interior update. That
 * makes the sharding conservative but correct.
 *
 * -Dforge.threading.parallelLighting=false disables sharding (global write lock). singleThreaded forces it off.
 */
public final class LightingExecutor
{
    private static final int SHARD_BLOCKS = 256;
    private static final int SHARD_MARGIN = 36;
    private static final int SHARD_MASK = 3;
    private static final ReentrantLock[] SHARDS = new ReentrantLock[16];
    private static final ReentrantReadWriteLock GLOBAL = new ReentrantReadWriteLock();
    private static final boolean ENABLED = !Threading.isSingleThreaded() &&
            Boolean.parseBoolean(System.getProperty("forge.threading.parallelLighting", "true"));

    static
    {
        for (int index = 0; index < SHARDS.length; index++)
        {
            SHARDS[index] = new ReentrantLock();
        }
    }

    private LightingExecutor()
    {
    }

    public static boolean isEnabled()
    {
        return ENABLED;
    }

    /**
     * Acquires a light-update lock. The caller must call {@link LightLock#release()} in a finally block.
     */
    public static LightLock acquire(World world, BlockPos pos)
    {
        if (!ENABLED)
        {
            ThreadingTelemetry.increment("light.global");
            GLOBAL.writeLock().lock();
            return new LightLock(null, true);
        }

        int x = pos.getX();
        int z = pos.getZ();
        int localX = Math.floorMod(x, SHARD_BLOCKS);
        int localZ = Math.floorMod(z, SHARD_BLOCKS);
        if (localX < SHARD_MARGIN || localX > SHARD_BLOCKS - SHARD_MARGIN ||
                localZ < SHARD_MARGIN || localZ > SHARD_BLOCKS - SHARD_MARGIN)
        {
            ThreadingTelemetry.increment("light.global");
            GLOBAL.writeLock().lock();
            return new LightLock(null, true);
        }

        ThreadingTelemetry.increment("light.shard");
        GLOBAL.readLock().lock();
        int shardX = Math.floorDiv(x, SHARD_BLOCKS) & SHARD_MASK;
        int shardZ = Math.floorDiv(z, SHARD_BLOCKS) & SHARD_MASK;
        ReentrantLock shard = SHARDS[(shardX << 2) | shardZ];
        shard.lock();
        return new LightLock(shard, false);
    }

    /**
     * Release token. Interior updates hold the global read lock plus one shard lock; boundary updates hold only the
     * global write lock.
     */
    public static final class LightLock
    {
        private final ReentrantLock shard;
        private final boolean writer;

        private LightLock(ReentrantLock shard, boolean writer)
        {
            this.shard = shard;
            this.writer = writer;
        }

        public void release()
        {
            if (this.writer)
            {
                GLOBAL.writeLock().unlock();
            }
            else
            {
                this.shard.unlock();
                GLOBAL.readLock().unlock();
            }
        }
    }
}
