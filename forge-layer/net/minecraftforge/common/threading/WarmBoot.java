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

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Warm-boot support: after the player closes the window, the fully initialized client is hidden instead of being
 * destroyed. A local IPC endpoint can show it or quit it. The next launch only sends SHOW, so mod preInit/init and
 * resource loading are skipped entirely on subsequent starts. Mod contracts are untouched: this is the same running
 * game, just hidden.
 *
 * -Dforge.threading.warmBoot=true enables it. The launcher sets it automatically.
 * Commands (127.0.0.1, default port 25566): SHOW, QUIT.
 */
@SideOnly(Side.CLIENT)
public final class WarmBoot
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("forge.threading.warmBoot", "false"));
    private static final int PORT = Integer.getInteger("forge.threading.warmBootPort", 25566).intValue();
    private static final String TITLE = System.getProperty("forge.threading.warmBootTitle", "Minecraft 1.12.2");
    private static final Object LOCK = new Object();
    private static volatile boolean started;
    private static volatile boolean hidden;
    private static volatile boolean quit;

    private WarmBoot()
    {
    }

    public interface WndEnumProc extends StdCallLibrary.StdCallCallback
    {
        boolean callback(Pointer hwnd, Pointer lParam);
    }

    public interface User32 extends StdCallLibrary
    {
        User32 INSTANCE = (User32)Native.loadLibrary("user32", User32.class);
        boolean EnumWindows(WndEnumProc callback, Pointer lParam);
        int GetWindowTextA(Pointer hwnd, byte[] text, int max);
        int GetClassNameA(Pointer hwnd, byte[] text, int max);
        int GetWindowThreadProcessId(Pointer hwnd, IntByReference pid);
        Pointer FindWindowA(String className, String windowName);
        boolean ShowWindow(Pointer hwnd, int command);
        boolean SetForegroundWindow(Pointer hwnd);
        boolean IsWindow(Pointer hwnd);
    }

    public static void start()
    {
        if (!ENABLED || started)
        {
            return;
        }
        synchronized (WarmBoot.class)
        {
            if (started)
            {
                return;
            }
            started = true;
        }

        Thread thread = new Thread(WarmBoot::listen, "ForgeWarmBoot");
        thread.setDaemon(true);
        thread.start();
        LOGGER.info("[warm-boot] enabled on 127.0.0.1:{} (window '{}')", Integer.valueOf(PORT), TITLE);
    }

    private static void listen()
    {
        try (ServerSocket server = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1")))
        {
            server.setReuseAddress(true);
            while (!quit)
            {
                try (Socket socket = server.accept())
                {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
                    String command = reader.readLine();
                    if ("SHOW".equalsIgnoreCase(command))
                    {
                        show();
                        writer.println("OK");
                    }
                    else if ("QUIT".equalsIgnoreCase(command))
                    {
                        writer.println("OK");
                        quit();
                    }
                    else
                    {
                        writer.println("ERR");
                    }
                }
                catch (Throwable ignored)
                {
                }
            }
        }
        catch (Throwable throwable)
        {
            LOGGER.warn("[warm-boot] IPC listener failed", throwable);
        }
    }

    private static Pointer window()
    {
        try
        {
            final int pid = currentPid();
            final Pointer[] found = new Pointer[1];
            User32.INSTANCE.EnumWindows((hwnd, lParam) ->
            {
                IntByReference process = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hwnd, process);
                if (process.getValue() != pid)
                {
                    return true;
                }
                byte[] classNameBuffer = new byte[128];
                int classNameLength = User32.INSTANCE.GetClassNameA(hwnd, classNameBuffer, classNameBuffer.length);
                String className = classNameLength > 0
                    ? new String(classNameBuffer, 0, Math.min(classNameLength, classNameBuffer.length), StandardCharsets.US_ASCII).trim()
                    : "";
                if ("LWJGL".equals(className))
                {
                    found[0] = hwnd;
                    return false;
                }
                byte[] buffer = new byte[256];
                int length = User32.INSTANCE.GetWindowTextA(hwnd, buffer, buffer.length);
                if (length > 0)
                {
                    String title = new String(buffer, 0, Math.min(length, buffer.length), StandardCharsets.US_ASCII).trim();
                    if (!title.isEmpty() && title.toLowerCase(Locale.ROOT).contains("minecraft"))
                    {
                        found[0] = hwnd;
                        return false;
                    }
                }
                return true;
            }, null);
            if (found[0] != null)
            {
                return found[0];
            }
            return User32.INSTANCE.FindWindowA(null, TITLE);
        }
        catch (Throwable throwable)
        {
            return null;
        }
    }

    private static int currentPid()
    {
        try
        {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            return Integer.parseInt(at > 0 ? name.substring(0, at) : name);
        }
        catch (Throwable throwable)
        {
            return -1;
        }
    }

    private static void show()
    {
        synchronized (LOCK)
        {
            hidden = false;
            LOCK.notifyAll();
        }
        Pointer hwnd = window();
        LOGGER.info("[warm-boot] show hwnd={}", hwnd);
        if (hwnd != null)
        {
            User32.INSTANCE.ShowWindow(hwnd, 9);
            User32.INSTANCE.SetForegroundWindow(hwnd);
        }
        resetCloseRequest();
    }

    private static void resetCloseRequest()
    {
        try
        {
            Field implField = org.lwjgl.opengl.Display.class.getDeclaredField("display_impl");
            implField.setAccessible(true);
            Object impl = implField.get(null);
            if (impl != null)
            {
                Field close = impl.getClass().getDeclaredField("close_requested");
                close.setAccessible(true);
                close.setBoolean(impl, false);
            }
        }
        catch (Throwable throwable)
        {
            LOGGER.warn("[warm-boot] close-request reset failed", throwable);
        }
    }

    private static void quit()
    {
        boolean wasHidden;
        synchronized (LOCK)
        {
            wasHidden = hidden;
            quit = true;
            hidden = false;
            LOCK.notifyAll();
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && !wasHidden)
        {
            mc.addScheduledTask(mc::shutdown);
        }
    }

    /**
     * Called from the client thread when the window close button is pressed. Hides the window and blocks the client
     * loop until SHOW or QUIT. Returns true when the game should resume, false when it should shut down.
     */
    public static boolean hideAndWait()
    {
        if (!ENABLED)
        {
            return false;
        }
        Pointer hwnd = window();
        LOGGER.info("[warm-boot] close requested hwnd={}", hwnd);
        if (hwnd == null)
        {
            LOGGER.warn("[warm-boot] close requested but window not found");
            return false;
        }

        Minecraft mc = Minecraft.getMinecraft();
        synchronized (LOCK)
        {
            hidden = true;
            if (mc != null)
            {
                mc.setWarmBootPaused(true);
            }
            User32.INSTANCE.ShowWindow(hwnd, 0);

            while (hidden && !quit)
            {
                try
                {
                    LOCK.wait(1000L);
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            boolean resume = !quit;
            LOGGER.info("[warm-boot] resume={} quit={}", Boolean.valueOf(resume), Boolean.valueOf(quit));
            hidden = false;
            if (resume)
            {
                if (mc != null)
                {
                    mc.setWarmBootPaused(false);
                }
                User32.INSTANCE.ShowWindow(hwnd, 9);
                User32.INSTANCE.SetForegroundWindow(hwnd);
                resetCloseRequest();
            }
            return resume;
        }
    }

    public static boolean isEnabled()
    {
        return ENABLED;
    }
}
