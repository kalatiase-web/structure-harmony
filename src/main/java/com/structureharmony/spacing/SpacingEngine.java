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
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Conflict resolution by displacement.
 *
 * When a structure would generate too close to a higher priority one, it is MOVED aside rather
 * than dropped. That distinction is the whole design: vanilla gives a structure exactly one
 * candidate chunk per spacing cell, so cancelling that candidate does not push the structure to
 * a nearby spot - the next cell is a whole spacing period away (34 chunks for villages), which
 * means the structure is gone from the entire region. Rejection cannot be tuned into something
 * reasonable; it has to be replaced.
 *
 * A structure is only ever cancelled if the user explicitly turns cancelWhenCannotMove on.
 * Otherwise an unsolvable conflict generates where it stood and is counted as unresolved.
 */
public final class SpacingEngine {

    public enum Outcome { ALLOW, MOVED, UNRESOLVED, CANCELLED }

    public record Decision(Outcome outcome, @Nullable ResourceLocation other,
                           int dx, int dy, int dz, double achieved, double required) {
        public static final Decision ALLOW = new Decision(Outcome.ALLOW, null, 0, 0, 0, 0, 0);
        public boolean moved() { return dx != 0 || dy != 0 || dz != 0; }
    }

    private record Conflict(ResourceLocation id, int x, int z, int required) {}

    private record Neighbour(Holder<Structure> holder, ResourceLocation id, StructurePlacement placement,
                             int radius, int spacing) {}

    private record View(List<Neighbour> neighbours, int rulesVersion) {}

    private static final Map<ChunkGeneratorStructureState, View> VIEWS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile int rulesVersion = 0;

    public static void invalidate() { rulesVersion++; VIEWS.clear(); }

    public static Decision resolve(SHContext.Ctx ctx, Structure structure, StructureStart start, ChunkPos owner) {
        if (!SHConfig.enabled.get()) return Decision.ALLOW;

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
        if (rules.ignored(selfHolder, selfId)) return Decision.ALLOW;

        BoundingBox box = Displacer.boxOf(start);
        if (box == null) return Decision.ALLOW;

        SHStats.attempt(selfId);

        int centreX = (box.minX() + box.maxX()) / 2;
        int centreZ = (box.minZ() + box.maxZ()) / 2;

        List<Conflict> conflicts = findConflicts(ctx, rules, selfHolder, selfId, centreX, centreZ);
        if (conflicts.isEmpty()) {
            SHStats.placed(selfId);
            return Decision.ALLOW;
        }

        Conflict worst = worstOf(conflicts, centreX, centreZ);
        double before = distance(centreX, centreZ, worst.x(), worst.z());

        // Pinned structures hold their ground; the other side of the conflict is what moves.
        if (rules.pinned(selfHolder, selfId)) {
            return finish(selfId, new Decision(Outcome.UNRESOLVED, worst.id(), 0, 0, 0, before, worst.required()));
        }

        int allowed = Math.min(rules.maxShift(selfHolder, selfId), Displacer.headroom(box, owner));
        if (allowed < 8) {
            return finish(selfId, decideUnresolved(selfId, worst, 0, 0, 0, before));
        }

        Decision found = search(ctx, structure, selfHolder, box, owner, conflicts, centreX, centreZ, allowed);
        if (found != null) return finish(selfId, found);

        return finish(selfId, decideUnresolved(selfId, worst, 0, 0, 0, before));
    }

