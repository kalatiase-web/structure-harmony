package com.structureharmony.mixin;

import com.structureharmony.SHContext;
import com.structureharmony.SHStats;
import com.structureharmony.StructureHarmony;
import com.structureharmony.spacing.SpacingEngine;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.StructureAccess;
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

    // require = 1: if this ever stops matching, fail loudly at load instead of silently
    // doing nothing. A spacing mod that quietly no-ops is worse than one that won't start.
    @Inject(method = "setStartForStructure", at = @At("HEAD"), cancellable = true, require = 1)
    private void structureharmony$filterOverlaps(SectionPos sectionPos, Structure structure,
                                                 StructureStart start, StructureAccess access, CallbackInfo ci) {
        SHStats.markSpacingHookLive();
        SHContext.Ctx ctx = SHContext.get();
        if (ctx == null || start == null || !start.isValid()) return;

        try {
            SpacingEngine.Decision decision =
                    SpacingEngine.check(ctx, structure, sectionPos.chunk(), start.getBoundingBox());
            if (decision.reject()) ci.cancel();
        } catch (Throwable t) {
            StructureHarmony.LOG.error("[StructureHarmony] spacing check failed - allowing placement", t);
        }
    }
}
