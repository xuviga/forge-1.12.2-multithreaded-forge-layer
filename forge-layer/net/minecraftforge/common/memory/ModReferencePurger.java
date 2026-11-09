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
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

/**
 * Opt-in reflective reference firewall for badly behaved mods.
 *
 * ChickenChunks/Energy-Control style leaks usually come from a static map in the mod that keeps the World, a
 * Chunk, or a world-bound Ticket after the dimension unloads. We cannot enumerate every loaded class without a
 * Java agent, but we can enumerate the classes of every active mod jar and inspect their static Map/Collection/
 * array fields. On WorldEvent.Unload we remove entries whose key or value is the unloaded world (or an object
 * whose direct field world/worldObj/level/dimension points at it).
 *
 * This is deliberately opt-in (-Dforge.memory.aggressivePurge=true or /forgemem aggressive on) and only touches
 * classes loaded by the mod class loader, never Forge/vanilla classes (those are purged explicitly elsewhere).
 * Every removal is logged so a bad interaction can be diagnosed.
 */
public final class ModReferencePurger
{
    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("ForgeMemory");
    private static final int MAX_CLASSES = Integer.getInteger("forge.memory.maxScannedClasses", 200000).intValue();
    private static final long MAX_ENTRIES = Long.getLong("forge.memory.maxScannedEntries", 5000000L).longValue();
    private static final Set<String> WORLD_FIELD_NAMES = ConcurrentHashMap.newKeySet();
    private static final Map<Class<?>, Field[]> WORLD_FIELD_CACHE = new ConcurrentHashMap<Class<?>, Field[]>();

    static
    {
        WORLD_FIELD_NAMES.add("world");
        WORLD_FIELD_NAMES.add("worldObj");
        WORLD_FIELD_NAMES.add("level");
        WORLD_FIELD_NAMES.add("dimension");
        WORLD_FIELD_NAMES.add("worldServer");
        WORLD_FIELD_NAMES.add("targetWorld");
    }

    private ModReferencePurger()
    {
    }

    public static int purge(World world)
    {
        if (world == null || !MemoryLeakGuard.isAggressive())
        {
            return 0;
        }

        final ClassLoader modLoader = Loader.instance().getModClassLoader();
        if (modLoader == null)
        {
            return 0;
        }

        int removed = 0;
        long examined = 0L;
        long start = System.nanoTime();
        int classes = 0;
        int scanned = 0;

        for (ModContainer mod : Loader.instance().getActiveModList())
        {
            File source = mod.getSource();
            if (source == null)
            {
                continue;
            }
            List<String> names = classNames(source);
            classes += names.size();
            for (String className : names)
            {
                if (scanned++ >= MAX_CLASSES)
                {
                    LOGGER.warn("[mem][aggressive] class scan cap reached ({}), stopping", Integer.valueOf(MAX_CLASSES));
                    break;
                }
                try
                {
                    Class<?> type = Class.forName(className, false, modLoader);
                    if (type.getClassLoader() != modLoader)
                    {
                        continue;
                    }
                    long limit = MAX_ENTRIES - examined;
                    if (limit <= 0L)
                    {
                        LOGGER.warn("[mem][aggressive] entry scan cap reached ({}), stopping", Long.valueOf(MAX_ENTRIES));
                        break;
                    }
                    PurgeResult result = purgeClass(type, world, limit);
                    removed += result.removed;
                    examined += result.examined;
                }
                catch (Throwable ignored)
                {
                    // Missing optional dependency, exotic class loader, etc. Not our problem.
                }
            }
        }

        if (removed > 0)
        {
            LOGGER.warn("[mem][aggressive] removed {} static references to unloaded world ({} classes, {} entries, {} ms)",
                    Integer.valueOf(removed), Integer.valueOf(scanned), Long.valueOf(examined),
                    Long.valueOf((System.nanoTime() - start) / 1000000L));
        }
        else
        {
            LOGGER.debug("[mem][aggressive] no mod static references to unloaded world ({} classes scanned)",
                    Integer.valueOf(scanned));
        }
        return removed;
    }

