/*
 * Minecraft Forge
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.common.chunkio;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.Maps;

import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.fml.common.FMLLog;

public class ChunkIOExecutor
{
    private static final int BASE_THREADS = 1;
    private static final int PLAYERS_PER_THREAD = 50;

    private static final Map<QueuedChunk, ChunkIOProvider> tasks = Maps.newConcurrentMap();
    private static final ThreadPoolExecutor pool = new ChunkIOThreadPoolExecutor(BASE_THREADS, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>(),
        new ThreadFactory()
        {
            private AtomicInteger count = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r)
            {
                Thread thread = new Thread(r, "Chunk I/O Executor Thread-" + count.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        }
    );

    static
    {
        net.minecraftforge.common.threading.ThreadingTelemetry.registerPool("chunkio", pool);
    }

    //Load the chunk completely in this thread. Dequeue as needed...
    public static Chunk syncChunkLoad(World world, AnvilChunkLoader loader, ChunkProviderServer provider, int x, int z)
    {
        QueuedChunk key = new QueuedChunk(x, z, world);
        ChunkIOProvider task = tasks.remove(key); // Remove task because we will call the sync callbacks directly
        if (task != null)
        {
            if (!pool.remove(task)) // If it wasn't in the pool, and run hasn't finished, then wait for the async thread.
            {
                synchronized(task)
                {
                    while (!task.runFinished())
                    {
                        try
                        {
                            task.wait();
                        }
                        catch (InterruptedException e)
                        {
                            e.printStackTrace(); // Something happened? Log it?
                        }
                    }
                }
            }
            else
            {
                // If the task was not run yet we still need to load the chunk
                task.run();
            }
        }
        else
        {
            task = new ChunkIOProvider(key, loader, provider);
            task.run();
        }
        task.syncCallback();
        return task.getChunk();
    }

    //Queue the chunk to be loaded, and call the runnable when finished
    public static void queueChunkLoad(World world, AnvilChunkLoader loader, ChunkProviderServer provider, int x, int z, Runnable runnable)
    {
        QueuedChunk key = new QueuedChunk(x, z, world);
        ChunkIOProvider task = tasks.get(key);
        if (task == null)
        {
            task = new ChunkIOProvider(key, loader, provider);
            task.addCallback(runnable); // Add before calling execute for thread safety
            tasks.put(key, task);
            pool.execute(task);
        }
        else
        {
            task.addCallback(runnable);
        }
    }

    // Remove the chunk from the queue if it's in the list.
    public static void dropQueuedChunkLoad(World world, int x, int z, Runnable runnable)
    {
        QueuedChunk key = new QueuedChunk(x, z, world);
        ChunkIOProvider task = tasks.get(key);
        if (task == null)
        {
            FMLLog.log.warn("Attempted to dequeue chunk that wasn't queued? {} @ ({}, {})", world.provider.getDimension(), x, z);
            return;
        }

        task.removeCallback(runnable);

        if (!task.hasCallback())
        {
            tasks.remove(key);
            pool.remove(task);
        }
    }

    /**
     * Drops queued/active chunk loads belonging to an unloaded world. QueuedChunk holds the World strongly, so an
     * abandoned entry otherwise keeps the whole dimension (and its capability providers) alive.
     *
     * @return number of removed tasks
     */
    public static int purgeWorld(World world)
    {
        if (world == null)
        {
            return 0;
        }
        int removed = 0;
        Iterator<Map.Entry<QueuedChunk, ChunkIOProvider>> iterator = tasks.entrySet().iterator();
        while (iterator.hasNext())
        {
            Map.Entry<QueuedChunk, ChunkIOProvider> entry = iterator.next();
            if (entry.getKey().world == world)
            {
                iterator.remove();
                pool.remove(entry.getValue());
                ++removed;
            }
        }
        return removed;
    }

    /** Removes finished tasks that already ran their callbacks. Safe to call periodically. */
    public static int purgeCompleted()
    {
        int removed = 0;
        Iterator<ChunkIOProvider> iterator = tasks.values().iterator();
        while (iterator.hasNext())
        {
            ChunkIOProvider task = iterator.next();
            if (task.runFinished())
            {
                iterator.remove();
                ++removed;
            }
        }
        return removed;
    }

    public static int getQueuedCount()
    {
        return tasks.size();
    }

    public static void adjustPoolSize(int players)
    {
        pool.setCorePoolSize(Math.max(BASE_THREADS, players / PLAYERS_PER_THREAD));
    }

    public static void tick()
    {
        // A join or a teleport can complete hundreds of async chunk loads in the same tick. Running every callback
        // here made ChunkIOExecutor.tick() the largest main-thread phase of the first server tick (180+ ms). Spend a
        // bounded slice per tick; unfinished callbacks stay in the map and run on the next tick.
        long budgetMs = Long.getLong("forge.threading.chunkioBudgetMs", 8L).longValue();
        long deadline = budgetMs <= 0L ? Long.MAX_VALUE : System.nanoTime() + budgetMs * 1000000L;
        int callbacks = 0;
        Iterator<ChunkIOProvider> itr = tasks.values().iterator();
        while (itr.hasNext())
        {
            ChunkIOProvider task = itr.next();
            if (task.runFinished())
            {
                itr.remove();

                if (task.hasCallback())
                {
                    task.syncCallback();
                    ++callbacks;

                    if (System.nanoTime() >= deadline)
                    {
                        net.minecraftforge.common.threading.ThreadingTelemetry.increment("chunkio.budgetStop");
                        break;
                    }
                }
            }
        }

        if (callbacks > 0)
        {
            net.minecraftforge.common.threading.ThreadingTelemetry.add("chunkio.callbacks", callbacks);
        }
    }
}