    /**
     * Tries evenly spaced directions, nearest to "straight away from the conflict" first, and
     * returns the smallest move that clears every conflict while staying inside the engine's
     * reference window, keeping the structure's own biome, and not needing an absurd vertical
     * correction. Falls back to the best partial improvement if nothing fully clears.
     */
    @Nullable
    private static Decision search(SHContext.Ctx ctx, Structure structure, @Nullable Holder<Structure> selfHolder,
                                   BoundingBox box, ChunkPos owner, List<Conflict> conflicts,
                                   int centreX, int centreZ, int allowed) {
        double awayX = 0, awayZ = 0;
        for (Conflict c : conflicts) {
            double ox = centreX - c.x();
            double oz = centreZ - c.z();
            double d = Math.sqrt(ox * ox + oz * oz);
            double need = c.required() - d;
            if (need <= 0) continue;
            if (d < 1.0E-4) { ox = 1; oz = 0; d = 1; }  // exactly coincident: pick a fixed axis
            awayX += (ox / d) * need;
            awayZ += (oz / d) * need;
        }
        double baseAngle = (awayX == 0 && awayZ == 0) ? 0 : Math.atan2(awayZ, awayX);

        int dirs = SHConfig.searchDirections.get();
        double step = (2 * Math.PI) / dirs;

        Decision best = null;
        double bestViolation = Double.MAX_VALUE;

        for (int k = 0; k < dirs; k++) {
            // 0, +1, -1, +2, -2 ... so directions closest to "straight away" are tried first.
            int offset = ((k + 1) / 2) * ((k % 2 == 0) ? 1 : -1);
            double angle = baseAngle + offset * step;
            double ux = Math.cos(angle);
            double uz = Math.sin(angle);

            for (int magnitude = 8; magnitude <= allowed; magnitude += 8) {
                int dx = (int) Math.round(ux * magnitude);
                int dz = (int) Math.round(uz * magnitude);
                if (dx == 0 && dz == 0) continue;
                if (!Displacer.fitsReferenceWindow(box, owner, dx, dz)) break;

                int newX = centreX + dx;
                int newZ = centreZ + dz;
                double violation = worstViolation(conflicts, newX, newZ);

                if (violation >= bestViolation && violation > 0) continue;

                if (SHConfig.keepBiome.get() && !biomeAllows(ctx, selfHolder, newX, newZ)) continue;

                int dy = verticalCorrection(ctx, structure, centreX, centreZ, newX, newZ);
                if (dy == Integer.MIN_VALUE) continue;

                Conflict worst = worstOf(conflicts, newX, newZ);
                double achieved = distance(newX, newZ, worst.x(), worst.z());
                Decision candidate = new Decision(
                        violation <= 0 ? Outcome.MOVED : Outcome.UNRESOLVED,
                        worst.id(), dx, dy, dz, achieved, worst.required());

                if (violation <= 0) return candidate;   // smallest fully clearing move wins
                bestViolation = violation;
                best = candidate;
            }
        }
        return best;
    }

    private static Decision decideUnresolved(ResourceLocation selfId, Conflict worst,
                                             int dx, int dy, int dz, double achieved) {
        Outcome outcome = SHConfig.cancelWhenCannotMove.get() ? Outcome.CANCELLED : Outcome.UNRESOLVED;
        return new Decision(outcome, worst.id(), dx, dy, dz, achieved, worst.required());
    }

    private static Decision finish(ResourceLocation selfId, Decision decision) {
        switch (decision.outcome()) {
            case MOVED -> SHStats.moved(selfId);
            case UNRESOLVED -> SHStats.unresolved(selfId);
            case CANCELLED -> SHStats.cancelled(selfId);
            default -> SHStats.placed(selfId);
        }
        if (decision.outcome() != Outcome.ALLOW) {
            SHStats.record(new SHStats.Event(selfId.toString(),
                    decision.other() == null ? "?" : decision.other().toString(),
                    0, 0, decision.dx(), decision.dy(), decision.dz(),
                    decision.achieved(), decision.required(), decision.outcome().name()));
            if (SHConfig.logMoves.get()) {
                StructureHarmony.LOG.info("[StructureHarmony] {} vs {}: {} by ({},{},{}) -> {} blocks apart (wanted {})",
                        selfId, decision.other(), decision.outcome(),
                        decision.dx(), decision.dy(), decision.dz(),
                        String.format("%.0f", decision.achieved()), decision.required());
            }
        }
        return decision;
    }

