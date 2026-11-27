package com.structureharmony;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * All tuning lives here. Every "*Overrides" list uses the same selector syntax:
 *
 *   "minecraft:village_plains=10"   exact structure id
 *   "@mymod=8"                      every structure from a mod id
 *   "#mymod:big_stuff=14"           every structure in a structure tag
 *   "*=6"                           fallback for everything
 *
 * More specific selectors win: exact id > tag > namespace > "*".
 */
public final class SHConfig {
    public static final ForgeConfigSpec SPEC;

    // ---- spacing ----
    public static ForgeConfigSpec.BooleanValue spacingEnabled;
    public static ForgeConfigSpec.IntValue defaultRadius;
    public static ForgeConfigSpec.IntValue extraBuffer;
    public static ForgeConfigSpec.IntValue maxScanRadius;
    public static ForgeConfigSpec.IntValue minNeighbourSpacing;
    public static ForgeConfigSpec.IntValue candidateBudget;
    public static ForgeConfigSpec.BooleanValue checkBiomes;
    public static ForgeConfigSpec.BooleanValue preferModdedOverVanilla;
    public static ForgeConfigSpec.DoubleValue failOpenRatio;
    public static ForgeConfigSpec.IntValue failOpenMinSamples;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> radiusOverrides;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> priorityOverrides;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> pairOverrides;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> spacingIgnored;

    // ---- blending ----
    public static ForgeConfigSpec.BooleanValue blendEnabled;
    public static ForgeConfigSpec.BooleanValue surfaceStructuresOnly;
    public static ForgeConfigSpec.IntValue margin;
    public static ForgeConfigSpec.IntValue maxRaise;
    public static ForgeConfigSpec.IntValue maxLower;
    public static ForgeConfigSpec.IntValue anchorOffset;
    public static ForgeConfigSpec.BooleanValue flattenInside;
    public static ForgeConfigSpec.BooleanValue clearInsideFully;
    public static ForgeConfigSpec.IntValue clearAbove;
    public static ForgeConfigSpec.BooleanValue fillCavesUnder;
    public static ForgeConfigSpec.IntValue caveFillDepth;
    public static ForgeConfigSpec.EnumValue<WaterMode> waterMode;
    public static ForgeConfigSpec.BooleanValue respectOtherStructures;
    public static ForgeConfigSpec.BooleanValue carveUnknownBlocks;
    public static ForgeConfigSpec.IntValue maxFootprintBlocks;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> blendIgnored;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> marginOverrides;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> maxRaiseOverrides;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> maxLowerOverrides;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> anchorOffsetOverrides;

    // ---- debug ----
    public static ForgeConfigSpec.BooleanValue logRejections;
    public static ForgeConfigSpec.BooleanValue logBlending;
    public static ForgeConfigSpec.IntValue historySize;

