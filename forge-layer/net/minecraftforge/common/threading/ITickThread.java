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

import net.minecraft.util.IThreadListener;
import net.minecraft.world.World;

/**
 * A thread that owns (part of) a world for the duration of a tick.
 *
 * Whole-dimension ticking uses a null region and the whole WorldServer; regionized ticking (Phase 3) uses a
 * non-null region. Code that mutates world state must run on the returned owner.
 */
public interface ITickThread extends IThreadListener
{
    World getWorld();

    Region getRegion();

    TickPhase getPhase();

    long getTickId();

    /**
     * @return true if this thread is allowed to mutate the given object (entity, tile entity, chunk, ...).
     *         The default implementation only checks the world and, when present, the region.
     */
    default boolean owns(Object target)
    {
        return true;
    }

    default boolean ownsChunk(int chunkX, int chunkZ)
    {
        Region region = getRegion();
        return region == null || region.containsChunk(chunkX, chunkZ);
    }

    default boolean isRegionThread()
    {
        return getRegion() != null;
    }

    default TickContext newContext()
    {
        TickContext context = new TickContext(Thread.currentThread());
        context.setWorld(getWorld());
        context.setRegion(getRegion());
        context.setPhase(getPhase());
        context.setTickId(getTickId());
        return context;
    }
}
