package com.structureharmony.mixin;

import com.structureharmony.SHContext;
import com.structureharmony.SHStats;
import com.structureharmony.StructureHarmony;
import com.structureharmony.spacing.Displacer;
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
 * The single point every structure passes through on its way into the world: vanilla builds a
 * start, then records it here.
 *
 * Injecting at HEAD means the start has not been registered yet, so its pieces can still be
 * translated - and because this runs during STRUCTURE_STARTS, everything downstream (terrain
 * beardification, structure references, piece placement) reads the moved position.
 *
 * Note this must not call StructureStart#getBoundingBox before moving: that method caches, and
 * the cached box would go stale the moment the pieces shift.
 */
@Mixin(StructureManager.class)
public abstract class StructureManagerMixin {

    // require = 1: if this ever stops matching, fail loudly at load instead of silently doing
    // nothing. A spacing mod that quietly no-ops is worse than one that will not start.
    @Inject(method = "setStartForStructure", at = @At("HEAD"), cancellable = true, require = 1)
    private void structureharmony$resolveOverlap(SectionPos sectionPos, Structure structure,
                                                 StructureStart start, StructureAccess access, CallbackInfo ci) {
        SHStats.markHookLive();
        SHContext.Ctx ctx = SHContext.get();
        if (ctx == null || start == null || !start.isValid()) return;

        try {
            SpacingEngine.Decision decision =
                    SpacingEngine.resolve(ctx, structure, start, sectionPos.chunk());

            if (decision.moved()) {
                Displacer.move(start, decision.dx(), decision.dy(), decision.dz());
            }
            if (decision.outcome() == SpacingEngine.Outcome.CANCELLED) {
                ci.cancel();
            }
        } catch (Throwable t) {
            StructureHarmony.LOG.error(
                    "[StructureHarmony] overlap resolution failed - placing the structure unchanged", t);
        }
    }
}
