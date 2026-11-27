package com.structureharmony.blend;

import com.structureharmony.SHConfig;
import com.structureharmony.SHRules;
import com.structureharmony.SHTags;
import com.structureharmony.StructureHarmony;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Terrain blending.
 *
 * Runs at the head of ChunkGenerator#applyBiomeDecoration, i.e. for the chunk currently being
 * decorated and BEFORE any structure piece or feature is placed in it. Every write is clamped
 * to that one chunk, so there is no cross-chunk write and no "setBlock in a far chunk" spam -
 * a structure that spans nine chunks simply gets blended nine times, one slice each.
 *
 * Only the ground is touched. No support pillars, no walls extended downward: the hillside is
 * cut back and the dip is filled in, then feathered out to natural height over `margin` blocks
 * with a smoothstep curve so there is no visible step or plateau edge.
 */
public final class TerrainSmoother {

    private record Job(ResourceLocation id, BoundingBox box, int margin, int maxRaise, int maxLower, int anchor) {}

    public static void blendChunk(ChunkGenerator generator, WorldGenLevel level, ChunkAccess chunk,
                                  StructureManager structureManager) {
        if (!SHConfig.blendEnabled.get()) return;

        SHRules.Parsed rules = SHRules.get();
        List<Job> jobs = collectJobs(level, chunk, structureManager, rules);
        if (jobs.isEmpty()) return;

        List<BoundingBox> foreign = SHConfig.respectOtherStructures.get()
                ? collectForeignBoxes(level, chunk, structureManager, jobs) : List.of();

        ChunkPos pos = chunk.getPos();
        int chunkMinX = pos.getMinBlockX();
        int chunkMinZ = pos.getMinBlockZ();
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight();
        int seaLevel = generator.getSeaLevel();
        int changed = 0;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = chunkMinX + lx;
                int z = chunkMinZ + lz;

                // Pick the structure this column belongs to: the nearest footprint wins, so two
                // neighbouring structures each get their own ramp instead of fighting.
                Job best = null;
                double bestDist = Double.MAX_VALUE;
                for (Job job : jobs) {
                    double d = distanceToFootprint(x, z, job.box());
                    if (d > job.margin()) continue;
                    if (d < bestDist || (d == bestDist && best != null && job.id().compareTo(best.id()) < 0)) {
                        bestDist = d;
                        best = job;
                    }
                }
                if (best == null) continue;
                if (isInsideAny(foreign, x, z)) continue;

                if (applyColumn(chunk, cursor, x, z, best, bestDist, minY, maxY, seaLevel)) changed++;
            }
        }

        if (changed > 0 && SHConfig.logBlending.get()) {
            StructureHarmony.LOG.info("[StructureHarmony] blended {} columns in chunk {},{} for {}",
                    changed, pos.x, pos.z, jobs.get(0).id());
        }
    }

    private static boolean applyColumn(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, int x, int z,
                                       Job job, double distance, int minY, int maxY, int seaLevel) {
        ColumnSampler.Column column = ColumnSampler.sample(chunk, x, z, minY);
        if (column == null) return false;

        boolean inside = distance <= 0.0D;
        if (inside && !SHConfig.flattenInside.get()) return false;

        SHConfig.WaterMode water = SHConfig.waterMode.get();
        if (column.watery() && water == SHConfig.WaterMode.SKIP_WATER_COLUMNS) return false;
        boolean keepWater = column.watery() && water != SHConfig.WaterMode.TREAT_AS_LAND;

        int anchor = job.box().minY() + job.anchor();
        int surface = column.groundY();

        double w = job.margin() <= 0 ? (inside ? 0.0D : 1.0D)
                                     : smoothstep(Math.min(1.0D, distance / job.margin()));
        int target = (int) Math.round(anchor * (1.0D - w) + surface * w);

        if (!inside) {
            target = Math.max(surface - job.maxLower(), Math.min(surface + job.maxRaise(), target));
        }
        if (keepWater && water == SHConfig.WaterMode.FILL_TO_SEA_LEVEL) {
            target = Math.min(target, seaLevel - 1);
        }
        target = Math.max(minY + 1, Math.min(maxY - 2, target));
        if (target == surface) {
            return clearOvergrowth(chunk, cursor, x, z, target, column.topY());
        }

        boolean touched = false;

        if (target > surface) {
            // Fill the dip up to the ramp height, then cap it with the local surface block.
            clearOvergrowth(chunk, cursor, x, z, surface, column.topY());
            for (int y = surface + 1; y < target; y++) {
                if (!setIfAllowed(chunk, cursor, x, y, z, column.fill())) break;
                touched = true;
            }
            touched |= setIfAllowed(chunk, cursor, x, target, z, column.top());
        } else {
            // Cut the hillside back. Inside the footprint we also take anything hanging over
            // the build, which is what stops structures being swallowed by a mountainside.
            int ceiling = column.topY();
            if (inside && SHConfig.clearInsideFully.get()) {
                ceiling = Math.max(ceiling, Math.min(maxY - 1, job.box().maxY() + SHConfig.clearAbove.get()));
            }
            for (int y = ceiling; y > target; y--) {
                BlockState state = chunk.getBlockState(cursor.set(x, y, z));
                if (state.isAir()) continue;
                if (!ColumnSampler.isRemovable(state)) break; // never chew through a build
                chunk.setBlockState(cursor, ColumnSampler.airOrWater(y, seaLevel, keepWater), false);
                touched = true;
            }
            touched |= setIfAllowed(chunk, cursor, x, target, z, column.top());
        }

        if (SHConfig.fillCavesUnder.get()) {
            int depth = SHConfig.caveFillDepth.get();
            for (int y = target - 1; y >= Math.max(minY + 1, target - depth); y--) {
                BlockState state = chunk.getBlockState(cursor.set(x, y, z));
                if (!state.isAir() && state.getFluidState().isEmpty()) break;
                BlockState filler = (y >= target - 1) ? column.fill() : column.deep();
                if (!setIfAllowed(chunk, cursor, x, y, z, filler)) break;
                touched = true;
            }
        }

        return touched;
    }

    private static boolean clearOvergrowth(ChunkAccess chunk, BlockPos.MutableBlockPos cursor,
                                           int x, int z, int fromY, int topY) {
        boolean touched = false;
        for (int y = fromY + 1; y <= topY; y++) {
            BlockState state = chunk.getBlockState(cursor.set(x, y, z));
            if (state.isAir()) continue;
            if (!state.is(SHTags.OVERGROWTH)) break;
            chunk.setBlockState(cursor, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), false);
            touched = true;
        }
        return touched;
    }

    private static boolean setIfAllowed(ChunkAccess chunk, BlockPos.MutableBlockPos cursor,
                                        int x, int y, int z, BlockState state) {
        BlockState current = chunk.getBlockState(cursor.set(x, y, z));
        if (current.is(SHTags.NEVER_REPLACE)) return false;
        if (!current.isAir() && !ColumnSampler.isRemovable(current)) return false;
        chunk.setBlockState(cursor, state, false);
        return true;
    }

    /** 0 inside the footprint, otherwise the straight-line distance to its edge. */
    private static double distanceToFootprint(int x, int z, BoundingBox box) {
        int dx = Math.max(0, Math.max(box.minX() - x, x - box.maxX()));
        int dz = Math.max(0, Math.max(box.minZ() - z, z - box.maxZ()));
        if (dx == 0 && dz == 0) return 0.0D;
        return Math.sqrt((double) dx * dx + (double) dz * dz);
    }

    private static double smoothstep(double t) { return t * t * (3.0D - 2.0D * t); }

    private static boolean isInsideAny(List<BoundingBox> boxes, int x, int z) {
        for (BoundingBox b : boxes) {
            if (x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ()) return true;
        }
        return false;
    }

    /**
     * Every structure whose footprint (plus its margin) reaches this chunk.
     *
     * Structure references are written into each chunk a structure's box covers, and FEATURES
     * depends on the STRUCTURE_REFERENCES of neighbouring chunks, so scanning the small
     * neighbourhood needed for the margin is safe and - unlike a saved index of placements -
     * completely independent of what order chunks were generated in.
     */
    private static List<Job> collectJobs(WorldGenLevel level, ChunkAccess chunk,
                                         StructureManager structureManager, SHRules.Parsed rules) {
        Registry<Structure> registry;
        try {
            registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        } catch (Throwable t) {
            return List.of();
        }

        int maxMargin = Math.max(SHConfig.margin.get(), 16);
        int reach = Math.min(2, (int) Math.ceil(maxMargin / 16.0D));
        ChunkPos center = chunk.getPos();
        int maxFootprint = SHConfig.maxFootprintBlocks.get();

        List<Job> jobs = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int ox = -reach; ox <= reach; ox++) {
            for (int oz = -reach; oz <= reach; oz++) {
                ChunkPos probe = new ChunkPos(center.x + ox, center.z + oz);
                List<StructureStart> starts;
                try {
                    starts = structureManager.startsForStructure(probe, s -> true);
                } catch (Throwable t) {
                    continue; // chunk outside the current worldgen region - nothing to do
                }
                for (StructureStart start : starts) {
                    if (!start.isValid()) continue;
                    Structure structure = start.getStructure();
                    ResourceLocation id = registry.getKey(structure);
                    if (id == null) continue;
                    Holder<Structure> holder = holderOf(registry, structure);
                    if (rules.blendIgnored(holder, id)) continue;
                    if (SHConfig.surfaceStructuresOnly.get()
                            && structure.step() != GenerationStep.Decoration.SURFACE_STRUCTURES) continue;

                    BoundingBox box = start.getBoundingBox();
                    if ((long) box.getXSpan() * box.getZSpan() > maxFootprint) continue;

                    String key = id + "@" + box.minX() + "," + box.minY() + "," + box.minZ();
                    if (!seen.add(key)) continue;

                    int margin = rules.margin(holder, id);
                    if (!reaches(box, margin, center)) continue;

                    jobs.add(new Job(id, box, margin, rules.maxRaise(holder, id),
                            rules.maxLower(holder, id), rules.anchorOffset(holder, id)));
                }
            }
        }

        jobs.sort((a, b) -> {
            int c = a.id().compareTo(b.id());
            if (c != 0) return c;
            c = Integer.compare(a.box().minX(), b.box().minX());
            if (c != 0) return c;
            return Integer.compare(a.box().minZ(), b.box().minZ());
        });
        return jobs;
    }

    /** Boxes of structures we are NOT blending, so their ground is left intact. */
    private static List<BoundingBox> collectForeignBoxes(WorldGenLevel level, ChunkAccess chunk,
                                                         StructureManager structureManager, List<Job> jobs) {
        List<BoundingBox> out = new ArrayList<>();
        Set<String> ours = new HashSet<>();
        for (Job job : jobs) ours.add(job.box().minX() + "," + job.box().minY() + "," + job.box().minZ());

        ChunkPos center = chunk.getPos();
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                try {
                    for (StructureStart start : structureManager.startsForStructure(
                            new ChunkPos(center.x + ox, center.z + oz), s -> true)) {
                        if (!start.isValid()) continue;
                        BoundingBox box = start.getBoundingBox();
                        if (ours.contains(box.minX() + "," + box.minY() + "," + box.minZ())) continue;
                        out.add(box);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    private static boolean reaches(BoundingBox box, int margin, ChunkPos chunk) {
        return box.maxX() + margin >= chunk.getMinBlockX()
                && box.minX() - margin <= chunk.getMaxBlockX()
                && box.maxZ() + margin >= chunk.getMinBlockZ()
                && box.minZ() - margin <= chunk.getMaxBlockZ();
    }

    @Nullable
    private static Holder<Structure> holderOf(Registry<Structure> registry, Structure structure) {
        try {
            return registry.wrapAsHolder(structure);
        } catch (Throwable t) {
            return null;
        }
    }

    private TerrainSmoother() {}
}
