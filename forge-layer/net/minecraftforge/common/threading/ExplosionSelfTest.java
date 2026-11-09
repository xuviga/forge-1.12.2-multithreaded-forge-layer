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

import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Opt-in server-side regression test for cross-region explosion writes.
 *
 *   -Dforge.threading.explosionSelftest=true
 *
 * Builds a stone platform straddling a region boundary, runs an explosion from a region worker, then verifies on the
 * owner thread that both same-region and cross-region blocks became air. This isolates "phantom block" bugs from
 * client/render issues.
 */
public final class ExplosionSelfTest
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");

    private ExplosionSelfTest()
    {
    }

    public static void runIfRequested(final MinecraftServer server, final WorldServer world)
    {
        if (world == null || !Boolean.parseBoolean(System.getProperty("forge.threading.explosionSelftest", "false")))
        {
            return;
        }

        final BlockPos sameRegion = new BlockPos(3, 70, 3);
        final BlockPos crossRegion = new BlockPos(-3, 70, -3);

        try
        {
            for (int x = -3; x <= 3; x++)
            {
                for (int z = -3; z <= 3; z++)
                {
                    world.setBlockState(new BlockPos(x, 70, z), Blocks.DIRT.getDefaultState(), 3);
                    world.setBlockState(new BlockPos(x, 71, z), Blocks.DIRT.getDefaultState(), 3);
                }
            }

            Thread worker = new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    TickContext context = TickContext.getOrCreate();
                    context.setWorld(world);
                    context.setRegion(Region.forChunk(1, world.provider.getDimension(), 0, 0,
                            Region.DEFAULT_SIZE_CHUNKS));
                    context.setPhase(TickPhase.ENTITIES);
                    context.setTickId(world.getTotalWorldTime());
                    try
                    {
                        world.createExplosion(null, 0.5D, 72.0D, 0.5D, 6.0F, true);
                    }
                    finally
                    {
                        TickContext.clear();
                    }
                }
            }, "Explosion SelfTest");

            worker.start();
            // Pump forwarded MAIN handlers while the worker runs, otherwise the dispatcher falls back to running
            // them inline on the test worker after the 50 ms wait.
            MainThreadDispatcher.join(worker, 5000L);

            Thread checker = new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        Thread.sleep(2000L);
                    }
                    catch (InterruptedException ignored)
                    {
                        Thread.currentThread().interrupt();
                        return;
                    }

                    server.addScheduledTask(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            boolean sameAir = world.getBlockState(sameRegion).getBlock() == Blocks.AIR;
                            boolean crossAir = world.getBlockState(crossRegion).getBlock() == Blocks.AIR;
                            boolean pass = sameAir && crossAir;
                            LOGGER.info("[explosion-selftest] {} sameRegionAir={} crossRegionAir={} queued={}",
                                    pass ? "PASS" : "FAIL", Boolean.valueOf(sameAir), Boolean.valueOf(crossAir),
                                    Integer.valueOf(world.getDeferredMutations().size()));
                        }
                    });
                }
            }, "Explosion SelfTest Checker");
            checker.setDaemon(true);
            checker.start();
        }
        catch (Throwable throwable)
        {
            LOGGER.error("[explosion-selftest] failed to run", throwable);
        }
    }
}
