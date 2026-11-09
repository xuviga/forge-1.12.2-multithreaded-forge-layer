/*
 * Minecraft Forge
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 */

package net.minecraftforge.common.memory;

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
 * /forgemem [status|purge|gc|aggressive on|off|capability on|off|dump]
 *
 * status  - heap, world/leak counters and the capability-owner audit.
 * purge   - drop finished chunk-IO tasks and reset leak tracking.
 * gc      - explicit diagnostic full GC, then report.
 * aggressive - toggle the reflective mod-static reference purger (also controls future unloads).
 * capability - toggle the weak capability-owner audit.
 * dump    - write the report to logs/forgemem-&lt;timestamp&gt;.txt.
 */
public class CommandForgeMemory extends CommandBase
{
    @Override
    public String getName()
    {
        return "forgemem";
    }

    @Override
    public String getUsage(ICommandSender sender)
    {
        return "/forgemem [status|purge|gc|aggressive on|off|capability on|off|dump]";
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
            sendLines(sender, MemoryLeakGuard.audit());
            return;
        }

        if ("purge".equalsIgnoreCase(args[0]))
        {
            sendLines(sender, MemoryLeakGuard.purge());
            return;
        }

        if ("gc".equalsIgnoreCase(args[0]))
        {
            sendLines(sender, MemoryLeakGuard.gc());
            return;
        }

        if ("dump".equalsIgnoreCase(args[0]))
        {
            try
            {
                File file = MemoryLeakGuard.writeDump();
                sender.sendMessage(new TextComponentString("Forge memory audit written to " + file.getAbsolutePath()));
            }
            catch (IOException exception)
            {
                throw new CommandException("Could not write memory audit: " + exception.getMessage());
            }
            return;
        }

        if ("aggressive".equalsIgnoreCase(args[0]))
        {
            if (args.length == 1)
            {
                sender.sendMessage(new TextComponentString("aggressive=" + MemoryLeakGuard.isAggressive()));
                return;
            }
            if ("on".equalsIgnoreCase(args[1]))
            {
                MemoryLeakGuard.setAggressive(true);
                sender.sendMessage(new TextComponentString("Forge memory aggressive mod-static purge enabled"));
                return;
            }
            if ("off".equalsIgnoreCase(args[1]))
            {
                MemoryLeakGuard.setAggressive(false);
                sender.sendMessage(new TextComponentString("Forge memory aggressive mod-static purge disabled"));
                return;
            }
            throw new CommandException(getUsage(sender));
        }

        if ("capability".equalsIgnoreCase(args[0]))
        {
            if (args.length == 1)
            {
                sender.sendMessage(new TextComponentString("capabilityAudit=" + CapabilityLeakTracker.isEnabled()));
                return;
            }
            if ("on".equalsIgnoreCase(args[1]))
            {
                CapabilityLeakTracker.setEnabled(true);
                sender.sendMessage(new TextComponentString("Capability-owner weak audit enabled"));
                return;
            }
            if ("off".equalsIgnoreCase(args[1]))
            {
                CapabilityLeakTracker.setEnabled(false);
                sender.sendMessage(new TextComponentString("Capability-owner weak audit disabled"));
                return;
            }
            throw new CommandException(getUsage(sender));
        }

        throw new CommandException(getUsage(sender));
    }

    private static void sendLines(ICommandSender sender, List<String> lines)
    {
        for (String line : lines)
        {
            sender.sendMessage(new TextComponentString(line));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos)
    {
        if (args.length == 1)
        {
            return getListOfStringsMatchingLastWord(args, "status", "purge", "gc", "aggressive", "capability", "dump");
        }
        if (args.length == 2 && ("aggressive".equalsIgnoreCase(args[0]) || "capability".equalsIgnoreCase(args[0])))
        {
            return getListOfStringsMatchingLastWord(args, "on", "off");
        }
        return Collections.emptyList();
    }
}
