package com.structureharmony;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;

public final class SHTags {
    /** Takes no part in spacing in either direction. */
    public static final TagKey<Structure> NO_SPACING = structure("no_spacing");
    /** Never moved itself, but others still move away from it. */
    public static final TagKey<Structure> NEVER_REJECT = structure("never_reject");
    /** Meant to hang in the air: exempt from every ground fit check. */
    public static final TagKey<Structure> FLOATING = structure("floating");
    /** Meant to be in water: never pulled onto land. */
    public static final TagKey<Structure> AQUATIC = structure("aquatic");

    private static TagKey<Structure> structure(String name) {
        return TagKey.create(Registries.STRUCTURE, new ResourceLocation(StructureHarmony.MODID, name));
    }

    private SHTags() {}
}
