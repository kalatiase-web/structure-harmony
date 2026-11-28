package com.structureharmony.spacing;

import com.structureharmony.SHConfig;
import com.structureharmony.SHRules;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which structures belong in water, and which are meant to hang in the air.
 *
 * Both matter because the fit checks would otherwise "fix" structures that were never broken:
 * dragging a shipwreck onto a beach, or dropping a deliberately floating island onto the ground.
 * Explicit tags and config lists win; the automatic test exists so the common cases work without
 * a list to maintain.
 */
public final class StructureTraits {

    private static final Map<String, Boolean> AQUATIC_BY_BIOME = new ConcurrentHashMap<>();

    /**
     * Aquatic if listed or tagged, or - when enabled - if the structure's own biome list contains
     * ocean or river biomes. A structure that asked to generate in the ocean clearly belongs there.
     */
    public static boolean isAquatic(@Nullable Holder<Structure> holder, ResourceLocation id, SHRules.Parsed rules) {
        if (rules.aquaticListed(holder, id)) return true;
        if (!SHConfig.aquaticFromBiomes.get() || holder == null) return false;
        return AQUATIC_BY_BIOME.computeIfAbsent(id.toString(), key -> {
            try {
                for (Holder<Biome> biome : holder.value().biomes()) {
                    if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN)
                            || biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_BEACH)) {
                        return true;
                    }
                }
            } catch (Throwable t) {
                return true;   // unsure: assume it belongs where it is rather than dragging it
            }
            return false;
        });
    }

    /**
     * Floating if listed or tagged, or if its base already sits well clear of the ground beneath
     * it - a structure vanilla deliberately put in the air should not be dropped onto the terrain.
     */
    public static boolean isFloating(@Nullable Holder<Structure> holder, ResourceLocation id,
                                     SHRules.Parsed rules, int boxMinY, @Nullable SiteCheck.Site site) {
        if (rules.floating(holder, id)) return true;
        if (site == null) return false;
        return boxMinY - site.highest() >= SHConfig.floatingDetectGap.get();
    }

    public static void reset() { AQUATIC_BY_BIOME.clear(); }

    private StructureTraits() {}
}
