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

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import java.lang.management.ManagementFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Top-right performance overlay: real FPS, frame time, TPS/MSPT, heap, physical RAM and per-core CPU load.
 *
 * CPU sampling uses NtQuerySystemInformation(SystemProcessorPerformanceInformation) through the JNA 4.4 core
 * already shipped with Minecraft 1.12.2. This works on Windows without jna-platform; OSHI 1.1's per-core path is
 * unusable there. Sampling runs on a daemon thread and is cached, so the render path never blocks.
 *
 * Toggle: F8 by default (key.stats_overlay in Controls) or Options -> Stats Overlay.
 */
@SideOnly(Side.CLIENT)
public final class StatsOverlay
{
    private static final int MAX_CORES = 32;
    private static final long SAMPLE_MS = 1000L;
    private static final int SYSTEM_PROCESSOR_PERFORMANCE_INFORMATION = 8;
    private static final int PROCESSOR_INFO_SIZE = 48;

    private static volatile boolean keyHeld;
    private static volatile float[] coreLoads = new float[0];
    private static volatile long totalRam;
    private static volatile long availableRam;
    private static volatile float overallCpu;
    private static volatile boolean samplerStarted;
    private static volatile boolean jnaFailed;
    private static volatile boolean renderFailed;
    private static volatile java.lang.management.OperatingSystemMXBean osBean;
    private static volatile long[] prevIdle;
    private static volatile long[] prevKernel;
    private static volatile long[] prevUser;

    private StatsOverlay()
    {
    }

    public interface NtDll extends Library
    {
        NtDll INSTANCE = (NtDll)Native.loadLibrary("ntdll", NtDll.class);
        int NtQuerySystemInformation(int infoClass, Pointer info, int length, IntByReference returned);
    }

