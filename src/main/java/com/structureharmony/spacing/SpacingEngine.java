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
 * Decides where a structure actually ends up.
 *
 * One scored search answers both questions at once - "is it too close to something" and "does it
 * fit the ground it is standing on" - because they compete: the flattest spot may be the one
 * nearest a village. Candidates include staying put, so a structure is only moved when moving
 * genuinely scores better than not moving.
 *
 * Conflicts are resolved by moving, never by deleting. Vanilla gives a structure one candidate
 * chunk per spacing cell, so cancelling a placement removes it from the whole region rather than
 * nudging it aside.
 *
 * EVERYTHING HERE DEPENDS ON ChunkGenerator#getBaseHeight, the engine's prediction of ground
 * height made before any terrain exists. A terrain mod whose final ground does not match that
 * prediction defeats these checks however carefully they are written, because the structure's
 * position has to be chosen before the ground it stands on has been built.
 */
public final class SpacingEngine {

    public enum Outcome { ALLOW, MOVED, FITTED, UNRESOLVED, CANCELLED }

    /** gap / targetGap are EDGE to edge, which is the number that actually describes an overlap. */
    public record Decision(Outcome outcome, @Nullable ResourceLocation other,
                           int dx, int dy, int dz, double gap, double targetGap) {
        public static final Decision ALLOW = new Decision(Outcome.ALLOW, null, 0, 0, 0, 0, 0);
        public boolean moved() { return dx != 0 || dy != 0 || dz != 0; }
    }

    private record Conflict(ResourceLocation id, int x, int z, int required, int halfSpan) {}

    private record Neighbour(Holder<Structure> holder, ResourceLocation id, StructurePlacement placement,
                             int radius, int spacing) {}

    private record View(List<Neighbour> neighbours, int rulesVersion) {}

    /** A destination under consideration. Lower score is better; staying put is one of these. */
    private record Candidate(int dx, int dy, int dz, double score, double violation, double gap, double targetGap,
                             @Nullable ResourceLocation other, boolean fitProblem) {}

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
        BoundingBox box = Displacer.boxOf(start);
        if (box == null) return Decision.ALLOW;

        // Learn the real footprint before anything else, so the size cache fills in even for
        // structures that take no part in spacing themselves.
        StructureSizes.observe(selfId, box);
        if (rules.ignored(selfHolder, selfId)) return Decision.ALLOW;

        SHStats.attempt(selfId);

        int selfHalfSpan = StructureSizes.halfSpanOf(box);
        int centreX = (box.minX() + box.maxX()) / 2;
        int centreZ = (box.minZ() + box.maxZ()) / 2;

        boolean surfaceStep = structure.step() == GenerationStep.Decoration.SURFACE_STRUCTURES;
        SiteCheck.Site origin = surfaceStep ? SiteCheck.sample(ctx, box, 0, 0) : null;

        boolean floating = StructureTraits.isFloating(selfHolder, selfId, rules, box.minY(), origin);
        boolean aquatic = StructureTraits.isAquatic(selfHolder, selfId, rules);
        boolean fit = SHConfig.fitCorrection.get() && surfaceStep && origin != null
                && !floating && !rules.noFit(selfHolder, selfId);

        List<Conflict> conflicts = findConflicts(ctx, rules, selfHolder, selfId, selfHalfSpan, centreX, centreZ);

        boolean strandedInWater = fit && SHConfig.keepLandStructuresOutOfWater.get() && !aquatic && origin.water();
        boolean onBadGround = fit && origin.relief() > SHConfig.maxRelief.get();

        if (conflicts.isEmpty() && !strandedInWater && !onBadGround) {
            // Nothing wrong horizontally. A lift may still be wanted so terrain does not cut in.
            int dy = fit ? verticalFor(box, origin, origin, true) : 0;
            if (dy != 0) {
                SHStats.fitted(selfId);
                return new Decision(Outcome.FITTED, null, 0, dy, 0, 0, 0);
            }
            SHStats.placed(selfId);
            return Decision.ALLOW;
        }

        Candidate best = search(ctx, structure, selfHolder, box, owner, conflicts, origin,
                selfHalfSpan, centreX, centreZ,
                Math.min(rules.maxShift(selfHolder, selfId), Displacer.SAFE_CHUNK_RADIUS * 16),
                fit, aquatic);

