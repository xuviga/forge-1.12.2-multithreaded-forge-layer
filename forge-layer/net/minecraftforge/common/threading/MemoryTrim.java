package net.minecraftforge.common.threading;

import java.lang.reflect.Field;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Low-memory helpers. Forge's LaunchClassLoader keeps the transformed bytecode of every loaded class in
 * {@code resourceCache}; on a large 1.12.2 pack that is hundreds of megabytes of {@code byte[]} that stays live
 * forever. Once the startup model/texture classes are loaded the bytes can be re-read on demand, so dropping the
 * cache is safe and releases a large contiguous block before texture decoding peaks.
 */
public final class MemoryTrim
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");
    private static volatile boolean trimmed;
    private static volatile long nextTrimAt;

    private MemoryTrim()
    {
    }

    public static boolean isLowMemory()
    {
        return Runtime.getRuntime().maxMemory() <= 1280L * 1024L * 1024L;
    }

    public static boolean isTrimmed()
    {
        return trimmed;
    }

    /** Called from the client loop; drops bytecode loaded since the last pass without touching loaded classes. */
    public static void trimIfDue()
    {
        if (!isLowMemory())
        {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextTrimAt)
        {
            return;
        }
        nextTrimAt = now + 30000L;
        trimLaunchClassLoaderCache();
    }

    public static void trimLaunchClassLoaderCache()
    {
        if (!isLowMemory())
        {
            return;
        }

        try
        {
            Class<?> launch = Class.forName("net.minecraft.launchwrapper.Launch");
            Object loader = launch.getField("classLoader").get(null);
            if (loader == null)
            {
                return;
            }

            Field field = loader.getClass().getDeclaredField("resourceCache");
            field.setAccessible(true);
            Object value = field.get(loader);
            if (value instanceof Map)
            {
                Map<?, ?> cache = (Map<?, ?>)value;
                int size = cache.size();
                cache.clear();
                trimmed = true;
                if (size > 0)
                {
                    LOGGER.info("[low-memory] cleared LaunchClassLoader byte cache: {} entries", Integer.valueOf(size));
                }
            }
        }
        catch (Throwable throwable)
        {
            LOGGER.warn("[low-memory] class byte cache trim failed", throwable);
        }
    }
}
