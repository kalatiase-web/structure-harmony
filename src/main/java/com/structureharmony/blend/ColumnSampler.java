package com.structureharmony.blend;

import com.structureharmony.SHConfig;
import com.structureharmony.SHTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;

/**
 * Reads a terrain column and works out which blocks to build new ground out of.
 *
 * Nothing is hardcoded per biome: whatever is already on the surface is reused, so this works
 * out of the box in modded biomes (red sand, mud, ash, whatever) without a compatibility list.
 */
public final class ColumnSampler {

    public record Column(int groundY, int topY, boolean watery,
                         BlockState top, BlockState fill, BlockState deep) {}

    /** How far down we are willing to look for real ground before giving up on a column. */
    private static final int MAX_PROBE = 40;

    @Nullable
    public static Column sample(ChunkAccess chunk, int x, int z, int minY) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        int topY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        int probe = Math.max(topY, chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z));
        boolean watery = false;

        int groundY = Integer.MIN_VALUE;
        int limit = Math.max(minY, probe - MAX_PROBE);
        for (int y = probe; y >= limit; y--) {
            BlockState state = chunk.getBlockState(cursor.set(x, y, z));
            if (!state.getFluidState().isEmpty()) { watery = true; continue; }
            if (state.isAir() || state.is(SHTags.OVERGROWTH)) continue;
            groundY = y;
            break;
        }
        if (groundY == Integer.MIN_VALUE) return null;

        BlockState top = chunk.getBlockState(cursor.set(x, groundY, z));
        BlockState fill = stateOr(chunk, cursor, x, groundY - 1, z, top);
        BlockState deep = stateOr(chunk, cursor, x, groundY - 4, z, fill);

        // A column of pure glass/wood tells us nothing useful about natural ground.
        if (!isUsable(top)) return null;
        if (!isUsable(fill)) fill = top;
        if (!isUsable(deep)) deep = fill;

        return new Column(groundY, topY, watery, top, fill, deep);
    }

    private static BlockState stateOr(ChunkAccess chunk, BlockPos.MutableBlockPos cursor,
                                      int x, int y, int z, BlockState fallback) {
        if (y < chunk.getMinBuildHeight()) return fallback;
        BlockState state = chunk.getBlockState(cursor.set(x, y, z));
        return state.isAir() || !state.getFluidState().isEmpty() ? fallback : state;
    }

    private static boolean isUsable(BlockState state) {
        if (state == null || state.isAir() || !state.getFluidState().isEmpty()) return false;
        if (state.is(SHTags.NEVER_REPLACE) || state.is(SHTags.OVERGROWTH)) return false;
        if (state.is(SHTags.TERRAIN)) return true;
        // Unknown block sitting on an untouched natural surface: almost certainly a modded
        // ground block, so it is fine to build new ground out of it. Add it to
        // #structureharmony:terrain if you also want it carved through.
        return !state.hasBlockEntity();
    }

    /**
     * May this block be removed when cutting terrain down?
     *
     * Deliberately conservative: anything that is not recognisable terrain stops the cut, which
     * is what keeps the smoother from eating another mod's build that already generated here.
     */
    public static boolean isRemovable(BlockState state) {
        if (state.isAir()) return true;
        if (state.is(SHTags.NEVER_REPLACE)) return false;
        if (!state.getFluidState().isEmpty()) return true;
        if (state.is(SHTags.TERRAIN) || state.is(SHTags.OVERGROWTH)) return true;
        return SHConfig.carveUnknownBlocks.get() && !state.hasBlockEntity();
    }

    public static BlockState airOrWater(int y, int seaLevel, boolean keepWater) {
        if (keepWater && y <= seaLevel) return Blocks.WATER.defaultBlockState();
        return Blocks.AIR.defaultBlockState();
    }

    private ColumnSampler() {}
}