        return finish(selfId, toDecision(best, conflicts.isEmpty()));
    }

    private static Decision toDecision(@Nullable Candidate best, boolean fitOnly) {
        if (best == null) {
            Outcome outcome = SHConfig.cancelWhenCannotMove.get() ? Outcome.CANCELLED : Outcome.UNRESOLVED;
            return new Decision(outcome, null, 0, 0, 0, 0, 0);
        }
        Outcome outcome;
        if (best.violation() > 0) {
            outcome = SHConfig.cancelWhenCannotMove.get() ? Outcome.CANCELLED : Outcome.UNRESOLVED;
        } else if (fitOnly) {
            // No conflict was involved, so this is a fit adjustment whether or not the ground
            // turned out to be perfect. UNRESOLVED is reserved for separation that fell short.
            outcome = best.moved() ? Outcome.FITTED : Outcome.ALLOW;
        } else {
            outcome = Outcome.MOVED;
        }
        return new Decision(outcome, best.other(), best.dx(), best.dy(), best.dz(),
                best.gap(), best.targetGap());
    }

    /**
     * Scored search over staying put plus evenly spaced destinations, nearest to "straight away
     * from the conflict" first. One score balances separation, water, ground evenness, how far the
     * structure has to be lifted and how far it travels, so the three goals trade off against each
     * other instead of one silently overriding the rest.
     */
    @Nullable
    private static Candidate search(SHContext.Ctx ctx, Structure structure, @Nullable Holder<Structure> selfHolder,
                                    BoundingBox box, ChunkPos owner, List<Conflict> conflicts,
                                    @Nullable SiteCheck.Site origin, int selfHalfSpan,
                                    int centreX, int centreZ, int allowed, boolean fit, boolean aquatic) {

        Candidate best = evaluate(ctx, box, conflicts, origin, origin, 0, 0, selfHalfSpan,
                centreX, centreZ, fit, aquatic);

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
        int siteBudget = SHConfig.siteCheckBudget.get();

        for (int k = 0; k < dirs && siteBudget > 0; k++) {
            // 0, +1, -1, +2, -2 ... so directions closest to "straight away" are tried first.
            int offset = ((k + 1) / 2) * ((k % 2 == 0) ? 1 : -1);
            double angle = baseAngle + offset * step;
            double ux = Math.cos(angle);
            double uz = Math.sin(angle);

            for (int magnitude = 16; magnitude <= allowed && siteBudget > 0; magnitude += 16) {
                int dx = (int) Math.round(ux * magnitude);
                int dz = (int) Math.round(uz * magnitude);
                if (dx == 0 && dz == 0) continue;
                if (!Displacer.fitsReferenceWindow(box, owner, dx, dz)) break;
                if (SHConfig.keepBiome.get() && !biomeAllows(ctx, selfHolder, centreX + dx, centreZ + dz)) continue;

                SiteCheck.Site site = origin;
                if (fit || SHConfig.followTerrainHeight.get()) {
                    siteBudget--;
                    site = SiteCheck.sample(ctx, box, dx, dz);
                    if (site == null) continue;
                }

                Candidate candidate = evaluate(ctx, box, conflicts, origin, site, dx, dz, selfHalfSpan,
                        centreX, centreZ, fit, aquatic);
                if (candidate == null) continue;
                if (best == null || candidate.score() < best.score()) best = candidate;
            }
        }
        return best;
    }

    @Nullable
    private static Candidate evaluate(SHContext.Ctx ctx, BoundingBox box, List<Conflict> conflicts,
                                      @Nullable SiteCheck.Site origin, @Nullable SiteCheck.Site site,
                                      int dx, int dz, int selfHalfSpan, int centreX, int centreZ,
                                      boolean fit, boolean aquatic) {
        int newX = centreX + dx;
        int newZ = centreZ + dz;

        int dy = 0;
        int excessLift = 0;
        if (site != null && origin != null) {
            int follow = followShift(origin, site);
            int wanted = fit ? liftNeeded(box, site, follow) : 0;
            int lift = Math.min(SHConfig.maxLift.get(), wanted);
            excessLift = wanted - lift;
            dy = follow + lift;
            int cap = fit ? SHConfig.maxFitVerticalShift.get() : SHConfig.maxVerticalShift.get();
            if (Math.abs(dy) > cap) return null;
        }

        double violation = worstViolation(conflicts, newX, newZ);
        Conflict worst = conflicts.isEmpty() ? null : worstOf(conflicts, newX, newZ);

        double score = Math.max(0, violation) * 1000.0;
        boolean fitProblem = false;

        if (fit && site != null) {
            if (!aquatic && site.water()) {
                score += 1_000_000.0;             // a land structure in the sea is the worst outcome
                fitProblem = true;
            }
            int over = site.relief() - SHConfig.maxRelief.get();
            if (over > 0) { score += over * 200.0; fitProblem = true; }
            // Needing more lift than we are willing to apply means this site does not fit; say so
            // rather than leaving the structure part-buried and calling it done.
            if (excessLift > 0) { score += excessLift * 200.0; fitProblem = true; }
            score += site.relief() * 2.0;          // mild preference for flatter ground generally
            score += Math.abs(dy) * 2.0;
            if (SHConfig.preferHigherGround.get() && origin != null) {
                // Bounded, so "on top of the slope" is a tiebreak and never the dominant term.
                score -= Math.min(48, Math.max(0, site.ground() - origin.ground())) * 1.0;
            }
        } else {
            score += Math.abs(dy) * 1.0;
        }
        score += Math.sqrt((double) dx * dx + (double) dz * dz) * 0.05;

        double gap = worst == null ? 0
                : distance(newX, newZ, worst.x(), worst.z()) - selfHalfSpan - worst.halfSpan();
        double targetGap = worst == null ? 0 : targetGap(worst, selfHalfSpan);

        return new Candidate(dx, dy, dz, score, violation, gap, targetGap,
                worst == null ? null : worst.id(), fitProblem);
    }

    /** Vertical shift from following the ground height between the two sites. */
    private static int followShift(SiteCheck.Site origin, SiteCheck.Site site) {
        return SHConfig.followTerrainHeight.get() ? site.ground() - origin.ground() : 0;
    }

    /**
     * How much lift this site would need for the structure's base to clear the highest ground
     * under it. Returned raw, un-capped, so the caller can treat "needs a lot" as evidence that
     * the site is wrong rather than quietly building a pedestal.
     */
    private static int liftNeeded(BoundingBox box, SiteCheck.Site site, int followShift) {
        if (!SHConfig.liftOutOfTerrain.get()) return 0;
        return Math.max(0, (site.highest() + 1) - (box.minY() + followShift));
    }

    private static int verticalFor(BoundingBox box, SiteCheck.Site origin, SiteCheck.Site site, boolean fit) {
        int follow = followShift(origin, site);
        if (!fit) return follow;
        return follow + Math.min(SHConfig.maxLift.get(), liftNeeded(box, site, follow));
    }

    private static Decision finish(ResourceLocation selfId, Decision decision) {
        switch (decision.outcome()) {
            case MOVED -> SHStats.moved(selfId);
            case FITTED -> SHStats.fitted(selfId);
            case UNRESOLVED -> SHStats.unresolved(selfId);
            case CANCELLED -> SHStats.cancelled(selfId);
            default -> SHStats.placed(selfId);
        }
        if (decision.outcome() != Outcome.ALLOW) {
            SHStats.record(new SHStats.Event(selfId.toString(),
                    decision.other() == null ? "-" : decision.other().toString(),
                    0, 0, decision.dx(), decision.dy(), decision.dz(),
                    decision.gap(), decision.targetGap(), decision.outcome().name()));
            if (SHConfig.logMoves.get()) {
                if (decision.other() == null) {
                    StructureHarmony.LOG.info("[StructureHarmony] {}: {} to fit the ground, shifted ({},{},{})",
                            selfId, decision.outcome(), decision.dx(), decision.dy(), decision.dz());
                } else {
                    StructureHarmony.LOG.info("[StructureHarmony] {} vs {}: {} by ({},{},{}) -> {} block gap (wanted {})",
                            selfId, decision.other(), decision.outcome(),
                            decision.dx(), decision.dy(), decision.dz(),
                            String.format("%.0f", decision.gap()), String.format("%.0f", decision.targetGap()));
                }
            }
        }
        return decision;
    }

    private static List<Conflict> findConflicts(SHContext.Ctx ctx, SHRules.Parsed rules,
                                                @Nullable Holder<Structure> selfHolder, ResourceLocation selfId,
                                                int selfHalfSpan, int centreX, int centreZ) {
        ChunkGeneratorStructureState state = ctx.state();
        long seed = state.getLevelSeed();
        List<Conflict> conflicts = new ArrayList<>();
        int budget = SHConfig.candidateBudget.get();
        int spent = 0;
        int centreChunkX = centreX >> 4;
        int centreChunkZ = centreZ >> 4;

        if (rules.pinned(selfHolder, selfId)) return conflicts;

        for (Neighbour n : view(ctx, rules).neighbours()) {
            if (spent >= budget) break;
            if (n.id().equals(selfId)) continue;   // same structure: vanilla spacing already handles it
            if (n.spacing() < SHConfig.minNeighbourSpacing.get()) continue;
            if (rules.compareOrder(selfHolder, selfId, n.holder(), n.id(), seed) >= 0) continue;

            final int neighbourHalf = StructureSizes.halfSpan(n.id());
            int required = rules.requiredDistance(selfHolder, selfId, n.holder(), n.id(),
                    selfHalfSpan, neighbourHalf);
            if (required <= 0) continue;
            int scanChunks = Math.min(16, (required >> 4) + 2);

            spent += CandidateScanner.scan(state, seed, n.placement(), centreChunkX, centreChunkZ,
                    scanChunks, budget - spent, (cx, cz) -> {
                        int bx = (cx << 4) + 8;
                        int bz = (cz << 4) + 8;
                        if (distance(centreX, centreZ, bx, bz) >= required) return true;
                        if (SHConfig.checkBiomes.get() && !biomeAllows(ctx, n.holder(), bx, bz)) return true;
                        conflicts.add(new Conflict(n.id(), bx, bz, required, neighbourHalf));
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

    private static double targetGap(Conflict conflict, int selfHalfSpan) {
        return Math.max(0, conflict.required() - selfHalfSpan - conflict.halfSpan());
    }

    private static double distance(int ax, int az, int bx, int bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dz * dz);
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
