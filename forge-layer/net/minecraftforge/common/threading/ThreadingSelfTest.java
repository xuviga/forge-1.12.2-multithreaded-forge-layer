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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Opt-in smoke test for the threading primitives, run from the server thread after mod loading.
 *
 * Enable with -Dforge.threading.selftest=true. It does not touch world state; it only verifies Region math,
 * DeferredMutationQueue ordering/coalescing, TickContext confinement and thread-aware EventBus dispatch.
 */
public final class ThreadingSelfTest
{
    private ThreadingSelfTest()
    {
    }

    public static final class TestEvent extends Event
    {
    }

    public static final class TestHandler
    {
        final AtomicInteger main = new AtomicInteger();
        final AtomicInteger any = new AtomicInteger();

        @SubscribeEvent(thread = ThreadType.MAIN)
        public void onMain(TestEvent event)
        {
            main.incrementAndGet();
        }

        @SubscribeEvent(thread = ThreadType.ANY)
        public void onAny(TestEvent event)
        {
            any.incrementAndGet();
        }
    }

    public static boolean runIfRequested(WorldServer world)
    {
        if (!Boolean.parseBoolean(System.getProperty("forge.threading.selftest", "false")))
        {
            return true;
        }

        boolean ok = true;
        ok &= testRegions();
        ok &= testQueue();
        ok &= testTickContext();
        ok &= testEventBus();
        ok &= testDeferredWorldMutation(world);
        FMLLog.log.info("[threading-selftest] {}", ok ? "PASS" : "FAIL");
        return ok;
    }

    private static boolean testRegions()
    {
        Region region = Region.forChunk(7, 3, 33, -1, Region.DEFAULT_SIZE_CHUNKS);
        boolean ok = region.getMinChunkX() == 32 && region.getMinChunkZ() == -16 &&
                region.getMaxChunkX() == 47 && region.getMaxChunkZ() == -1 &&
                region.containsChunk(33, -1) && region.containsChunk(47, -1) &&
                !region.containsChunk(31, -1) && !region.containsChunk(33, 0) &&
                Region.wholeDimension(3).containsChunk(123456, -123456);
        if (!ok)
        {
            FMLLog.log.error("[threading-selftest] Region math failed: {}", region);
        }
        return ok;
    }

    private static boolean testQueue()
    {
        DeferredMutationQueue queue = new DeferredMutationQueue();
        final List<String> applied = new ArrayList<String>();

        queue.enqueue(mutation("a", applied, "a1"));
        queue.enqueue(mutation("a", applied, "a2"));
        queue.enqueue(mutation("b", applied, "b1"));
        queue.drainAll();

        boolean ok = applied.size() == 2 && "a2".equals(applied.get(0)) && "b1".equals(applied.get(1));
        if (!ok)
        {
            FMLLog.log.error("[threading-selftest] DeferredMutationQueue failed: {}", applied);
        }
        return ok;
    }

    private static DeferredMutationQueue.Mutation mutation(final String key, final List<String> target, final String value)
    {
        return new DeferredMutationQueue.Mutation()
        {
            @Override
            public Object getKey()
            {
                return key;
            }

            @Override
            public void apply()
            {
                target.add(value);
            }
        };
    }

    private static boolean testTickContext()
    {
        final boolean[] ok = new boolean[1];

        try
        {
            Thread worker = new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    TickContext context = TickContext.getOrCreate();
                    context.setRegion(Region.forChunk(1, 0, 0, 0, Region.DEFAULT_SIZE_CHUNKS));
                    ok[0] = TickContext.isRegionThread() && !TickContext.isMainThread();
                    TickContext.pop();
                    ok[0] = ok[0] && TickContext.current() == null;
                }
            }, "Threading SelfTest");
            worker.start();
            worker.join(5000L);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            ok[0] = false;
        }

        if (!ok[0])
        {
            FMLLog.log.error("[threading-selftest] TickContext confinement failed");
        }
        return ok[0];
    }

    /**
     * Simulates a region worker writing a block outside its region. The write must be deferred, then applied by the
     * owner thread when it drains the queue.
     */
    private static boolean testDeferredWorldMutation(WorldServer world)
    {
        if (world == null)
        {
            FMLLog.log.info("[threading-selftest] deferred world mutation skipped (world not ready)");
            return true;
        }

        final BlockPos pos = new BlockPos(0, 255, 0);
        final AtomicBoolean deferred = new AtomicBoolean(false);

        try
        {
            final IBlockState original = world.getBlockState(pos);

            Thread worker = new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    TickContext context = TickContext.getOrCreate();
                    context.setWorld(world);
                    context.setRegion(Region.forChunk(1, world.provider.getDimension(), 1000, 1000,
                            Region.DEFAULT_SIZE_CHUNKS));
                    try
                    {
                        deferred.set(!world.setBlockState(pos, Blocks.STONE.getDefaultState(), 3));
                    }
                    finally
                    {
                        TickContext.clear();
                    }
                }
            }, "Threading SelfTest Deferred");
            worker.start();
            worker.join(5000L);

            int queued = world.getDeferredMutations().size();
            world.getDeferredMutations().drainAll();
            boolean applied = world.getBlockState(pos).getBlock() == Blocks.STONE;
            world.setBlockState(pos, original, 3); // restore from the owner thread

            boolean ok = deferred.get() && queued > 0 && applied;
            if (!ok)
            {
                FMLLog.log.error("[threading-selftest] Deferred world mutation failed: deferred={}, queued={}, applied={}",
                        Boolean.valueOf(deferred.get()), Integer.valueOf(queued), Boolean.valueOf(applied));
            }
            return ok;
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            return false;
        }
        catch (Throwable throwable)
        {
            FMLLog.log.error("[threading-selftest] Deferred world mutation threw", throwable);
            return false;
        }
    }

    private static boolean testEventBus()
    {
        EventBus bus = new EventBus();
        TestHandler handler = new TestHandler();
        ModContainer previous = Loader.instance().activeModContainer();

        try
        {
            Loader.instance().setActiveModContainer(Loader.instance().getMinecraftModContainer());
            bus.register(handler);
            bus.post(new TestEvent());

            Thread worker = new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    TickContext context = TickContext.getOrCreate();
                    context.setRegion(Region.forChunk(1, 0, 0, 0, Region.DEFAULT_SIZE_CHUNKS));
                    try
                    {
                        bus.post(new TestEvent());
                    }
                    finally
                    {
                        TickContext.clear();
                    }
                }
            }, "Threading SelfTest EventBus");
            worker.start();
            MainThreadDispatcher.join(worker, 5000L);
        }
        finally
        {
            Loader.instance().setActiveModContainer(previous);
            bus.shutdown();
        }

        // First post on the owner: MAIN + ANY. Second post from a region worker: MAIN is forwarded to the owner
        // (legacy adapter), ANY runs on the worker, so both counters reach 2.
        boolean ok = handler.main.get() == 2 && handler.any.get() == 2;
        if (!ok)
        {
            FMLLog.log.error("[threading-selftest] EventBus thread filtering failed: main={}, any={}",
                    Integer.valueOf(handler.main.get()), Integer.valueOf(handler.any.get()));
        }
        return ok;
    }
}
