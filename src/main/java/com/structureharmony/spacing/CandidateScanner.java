package com.structureharmony.spacing;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.List;

/**
 * Works out, analytically, where a structure's OTHER candidate positions are near a point -
 * without generating anything and without caring what order chunks happen to be generated in.
 *
 * That determinism is the whole point: ask the same question twice on the same seed and you
 * get the same answer, so a structure never randomly vanishes depending on which direction
 * the player walked.
 */
public final class CandidateScanner {

    public interface Sink {
        /** @return false to stop scanning early. */
        boolean accept(int chunkX, int chunkZ);
    }

    /**
     * @param radius search radius in chunks around (centerX, centerZ)
     * @param budget max candidate positions to examine
     * @return number of positions examined
     */
    public static int scan(ChunkGeneratorStructureState state, long seed, StructurePlacement placement,
                           int centerX, int centerZ, int radius, int budget, Sink sink) {
        if (placement instanceof RandomSpreadStructurePlacement spread) {
            return scanSpread(state, seed, spread, centerX, centerZ, radius, budget, sink);
        }
        if (placement instanceof ConcentricRingsStructurePlacement rings) {
            return scanRings(state, rings, centerX, centerZ, radius, sink);
        }
        return scanBruteForce(state, placement, centerX, centerZ, Math.min(radius, 8), budget, sink);
    }

    private static int scanSpread(ChunkGeneratorStructureState state, long seed, RandomSpreadStructurePlacement spread,
                                  int centerX, int centerZ, int radius, int budget, Sink sink) {
        int spacing = Math.max(1, spread.spacing());
        int minCell = Math.floorDiv(centerX - radius, spacing);
        int maxCell = Math.floorDiv(centerX + radius, spacing);
        int minCellZ = Math.floorDiv(centerZ - radius, spacing);
        int maxCellZ = Math.floorDiv(centerZ + radius, spacing);
        int examined = 0;
        long r2 = (long) radius * radius;

        for (int cellX = minCell; cellX <= maxCell; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                if (++examined > budget) return examined;
                ChunkPos pos = spread.getPotentialStructureChunk(seed, cellX, cellZ);
                long dx = pos.x - centerX;
                long dz = pos.z - centerZ;
                if (dx * dx + dz * dz > r2) continue;
                // Full vanilla gate: spread + frequency reduction + exclusion zones.
                if (state != null && !spread.isStructureChunk(state, pos.x, pos.z)) continue;
                if (!sink.accept(pos.x, pos.z)) return examined;
            }
        }
        return examined;
    }

    private static int scanRings(ChunkGeneratorStructureState state, ConcentricRingsStructurePlacement rings,
                                 int centerX, int centerZ, int radius, Sink sink) {
        List<ChunkPos> positions;
        try {
            positions = state == null ? null : state.getRingPositionsFor(rings);
        } catch (Throwable t) {
            return 0;
        }
        if (positions == null || positions.isEmpty()) return 0;
        long r2 = (long) radius * radius;
        int examined = 0;
        for (ChunkPos pos : positions) {
            examined++;
            long dx = pos.x - centerX;
            long dz = pos.z - centerZ;
            if (dx * dx + dz * dz > r2) continue;
            if (!sink.accept(pos.x, pos.z)) return examined;
        }
        return examined;
    }

    /** Fallback for unknown/custom placement types: ask the placement itself, square by square. */
    private static int scanBruteForce(ChunkGeneratorStructureState state, StructurePlacement placement,
                                      int centerX, int centerZ, int radius, int budget, Sink sink) {
        if (state == null) return 0;
        int examined = 0;
        long r2 = (long) radius * radius;
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                if (++examined > budget) return examined;
                long dx = x - centerX;
                long dz = z - centerZ;
                if (dx * dx + dz * dz > r2) continue;
                if (!placement.isStructureChunk(state, x, z)) continue;
                if (!sink.accept(x, z)) return examined;
            }
        }
        return examined;
    }

    /** Spacing of a placement in chunks, or Integer.MAX_VALUE when it has no grid. */
    public static int spacingOf(StructurePlacement placement) {
        if (placement instanceof RandomSpreadStructurePlacement spread) return Math.max(1, spread.spacing());
        return Integer.MAX_VALUE;
    }

    private CandidateScanner() {}
}
