package com.structureharmony.spacing;

import com.structureharmony.SHConfig;
import com.structureharmony.SHContext;
import com.structureharmony.SHRules;
import com.structureharmony.SHStats;
import com.structureharmony.StructureHarmony;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The overlap check.
 *
 * Called from StructureManager#setStartForStructure, i.e. after a structure start has been
 * built but before it is recorded. Returning a rejection drops that ONE candidate position.
 * The structure keeps every other cell of its own placement grid, which is why this does not
 * "turn structures off" the way a blanket spacing bump does.
 */
public final class SpacingEngine {

    public record Decision(boolean reject, @Nullable ResourceLocation other, double distance, double required) {
        public static final Decision ALLOW = new Decision(false, null, 0, 0);
    }

    /** Cached per-generator view of every structure that could act as a neighbour. */
    private record Neighbour(Holder<Structure> holder, ResourceLocation id, StructurePlacement placement,
                             int radius, int spacing) {}

    private record View(List<Neighbour> neighbours, int maxRadius, int rulesVersion) {}

    private static final Map<ChunkGeneratorStructureState, View> VIEWS = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile int rulesVersion = 0;

    public static void invalidate() { rulesVersion++; VIEWS.clear(); }

    public static Decision check(SHContext.Ctx ctx, Structure structure, ChunkPos pos, @Nullable BoundingBox box) {
        if (!SHConfig.spacingEnabled.get()) return Decision.ALLOW;

        Registry<Structure> registry;
        try {
            registry = ctx.registries().registryOrThrow(Registries.STRUCTURE);
        } catch (Throwable t) {
            return Decision.ALLOW;
        }
        ResourceLocation selfId = registry.getKey(structure);
        if (selfId == null) return Decision.ALLOW;
        Holder<Structure> selfHolder = holderOf(registry, structure);

        SHRules.Parsed rules = SHRules.get();
        if (rules.spacingIgnored(selfHolder, selfId) || rules.neverReject(selfHolder)) return Decision.ALLOW;

        SHStats.attempt(selfId);
        if (SHStats.shouldFailOpen(selfId)) {
            SHStats.failOpen(selfId);
            return Decision.ALLOW;
        }

        ChunkGeneratorStructureState state = ctx.state();
        long seed = state.getLevelSeed();
        View view = view(ctx, rules);
        if (view.neighbours().isEmpty()) return Decision.ALLOW;

        int selfRadius = Math.max(rules.radius(selfHolder, selfId), boxRadiusInChunks(box));
        int buffer = SHConfig.extraBuffer.get();
        int cap = SHConfig.maxScanRadius.get();
        int budget = SHConfig.candidateBudget.get();
        int spent = 0;

        Decision[] result = { Decision.ALLOW };

        for (Neighbour n : view.neighbours()) {
            if (spent >= budget) break;
            if (n.id().equals(selfId)) continue; // same structure: vanilla spacing already handles it
            if (n.spacing() < SHConfig.minNeighbourSpacing.get()) continue;

            // Only ever yield to something that outranks us. The order is total and antisymmetric,
            // so two structures can never cancel each other out.
            if (rules.compareOrder(selfHolder, selfId, n.holder(), n.id(), seed) >= 0) continue;

            int required = rules.pairDistance(selfHolder, selfId, n.holder(), n.id());
            if (required < 0) required = selfRadius + n.radius() + buffer;
            if (required <= 0) continue;
            int scanRadius = Math.min(required, cap);

            final int req = required;
            spent += CandidateScanner.scan(state, seed, n.placement(), pos.x, pos.z, scanRadius,
                    budget - spent, (cx, cz) -> {
                        double dx = cx - pos.x;
                        double dz = cz - pos.z;
                        double dist = Math.sqrt(dx * dx + dz * dz);
                        if (dist >= req) return true;
                        if (SHConfig.checkBiomes.get() && !biomePlausible(ctx, n.holder(), cx, cz)) return true;
                        result[0] = new Decision(true, n.id(), dist, req);
                        return false;
                    });

            if (result[0].reject()) break;
        }

        if (result[0].reject()) {
            SHStats.rejected(selfId, result[0].other(), pos.x, pos.z, result[0].distance(), result[0].required());
            if (SHConfig.logRejections.get()) {
                StructureHarmony.LOG.info("[StructureHarmony] skipped {} at chunk {},{} - {} is {} chunks away (needs {})",
                        selfId, pos.x, pos.z, result[0].other(),
                        String.format("%.1f", result[0].distance()), result[0].required());
            }
        } else {
            SHStats.placed(selfId);
        }
        return result[0];
    }

    /**
     * Cheap approximation of "could that structure really generate there": does its biome list
     * contain the biome at that chunk? Without this you lose structures to phantom neighbours
     * that would never have passed their own biome check - the exact failure mode that makes
     * other spacing mods eat your structures.
     */
    private static boolean biomePlausible(SHContext.Ctx ctx, Holder<Structure> other, int chunkX, int chunkZ) {
        try {
            BiomeSource source = ctx.biomeSource();
            Climate.Sampler sampler = ctx.state().randomState().sampler();
            int qx = QuartPos.fromBlock((chunkX << 4) + 8);
            int qz = QuartPos.fromBlock((chunkZ << 4) + 8);
            int qy = QuartPos.fromBlock(ctx.generator().getSeaLevel());
            Holder<Biome> biome = source.getNoiseBiome(qx, qy, qz, sampler);
            return other.value().biomes().contains(biome);
        } catch (Throwable t) {
            return true; // never let a failed guess cause a rejection
        }
    }

    private static View view(SHContext.Ctx ctx, SHRules.Parsed rules) {
        ChunkGeneratorStructureState state = ctx.state();
        View cached = VIEWS.get(state);
        if (cached != null && cached.rulesVersion() == rulesVersion) return cached;

        List<Neighbour> list = new ArrayList<>();
        int maxRadius = 0;
        Registry<Structure> structures = ctx.registries().registryOrThrow(Registries.STRUCTURE);

        List<Holder<StructureSet>> sets;
        try {
            sets = state.possibleStructureSets();
        } catch (Throwable t) {
            sets = List.of();
        }

        for (Holder<StructureSet> setHolder : sets) {
            StructureSet set = setHolder.value();
            StructurePlacement placement = set.placement();
            int spacing = CandidateScanner.spacingOf(placement);
            for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                Holder<Structure> holder = entry.structure();
                ResourceLocation id = structures.getKey(holder.value());
                if (id == null) continue;
                if (rules.spacingIgnored(holder, id)) continue;
                int radius = rules.radius(holder, id);
                maxRadius = Math.max(maxRadius, radius);
                list.add(new Neighbour(holder, id, placement, radius, spacing));
            }
        }

        // Highest priority first: the common case is yielding to something big and important,
        // and finding it early lets us stop scanning sooner.
        long seed = state.getLevelSeed();
        list.sort((a, b) -> rules.compareOrder(b.holder(), b.id(), a.holder(), a.id(), seed));

        View built = new View(List.copyOf(list), maxRadius, rulesVersion);
        VIEWS.put(state, built);
        return built;
    }

    private static int boxRadiusInChunks(@Nullable BoundingBox box) {
        if (box == null) return 0;
        int span = Math.max(box.getXSpan(), box.getZSpan());
        return (int) Math.ceil(span / 2.0D / 16.0D);
    }

    @Nullable
    private static Holder<Structure> holderOf(Registry<Structure> registry, Structure structure) {
        try {
            return registry.wrapAsHolder(structure);
        } catch (Throwable t) {
            return null;
        }
    }

    private SpacingEngine() {}
}
