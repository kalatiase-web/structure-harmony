package com.structureharmony;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * All distances here are in BLOCKS.
 *
 * Every "*Overrides" list uses the same selector syntax, most specific wins:
 *
 *   "minecraft:village_plains=140"   exact structure id
 *   "@mymod=120"                     every structure from a mod id
 *   "#mymod:big_stuff=160"           every structure in a structure tag
 *   "*=100"                          fallback for everything
 */
public final class SHConfig {
    public static final ForgeConfigSpec SPEC;

    public static ForgeConfigSpec.BooleanValue enabled;
    public static ForgeConfigSpec.IntValue minSeparation;
    public static ForgeConfigSpec.EnumValue<SeparationMode> separationMode;
    public static ForgeConfigSpec.IntValue assumedHalfSpan;
    public static ForgeConfigSpec.BooleanValue avoidWater;
    public static ForgeConfigSpec.IntValue waterDepthTolerance;
    public static ForgeConfigSpec.IntValue footprintSamples;
    public static ForgeConfigSpec.IntValue maxExtraRelief;
    public static ForgeConfigSpec.IntValue siteCheckBudget;
    public static ForgeConfigSpec.IntValue extraBuffer;
    public static ForgeConfigSpec.IntValue maxShift;
    public static ForgeConfigSpec.BooleanValue followTerrainHeight;
    public static ForgeConfigSpec.IntValue maxVerticalShift;
    public static ForgeConfigSpec.BooleanValue keepBiome;
    public static ForgeConfigSpec.BooleanValue cancelWhenCannotMove;
    public static ForgeConfigSpec.BooleanValue checkBiomes;
    public static ForgeConfigSpec.BooleanValue preferModdedOverVanilla;
    public static ForgeConfigSpec.BooleanValue sizeDecidesWhoMoves;
    public static ForgeConfigSpec.IntValue minNeighbourSpacing;
    public static ForgeConfigSpec.IntValue candidateBudget;
    public static ForgeConfigSpec.IntValue searchDirections;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> radiusOverrides;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> priorityOverrides;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> pairOverrides;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> maxShiftOverrides;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> ignored;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> pinned;

    public static ForgeConfigSpec.BooleanValue logMoves;
    public static ForgeConfigSpec.IntValue historySize;

    public enum SeparationMode { EDGE_TO_EDGE, CENTRE_TO_CENTRE }

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("When two structures would generate on top of each other, the higher priority one",
                  "stays where it is and the lower priority one is MOVED aside - its pieces are",
                  "translated so it ends up at least minSeparation blocks away. Nothing is deleted.",
                  "",
                  "If a structure cannot be moved far enough to clear the conflict, it is generated",
                  "where it was anyway, and counted as 'unresolved' in /structureharmony report.",
                  "It is never cancelled unless you explicitly turn cancelWhenCannotMove on.").push("spacing");

        enabled = b.define("enabled", true);

        minSeparation = b.comment("Minimum distance in BLOCKS between two different structures.",
                                  "With the default separationMode this is measured between the EDGES of their",
                                  "bounding boxes, so a 300 block structure really does keep everything 100 blocks",
                                  "clear of it rather than 100 blocks from its centre.")
                         .defineInRange("minSeparation", 100, 0, 512);

        separationMode = b.comment("EDGE_TO_EDGE   - minSeparation is the clear gap between the two footprints.",
                                   "CENTRE_TO_CENTRE - minSeparation is measured centre to centre, so large",
                                   "structures can still be clipped into. Only use this if EDGE_TO_EDGE spreads",
                                   "things out more than you want.")
                          .defineEnum("separationMode", SeparationMode.EDGE_TO_EDGE);

        assumedHalfSpan = b.comment("Footprint sizes are learned from structures as they generate. Until a structure",
                                    "has been seen at least once, this stands in for half its width, in blocks.")
                           .defineInRange("assumedHalfSpan", 24, 0, 256);

        avoidWater = b.comment("Never move a land structure onto water. A pond inside a plains biome is still the",
                               "plains biome, so the biome check alone does not catch it - this compares the surface",
                               "height against the solid floor instead. Structures that already stood in water",
                               "(shipwrecks, ocean ruins) are still allowed to move within it.")
                      .define("avoidWater", true);

        waterDepthTolerance = b.comment("Blocks of fluid above the solid floor before a column counts as water.")
                               .defineInRange("waterDepthTolerance", 2, 1, 32);

        footprintSamples = b.comment("Ground is sampled on an N x N grid across the footprint rather than at the centre",
                                     "point, which is what catches a destination that is flat in the middle and a cliff",
                                     "at the corner. Higher is more accurate and slower.")
                            .defineInRange("footprintSamples", 3, 2, 8);

        maxExtraRelief = b.comment("Do not move a structure somewhere whose ground is this many blocks more uneven",
                                   "than where it already stood. Keeps a build from being shifted onto a slope and",
                                   "ending up with terrain cutting through it.")
                          .defineInRange("maxExtraRelief", 4, 0, 64);

        siteCheckBudget = b.comment("Max destinations whose ground is sampled per conflict. Performance guard.")
                           .defineInRange("siteCheckBudget", 24, 4, 256);

        extraBuffer = b.comment("Extra blocks added on top of every required distance.")
                       .defineInRange("extraBuffer", 0, 0, 512);