    private static void ensureSampler()
    {
        if (samplerStarted)
        {
            return;
        }
        synchronized (StatsOverlay.class)
        {
            if (samplerStarted)
            {
                return;
            }
            samplerStarted = true;
            Thread thread = new Thread(StatsOverlay::sampleLoop, "ForgeStatsSampler");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static void sampleLoop()
    {
        try
        {
            osBean = ManagementFactory.getOperatingSystemMXBean();
        }
        catch (Throwable ignored)
        {
        }

        int count = Math.min(MAX_CORES, Runtime.getRuntime().availableProcessors());
        Memory memory = null;
        IntByReference returned = new IntByReference();
        try
        {
            memory = new Memory((long)count * (long)PROCESSOR_INFO_SIZE);
        }
        catch (Throwable throwable)
        {
            jnaFailed = true;
        }

        while (true)
        {
            try
            {
                if (memory != null && !jnaFailed)
                {
                    int status = NtDll.INSTANCE.NtQuerySystemInformation(SYSTEM_PROCESSOR_PERFORMANCE_INFORMATION,
                            memory, (int)memory.size(), returned);
                    if (status != 0)
                    {
                        jnaFailed = true;
                    }
                    else
                    {
                        long[] idle = new long[count];
                        long[] kernel = new long[count];
                        long[] user = new long[count];
                        for (int index = 0; index < count; index++)
                        {
                            long base = (long)index * (long)PROCESSOR_INFO_SIZE;
                            idle[index] = memory.getLong(base);
                            kernel[index] = memory.getLong(base + 8L);
                            user[index] = memory.getLong(base + 16L);
                        }

                        long[] oldIdle = prevIdle;
                        long[] oldKernel = prevKernel;
                        long[] oldUser = prevUser;
                        if (oldIdle != null && oldIdle.length == count)
                        {
                            float[] loads = new float[count];
                            float sum = 0.0F;
                            for (int index = 0; index < count; index++)
                            {
                                long di = idle[index] - oldIdle[index];
                                long dk = kernel[index] - oldKernel[index];
                                long du = user[index] - oldUser[index];
                                long total = di + dk + du;
                                float load = total <= 0L ? 0.0F : (float)((double)(total - di) / (double)total);
                                if (load < 0.0F)
                                {
                                    load = 0.0F;
                                }
                                if (load > 1.0F)
                                {
                                    load = 1.0F;
                                }
                                loads[index] = load;
                                sum += load;
                            }
                            coreLoads = loads;
                            overallCpu = sum / (float)Math.max(1, count);
                        }
                        prevIdle = idle;
                        prevKernel = kernel;
                        prevUser = user;
                    }
                }

                if (osBean instanceof com.sun.management.OperatingSystemMXBean)
                {
                    com.sun.management.OperatingSystemMXBean sun = (com.sun.management.OperatingSystemMXBean)osBean;
                    totalRam = sun.getTotalPhysicalMemorySize();
                    availableRam = sun.getFreePhysicalMemorySize();
                    if (jnaFailed || coreLoads.length == 0)
                    {
                        double load = sun.getSystemCpuLoad();
                        overallCpu = load < 0.0D ? 0.0F : (float)load;
                    }
                }
            }
            catch (Throwable throwable)
            {
                jnaFailed = true;
            }

            try
            {
                Thread.sleep(SAMPLE_MS);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public static void render(Minecraft mc)
    {
        ensureSampler();

        if (renderFailed || mc == null || mc.gameSettings == null || mc.fontRenderer == null || !mc.gameSettings.statsOverlay)
        {
            return;
        }

        try
        {
            draw(mc);
        }
        catch (Throwable throwable)
        {
            renderFailed = true;
        }
    }

    private static void draw(Minecraft mc)
    {
        ScaledResolution resolution = new ScaledResolution(mc);
        int screenWidth = resolution.getScaledWidth();
        int lineHeight = 10;
        float[] cores = coreLoads;
        int coreColumns = cores.length > 0 ? Math.min(16, cores.length) : 0;
        int coreRows = cores.length > coreColumns ? 2 : 1;
        int width = 218;
        int height = 8 + lineHeight * (7 + coreRows);
        int x = screenWidth - width - 4;
        int y = 4;

        Gui.drawRect(x, y, x + width, y + height, 0x90000000);
        Gui.drawRect(x, y, x + 1, y + height, 0xFF3AA0FF);

        int textX = x + 6;
        int textY = y + 5;
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        long heapMax = runtime.maxMemory();
        int fps = Minecraft.getDebugFPS();
        double frameMs = ThreadingMetrics.getLastClientFrameMs();
        double frameMax = ThreadingMetrics.getLastClientMaxMs();
        double mspt = ThreadingMetrics.getLastMspt();
        double msptMax = ThreadingMetrics.getLastMaxMspt();
        double tps = ThreadingMetrics.getLastTps();
        long physicalTotal = totalRam;
        long physicalFree = availableRam;
        long physicalUsed = physicalTotal > 0 && physicalFree >= 0 ? physicalTotal - physicalFree : 0;

        drawLine(mc, textX, textY, String.format("FPS %d  frame %.1f/%.1f ms", Integer.valueOf(fps), Double.valueOf(frameMs), Double.valueOf(frameMax)), 0xFFFFFFFF);
        textY += lineHeight;
        drawLine(mc, textX, textY, String.format("TPS %.2f  MSPT %.1f/%.1f ms", Double.valueOf(tps), Double.valueOf(mspt), Double.valueOf(msptMax)), tps >= 19.0D ? 0xFF55FF55 : 0xFFFFAA00);
        textY += lineHeight;
        drawLine(mc, textX, textY, String.format("Heap %.0f/%.0f MB", Double.valueOf(heapUsed / 1048576.0D), Double.valueOf(heapMax / 1048576.0D)), 0xFFCCCCCC);
        textY += lineHeight;
        if (physicalTotal > 0)
        {
            drawLine(mc, textX, textY, String.format("RAM %.1f/%.1f GB", Double.valueOf(physicalUsed / 1073741824.0D), Double.valueOf(physicalTotal / 1073741824.0D)), 0xFFCCCCCC);
        }
        else
        {
            drawLine(mc, textX, textY, "RAM sampling...", 0xFFCCCCCC);
        }
        textY += lineHeight;
        drawLine(mc, textX, textY, String.format("CPU %.1f%%  threads %d  regions %d", Double.valueOf(overallCpu * 100.0F), Integer.valueOf(Thread.activeCount()), Integer.valueOf(Threading.getRegionThreads())), cpuColor(overallCpu));
        textY += lineHeight;
        drawLine(mc, textX, textY, String.format("LOD %d/%d/%d sim%d skip%d",
                Integer.valueOf(RegionManager.getLodNear()), Integer.valueOf(RegionManager.getLodMid()),
                Integer.valueOf(RegionManager.getLodFar()), Integer.valueOf(Threading.getDynamicSimulationDistance()),
                Long.valueOf(ThreadingTelemetry.counter("entity.lod.skipped"))), 0xFFCCCCCC);
        textY += lineHeight;

        if (cores.length > 0)
        {
            drawLine(mc, textX, textY, "Cores", 0xFFCCCCCC);
            textY += lineHeight;
            int barWidth = 11;
            int gap = 2;
            int barHeight = 8;
            for (int index = 0; index < cores.length; index++)
            {
                int column = index % coreColumns;
                int row = index / coreColumns;
                int barX = textX + column * (barWidth + gap);
                int barY = textY + row * (barHeight + 3);
                Gui.drawRect(barX, barY, barX + barWidth, barY + barHeight, 0x60000000);
                int fill = Math.max(1, Math.round(cores[index] * barHeight));
                Gui.drawRect(barX, barY + barHeight - fill, barX + barWidth, barY + barHeight, cpuColor(cores[index]));
                mc.fontRenderer.drawStringWithShadow(String.valueOf(index % 10), barX + 3, barY + barHeight + 1, 0xFFAAAAAA);
            }
        }
        else
        {
            drawLine(mc, textX, textY, jnaFailed ? "Cores: JNA unavailable" : "Cores: sampling...", 0xFFFFAA00);
        }
    }

    private static void drawLine(Minecraft mc, int x, int y, String text, int color)
    {
        mc.fontRenderer.drawStringWithShadow(text, x, y, color);
    }

    private static int cpuColor(float load)
    {
        if (load >= 0.85F)
        {
            return 0xFFFF5555;
        }
        if (load >= 0.60F)
        {
            return 0xFFFFAA00;
        }
        return 0xFF55FF55;
    }

    /** True while the toggle key is held; Minecraft uses this for edge detection. */
    public static boolean isKeyHeld()
    {
        return keyHeld;
    }

    public static void setKeyHeld(boolean held)
    {
        keyHeld = held;
    }
}
