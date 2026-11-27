package com.structureharmony;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;

import javax.annotation.Nullable;

/**
 * ChunkGenerator#createStructures has the registries, the generator's structure state and the
 * chunk; StructureManager#setStartForStructure, where the work happens, gets none of them.
 * createStructures calls it synchronously on the same worldgen thread, so a thread-local
 * handover is safe. Set-and-overwrite, never a stack.
 */
public final class SHContext {

    public record Ctx(RegistryAccess registries, ChunkGeneratorStructureState state,
                      BiomeSource biomeSource, ChunkGenerator generator, LevelHeightAccessor heightAccessor) {}

    private static final ThreadLocal<Ctx> CURRENT = new ThreadLocal<>();

    public static void set(Ctx ctx) { CURRENT.set(ctx); }
    public static void clear() { CURRENT.remove(); }
    @Nullable public static Ctx get() { return CURRENT.get(); }

    private SHContext() {}
}