        maxShift = b.comment("Largest horizontal displacement, in blocks, this mod will apply.",
                             "",
                             "HARD ENGINE LIMIT: a structure is only placed by chunks within 8 chunks",
                             "(128 blocks) of the chunk that owns it, so a structure can never be moved",
                             "far enough that part of its bounding box leaves that window - it would be",
                             "generated sliced in half. The real limit is therefore 112 blocks minus the",
                             "structure's own radius, and large structures (villages, mansions) can only",
                             "move a short distance. Raising this value past 112 does nothing.")
                    .defineInRange("maxShift", 96, 0, 112);

        maxShiftOverrides = b.comment("Per-structure maxShift in blocks.")
                             .defineList("maxShiftOverrides", List.of(), SHConfig::isSelectorEntry);

        followTerrainHeight = b.comment("After moving a structure sideways, shift it vertically by the difference in",
                                        "ground height between the old and new spot, so it does not end up floating",
                                        "or buried. Only applies to surface structures.")
                               .define("followTerrainHeight", true);

        maxVerticalShift = b.comment("Cap on that vertical correction, in blocks. If the ground height differs by more",
                                     "than this, the move is treated as unusable and another direction is tried.")
                            .defineInRange("maxVerticalShift", 24, 0, 128);

        keepBiome = b.comment("Reject a move that would land the structure in a biome its own biome list does not",
                              "allow, and try another direction instead. Stops a desert pyramid being nudged into",
                              "a jungle.")
                     .define("keepBiome", true);

        cancelWhenCannotMove = b.comment("Off by default, and leaving it off is the point of this mod: when a conflict",
                                         "cannot be solved by moving, generate the structure anyway rather than losing it.",
                                         "Turning this on restores strict spacing at the cost of missing structures.")
                                .define("cancelWhenCannotMove", false);

        checkBiomes = b.comment("Only treat a neighbour as a conflict if its biome list matches the biome at its own",
                                "spot. Without this, structures get shoved around by neighbours that would never",
                                "have generated there. (Approximate - samples one biome per candidate chunk.)")
                       .define("checkBiomes", true);

        preferModdedOverVanilla = b.comment("Give every non-minecraft: structure +10 priority, so a vanilla structure is the one",
                                            "that gets moved when it collides with a modded one.",
                                            "",
                                            "OFF by default, and that is deliberate. It mattered when losing a conflict meant",
                                            "being deleted. Now that the loser is only moved, forcing the vanilla structure to be",
                                            "the mover usually makes things WORSE: vanilla villages are large, and a large",
                                            "structure has almost no room to move (see maxShift), so the pair ends up barely",
                                            "separated. Leave this off unless you specifically want modded structures pinned.")
                                   .define("preferModdedOverVanilla", false);

        sizeDecidesWhoMoves = b.comment("When no explicit priority separates two structures, the LARGER one stays put and the",
                                        "smaller one moves. The smaller structure has far more displacement headroom, so this",
                                        "is what actually buys you the full separation distance. priorityOverrides still wins",
                                        "over this when you set one.")
                               .define("sizeDecidesWhoMoves", true);

        minNeighbourSpacing = b.comment("Ignore structures whose placement spacing is below this (in chunks) when looking",
                                        "for conflicts - mineshaft-style structures that exist almost everywhere.")
                               .defineInRange("minNeighbourSpacing", 4, 1, 64);

        candidateBudget = b.comment("Max neighbour candidate positions examined per placement. Performance guard.")
                           .defineInRange("candidateBudget", 4096, 64, 262144);

        searchDirections = b.comment("How many evenly spaced directions are tried when looking for somewhere to move to.")
                            .defineInRange("searchDirections", 16, 4, 64);

        radiusOverrides = b.comment("EXTRA padding in blocks for a structure, on top of its own measured footprint.",
                                    "Normally leave this empty: real footprint sizes are measured automatically, so",
                                    "there is no list to keep up to date for modded structures. Use it only to give",
                                    "something more breathing room than its bounding box implies.")
                           .defineList("radiusOverrides", List.of(), SHConfig::isSelectorEntry);

        priorityOverrides = b.comment("Higher priority stays put; the lower one is the one that moves. Default 0",
                                      "(minecraft: structures get -10 while preferModdedOverVanilla is true).",
                                      "Ties are broken by a seed-derived hash, so the outcome is stable per world",
                                      "but not biased toward whichever mod id sorts first.")
                             .defineList("priorityOverrides", List.of(
                                     "minecraft:stronghold=1000",
                                     "minecraft:buried_treasure=1000"
                             ), SHConfig::isSelectorEntry);

        pairOverrides = b.comment("Exact required distance in blocks for one specific pair, overriding everything",
                                  "else. Use 0 to let a pair sit together freely.",
                                  "Format: \"selectorA|selectorB=blocks\"")
                         .defineList("pairOverrides", List.of(
                                 "minecraft:village_plains|minecraft:pillager_outpost=0"
                         ), SHConfig::isPairEntry);

        ignored = b.comment("Structures that take no part in spacing at all - never moved, never moved around.",
                            "The datapack tag #structureharmony:no_spacing does the same and already covers",
                            "strongholds, mineshafts, buried treasure, fortresses and end cities.")
                   .defineList("ignored", List.of(), SHConfig::isSelector);

        pinned = b.comment("Structures that are never moved themselves, but that other structures still move away",
                           "from. Use this for anything position-critical. #structureharmony:never_reject does",
                           "the same thing.")
                  .defineList("pinned", List.of(), SHConfig::isSelector);

        b.pop();

        b.push("debug");
        logMoves = b.comment("Log every move and every unresolved conflict to the server log.").define("logMoves", false);
        historySize = b.defineInRange("historySize", 200, 0, 5000);
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