    public enum WaterMode { SKIP_WATER_COLUMNS, FILL_TO_SEA_LEVEL, TREAT_AS_LAND }

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Keeps structures from different mods (and vanilla) from generating on top of each other.",
                  "Distances are in CHUNKS. A rejected candidate is only skipped at that one spot -",
                  "the structure still gets every other position its own spacing grid gives it, so",
                  "nothing is ever disabled outright.").push("spacing");
        spacingEnabled = b.define("enabled", true);
        defaultRadius = b.comment("Default 'personal space' radius, in chunks, for a structure with no override.",
                                  "Required distance between two structures = radiusA + radiusB + extraBuffer.",
                                  "So 4 + 4 + 0 = 8 chunks = 128 blocks apart by default.")
                          .defineInRange("defaultRadius", 4, 0, 64);
        extraBuffer = b.comment("Extra chunks added on top of every pair distance. Raise this for a quick global spread-out.")
                       .defineInRange("extraBuffer", 0, 0, 64);
        maxScanRadius = b.comment("Hard cap on how far (chunks) a single check will look for neighbours. Perf guard.")
                         .defineInRange("maxScanRadius", 24, 1, 64);
        minNeighbourSpacing = b.comment("Structures whose own placement spacing is below this are never treated as",
                                        "neighbours to yield to (mineshaft-style 'everywhere' structures). Perf + sanity guard.")
                               .defineInRange("minNeighbourSpacing", 4, 1, 64);
        candidateBudget = b.comment("Max neighbour candidate positions examined per placement attempt. Perf guard.")
                           .defineInRange("candidateBudget", 4096, 64, 262144);
        checkBiomes = b.comment("Only yield to a neighbour whose biome list actually matches the biome at that spot.",
                                "Strongly recommended: without it you lose structures to neighbours that would",
                                "never have generated there anyway. (Approximate - samples the biome at y=64.)")
                       .define("checkBiomes", true);
        preferModdedOverVanilla = b.comment("Give every non-minecraft: structure a +10 priority over vanilla ones, so when a",
                                            "modded structure and a vanilla structure collide, the vanilla one yields.")
                                   .define("preferModdedOverVanilla", true);
        failOpenRatio = b.comment("Safety valve against starving a structure. If a structure has already lost more than",
                                  "this fraction of its candidate positions, it stops being rejected. 1.0 disables the valve.",
                                  "Counters are per structure per world seed and reset on server restart.")
                         .defineInRange("failOpenRatio", 0.6D, 0.0D, 1.0D);
        failOpenMinSamples = b.comment("Minimum attempts before failOpenRatio is allowed to kick in.")
                              .defineInRange("failOpenMinSamples", 8, 1, 10000);
        radiusOverrides = b.comment("Per-structure radius in chunks. Bigger structures want bigger numbers.")
                           .defineList("radiusOverrides", List.of(
                                   "minecraft:mansion=12",
                                   "minecraft:monument=8",
                                   "minecraft:village_plains=7",
                                   "minecraft:village_desert=7",
                                   "minecraft:village_savanna=7",
                                   "minecraft:village_snowy=7",
                                   "minecraft:village_taiga=7",
                                   "minecraft:pillager_outpost=7",
                                   "minecraft:ancient_city=10",
                                   "minecraft:trail_ruins=5",
                                   "minecraft:ruined_portal=2",
                                   "minecraft:ruined_portal_desert=2",
                                   "minecraft:ruined_portal_jungle=2",
                                   "minecraft:ruined_portal_swamp=2",
                                   "minecraft:ruined_portal_mountain=2",
                                   "minecraft:ruined_portal_ocean=2",
                                   "minecraft:ruined_portal_nether=2"
                           ), SHConfig::isSelectorEntry);
        priorityOverrides = b.comment("Higher priority wins a collision; the lower one yields. Default 0",
                                      "(minecraft: structures get -10 when preferModdedOverVanilla is true).",
                                      "Equal priority is broken by a seed-derived hash, so it is stable but not alphabetical.")
                             .defineList("priorityOverrides", List.of(
                                     "minecraft:stronghold=1000",
                                     "minecraft:buried_treasure=1000"
                             ), SHConfig::isSelectorEntry);
        pairOverrides = b.comment("Exact required distance in chunks between two specific selectors.",
                                  "Overrides radiusA+radiusB+extraBuffer. Use 0 to let a pair overlap freely.",
                                  "Format: \"selectorA|selectorB=distance\"")
                         .defineList("pairOverrides", List.of(
                                 "minecraft:village_plains|minecraft:pillager_outpost=0"
                         ), SHConfig::isPairEntry);
        spacingIgnored = b.comment("Structures that take no part in spacing at all (neither yields nor is yielded to).",
                                   "The datapack tag #structureharmony:no_spacing does the same thing and already",
                                   "covers strongholds, mineshafts, buried treasure, fortresses and end cities.")
                          .defineList("ignored", List.of(), SHConfig::isSelector);
        b.pop();

        b.comment("Reshapes the ground around a structure so it meets the build instead of floating",
                  "over a slope or being swallowed by a mountain. No foundation pillars are placed -",
                  "only the terrain itself is raised, lowered and feathered back to normal.").push("blending");
        blendEnabled = b.define("enabled", true);
        surfaceStructuresOnly = b.comment("Only blend structures in the SURFACE_STRUCTURES generation step.",
                                          "Keeps the smoother away from mineshafts, fortresses and other underground builds.")
                                 .define("surfaceStructuresOnly", true);
        margin = b.comment("How far out (blocks) the terrain is feathered back to its natural height.")
                  .defineInRange("margin", 10, 0, 48);
        maxRaise = b.comment("Max blocks the ground outside the footprint may be raised.")
                    .defineInRange("maxRaise", 10, 0, 64);
        maxLower = b.comment("Max blocks the ground outside the footprint may be cut down.")
                    .defineInRange("maxLower", 20, 0, 128);
        anchorOffset = b.comment("Ground level = structure bounding box minY + this. Use -1 or -2 if structures end up",
                                 "sitting one block proud of the dirt, +1 if the bottom course gets buried.")
                        .defineInRange("anchorOffset", 0, -16, 16);
        flattenInside = b.comment("Level the ground under the footprint itself to the anchor level.")
                         .define("flattenInside", true);
        clearInsideFully = b.comment("Inside the footprint, ignore maxLower and carve all the way out to the top of the",
                                     "structure box. This is what stops builds being buried in a mountainside.")
                            .define("clearInsideFully", true);
        clearAbove = b.comment("Extra blocks of headroom cleared above the structure box when clearInsideFully is on.")
                      .defineInRange("clearAbove", 2, 0, 32);
        fillCavesUnder = b.comment("Plug air pockets found directly under the new ground surface.")
                          .define("fillCavesUnder", true);
        caveFillDepth = b.defineInRange("caveFillDepth", 4, 0, 32);
        waterMode = b.comment("What to do with columns whose surface is water.",
                              "SKIP_WATER_COLUMNS - leave the sea alone (default)",
                              "FILL_TO_SEA_LEVEL  - raise the sea floor but never above sea level",
                              "TREAT_AS_LAND      - reshape it like dry ground")
                     .defineEnum("waterMode", WaterMode.SKIP_WATER_COLUMNS);
        respectOtherStructures = b.comment("Never reshape ground that sits inside another structure's bounding box.")
                                  .define("respectOtherStructures", true);
        carveUnknownBlocks = b.comment("Allow cutting through blocks that are in none of the structureharmony block tags.",
                                       "Leave this off unless a modded biome's stone/dirt refuses to be carved - the safer",
                                       "fix is adding that block to the #structureharmony:terrain tag in a datapack.")
                              .define("carveUnknownBlocks", false);
        maxFootprintBlocks = b.comment("Skip blending for absurdly large structures (footprint in blocks, X*Z). Perf guard.")
                              .defineInRange("maxFootprintBlocks", 65536, 256, 1048576);
        blendIgnored = b.comment("Structures never blended. The tag #structureharmony:no_smoothing does the same and",
                                  "already covers underwater/underground vanilla structures.")
                        .defineList("ignored", List.of(), SHConfig::isSelector);
        marginOverrides = b.defineList("marginOverrides", List.of("minecraft:mansion=14"), SHConfig::isSelectorEntry);
        maxRaiseOverrides = b.defineList("maxRaiseOverrides", List.of(), SHConfig::isSelectorEntry);
        maxLowerOverrides = b.defineList("maxLowerOverrides", List.of(), SHConfig::isSelectorEntry);
        anchorOffsetOverrides = b.defineList("anchorOffsetOverrides", List.of(), SHConfig::isSelectorEntry);
        b.pop();

        b.push("debug");
        logRejections = b.comment("Log every spacing rejection to the server log.").define("logRejections", false);
        logBlending = b.define("logBlending", false);
        historySize = b.comment("How many recent rejections /structureharmony why keeps in memory.")
                       .defineInRange("historySize", 200, 0, 5000);
        b.pop();

        SPEC = b.build();
    }

    private static boolean isSelector(Object o) {
        return o instanceof String s && !s.isBlank() && !s.contains("=");
    }

    private static boolean isSelectorEntry(Object o) {
        if (!(o instanceof String s)) return false;
        int i = s.lastIndexOf('=');
        if (i <= 0 || i == s.length() - 1) return false;
        try { Integer.parseInt(s.substring(i + 1).trim()); } catch (NumberFormatException e) { return false; }
        return true;
    }

    private static boolean isPairEntry(Object o) {
        return isSelectorEntry(o) && ((String) o).indexOf('|') > 0;
    }

    private SHConfig() {}
}