    private static PurgeResult purgeClass(Class<?> type, World world, long limit)
    {
        PurgeResult result = new PurgeResult();
        Field[] fields = type.getDeclaredFields();
        for (Field field : fields)
        {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || field.isSynthetic())
            {
                continue;
            }
            Class<?> fieldType = field.getType();
            boolean isMap = Map.class.isAssignableFrom(fieldType);
            boolean isCollection = Collection.class.isAssignableFrom(fieldType);
            if (!isMap && !isCollection && !fieldType.isArray())
            {
                continue;
            }

            Object value;
            try
            {
                field.setAccessible(true);
                value = field.get(null);
            }
            catch (Throwable ignored)
            {
                continue;
            }
            if (value == null)
            {
                continue;
            }

            int removed = purgeValue(value, world, limit - result.examined);
            if (removed > 0)
            {
                result.removed += removed;
                LOGGER.warn("[mem][aggressive] {}.{}: removed {} reference(s) to unloaded world",
                        type.getName(), field.getName(), Integer.valueOf(removed));
            }
            result.examined += Math.max(removed, 1);
        }
        return result;
    }

    private static int purgeValue(Object value, World world, long limit)
    {
        if (limit <= 0L)
        {
            return 0;
        }
        int removed = 0;
        try
        {
            if (value instanceof Map)
            {
                Iterator<? extends Map.Entry<?, ?>> iterator = ((Map<?, ?>) value).entrySet().iterator();
                while (iterator.hasNext())
                {
                    Map.Entry<?, ?> entry = iterator.next();
                    if (matches(entry.getKey(), world) || matches(entry.getValue(), world))
                    {
                        iterator.remove();
                        removed++;
                    }
                }
            }
            else if (value instanceof Collection)
            {
                Iterator<?> iterator = ((Collection<?>) value).iterator();
                while (iterator.hasNext())
                {
                    if (matches(iterator.next(), world))
                    {
                        iterator.remove();
                        removed++;
                    }
                }
            }
            else if (value.getClass().isArray() && !value.getClass().getComponentType().isPrimitive())
            {
                int length = Array.getLength(value);
                for (int index = 0; index < length; index++)
                {
                    if (matches(Array.get(value, index), world))
                    {
                        Array.set(value, index, null);
                        removed++;
                    }
                }
            }
        }
        catch (UnsupportedOperationException ignored)
        {
            // Immutable view; nothing we can do without replacing the field.
        }
        catch (Throwable ignored)
        {
            // Concurrent modification in a mod map or a hostile collection implementation.
        }
        return removed;
    }

    private static boolean matches(Object candidate, World world)
    {
        if (candidate == null)
        {
            return false;
        }
        if (candidate == world)
        {
            return true;
        }
        if (candidate instanceof Class || candidate instanceof ClassLoader || candidate instanceof String)
        {
            return false;
        }
        try
        {
            if (candidate instanceof Chunk)
            {
                return ((Chunk) candidate).getWorld() == world;
            }
            if (candidate instanceof TileEntity)
            {
                return ((TileEntity) candidate).getWorld() == world;
            }
            if (candidate instanceof Entity)
            {
                return ((Entity) candidate).world == world;
            }
            for (Field field : worldFields(candidate.getClass()))
            {
                Object referenced;
                try
                {
                    referenced = field.get(candidate);
                }
                catch (Throwable ignored)
                {
                    continue;
                }
                if (referenced == world)
                {
                    return true;
                }
            }
        }
        catch (Throwable ignored)
        {
        }
        return false;
    }

    private static Field[] worldFields(Class<?> type)
    {
        Field[] cached = WORLD_FIELD_CACHE.get(type);
        if (cached != null)
        {
            return cached;
        }
        List<Field> fields = new ArrayList<Field>();
        for (Field field : type.getDeclaredFields())
        {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive())
            {
                continue;
            }
            if (!WORLD_FIELD_NAMES.contains(field.getName()))
            {
                continue;
            }
            try
            {
                field.setAccessible(true);
                fields.add(field);
            }
            catch (Throwable ignored)
            {
            }
        }
        Field[] result = fields.toArray(new Field[fields.size()]);
        WORLD_FIELD_CACHE.put(type, result);
        return result;
    }

    private static List<String> classNames(File source)
    {
        List<String> result = new ArrayList<String>();
        try
        {
            if (source.isDirectory())
            {
                collectDirectory(source.toPath(), source.toPath(), result);
            }
            else if (source.isFile())
            {
                try (JarFile jar = new JarFile(source))
                {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements())
                    {
                        String name = entries.nextElement().getName();
                        if (name.endsWith(".class") && !name.startsWith("META-INF/"))
                        {
                            result.add(name.substring(0, name.length() - 6).replace('/', '.'));
                        }
                    }
                }
            }
        }
        catch (Throwable ignored)
        {
        }
        return result;
    }

    private static void collectDirectory(Path root, Path directory, List<String> result)
    {
        File[] files = directory.toFile().listFiles();
        if (files == null)
        {
            return;
        }
        for (File file : files)
        {
            if (file.isDirectory())
            {
                collectDirectory(root, file.toPath(), result);
            }
            else if (file.getName().endsWith(".class"))
            {
                String relative = root.relativize(file.toPath()).toString().replace(File.separatorChar, '/');
                result.add(relative.substring(0, relative.length() - 6).replace('/', '.'));
            }
        }
    }

    private static final class PurgeResult
    {
        private int removed;
        private long examined;
    }
}
