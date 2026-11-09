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

import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Ordered fan-out/fan-in helper for the FML loading pipeline.
 *
 * Mod jars/directories and recipe JSON files are independent work items, but the game insists on deterministic
 * results: candidate scan order drives duplicate detection, and recipe registration order must match the on-disk
 * order. This helper therefore never lets workers append into shared output structures. Each index owns its slot in
 * a pre-sized array, the pool is joined before the caller reads the array, and the caller merges in index order.
 *
 * A dedicated short-lived {@link ForkJoinPool} is used instead of the common pool so loading cannot be starved by
 * (or starve) unrelated work, and so the pool is gone once the phase is over. The pool is not registered with
 * {@link ThreadingTelemetry} because it does not outlive the phase; the phase duration is recorded instead.
 */
public final class LoadingExecutor
{
    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("ForgeThreading");

    private LoadingExecutor()
    {
    }

    /**
     * Runs {@code task} for every index in {@code [0, count)} and returns only after all of them have completed.
     * Results are stored by the task itself, so ordering is whatever the caller's array defines.
     *
     * @param count number of independent work items
     * @param phase telemetry key, e.g. {@code load.discovery}
     * @param task  per-index work item; must not throw for recoverable failures
     * @return true when the work actually ran on more than one thread
     */
    public static boolean runIndexed(int count, String phase, IndexedTask task)
    {
        return runIndexed(count, phase, task, Threading.isParallelLoadingEnabled());
    }

    /**
     * Same as {@link #runIndexed(int, String, IndexedTask)} but lets the caller choose the phase-specific gate
     * (discovery vs recipes) without duplicating the sequential fallback.
     */
    public static boolean runIndexed(int count, String phase, IndexedTask task, boolean allowParallel)
    {
        long start = System.nanoTime();
        try
        {
            if (count <= 1 || !allowParallel || !Threading.isParallelLoadingEnabled())
            {
                for (int index = 0; index < count; index++)
                {
                    task.run(index);
                }
                return false;
            }

            final int threads = Math.min(Threading.getLoadThreads(), count);
            if (threads <= 1)
            {
                for (int index = 0; index < count; index++)
                {
                    task.run(index);
                }
                return false;
            }

            if (LOGGER.isDebugEnabled())
            {
                LOGGER.debug("[load] {}: {} items on {} threads", phase, Integer.valueOf(count), Integer.valueOf(threads));
            }

            ForkJoinPool pool = new ForkJoinPool(threads);
            try
            {
                pool.submit(() -> IntStream.range(0, count).parallel().forEach(task::run)).join();
            }
            finally
            {
                pool.shutdown();
            }
            return true;
        }
        finally
        {
            ThreadingTelemetry.recordPhase(phase, System.nanoTime() - start);
        }
    }

    /** Work item keyed by a stable index into the caller's ordered input. */
    public interface IndexedTask
    {
        void run(int index);
    }
}
