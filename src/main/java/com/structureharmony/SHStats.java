package com.structureharmony;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counters so "is it working" and "did it eat my structures" are answerable in game.
 * placed + moved + unresolved should equal attempts; cancelled should stay at zero unless
 * cancelWhenCannotMove was turned on.
 */
public final class SHStats {

    public record Entry(AtomicInteger attempts, AtomicInteger placed, AtomicInteger moved,
                        AtomicInteger fitted, AtomicInteger unresolved, AtomicInteger cancelled) {
        public Entry() {
            this(new AtomicInteger(), new AtomicInteger(), new AtomicInteger(),
                 new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        }
    }

    public record Event(String structure, String other, int fromX, int fromZ,
                        int dx, int dy, int dz, double gap, double targetGap, String outcome) {}

    private static volatile boolean hookLive = false;

    public static void markHookLive() { hookLive = true; }
    public static boolean isHookLive() { return hookLive; }

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Deque<Event> HISTORY = new ArrayDeque<>();

    private static Entry entry(ResourceLocation id) {
        return ENTRIES.computeIfAbsent(id.toString(), k -> new Entry());
    }

    public static void attempt(ResourceLocation id) { entry(id).attempts().incrementAndGet(); }
    public static void placed(ResourceLocation id) { entry(id).placed().incrementAndGet(); }

    public static void moved(ResourceLocation id) { entry(id).moved().incrementAndGet(); }
    public static void fitted(ResourceLocation id) { entry(id).fitted().incrementAndGet(); }
    public static void unresolved(ResourceLocation id) { entry(id).unresolved().incrementAndGet(); }
    public static void cancelled(ResourceLocation id) { entry(id).cancelled().incrementAndGet(); }

    public static void record(Event event) {
        int cap = SHConfig.historySize.get();
        if (cap <= 0) return;
        synchronized (HISTORY) {
            HISTORY.addLast(event);
            while (HISTORY.size() > cap) HISTORY.removeFirst();
        }
    }

    public static Map<String, Entry> snapshot() { return Map.copyOf(ENTRIES); }

    public static List<Event> history() {
        synchronized (HISTORY) { return List.copyOf(HISTORY); }
    }

    public static void reset() {
        ENTRIES.clear();
        hookLive = false;
        synchronized (HISTORY) { HISTORY.clear(); }
    }

    private SHStats() {}
}
