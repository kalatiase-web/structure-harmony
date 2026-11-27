package com.structureharmony.mixin;

import com.structureharmony.SHContext;
import com.structureharmony.StructureHarmony;
import com.structureharmony.spacing.SpacingEngine;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The single choke point every structure goes through: vanilla builds a start, then records it
 * here. Cancelling the record drops that one placement attempt and nothing else - the structure
 * keeps the rest of its placement grid, so this never silently removes a structure from a world.
 */
@Mixin(StructureManager.class)
public abstract class StructureManagerMixin {

    @Inject(method = "setStartForStructure", at = @At("HEAD"), cancellable = true)
    private void structureharmony$filterOverlaps(SectionPos sectionPos, Structure structure,
                                                 StructureStart start, ChunkAccess chunk, CallbackInfo ci) {
        SHContext.Ctx ctx = SHContext.get();
        if (ctx == null || start == null || !start.isValid()) return;

        try {
            SpacingEngine.Decision decision =
                    SpacingEngine.check(ctx, structure, chunk.getPos(), start.getBoundingBox());
            if (decision.reject()) ci.cancel();
        } catch (Throwable t) {
            StructureHarmony.LOG.error("[StructureHarmony] spacing check failed - allowing placement", t);
        }
    }
}
