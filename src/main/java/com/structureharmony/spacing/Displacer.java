package com.structureharmony.spacing;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Moves a structure by translating its pieces.
 *
 * This is an operation vanilla performs on itself - JigsawPlacement shifts whole piece trees,
 * and StructurePiece#move is overridden by PoolElementStructurePiece to carry the jigsaw
 * position and junctions along with the bounding box - so jigsaw structures (villages,
 * outposts, and most modded structures) translate correctly rather than coming apart.
 *
 * Doing it during STRUCTURE_STARTS matters: the NOISE stage runs afterwards and reads structure
 * starts through Beardifier, so a structure that declares terrain adaptation gets vanilla's own
 * terrain shaping applied at its NEW position, for free.
 *
 * THE HARD LIMIT. ChunkGenerator#createReferences only looks 8 chunks around each chunk when
 * working out which structures that chunk must place. A structure whose bounding box ends up
 * further than that from the chunk that owns it is simply never placed by the outer chunks - it
 * generates cut in half. Every candidate move is therefore checked against that window, and a
 * move that breaks it is discarded rather than applied.
 */
public final class Displacer {

    /** 8 is vanilla's reference radius; stay one chunk inside it. */
    public static final int SAFE_CHUNK_RADIUS = 7;

    private Displacer() {}

    /**
     * The structure's bounding box, computed from its pieces WITHOUT calling
     * StructureStart#getBoundingBox, which would populate a cache that then goes stale the
     * moment the pieces are moved.
     */
    @Nullable
    public static BoundingBox boxOf(StructureStart start) {
        List<StructurePiece> pieces = start.getPieces().pieces();
        if (pieces.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (StructurePiece piece : pieces) {
            BoundingBox b = piece.getBoundingBox();
            minX = Math.min(minX, b.minX()); minY = Math.min(minY, b.minY()); minZ = Math.min(minZ, b.minZ());
            maxX = Math.max(maxX, b.maxX()); maxY = Math.max(maxY, b.maxY()); maxZ = Math.max(maxZ, b.maxZ());
        }
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Would the box, shifted by (dx,dz), still be placed in full by the chunks around its owner? */
    public static boolean fitsReferenceWindow(BoundingBox box, ChunkPos owner, int dx, int dz) {
        int minCX = (box.minX() + dx) >> 4;
        int maxCX = (box.maxX() + dx) >> 4;
        int minCZ = (box.minZ() + dz) >> 4;
        int maxCZ = (box.maxZ() + dz) >> 4;
        int distance = Math.max(
                Math.max(Math.abs(minCX - owner.x), Math.abs(maxCX - owner.x)),
                Math.max(Math.abs(minCZ - owner.z), Math.abs(maxCZ - owner.z)));
        return distance <= SAFE_CHUNK_RADIUS;
    }

    /** Largest shift, in blocks, that could ever fit the reference window for this box. */
    public static int headroom(BoundingBox box, ChunkPos owner) {
        int span = Math.max(box.getXSpan(), box.getZSpan());
        int room = (SAFE_CHUNK_RADIUS * 16) - (span / 2) - 16;
        return Math.max(0, room);
    }

    public static void move(StructureStart start, int dx, int dy, int dz) {
        if (dx == 0 && dy == 0 && dz == 0) return;
        for (StructurePiece piece : start.getPieces().pieces()) {
            piece.move(dx, dy, dz);
        }
    }
}
