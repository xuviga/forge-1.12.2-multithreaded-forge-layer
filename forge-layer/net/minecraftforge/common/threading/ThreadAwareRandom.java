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

import java.util.Random;

/**
 * World.rand replacement for regionized ticking.
 *
 * On the owner thread it delegates to the ordinary per-world Random (vanilla sequence preserved). On a region worker
 * it uses a thread-local Random reseeded deterministically from (world seed, tick id, region id), so entity/TE code
 * that calls world.rand no longer interleaves with other regions and replayed ticks are stable.
 *
 * The field type stays java.util.Random, so mods keep compiling and calling the same API.
 */
public final class ThreadAwareRandom extends Random
{
    private static final long serialVersionUID = 1L;

    private final long worldSeed;
    private volatile long generation;
    private volatile long baseSeed;
    private transient Delegate base;

    private static final ThreadLocal<State> REGION_STATE = new ThreadLocal<State>()
    {
        @Override
        protected State initialValue()
        {
            return new State();
        }
    };

    public ThreadAwareRandom(long worldSeed)
    {
        super(worldSeed);
        this.worldSeed = worldSeed;
        this.baseSeed = worldSeed;
        this.base = new Delegate(worldSeed);
    }

    @Override
    protected int next(int bits)
    {
        TickContext context = TickContext.current();
        if (context != null && context.getRegion() != null)
        {
            State state = REGION_STATE.get();
            long tickId = context.getTickId();
            int regionId = context.getRegion().getId();
            int phase = context.getPhase().ordinal();
            long version = generation;
            // If the worker published a per-entity/TE/chunk key, use it instead of the region id. This makes the
            // RNG stream independent of region size and of how work is distributed across threads.
            long rngKey = context.hasRngKey() ? context.getRngKey()
                    : (((long)regionId) << 32) ^ 0xD1B54A32D192ED03L;

            if (state.random == null || state.tickId != tickId || state.key != rngKey || state.phase != phase || state.version != version)
            {
                long seed = worldSeed ^ (tickId * 0x9e3779b97f4a7c15L) ^ rngKey ^
                        (((long)phase) * 0x94d049bb133111ebL) ^ (version * 0xbf58476d1ce4e5b9L);
                state.random = new Delegate(seed);
                state.tickId = tickId;
                state.key = rngKey;
                state.phase = phase;
                state.version = version;
            }
            return state.random.nextBits(bits);
        }

        return base.nextBits(bits);
    }

    @Override
    public synchronized void setSeed(long seed)
    {
        this.baseSeed = seed;
        this.generation++;
        Delegate current = this.base;
        if (current != null)
        {
            current.setSeed(seed);
        }
    }

    public long getWorldSeed()
    {
        return worldSeed;
    }

    private static final class Delegate extends Random
    {
        private static final long serialVersionUID = 1L;

        private Delegate(long seed)
        {
            super(seed);
        }

        private int nextBits(int bits)
        {
            return super.next(bits);
        }
    }

    private static final class State
    {
        private Delegate random;
        private long tickId;
        private long key;
        private int phase;
        private long version = -1L;
    }
}
