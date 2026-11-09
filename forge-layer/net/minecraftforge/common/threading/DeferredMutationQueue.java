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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Ordered, coalescing hand-off for mutations produced by a non-owner thread.
 *
 * Region workers enqueue with a deterministic order key (regionId, phase, per-context sequence), so the owner
 * applies cross-region effects in a replay-stable order regardless of which worker finished first. Legacy
 * (non-region) enqueues keep FIFO order with last-write-wins coalescing per key and are drained after the ordered set.
 */
public final class DeferredMutationQueue
{
    public interface Mutation
    {
        /** Coalescing key, or null when every occurrence must be applied. */
        Object getKey();

        void apply();
    }

    private final ConcurrentSkipListMap<Long, Mutation> ordered = new ConcurrentSkipListMap<Long, Mutation>();
    private final ConcurrentHashMap<Object, Long> latestOrdered = new ConcurrentHashMap<Object, Long>();
    private final ConcurrentLinkedQueue<Mutation> legacy = new ConcurrentLinkedQueue<Mutation>();
    private final ConcurrentHashMap<Object, Mutation> latestLegacy = new ConcurrentHashMap<Object, Mutation>();

    public void enqueue(Mutation mutation)
    {
        ThreadingTelemetry.increment("deferred.legacy.enqueued");
        Object key = mutation.getKey();
        if (key != null)
        {
            // Last write wins for the same position, matching the ordered queue and avoiding duplicate block/TE
            // work when several legacy callers touch the same position in one tick.
            Mutation previous = latestLegacy.put(key, mutation);
            if (previous != null)
            {
                ThreadingTelemetry.increment("deferred.legacy.coalesced");
                legacy.remove(previous);
            }
        }
        legacy.add(mutation);
    }

    public void enqueueOrdered(long order, Mutation mutation)
    {
        ThreadingTelemetry.increment("deferred.ordered.enqueued");
        Object key = mutation.getKey();
        if (key != null)
        {
            Long previous = latestOrdered.put(key, Long.valueOf(order));
            if (previous != null)
            {
                ThreadingTelemetry.increment("deferred.ordered.coalesced");
                ordered.remove(previous);
            }
        }
        ordered.put(Long.valueOf(order), mutation);
    }

    public int drain(int max)
    {
        int applied = 0;

        while (applied < max)
        {
            Map.Entry<Long, Mutation> entry = ordered.pollFirstEntry();
            if (entry == null)
            {
                break;
            }
            Mutation mutation = entry.getValue();
            Object key = mutation.getKey();
            if (key != null)
            {
                latestOrdered.remove(key, entry.getKey());
            }
            mutation.apply();
            ThreadingTelemetry.increment("deferred.ordered.applied");
            applied++;
        }

        while (applied < max)
        {
            Mutation mutation = legacy.poll();
            if (mutation == null)
            {
                break;
            }
            Object key = mutation.getKey();
            if (key != null && !latestLegacy.remove(key, mutation))
            {
                // A newer mutation for the same key superseded this one after it was queued.
                ThreadingTelemetry.increment("deferred.legacy.stale");
                continue;
            }
            mutation.apply();
            ThreadingTelemetry.increment("deferred.legacy.applied");
            applied++;
        }

        return applied;
    }

    public int drainAll()
    {
        return drain(Integer.MAX_VALUE);
    }

    public int size()
    {
        return ordered.size() + legacy.size();
    }

    public boolean isEmpty()
    {
        return ordered.isEmpty() && legacy.isEmpty();
    }

    public void clear()
    {
        ordered.clear();
        latestOrdered.clear();
        legacy.clear();
        latestLegacy.clear();
    }
}
