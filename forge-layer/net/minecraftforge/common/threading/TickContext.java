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
import java.util.Deque;
import net.minecraft.world.World;

/**
 * Thread-confined description of what the current thread is doing: which world, which region, which phase, which
 * tick. Event dispatch and Forge side detection read this instead of guessing from ThreadGroup.
 *
 * The context is a stack so a handler can temporarily enter another world (e.g. a cross-dimension teleport) and
 * restore the previous context.
 */
public final class TickContext
{
    private static final ThreadLocal<Deque<TickContext>> STACK = new ThreadLocal<Deque<TickContext>>()
    {
        @Override
        protected Deque<TickContext> initialValue()
        {
            return new ArrayDeque<TickContext>();
        }
    };

    private final Thread owner;
    private World world;
    private Region region;
    private TickPhase phase = TickPhase.INPUT;
    private long tickId;
    private long mutationSequence;
    private long rngKey;
    private boolean rngKeySet;

    public TickContext(Thread owner)
    {
        this.owner = owner;
    }

    public static TickContext current()
    {
        return STACK.get().peek();
    }

    public static TickContext getOrCreate()
    {
        TickContext context = current();
        if (context == null)
        {
            context = new TickContext(Thread.currentThread());
            STACK.get().push(context);
        }
        return context;
    }

    public static void push(TickContext context)
    {
        STACK.get().push(context);
    }

    public static TickContext pop()
    {
        Deque<TickContext> stack = STACK.get();
        TickContext context = stack.poll();
        if (stack.isEmpty())
        {
            STACK.remove();
        }
        return context;
    }

    public static void clear()
    {
        STACK.remove();
    }

    /** True only for sub-dimension region workers (Phase 3). */
    public static boolean isRegionThread()
    {
        TickContext context = current();
        return context != null && context.getRegion() != null;
    }

    /** True on the client thread, the server thread, and when no context has been published. */
    public static boolean isMainThread()
    {
        TickContext context = current();
        return context == null || (context.getRegion() == null && context.getWorld() == null);
    }

    /** True for any thread that has a world context (main world tick or region worker). */
    public static boolean isGameThread()
    {
        TickContext context = current();
        return context == null || context.getWorld() != null;
    }

    public Thread getOwner()
    {
        return owner;
    }

    public World getWorld()
    {
        return world;
    }

    public void setWorld(World world)
    {
        this.world = world;
    }

    public Region getRegion()
    {
        return region;
    }

    public void setRegion(Region region)
    {
        this.region = region;
    }

    public TickPhase getPhase()
    {
        return phase;
    }

    public void setPhase(TickPhase phase)
    {
        this.phase = phase;
    }

    public long getTickId()
    {
        return tickId;
    }

    public void setTickId(long tickId)
    {
        this.tickId = tickId;
    }

    /** Monotonic per-context sequence used to build deterministic (regionId, phase, seq) commit keys. */
    public long nextMutationSeq()
    {
        return ++this.mutationSequence;
    }

    /**
     * Deterministic RNG namespace for the object currently being ticked (entity UUID, TE position, chunk key).
     * When set, ThreadAwareRandom derives its stream from this key instead of the region id, so the world
     * evolution does not depend on how many regions or threads the scheduler happens to use.
     */
    public void setRngKey(long key)
    {
        this.rngKey = key;
        this.rngKeySet = true;
    }

    public void clearRngKey()
    {
        this.rngKeySet = false;
    }

    public boolean hasRngKey()
    {
        return this.rngKeySet;
    }

    public long getRngKey()
    {
        return this.rngKey;
    }
}
