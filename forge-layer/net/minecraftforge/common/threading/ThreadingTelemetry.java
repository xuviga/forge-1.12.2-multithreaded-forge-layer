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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Central runtime telemetry for every custom pipeline.
 *
 * This is deliberately allocation-light and thread-safe. The server marks phases with
 * {@link #recordPhase(String, long)}, increments counters with {@link #increment(String)} and registers pools with
 * {@link #registerPool(String, ExecutorService)}. The F3 overlay and /forgethreading command read a consistent snapshot.
 */
public final class ThreadingTelemetry
{
    private static final Logger LOGGER = LogManager.getLogger("ForgeThreading");
    private static final int WINDOW = 100;
    private static final ConcurrentHashMap<String, Phase> PHASES = new ConcurrentHashMap<String, Phase>();
    private static final ConcurrentHashMap<String, AtomicLong> COUNTERS = new ConcurrentHashMap<String, AtomicLong>();
    private static final ConcurrentHashMap<String, Rate> RATES = new ConcurrentHashMap<String, Rate>();
    private static final ConcurrentHashMap<String, ExecutorService> POOLS = new ConcurrentHashMap<String, ExecutorService>();
    private static final AtomicBoolean OVERLAY = new AtomicBoolean(
            Boolean.parseBoolean(System.getProperty("forge.threading.overlay", "true")));
    private static final AtomicBoolean overlayLogged = new AtomicBoolean();

    private static final AtomicLong tickCount = new AtomicLong();
    private static final AtomicLong tickTotalNanos = new AtomicLong();
    private static final AtomicLong tickMaxNanos = new AtomicLong();
    private static volatile double mspt = 0.0D;
    private static volatile double tps = 0.0D;
    private static volatile double maxMspt = 0.0D;

    private static final AtomicLong frameCount = new AtomicLong();
    private static final AtomicLong frameTotalNanos = new AtomicLong();
    private static final AtomicLong frameMaxNanos = new AtomicLong();
    private static volatile double fps = 0.0D;

    private ThreadingTelemetry()
    {
    }

    public static void registerPool(String name, ExecutorService service)
    {
        if (name != null && service != null)
        {
            POOLS.put(name, service);
        }
    }

    public static void increment(String key)
    {
        add(key, 1L);
    }

    public static void add(String key, long value)
    {
        if (key == null || value == 0L)
        {
            return;
        }
        AtomicLong counter = COUNTERS.get(key);
        if (counter == null)
        {
            AtomicLong created = new AtomicLong();
            counter = COUNTERS.putIfAbsent(key, created);
            if (counter == null)
            {
                counter = created;
            }
        }
        counter.addAndGet(value);
    }

    public static long counter(String key)
    {
        AtomicLong counter = COUNTERS.get(key);
        return counter == null ? 0L : counter.get();
    }

    /** Packets/ticks/events per second since the previous call for this key. */
    public static double rate(String key)
    {
        Rate rate = RATES.get(key);
        if (rate == null)
        {
            Rate created = new Rate();
            rate = RATES.putIfAbsent(key, created);
            if (rate == null)
            {
                rate = created;
            }
        }
        return rate.sample(counter(key));
    }

    public static void recordPhase(String name, long nanos)
    {
        if (name == null || nanos < 0L)
        {
            return;
        }
        Phase phase = PHASES.get(name);
        if (phase == null)
        {
            Phase created = new Phase();
            phase = PHASES.putIfAbsent(name, created);
            if (phase == null)
            {
                phase = created;
            }
        }
        phase.record(nanos);
    }

    /**
     * Accumulates a phase cost into the current server-tick window. Use this for phases that are called many times
     * per tick or from dimension/region workers (lighting, entity tasks, etc.). endServerTick publishes and resets it.
     */
    public static void addPhase(String name, long nanos)
    {
        if (name == null || nanos < 0L)
        {
            return;
        }
        Phase phase = PHASES.get(name);
        if (phase == null)
        {
            Phase created = new Phase();
            phase = PHASES.putIfAbsent(name, created);
            if (phase == null)
            {
                phase = created;
            }
        }
        phase.add(nanos);
    }

    public static void endServerTick()
    {
        for (Phase phase : PHASES.values())
        {
            phase.publishWindow();
        }
    }

    public static double phaseWindowLastMs(String name)
    {
        Phase phase = PHASES.get(name);
        return phase == null ? 0.0D : phase.windowLast();
    }

    public static double phaseWindowAvgMs(String name)
    {
        Phase phase = PHASES.get(name);
        return phase == null ? 0.0D : phase.windowAverage();
    }

    public static double phaseWindowMaxMs(String name)
    {
        Phase phase = PHASES.get(name);
        return phase == null ? 0.0D : phase.windowMax();
    }

    /**
     * Sum of the non-overlapping world phases for the last server tick. world.lighting is a cross-cutting cost and is
     * intentionally excluded; world.scheduledTicks/randomTicks/chunkTick/etc. are already inside world.tick.
     */
    public static double worldTotalMs()
    {
        return phaseWindowLastMs("world.tick")
                + phaseWindowLastMs("world.entities")
                + phaseWindowLastMs("world.tileEntities")
                + phaseWindowLastMs("world.tracking")
                + phaseWindowLastMs("world.pathfinding")
                + phaseWindowLastMs("world.hooks")
                + phaseWindowLastMs("world.deferred")
                + phaseWindowLastMs("world.hash");
    }

    public static double phaseLastMs(String name)
    {
        Phase phase = PHASES.get(name);
        return phase == null ? 0.0D : phase.last() / 1000000.0D;
    }

    public static double phaseAvgMs(String name)
    {
        Phase phase = PHASES.get(name);
        return phase == null ? 0.0D : phase.average() / 1000000.0D;
    }

    public static double phaseMaxMs(String name)
    {
        Phase phase = PHASES.get(name);
        return phase == null ? 0.0D : phase.max() / 1000000.0D;
    }

    public static void recordServerTick(long nanos)
    {
        BackgroundPacer.noteServerTick(nanos);
        long count = tickCount.incrementAndGet();
        tickTotalNanos.addAndGet(nanos);
        long previous = tickMaxNanos.get();
        while (nanos > previous && !tickMaxNanos.compareAndSet(previous, nanos))
        {
            previous = tickMaxNanos.get();
        }
        if (count >= WINDOW)
        {
            long window = tickCount.getAndSet(0);
            long total = tickTotalNanos.getAndSet(0);
            long max = tickMaxNanos.getAndSet(0);
            mspt = (double)total / (double)window / 1000000.0D;
            maxMspt = max / 1000000.0D;
            tps = Math.min(20.0D, 1000.0D / Math.max(0.001D, mspt));
        }
    }

    public static void recordClientFrame(long nanos)
    {
        BackgroundPacer.noteClientFrame(nanos);
        long count = frameCount.incrementAndGet();
        frameTotalNanos.addAndGet(nanos);
        long previous = frameMaxNanos.get();
        while (nanos > previous && !frameMaxNanos.compareAndSet(previous, nanos))
        {
            previous = frameMaxNanos.get();
        }
        if (count >= WINDOW)
        {
            long window = frameCount.getAndSet(0);
            long total = frameTotalNanos.getAndSet(0);
            frameMaxNanos.getAndSet(0);
            fps = 1000.0D / Math.max(0.001D, (double)total / (double)window / 1000000.0D);
        }
    }

    public static double getTps()
    {
        return tps;
    }

    public static double getMspt()
    {
        return mspt;
    }

    public static double getMaxMspt()
    {
        return maxMspt;
    }

    public static double getFps()
    {
        return fps;
    }

    public static boolean isOverlayEnabled()
    {
        return OVERLAY.get();
    }

    public static void setOverlayEnabled(boolean enabled)
    {
        OVERLAY.set(enabled);
    }

    public static PoolSnapshot pool(String name)
    {
        ExecutorService service = POOLS.get(name);
        if (service instanceof ThreadPoolExecutor)
        {
            ThreadPoolExecutor pool = (ThreadPoolExecutor)service;
            return new PoolSnapshot(pool.getPoolSize(), pool.getActiveCount(), pool.getQueue().size(),
                    pool.getCompletedTaskCount(), pool.getLargestPoolSize());
        }
        if (service instanceof ForkJoinPool)
        {
            ForkJoinPool pool = (ForkJoinPool)service;
            return new PoolSnapshot(pool.getPoolSize(), pool.getActiveThreadCount(), pool.getQueuedSubmissionCount(),
                    pool.getQueuedTaskCount(), pool.getPoolSize());
        }
        return new PoolSnapshot(0, 0, 0, 0, 0);
    }

    private static String poolLine(String name)
    {
        PoolSnapshot pool = pool(name);
        return name + "=" + pool.size + "/" + pool.active + "q" + pool.queue;
    }

    public static void appendOverlayLines(List<String> lines)
    {
        if (overlayLogged.compareAndSet(false, true))
        {
            LOGGER.info("[telemetry] F3 overlay append called; enabled={} property={} existingLines={}",
                    Boolean.valueOf(OVERLAY.get()), System.getProperty("forge.threading.overlay", "<unset>"),
                    Integer.valueOf(lines.size()));
        }
        if (!OVERLAY.get())
        {
            return;
        }

        lines.add("");
        lines.add(String.format(Locale.ROOT, "[ForgeThreading] TPS=%.2f MSPT=%.2f max=%.2f",
                Double.valueOf(tps), Double.valueOf(mspt), Double.valueOf(maxMspt)));
        lines.add(String.format(Locale.ROOT,
                "[Threading] dim=%d regions=%d size=%d auto=%s blocks=%s sched=%s light=%s",
                Integer.valueOf(Threading.getParallelDimensions()), Integer.valueOf(Threading.getRegionThreads()),
                Integer.valueOf(Threading.getRegionSizeChunks()), Boolean.valueOf(ThreadingAutopilot.isEnabled()),
                Boolean.valueOf(Threading.isRegionBlockTicksEnabled()),
                Boolean.valueOf(Threading.isParallelBlockUpdatesEnabled()), Boolean.valueOf(LightingExecutor.isEnabled())));
        lines.add("[Pools] " + poolLine("world") + " " + poolLine("region") + " " + poolLine("path") + " "
                + poolLine("chunkio") + " " + poolLine("packetOut") + " " + poolLine("packetIn"));
        lines.add(String.format(Locale.ROOT, "[Net] out=%.0f/s in=%.0f/s fwd=%.0f/s main=%.0f/s serFail=%d inFail=%d",
                Double.valueOf(rate("net.out")), Double.valueOf(rate("net.in")),
                Double.valueOf(rate("inbound.forwarded")), Double.valueOf(rate("inbound.main")),
                Long.valueOf(counter("net.out.failed")), Long.valueOf(counter("net.in.failed"))));
        lines.add(String.format(Locale.ROOT,
                "[Main] tick=%.2f world=%.2f net=%.2f players=%.2f cmd=%.2f tickables=%.2f pregen=%.2f ms",
                Double.valueOf(phaseAvgMs("main.serverTick")), Double.valueOf(phaseAvgMs("main.world")),
                Double.valueOf(phaseAvgMs("main.network")), Double.valueOf(phaseAvgMs("main.players")),
                Double.valueOf(phaseAvgMs("main.commandFunctions")), Double.valueOf(phaseAvgMs("main.tickables")),
                Double.valueOf(phaseAvgMs("main.pregen"))));
        lines.add(String.format(Locale.ROOT, "[Client] frame=%.2f tick=%.2f ms",
                Double.valueOf(phaseAvgMs("client.frame")), Double.valueOf(phaseAvgMs("client.tick"))));
        lines.add(String.format(Locale.ROOT,
                "[World] total=%.2f base=%.2f sched=%.2f random=%.2f chunk=%.2f chunkSrc=%.2f spawner=%.2f map=%.2f ms",
                Double.valueOf(worldTotalMs()), Double.valueOf(phaseWindowLastMs("world.tick")),
                Double.valueOf(phaseWindowLastMs("world.scheduledTicks")),
                Double.valueOf(phaseWindowLastMs("world.randomTicks")), Double.valueOf(phaseWindowLastMs("world.chunkTick")),
                Double.valueOf(phaseWindowLastMs("world.chunkSource")), Double.valueOf(phaseWindowLastMs("world.mobSpawner")),
                Double.valueOf(phaseWindowLastMs("world.chunkMap"))));
        lines.add(String.format(Locale.ROOT,
                "[World] entities=%.2f te=%.2f track=%.2f path=%.2f light*=%.2f hooks=%.2f deferred=%.2f ms",
                Double.valueOf(phaseWindowLastMs("world.entities")), Double.valueOf(phaseWindowLastMs("world.tileEntities")),
                Double.valueOf(phaseWindowLastMs("world.tracking")), Double.valueOf(phaseWindowLastMs("world.pathfinding")),
                Double.valueOf(phaseWindowLastMs("world.lighting")), Double.valueOf(phaseWindowLastMs("world.hooks")),
                Double.valueOf(phaseWindowLastMs("world.deferred"))));
        lines.add(String.format(Locale.ROOT,
                "[Fallback] dispatch=%d q=%d eventInline=%d eventSkip=%d render=%d",
                Long.valueOf(counter("dispatch.fallbacks")), Integer.valueOf(MainThreadDispatcher.getQueueSize()),
                Long.valueOf(counter("event.inline")),
                Long.valueOf(counter("event.skipped")), Long.valueOf(counter("render.fallback"))));
        lines.add(String.format(Locale.ROOT,
                "[Chunks] enqueued=%d immediate=%d uploads=%d skipped=%d",
                Long.valueOf(counter("render.chunk.enqueued")), Long.valueOf(counter("render.chunk.immediate")),
                Long.valueOf(counter("render.chunk.uploads")), Long.valueOf(counter("render.chunk.deferred"))));
        BackgroundPacer.appendOverlayLines(lines);
    }

    public static List<String> getSummaryLines()
    {
        List<String> lines = new ArrayList<String>();
        lines.add(String.format(Locale.ROOT, "ForgeThreading: TPS=%.2f MSPT=%.2f max=%.2f FPS=%.0f",
                Double.valueOf(tps), Double.valueOf(mspt), Double.valueOf(maxMspt), Double.valueOf(fps)));
        lines.add("preset=" + Threading.getPreset() + " singleThreaded=" + Threading.isSingleThreaded()
                + " dimensions=" + Threading.getParallelDimensions() + " regions=" + Threading.getRegionThreads()
                + " regionSize=" + Threading.getRegionSizeChunks() + " autopilot=" + ThreadingAutopilot.isEnabled()
                + " blockTicks=" + Threading.isRegionBlockTicksEnabled()
                + " scheduled=" + Threading.isParallelBlockUpdatesEnabled()
                + " lighting=" + LightingExecutor.isEnabled()
                + " packetOut=" + PacketSerialization.isEnabled()
                + " packetIn=" + InboundPacketPipeline.isEnabled());
        lines.add("pools: " + poolLine("world") + " " + poolLine("region") + " " + poolLine("path") + " "
                + poolLine("chunkio") + " " + poolLine("packetOut") + " " + poolLine("packetIn"));
        lines.add(String.format(Locale.ROOT, "net: out=%.0f/s in=%.0f/s fwd=%.0f/s main=%.0f/s serFail=%d inFail=%d",
                Double.valueOf(rate("net.out")), Double.valueOf(rate("net.in")),
                Double.valueOf(rate("inbound.forwarded")), Double.valueOf(rate("inbound.main")),
                Long.valueOf(counter("net.out.failed")), Long.valueOf(counter("net.in.failed"))));
        lines.add(String.format(Locale.ROOT,
                "main phases ms(l/p): world=%.2f/%.2f net=%.2f/%.2f players=%.2f/%.2f cmd=%.2f/%.2f tickables=%.2f/%.2f pregen=%.2f/%.2f",
                Double.valueOf(phaseLastMs("main.world")), Double.valueOf(phaseAvgMs("main.world")),
                Double.valueOf(phaseLastMs("main.network")), Double.valueOf(phaseAvgMs("main.network")),
                Double.valueOf(phaseLastMs("main.players")), Double.valueOf(phaseAvgMs("main.players")),
                Double.valueOf(phaseLastMs("main.commandFunctions")), Double.valueOf(phaseAvgMs("main.commandFunctions")),
                Double.valueOf(phaseLastMs("main.tickables")), Double.valueOf(phaseAvgMs("main.tickables")),
                Double.valueOf(phaseLastMs("main.pregen")), Double.valueOf(phaseAvgMs("main.pregen"))));
        lines.add(String.format(Locale.ROOT,
                "world(last tick): total=%.2f base=%.2f sched=%.2f random=%.2f chunk=%.2f chunkSrc=%.2f spawner=%.2f map=%.2f ms",
                Double.valueOf(worldTotalMs()), Double.valueOf(phaseWindowLastMs("world.tick")),
                Double.valueOf(phaseWindowLastMs("world.scheduledTicks")),
                Double.valueOf(phaseWindowLastMs("world.randomTicks")), Double.valueOf(phaseWindowLastMs("world.chunkTick")),
                Double.valueOf(phaseWindowLastMs("world.chunkSource")), Double.valueOf(phaseWindowLastMs("world.mobSpawner")),
                Double.valueOf(phaseWindowLastMs("world.chunkMap"))));
        lines.add(String.format(Locale.ROOT,
                "world(last tick): entities=%.2f te=%.2f track=%.2f path=%.2f light*=%.2f hooks=%.2f deferred=%.2f ms",
                Double.valueOf(phaseWindowLastMs("world.entities")), Double.valueOf(phaseWindowLastMs("world.tileEntities")),
                Double.valueOf(phaseWindowLastMs("world.tracking")), Double.valueOf(phaseWindowLastMs("world.pathfinding")),
                Double.valueOf(phaseWindowLastMs("world.lighting")), Double.valueOf(phaseWindowLastMs("world.hooks")),
                Double.valueOf(phaseWindowLastMs("world.deferred"))));
        lines.add("counters: dispatchFallback=" + counter("dispatch.fallbacks")
                + " dispatchQueue=" + MainThreadDispatcher.getQueueSize()
                + " eventInline=" + counter("event.inline")
                + " eventSkip=" + counter("event.skipped")
                + " scheduledTasks=" + counter("region.scheduled.tasks")
                + " lightGlobal=" + counter("light.global")
                + " lightShard=" + counter("light.shard")
                + " autopilotTrial=" + counter("autopilot.trial")
                + " autopilotAccept=" + counter("autopilot.accept")
                + " autopilotReject=" + counter("autopilot.reject"));
        return lines;
    }

    public static List<String> getDetailedLines()
    {
        List<String> lines = new ArrayList<String>(getSummaryLines());
        lines.add("phases:");
        for (Map.Entry<String, Phase> entry : new java.util.TreeMap<String, Phase>(PHASES).entrySet())
        {
            Phase phase = entry.getValue();
            lines.add(String.format(Locale.ROOT, "  %s last=%.3fms avg=%.3fms max=%.3fms wmax=%.3fms count=%d",
                    entry.getKey(), Double.valueOf(phase.last() / 1000000.0D),
                    Double.valueOf(phase.average() / 1000000.0D), Double.valueOf(phase.max() / 1000000.0D),
                    Double.valueOf(phase.windowMax()), Long.valueOf(phase.count())));
        }
        lines.add("counters:");
        for (Map.Entry<String, AtomicLong> entry : new java.util.TreeMap<String, AtomicLong>(COUNTERS).entrySet())
        {
            lines.add("  " + entry.getKey() + "=" + entry.getValue().get());
        }
        lines.add("pools:");
        for (String name : new java.util.TreeSet<String>(POOLS.keySet()))
        {
            PoolSnapshot pool = pool(name);
            lines.add("  " + name + " size=" + pool.size + " active=" + pool.active + " queue=" + pool.queue
                    + " completed=" + pool.completed + " largest=" + pool.largest);
        }
        return lines;
    }

    public static File dumpToFile() throws IOException
    {
        File directory = new File("logs");
        if (!directory.exists() && !directory.mkdirs())
        {
            throw new IOException("Could not create logs directory " + directory.getAbsolutePath());
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File file = new File(directory, "forgethreading-" + timestamp + ".txt");
        Files.write(file.toPath(), getDetailedLines(), StandardCharsets.UTF_8);
        LOGGER.info("[telemetry] dumped threading telemetry to {}", file.getAbsolutePath());
        return file;
    }

    public static void resetCounters()
    {
        COUNTERS.clear();
        PHASES.clear();
        RATES.clear();
    }

    private static final class Phase
    {
        private long last;
        private double average;
        private long max;
        private long count;
        private final AtomicLong windowNanos = new AtomicLong();
        private final AtomicLong windowSamples = new AtomicLong();
        private final AtomicLong windowMaxNanos = new AtomicLong();
        private volatile double windowLastMs;
        private volatile double windowAvgMs;
        private volatile double windowMaxMs;

        private synchronized void record(long nanos)
        {
            this.last = nanos;
            this.average = this.count == 0L ? nanos : (this.average * 7.0D + nanos) / 8.0D;
            this.max = Math.max(this.max, nanos);
            this.count++;
        }

        private void add(long nanos)
        {
            this.windowNanos.addAndGet(nanos);
            this.windowSamples.incrementAndGet();
            long previous = this.windowMaxNanos.get();
            while (nanos > previous && !this.windowMaxNanos.compareAndSet(previous, nanos))
            {
                previous = this.windowMaxNanos.get();
            }
        }

        private synchronized void publishWindow()
        {
            long samples = this.windowSamples.getAndSet(0L);
            long nanos = this.windowNanos.getAndSet(0L);
            long max = this.windowMaxNanos.getAndSet(0L);
            if (samples > 0L)
            {
                double ms = (double)nanos / 1000000.0D;
                this.windowLastMs = ms;
                this.windowMaxMs = (double)max / 1000000.0D;
                this.windowAvgMs = this.windowAvgMs == 0.0D ? ms : (this.windowAvgMs * 7.0D + ms) / 8.0D;
            }
        }

        private double windowMax()
        {
            return this.windowMaxMs;
        }

        private double windowLast()
        {
            return this.windowLastMs;
        }

        private double windowAverage()
        {
            return this.windowAvgMs;
        }

        private synchronized long last()
        {
            return this.last;
        }

        private synchronized double average()
        {
            return this.average;
        }

        private synchronized long max()
        {
            return this.max;
        }

        private synchronized long count()
        {
            return this.count;
        }
    }

    private static final class Rate
    {
        private long lastCount;
        private long lastNanos = System.nanoTime();

        private synchronized double sample(long currentCount)
        {
            long now = System.nanoTime();
            long elapsed = now - this.lastNanos;
            long delta = currentCount - this.lastCount;
            this.lastCount = currentCount;
            this.lastNanos = now;
            if (elapsed <= 0L)
            {
                return 0.0D;
            }
            return (double)delta * 1000000000.0D / (double)elapsed;
        }
    }

    public static final class PoolSnapshot
    {
        public final int size;
        public final int active;
        public final int queue;
        public final long completed;
        public final int largest;

        private PoolSnapshot(int size, int active, int queue, long completed, int largest)
        {
            this.size = size;
            this.active = active;
            this.queue = queue;
            this.completed = completed;
            this.largest = largest;
        }
    }
}
