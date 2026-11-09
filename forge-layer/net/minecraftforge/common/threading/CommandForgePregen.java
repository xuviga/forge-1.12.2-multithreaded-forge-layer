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

import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

public class CommandForgePregen extends CommandBase
{
    @Override
    public String getName()
    {
        return "forgepregen";
    }

    @Override
    public String getUsage(ICommandSender sender)
    {
        return "/forgepregen <radius>";
    }

    @Override
    public int getRequiredPermissionLevel()
    {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException
    {
        if (args.length != 1)
        {
            throw new CommandException("Usage: " + this.getUsage(sender));
        }

        int radius = parseInt(args[0], 1, 64);
        BlockPos center = sender.getPosition();
        WorldServer world = sender.getEntityWorld() instanceof WorldServer
                ? (WorldServer)sender.getEntityWorld()
                : server.getWorld(0);
        int chunkX = center.getX() >> 4;
        int chunkZ = center.getZ() >> 4;
        int total = PregenManager.start(world, chunkX, chunkZ, radius);
        sender.sendMessage(new TextComponentString("Pregen started: radius=" + radius + " chunks=" + total));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos)
    {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, "8", "16", "24", "32") : Collections.<String>emptyList();
    }
}
