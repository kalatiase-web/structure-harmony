package com.structureharmony.mixin;

import com.structureharmony.SHContext;
import com.structureharmony.blend.TerrainSmoother;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    /**
     * createStructures owns the registries and the generator's structure state. Hand them to the
     * thread-local so the filter in StructureManagerMixin, which runs synchronously inside this
     * call, can see them.
     */
    @Inject(method = "createStructures", at = @At("HEAD"))
    private void structureharmony$pushContext(RegistryAccess registries, ChunkGeneratorStructureState state,
                                              StructureManager structureManager, ChunkAccess chunk,
                                              StructureTemplateManager templates, CallbackInfo ci) {
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        SHContext.set(new SHContext.Ctx(registries, state, self.getBiomeSource(), self));
    }

    @Inject(method = "createStructures", at = @At("RETURN"))
    private void structureharmony$popContext(RegistryAccess registries, ChunkGeneratorStructureState state,
                                             StructureManager structureManager, ChunkAccess chunk,
                                             StructureTemplateManager templates, CallbackInfo ci) {
        SHContext.clear();
    }

    /**
     * Terrain blending. HEAD is before any structure piece or feature lands in this chunk, and
     * after the chunk's own terrain and surface are final - exactly the window we want.
     */
    @Inject(method = "applyBiomeDecoration", at = @At("HEAD"))
    private void structureharmony$blendTerrain(WorldGenLevel level, ChunkAccess chunk,
                                               StructureManager structureManager, CallbackInfo ci) {
        try {
            TerrainSmoother.blendChunk((ChunkGenerator) (Object) this, level, chunk, structureManager);
        } catch (Throwable t) {
            com.structureharmony.StructureHarmony.LOG.error(
                    "[StructureHarmony] terrain blending failed in chunk {} - chunk left untouched",
                    chunk.getPos(), t);
        }
    }
}
