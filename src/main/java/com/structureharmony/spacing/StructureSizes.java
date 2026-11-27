package com.structureharmony.spacing;

import com.structureharmony.SHConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Observed footprint sizes, learned from the structures that actually get generated.
 *
 * Needed because a structure's real extent cannot be known before it is built, so a conflict
 * check cannot ask "how big is that neighbour going to be". The previous version used a
 * hand-written radius list instead, which meant any structure not on the list - every modded
 * one - was treated as a point. A 300 block structure then only reserved the global minimum
 * around its centre, and smaller structures were happily placed inside it.
 *
 * The cache records the largest half-span seen per structure id, so it only ever grows more
 * conservative. It warms up over the first few hundred chunks of a new world and is held per
 * session; until a structure has been seen once, assumedHalfSpan stands in for it.
 */
public final class StructureSizes {

    private static final Map<String, Integer> HALF_SPANS = new ConcurrentHashMap<>();

    public static int halfSpanOf(BoundingBox box) {
        return (Math.max(box.getXSpan(), box.getZSpan()) + 1) / 2;
    }

    public static void observe(ResourceLocation id, BoundingBox box) {
        HALF_SPANS.merge(id.toString(), halfSpanOf(box), Math::max);
    }

    /** Largest half-span seen for this structure, or the configured assumption if never seen. */
    public static int halfSpan(ResourceLocation id) {
        Integer seen = HALF_SPANS.get(id.toString());
        return seen != null ? seen : SHConfig.assumedHalfSpan.get();
    }

    public static boolean isKnown(ResourceLocation id) {
        return HALF_SPANS.containsKey(id.toString());
    }

    public static Map<String, Integer> snapshot() { return Map.copyOf(HALF_SPANS); }

    public static void reset() { HALF_SPANS.clear(); }

    private StructureSizes() {}
}
