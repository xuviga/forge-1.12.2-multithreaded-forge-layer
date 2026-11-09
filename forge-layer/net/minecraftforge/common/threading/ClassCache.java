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

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.common.ForgeVersion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Persistent class-byte cache.
 *
 * LaunchClassLoader already keeps raw class bytes in a ConcurrentHashMap named resourceCache. We pre-populate that
 * map from a disk file before mod discovery/init, so classloading during preInit/init does not have to open and
 * decompress every mod jar again. The map is populated by reflection and remains a normal ConcurrentHashMap, so
 * LaunchClassLoader's own getClassBytes path stays intact.
 *
 * The cache file is keyed by mods + the Forge jar metadata + Forge/MC/cache version. Only .class byte arrays are
 * stored; nothing is executed while building the cache.
 *
 * -Dforge.threading.classCache=false disables it.
 */
public final class ClassCache
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");
    // Measured on the 38-mod test pack: building/loading class bytes did not beat the normal
    // LaunchClassLoader path and added ~165 MB. Keep it as an explicit opt-in experiment only.
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("forge.threading.classCache", "false"));
    private static final int MAGIC = 0x434C4331;
    private static final int VERSION = 2;
    private static final int MAX_CACHE_FILES = 3;
    private static final long MAX_CACHE_BYTES = 256L * 1024L * 1024L;
    private static volatile boolean installed;
    private static volatile boolean building;
    private static volatile String cachedKey;

    private ClassCache()
    {
    }

    public static void install(LaunchClassLoader loader)
    {
        if (!ENABLED || installed || loader == null)
        {
            return;
        }
        synchronized (ClassCache.class)
        {
            if (installed)
            {
                return;
            }
            installed = true;
        }

        try
        {
            File gameDir = Launch.minecraftHome != null ? Launch.minecraftHome : new File(System.getProperty("user.dir"));
            Map<String, byte[]> cache = resourceCache(loader);
            if (cache == null)
            {
                return;
            }

            File file = cacheFile(gameDir);
            if (file != null && file.isFile() && file.length() <= MAX_CACHE_BYTES)
            {
                load(cache, file);
            }
            else
            {
                buildAsync(gameDir, loader, cache, file);
            }
        }
        catch (Throwable throwable)
        {
            LOGGER.warn("[class-cache] install failed; using normal classloading", throwable);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, byte[]> resourceCache(LaunchClassLoader loader) throws Exception
    {
        Field field = LaunchClassLoader.class.getDeclaredField("resourceCache");
        field.setAccessible(true);
        Object value = field.get(loader);
        return value instanceof Map ? (Map<String, byte[]>)value : null;
    }

    private static void load(Map<String, byte[]> cache, File file)
    {
        long start = System.nanoTime();
        int loaded = 0;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file))))
        {
            if (in.readInt() != MAGIC || in.readInt() != VERSION)
            {
                LOGGER.warn("[class-cache] incompatible cache {}", file.getName());
                return;
            }
            String key = in.readUTF();
            if (!key.equals(key(file.getParentFile().getParentFile())))
            {
                LOGGER.info("[class-cache] key changed, ignoring {}", file.getName());
                return;
            }
            int count = in.readInt();
            for (int index = 0; index < count; index++)
            {
                String className = in.readUTF();
                int length = in.readInt();
                byte[] data = new byte[length];
                in.readFully(data);
                if (cache.putIfAbsent(className, data) == null)
                {
                    loaded++;
                }
            }
        }
        catch (Throwable throwable)
        {
            LOGGER.warn("[class-cache] load failed", throwable);
            return;
        }

        double ms = (System.nanoTime() - start) / 1.0E6D;
        LOGGER.info("[class-cache] loaded {} class byte arrays in {} ms from {}", Integer.valueOf(loaded),
                String.format("%.1f", Double.valueOf(ms)), file.getName());
    }

    private static void buildAsync(File gameDir, LaunchClassLoader loader, Map<String, byte[]> cache, File target)
    {
        if (building || target == null)
        {
            return;
        }
        synchronized (ClassCache.class)
        {
            if (building)
            {
                return;
            }
            building = true;
        }

        Thread thread = new Thread(() -> build(gameDir, loader, cache, target), "ForgeClassCache-Build");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
        LOGGER.info("[class-cache] building cache in background");
    }

    private static void build(File gameDir, LaunchClassLoader loader, Map<String, byte[]> cache, File target)
    {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        long start = System.nanoTime();
        int written = 0;

        try
        {
            if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs())
            {
                return;
            }

            Set<File> jars = jars(gameDir, loader);
            Set<String> seen = new HashSet<String>();

            try (RandomAccessFile out = new RandomAccessFile(tmp, "rw"))
            {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeUTF(key(gameDir));
                long countPosition = out.getFilePointer();
                out.writeInt(0);

                for (File jar : jars)
                {
                    try (JarFile zip = new JarFile(jar))
                    {
                        if (isSignedOrSealed(zip))
                        {
                            continue;
                        }

                        Enumeration<? extends ZipEntry> entries = zip.entries();
                        while (entries.hasMoreElements())
                        {
                            ZipEntry entry = entries.nextElement();
                            String name = entry.getName();
                            if (entry.isDirectory() || !name.endsWith(".class"))
                            {
                                continue;
                            }
                            String className = name.substring(0, name.length() - 6).replace('/', '.');
                            if (!seen.add(className))
                            {
                                continue;
                            }

                            byte[] data;
                            try (InputStream in = zip.getInputStream(entry))
                            {
                                data = readAll(in, entry.getSize());
                            }
                            cache.putIfAbsent(className, data);
                            out.writeUTF(className);
                            out.writeInt(data.length);
                            out.write(data);
                            written++;
                        }
                    }
                    catch (Throwable throwable)
                    {
                        // A malformed/nested jar must not break startup or the cache.
                    }
                }

                out.seek(countPosition);
                out.writeInt(written);
            }

            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            double ms = (System.nanoTime() - start) / 1.0E6D;
            LOGGER.info("[class-cache] built {} class byte arrays in {} ms to {}", Integer.valueOf(written),
                    String.format("%.1f", Double.valueOf(ms)), target.getName());
            cleanup(target.getParentFile());
        }
        catch (Throwable throwable)
        {
            LOGGER.warn("[class-cache] build failed", throwable);
            if (tmp.isFile())
            {
                tmp.delete();
            }
        }
    }

    private static boolean isSignedOrSealed(JarFile jar)
    {
        try
        {
            Manifest manifest = jar.getManifest();
            if (manifest != null && "true".equalsIgnoreCase(manifest.getMainAttributes().getValue("Sealed")))
            {
                return true;
            }
        }
        catch (Throwable ignored)
        {
        }

        Enumeration<? extends ZipEntry> entries = jar.entries();
        while (entries.hasMoreElements())
        {
            String name = entries.nextElement().getName().toUpperCase(Locale.ROOT);
            if (name.startsWith("META-INF/")
                    && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC")))
            {
                return true;
            }
        }
        return false;
    }

    private static Set<File> jars(File gameDir, LaunchClassLoader loader)
    {
        Set<File> jars = new HashSet<File>();
        try
        {
            for (URL url : loader.getSources())
            {
                if ("file".equalsIgnoreCase(url.getProtocol()))
                {
                    URI uri = url.toURI();
                    File file = new File(uri);
                    if (file.isFile() && (file.getName().endsWith(".jar") || file.getName().endsWith(".zip")))
                    {
                        jars.add(file);
                    }
                }
            }
        }
        catch (Throwable ignored)
        {
        }

        File mods = new File(gameDir, "mods");
        if (mods.isDirectory())
        {
            try (Stream<Path> stream = Files.walk(mods.toPath()))
            {
                stream.filter(Files::isRegularFile)
                        .map(Path::toFile)
                        .filter(file -> file.getName().endsWith(".jar") || file.getName().endsWith(".zip"))
                        .forEach(jars::add);
            }
            catch (Throwable ignored)
            {
            }
        }

        try
        {
            URL location = ClassCache.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null && "file".equalsIgnoreCase(location.getProtocol()))
            {
                File file = new File(location.toURI());
                if (file.isFile())
                {
                    jars.add(file);
                }
            }
        }
        catch (Throwable ignored)
        {
        }
        return jars;
    }

    private static byte[] readAll(InputStream in, long size) throws IOException
    {
        if (size > 0L && size <= Integer.MAX_VALUE)
        {
            byte[] data = new byte[(int)size];
            int offset = 0;
            while (offset < data.length)
            {
                int read = in.read(data, offset, data.length - offset);
                if (read < 0)
                {
                    break;
                }
                offset += read;
            }
            if (offset == data.length)
            {
                return data;
            }
        }

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0)
        {
            bytes.write(buffer, 0, read);
        }
        return bytes.toByteArray();
    }

    private static File cacheFile(File gameDir) throws Exception
    {
        File dir = new File(gameDir, "forge-class-cache");
        return new File(dir, "classcache-" + key(gameDir) + ".bin");
    }

    private static String key(File gameDir)
    {
        String key = cachedKey;
        if (key != null)
        {
            return key;
        }
        synchronized (ClassCache.class)
        {
            if (cachedKey != null)
            {
                return cachedKey;
            }
            try
            {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                update(digest, "class-cache=" + VERSION);
                update(digest, "mc=" + ForgeVersion.mcVersion);
                update(digest, "forge=" + ForgeVersion.majorVersion + "." + ForgeVersion.minorVersion + "."
                        + ForgeVersion.revisionVersion + "." + ForgeVersion.buildVersion);
                hashDir(digest, new File(gameDir, "mods"));
                try
                {
                    URL location = ClassCache.class.getProtectionDomain().getCodeSource().getLocation();
                    if (location != null && "file".equalsIgnoreCase(location.getProtocol()))
                    {
                        File file = new File(location.toURI());
                        update(digest, file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified());
                    }
                }
                catch (Throwable ignored)
                {
                }
                byte[] hash = digest.digest();
                StringBuilder builder = new StringBuilder(hash.length * 2);
                for (byte value : hash)
                {
                    builder.append(String.format("%02x", Integer.valueOf(value & 255)));
                }
                cachedKey = builder.toString();
                return cachedKey;
            }
            catch (Throwable throwable)
            {
                cachedKey = "nocache";
                return cachedKey;
            }
        }
    }

    private static void hashDir(MessageDigest digest, File dir)
    {
        if (dir == null || !dir.isDirectory())
        {
            return;
        }
        Path root = dir.toPath();
        try (Stream<Path> stream = Files.walk(root))
        {
            stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .forEach(path ->
                    {
                        try
                        {
                            String relative = root.relativize(path).toString().replace('\\', '/');
                            update(digest, relative + "|" + Files.size(path) + "|" + Files.getLastModifiedTime(path).toMillis());
                        }
                        catch (Throwable ignored)
                        {
                        }
                    });
        }
        catch (Throwable ignored)
        {
        }
    }

    private static void update(MessageDigest digest, String value)
    {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte)0);
    }

    private static void cleanup(File dir)
    {
        File[] files = dir.listFiles((directory, name) -> name.startsWith("classcache-") && name.endsWith(".bin"));
        if (files == null || files.length <= MAX_CACHE_FILES)
        {
            return;
        }
        List<File> list = new ArrayList<File>();
        for (File file : files)
        {
            list.add(file);
        }
        list.sort(Comparator.comparingLong(File::lastModified).reversed());
        for (int index = MAX_CACHE_FILES; index < list.size(); index++)
        {
            list.get(index).delete();
        }
    }
}
