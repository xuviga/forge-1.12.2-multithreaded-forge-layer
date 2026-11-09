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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ic2.api.event.ExplosionEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * IC2's ExplosionIC2 removes blocks itself and only sends its own particle effect packet, not SPacketExplosion.
 * Some block-change updates can be lost/merged while the explosion drops are spawned, leaving ghost blocks on the
 * client. After the explosion has fully completed, resend the affected chunks once so the client replaces its
 * block data from the authoritative server state.
 *
 * Explosions are batched per server tick and chunks are deduplicated: ten stacked dynamite must not produce ten
 * copies of every chunk packet and ten render rebuild bursts.
 */
public final class IC2ExplosionSync
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");
    private static final List<Pending> PENDING = new ArrayList<Pending>();
    private static boolean registered;

    private IC2ExplosionSync()
    {
    }

    public static void register()
    {
        if (!registered)
        {
            registered = true;
            MinecraftForge.EVENT_BUS.register(new IC2ExplosionSync());
            LOGGER.info("[ForgeThreading] IC2 explosion block resync registered");
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onExplosion(ExplosionEvent event)
    {
        if (event.getWorld() == null || event.getWorld().isRemote)
        {
            return;
        }

        synchronized (PENDING)
        {
            PENDING.add(new Pending((WorldServer)event.getWorld(), event.pos, event.power, event.rangeLimit));
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }

        List<Pending> ready = null;
        synchronized (PENDING)
        {
            if (PENDING.isEmpty())
            {
                return;
            }

            for (Iterator<Pending> iterator = PENDING.iterator(); iterator.hasNext();)
            {
                Pending pending = iterator.next();
                if (--pending.delayTicks <= 0)
                {
                    if (ready == null)
                    {
                        ready = new ArrayList<Pending>();
                    }
                    ready.add(pending);
                    iterator.remove();
                }
            }
        }

        if (ready != null)
        {
            resyncBatch(ready);
        }
    }

    private static void resyncBatch(List<Pending> ready)
    {
        Map<WorldServer, List<Pending>> byWorld = new HashMap<WorldServer, List<Pending>>();
        for (Pending pending : ready)
        {
            List<Pending> list = byWorld.get(pending.world);
            if (list == null)
            {
                list = new ArrayList<Pending>();
                byWorld.put(pending.world, list);
            }
            list.add(pending);
        }

        for (Map.Entry<WorldServer, List<Pending>> entry : byWorld.entrySet())
        {
            resyncWorld(entry.getKey(), entry.getValue());
        }
    }

    private static void resyncWorld(WorldServer world, List<Pending> explosions)
    {
        Set<Long> chunkKeys = new HashSet<Long>();
        double maxRadius = 0.0D;

        for (Pending pending : explosions)
        {
            double radius = Math.min(64.0D, Math.max(24.0D, Math.max(pending.power * 3.0D, pending.rangeLimit * 2.0D)));
            maxRadius = Math.max(maxRadius, radius);
            int minChunkX = (int)Math.floor((pending.pos.x - radius) / 16.0D);
            int maxChunkX = (int)Math.floor((pending.pos.x + radius) / 16.0D);
            int minChunkZ = (int)Math.floor((pending.pos.z - radius) / 16.0D);
            int maxChunkZ = (int)Math.floor((pending.pos.z + radius) / 16.0D);

            for (int chunkX = minChunkX; chunkX <= maxChunkX; ++chunkX)
            {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; ++chunkZ)
                {
                    chunkKeys.add(Long.valueOf(ChunkPos.asLong(chunkX, chunkZ)));
                }
            }
        }

        // Build each chunk packet once and reuse it for every nearby player.
        Map<Long, SPacketChunkData[]> packets = new HashMap<Long, SPacketChunkData[]>(chunkKeys.size());
        for (Long key : chunkKeys)
        {
            int chunkX = (int)(key.longValue() & 0xffffffffL);
            int chunkZ = (int)(key.longValue() >>> 32);
            Chunk chunk = world.getChunk(chunkX, chunkZ);

            // Never send filter 65535: that sets fullChunk=true on the client and replaces the whole Chunk,
            // including its entity list (explosion drops would vanish). Two non-full masks update all sections.
            SPacketChunkData lower = new SPacketChunkData(chunk, 0x7FFF);
            lower.setForgeResync(true);
            SPacketChunkData upper = new SPacketChunkData(chunk, 0x8000);
            upper.setForgeResync(true);
            packets.put(key, new SPacketChunkData[] { lower, upper });
        }

        double playerRangeSq = (maxRadius + 64.0D) * (maxRadius + 64.0D);
        int players = 0;
        int packetsSent = 0;
        for (net.minecraft.entity.player.EntityPlayer playerBase : world.playerEntities)
        {
            if (!(playerBase instanceof EntityPlayerMP))
            {
                continue;
            }
            EntityPlayerMP player = (EntityPlayerMP)playerBase;

            boolean near = false;
            for (Pending pending : explosions)
            {
                if (player.getDistanceSq(pending.pos.x, pending.pos.y, pending.pos.z) <= playerRangeSq)
                {
                    near = true;
                    break;
                }
            }
            if (!near)
            {
                continue;
            }

            ++players;
            for (SPacketChunkData[] pair : packets.values())
            {
                player.connection.sendPacket(pair[0]);
                player.connection.sendPacket(pair[1]);
                packetsSent += 2;
            }
        }

        if (Boolean.getBoolean("forge.threading.debugPickup"))
        {
            LOGGER.info("[pickup-debug] IC2 explosion resync batch: explosions={} uniqueChunks={} players={} packetsSent={}",
                    Integer.valueOf(explosions.size()), Integer.valueOf(chunkKeys.size()), Integer.valueOf(players),
                    Integer.valueOf(packetsSent));
        }
    }

    /** Drops pending explosion resync entries that still point at an unloaded world. */
    public static int clearWorld(net.minecraft.world.World world)
    {
        if (world == null)
        {
            return 0;
        }
        int removed = 0;
        synchronized (PENDING)
        {
            for (java.util.Iterator<Pending> iterator = PENDING.iterator(); iterator.hasNext(); )
            {
                if (iterator.next().world == world)
                {
                    iterator.remove();
                    ++removed;
                }
            }
        }
        return removed;
    }

    private static final class Pending
    {
        private final WorldServer world;
        private final Vec3d pos;
        private final double power;
        private final double rangeLimit;
        private int delayTicks = 5;

        private Pending(WorldServer world, Vec3d pos, double power, double rangeLimit)
        {
            this.world = world;
            this.pos = new Vec3d(pos.x, pos.y, pos.z);
            this.power = power;
            this.rangeLimit = rangeLimit;
        }
    }
}
