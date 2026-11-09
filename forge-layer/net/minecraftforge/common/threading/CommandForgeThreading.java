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

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;

/**
 * /forgethreading [status|overlay on|off|dump|reset]
 *
 * Status prints TPS/MSPT, pool sizes/queues, main-thread phase costs and fallback counters. Overlay toggles the F3
 * lines. Dump writes the same detailed snapshot to logs/forgethreading-<timestamp>.txt.
 */
public class CommandForgeThreading extends CommandBase
{
    @Override
    public String getName()
    {
        return "forgethreading";
    }

    @Override
    public String getUsage(ICommandSender sender)
    {
        return "/forgethreading [status|overlay on|off|handlers|dump|reset]";
    }

    @Override
    public int getRequiredPermissionLevel()
    {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException
    {
        if (args.length == 0 || "status".equalsIgnoreCase(args[0]))
        {
            sendLines(sender, ThreadingTelemetry.getDetailedLines());
            return;
        }

        if ("overlay".equalsIgnoreCase(args[0]))
        {
            if (args.length == 1)
            {
                sender.sendMessage(new TextComponentString("overlay=" + ThreadingTelemetry.isOverlayEnabled()));
                return;
            }
            if ("on".equalsIgnoreCase(args[1]))
            {
                ThreadingTelemetry.setOverlayEnabled(true);
                sender.sendMessage(new TextComponentString("ForgeThreading F3 overlay enabled"));
                return;
            }
            if ("off".equalsIgnoreCase(args[1]))
            {
                ThreadingTelemetry.setOverlayEnabled(false);
                sender.sendMessage(new TextComponentString("ForgeThreading F3 overlay disabled"));
                return;
            }
            throw new CommandException(getUsage(sender));
        }

        if ("handlers".equalsIgnoreCase(args[0]))
        {
            sender.sendMessage(new TextComponentString("Inbound PLAY audit (static):"));
            sender.sendMessage(new TextComponentString(" server: 29 handlers, 27 call PacketThreadUtil; offload: handleResourcePackStatus, processKeepAlive"));
            sender.sendMessage(new TextComponentString(" client: 76 handlers, 72 call PacketThreadUtil; offload: handleDisconnect, handlePlayerListHeaderFooter, handleKeepAlive, handleResourcePack"));
            sender.sendMessage(new TextComponentString(" offloaded handlers are ack/UI-state/keepalive/resource-pack paths; world/player mutation handlers forward to the game thread"));
            return;
        }

        if ("dump".equalsIgnoreCase(args[0]))
        {
            try
            {
                File file = ThreadingTelemetry.dumpToFile();
                sender.sendMessage(new TextComponentString("ForgeThreading dump: " + file.getAbsolutePath()));
            }
            catch (IOException exception)
            {
                throw new CommandException("Could not write telemetry dump: " + exception.getMessage());
            }
            return;
        }

        if ("reset".equalsIgnoreCase(args[0]))
        {
            ThreadingTelemetry.resetCounters();
            sender.sendMessage(new TextComponentString("ForgeThreading counters and phases reset"));
            return;
        }

        throw new CommandException(getUsage(sender));
    }

    private static void sendLines(ICommandSender sender, List<String> lines)
    {
        for (int index = 0; index < lines.size(); index++)
        {
            sender.sendMessage(new TextComponentString(lines.get(index)));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos)
    {
        if (args.length == 1)
        {
            return getListOfStringsMatchingLastWord(args, "status", "overlay", "handlers", "dump", "reset");
        }
        if (args.length == 2 && "overlay".equalsIgnoreCase(args[0]))
        {
            return getListOfStringsMatchingLastWord(args, "on", "off");
        }
        return Collections.emptyList();
    }
}
