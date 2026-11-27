package com.structureharmony;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counters so you can prove the mod is working and that nothing got starved.
 * Also powers the fail-open safety valve: a structure that has already lost too many
 * of its candidate positions stops being rejected.
 */
public final class SHStats {

    public record Entry(AtomicInteger attempts, AtomicInteger placed, AtomicInteger rejected, AtomicInteger failOpen) {
        public Entry() { this(new AtomicInteger(), new AtomicInteger(), new AtomicInteger(), new AtomicInteger()); }
    }

    public record Rejection(String structure, String yieldedTo, int chunkX, int chunkZ, double distance, double required) {}

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Deque<Rejection> HISTORY = new ArrayDeque<>();

    private static Entry entry(ResourceLocation id) {
        return ENTRIES.computeIfAbsent(id.toString(), k -> new Entry());
    }

    public static void attempt(ResourceLocation id) { entry(id).attempts().incrementAndGet(); }
    public static void placed(ResourceLocation id) { entry(id).placed().incrementAndGet(); }
    public static void failOpen(ResourceLocation id) { entry(id).failOpen().incrementAndGet(); }

    public static void rejected(ResourceLocation id, ResourceLocation other, int cx, int cz, double dist, double required) {
        entry(id).rejected().incrementAndGet();
        int cap = SHConfig.historySize.get();
        if (cap <= 0) return;
        Rejection r = new Rejection(id.toString(), other.toString(), cx, cz, dist, required);
        synchronized (HISTORY) {
            HISTORY.addLast(r);
            while (HISTORY.size() > cap) HISTORY.removeFirst();
        }
    }

    /** True when this structure has lost too much already and should be left alone. */
    public static boolean shouldFailOpen(ResourceLocation id) {
        double ratio = SHConfig.failOpenRatio.get();
        if (ratio >= 1.0D) return false;
        Entry e = ENTRIES.get(id.toString());
        if (e == null) return false;
        int attempts = e.attempts().get();
        if (attempts < SHConfig.failOpenMinSamples.get()) return false;
        return (double) e.rejected().get() / attempts > ratio;
    }

    public static Map<String, Entry> snapshot() { return Map.copyOf(ENTRIES); }

    public static List<Rejection> history() {
        synchronized (HISTORY) { return List.copyOf(HISTORY); }
    }

    public static void reset() {
        ENTRIES.clear();
        synchronized (HISTORY) { HISTORY.clear(); }
    }

    private SHStats() {}
}