    private static List<Conflict> findConflicts(SHContext.Ctx ctx, SHRules.Parsed rules,
                                                @Nullable Holder<Structure> selfHolder, ResourceLocation selfId,
                                                int centreX, int centreZ) {
        ChunkGeneratorStructureState state = ctx.state();
        long seed = state.getLevelSeed();
        List<Conflict> conflicts = new ArrayList<>();
        int budget = SHConfig.candidateBudget.get();
        int spent = 0;
        int centreChunkX = centreX >> 4;
        int centreChunkZ = centreZ >> 4;

        for (Neighbour n : view(ctx, rules).neighbours()) {
            if (spent >= budget) break;
            if (n.id().equals(selfId)) continue;   // same structure: vanilla spacing already handles it
            if (n.spacing() < SHConfig.minNeighbourSpacing.get()) continue;
            if (rules.compareOrder(selfHolder, selfId, n.holder(), n.id(), seed) >= 0) continue;

            int required = rules.requiredDistance(selfHolder, selfId, n.holder(), n.id());
            if (required <= 0) continue;
            int scanChunks = Math.min(16, (required >> 4) + 2);

            spent += CandidateScanner.scan(state, seed, n.placement(), centreChunkX, centreChunkZ,
                    scanChunks, budget - spent, (cx, cz) -> {
                        int bx = (cx << 4) + 8;
                        int bz = (cz << 4) + 8;
                        if (distance(centreX, centreZ, bx, bz) >= required) return true;
                        if (SHConfig.checkBiomes.get() && !biomeAllows(ctx, n.holder(), bx, bz)) return true;
                        conflicts.add(new Conflict(n.id(), bx, bz, required));
                        return true;
                    });
        }
        return conflicts;
    }

    /** How far short of its required distance the closest offender still is. <= 0 means clear. */
    private static double worstViolation(List<Conflict> conflicts, int x, int z) {
        double worst = Double.NEGATIVE_INFINITY;
        for (Conflict c : conflicts) {
            worst = Math.max(worst, c.required() - distance(x, z, c.x(), c.z()));
        }
        return worst == Double.NEGATIVE_INFINITY ? 0 : worst;
    }

    private static Conflict worstOf(List<Conflict> conflicts, int x, int z) {
        Conflict worst = conflicts.get(0);
        double worstValue = Double.NEGATIVE_INFINITY;
        for (Conflict c : conflicts) {
            double v = c.required() - distance(x, z, c.x(), c.z());
            if (v > worstValue) { worstValue = v; worst = c; }
        }
        return worst;
    }

    private static double distance(int ax, int az, int bx, int bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Vertical correction so a structure moved sideways still meets the ground.
     * Returns Integer.MIN_VALUE when the height difference is too large to be worth doing,
     * which makes the caller try a different direction.
     */
    private static int verticalCorrection(SHContext.Ctx ctx, Structure structure,
                                          int oldX, int oldZ, int newX, int newZ) {
        if (!SHConfig.followTerrainHeight.get()) return 0;
        if (structure.step() != GenerationStep.Decoration.SURFACE_STRUCTURES) return 0;
        try {
            int oldGround = ctx.generator().getBaseHeight(oldX, oldZ, Heightmap.Types.WORLD_SURFACE_WG,
                    ctx.heightAccessor(), ctx.state().randomState());
            int newGround = ctx.generator().getBaseHeight(newX, newZ, Heightmap.Types.WORLD_SURFACE_WG,
                    ctx.heightAccessor(), ctx.state().randomState());
            int dy = newGround - oldGround;
            if (Math.abs(dy) > SHConfig.maxVerticalShift.get()) return Integer.MIN_VALUE;
            return dy;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Approximate: does this structure's biome list allow the biome at this spot? */
    private static boolean biomeAllows(SHContext.Ctx ctx, @Nullable Holder<Structure> structure, int x, int z) {
        if (structure == null) return true;
        try {
            Climate.Sampler sampler = ctx.state().randomState().sampler();
            Holder<Biome> biome = ctx.biomeSource().getNoiseBiome(
                    QuartPos.fromBlock(x), QuartPos.fromBlock(ctx.generator().getSeaLevel()),
                    QuartPos.fromBlock(z), sampler);
            return structure.value().biomes().contains(biome);
        } catch (Throwable t) {
            return true;   // a failed guess must never change the outcome
        }
    }

    private static View view(SHContext.Ctx ctx, SHRules.Parsed rules) {
        ChunkGeneratorStructureState state = ctx.state();
        View cached = VIEWS.get(state);
        if (cached != null && cached.rulesVersion() == rulesVersion) return cached;

        List<Neighbour> list = new ArrayList<>();
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
                if (rules.ignored(holder, id)) continue;
                list.add(new Neighbour(holder, id, placement, rules.radius(holder, id), spacing));
            }
        }

        long seed = state.getLevelSeed();
        list.sort((a, b) -> rules.compareOrder(b.holder(), b.id(), a.holder(), a.id(), seed));

        View built = new View(List.copyOf(list), rulesVersion);
        VIEWS.put(state, built);
        return built;
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
