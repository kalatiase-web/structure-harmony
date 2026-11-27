package com.structureharmony;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.Structure;

public final class SHTags {
    public static final TagKey<Structure> NO_SPACING   = structure("no_spacing");
    public static final TagKey<Structure> NEVER_REJECT = structure("never_reject");
    public static final TagKey<Structure> NO_SMOOTHING = structure("no_smoothing");

    public static final TagKey<Block> TERRAIN       = block("terrain");
    public static final TagKey<Block> OVERGROWTH    = block("overgrowth");
    public static final TagKey<Block> NEVER_REPLACE = block("never_replace");

    private static TagKey<Structure> structure(String name) {
        return TagKey.create(Registries.STRUCTURE, new ResourceLocation(StructureHarmony.MODID, name));
    }

    private static TagKey<Block> block(String name) {
        return TagKey.create(Registries.BLOCK, new ResourceLocation(StructureHarmony.MODID, name));
    }

    private SHTags() {}
}
