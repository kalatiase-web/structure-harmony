package com.structureharmony;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.ChunkGeneratorStructureState;

import javax.annotation.Nullable;

/**
 * ChunkGenerator#createStructures hands us the registries and the generator's structure state;
 * StructureManager#setStartForStructure, where the actual filtering happens, does not get them.
 * createStructures calls setStartForStructure synchronously on the same worldgen thread, so a
 * thread-local handover is safe. Set-and-overwrite (never a stack), so a thrown exception
 * upstream cannot leave a stale value that matters.
 */
public final class SHContext {

    public record Ctx(RegistryAccess registries, ChunkGeneratorStructureState state,
                      BiomeSource biomeSource, ChunkGenerator generator) {}

    private static final ThreadLocal<Ctx> CURRENT = new ThreadLocal<>();

    public static void set(Ctx ctx) { CURRENT.set(ctx); }
    public static void clear() { CURRENT.remove(); }
    @Nullable public static Ctx get() { return CURRENT.get(); }

    private SHContext() {}
}
