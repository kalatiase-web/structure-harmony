package com.structureharmony.mixin;

import com.structureharmony.SHContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * createStructures owns the registries, the generator's structure state and the chunk being
 * generated. Hand them to the thread-local so the resolver in StructureManagerMixin, which runs
 * synchronously inside this call, can see them.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    @Inject(method = "createStructures", at = @At("HEAD"), require = 1)
    private void structureharmony$pushContext(RegistryAccess registries, ChunkGeneratorStructureState state,
                                              StructureManager structureManager, ChunkAccess chunk,
                                              StructureTemplateManager templates, CallbackInfo ci) {
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        SHContext.set(new SHContext.Ctx(registries, state, self.getBiomeSource(), self, chunk));
    }

    @Inject(method = "createStructures", at = @At("RETURN"), require = 1)
    private void structureharmony$popContext(RegistryAccess registries, ChunkGeneratorStructureState state,
                                             StructureManager structureManager, ChunkAccess chunk,
                                             StructureTemplateManager templates, CallbackInfo ci) {
        SHContext.clear();
    }
}
