package com.structureharmony.spacing;

import com.structureharmony.SHConfig;
import com.structureharmony.SHContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * Asks whether a spot is somewhere a structure could sensibly stand, by sampling a grid of
 * ground columns across the footprint rather than looking at a single point.
 *
 * Two things this catches that a biome check cannot:
 *
 *  - Water. A pond or lake inside a plains biome is still the plains biome, so a biome list
 *    check passes and the structure lands in the water. Comparing WORLD_SURFACE_WG (which
 *    counts fluid) against OCEAN_FLOOR_WG (which does not) finds the fluid directly.
 *  - Slopes. Sampling only the centre column hides the fact that one corner of the footprint
 *    is twelve blocks higher, which is what leaves terrain cutting through a wall.
 *
 * Ground height is taken from OCEAN_FLOOR_WG so a structure is levelled against solid ground
 * rather than against the surface of a pond.
 */
public final class SiteCheck {

    public record Site(int ground, int lowest, int highest, boolean water) {
        /** How uneven the ground under the footprint is, in blocks. */
        public int relief() { return highest - lowest; }
    }

    /**
     * @param box the structure's current bounding box
     * @param dx  horizontal offset being considered
     * @param dz  horizontal offset being considered
     */
    @Nullable
    public static Site sample(SHContext.Ctx ctx, BoundingBox box, int dx, int dz) {
        int grid = Math.max(2, SHConfig.footprintSamples.get());
        int[] grounds = new int[grid * grid];
        boolean water = false;
        int depthTolerance = SHConfig.waterDepthTolerance.get();
        int index = 0;

        try {
            for (int ix = 0; ix < grid; ix++) {
                for (int iz = 0; iz < grid; iz++) {
                    int x = lerp(box.minX(), box.maxX(), ix, grid) + dx;
                    int z = lerp(box.minZ(), box.maxZ(), iz, grid) + dz;

                    int floor = ctx.generator().getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG,
                            ctx.heightAccessor(), ctx.state().randomState());
                    int surface = ctx.generator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
                            ctx.heightAccessor(), ctx.state().randomState());

                    if (surface - floor >= depthTolerance) water = true;
                    grounds[index++] = floor;
                }
            }
        } catch (Throwable t) {
            return null;   // cannot judge the site, so the caller must not move there
        }

        Arrays.sort(grounds);
        return new Site(grounds[grounds.length / 2], grounds[0], grounds[grounds.length - 1], water);
    }

    private static int lerp(int min, int max, int step, int steps) {
        if (steps <= 1) return (min + max) / 2;
        return min + (int) ((long) (max - min) * step / (steps - 1));
    }

    private SiteCheck() {}
}
