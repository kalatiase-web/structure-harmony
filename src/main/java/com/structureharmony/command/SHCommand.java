package com.structureharmony.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.structureharmony.SHConfig;
import com.structureharmony.SHRules;
import com.structureharmony.SHStats;
import com.structureharmony.spacing.Displacer;
import com.structureharmony.spacing.SpacingEngine;
import com.structureharmony.spacing.StructureSizes;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SHCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("structureharmony")
                .requires(src -> src.hasPermission(2));

        root.then(Commands.literal("report").executes(ctx -> report(ctx.getSource())));

        root.then(Commands.literal("why")
                .then(Commands.argument("structure", ResourceLocationArgument.id())
                        .executes(ctx -> why(ctx.getSource(), ResourceLocationArgument.getId(ctx, "structure")))));

        root.then(Commands.literal("distance")
                .then(Commands.argument("a", ResourceLocationArgument.id())
                        .then(Commands.argument("b", ResourceLocationArgument.id())
                                .executes(ctx -> distance(ctx.getSource(),
                                        ResourceLocationArgument.getId(ctx, "a"),
                                        ResourceLocationArgument.getId(ctx, "b"))))));

        root.then(Commands.literal("sizes").executes(ctx -> sizes(ctx.getSource())));

        root.then(Commands.literal("reset").executes(ctx -> {
            SHStats.reset();
            ctx.getSource().sendSuccess(() -> Component.literal("Structure Harmony counters cleared."), true);
            return 1;
        }));

        root.then(Commands.literal("reload").executes(ctx -> {
            SHRules.invalidate();
            SpacingEngine.invalidate();
            ctx.getSource().sendSuccess(() -> Component.literal("Structure Harmony rules reloaded from config."), true);
            return 1;
        }));

        dispatcher.register(root);
        dispatcher.register(Commands.literal("sharmony").requires(src -> src.hasPermission(2))
                .redirect(dispatcher.getRoot().getChild("structureharmony")));
    }

    /**
     * placed + moved + unresolved should add up to attempts. cancelled stays at zero unless
     * cancelWhenCannotMove was turned on, and a non-zero cancelled column is the only way this
     * mod can cost you a structure.
     */
    private static int report(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "hook: " + (SHStats.isHookLive() ? "active" : "not seen yet - generate some new chunks")), false);

        Map<String, SHStats.Entry> snapshot = SHStats.snapshot();
        if (snapshot.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No structure placements seen yet this session."), false);
            return 0;
        }

        List<Map.Entry<String, SHStats.Entry>> rows = new ArrayList<>(snapshot.entrySet());
        rows.sort((a, b) -> Integer.compare(b.getValue().attempts().get(), a.getValue().attempts().get()));

        int moved = 0, fitted = 0, unresolved = 0, cancelled = 0;
        source.sendSuccess(() -> Component.literal(
                "structure: attempts = placed + moved + fitted + unresolved"), false);
        for (Map.Entry<String, SHStats.Entry> row : rows) {
            SHStats.Entry e = row.getValue();
            moved += e.moved().get();
            fitted += e.fitted().get();
            unresolved += e.unresolved().get();
            cancelled += e.cancelled().get();
            String line = String.format("%s: %d = %d + %d moved + %d fitted + %d unresolved%s",
                    row.getKey(), e.attempts().get(), e.placed().get(), e.moved().get(), e.fitted().get(),
                    e.unresolved().get(),
                    e.cancelled().get() > 0 ? (" + " + e.cancelled().get() + " CANCELLED") : "");
            source.sendSuccess(() -> Component.literal(line), false);
        }

        final int m = moved, f = fitted, u = unresolved, c = cancelled;
        source.sendSuccess(() -> Component.literal(String.format(
                "totals: %d moved for spacing, %d adjusted to fit the ground, %d unresolved (generated anyway), %d cancelled",
                m, f, u, c)), false);
        return rows.size();
    }

    private static int why(CommandSourceStack source, ResourceLocation id) {
        List<SHStats.Event> history = SHStats.history().stream()
                .filter(e -> e.structure().equals(id.toString()))
                .toList();
        if (history.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    id + " has not been moved or blocked recently (or debug.historySize is 0)."), false);
            return 0;
        }
        int from = Math.max(0, history.size() - 10);
        for (SHStats.Event e : history.subList(from, history.size())) {
            String line = String.format("%s vs %s: shift (%d,%d,%d) -> %.0f block gap, wanted %.0f",
                    e.outcome(), e.other(), e.dx(), e.dy(), e.dz(), e.gap(), e.targetGap());
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return history.size();
    }

    private static int distance(CommandSourceStack source, ResourceLocation a, ResourceLocation b) {
        ServerLevel level = source.getLevel();
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Holder<Structure> ha = registry.getHolder(ResourceKey.create(Registries.STRUCTURE, a)).orElse(null);
        Holder<Structure> hb = registry.getHolder(ResourceKey.create(Registries.STRUCTURE, b)).orElse(null);
        if (ha == null || hb == null) {
            source.sendFailure(Component.literal("Unknown structure id: " + (ha == null ? a : b)));
            return 0;
        }

        SHRules.Parsed rules = SHRules.get();
        long seed = level.getSeed();
        int halfA = StructureSizes.halfSpan(a);
        int halfB = StructureSizes.halfSpan(b);
        int required = rules.requiredDistance(ha, a, hb, b, halfA, halfB);
        int order = rules.compareOrder(ha, a, hb, b, seed);
        String mover = order < 0 ? a.toString() : b.toString();

        source.sendSuccess(() -> Component.literal(String.format(
                "%s (half-span %d%s, padding %d, priority %d)",
                a, halfA, StructureSizes.isKnown(a) ? " measured" : " ASSUMED - not seen yet",
                rules.radius(ha, a), rules.priority(ha, a))), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "%s (half-span %d%s, padding %d, priority %d)",
                b, halfB, StructureSizes.isKnown(b) ? " measured" : " ASSUMED - not seen yet",
                rules.radius(hb, b), rules.priority(hb, b))), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "required centre-to-centre distance: %d blocks (%s, gap %d)",
                required, SHConfig.separationMode.get(), SHConfig.minSeparation.get())), false);

        if (rules.ignored(ha, a) || rules.ignored(hb, b)) {
            source.sendSuccess(() -> Component.literal(
                    "one of these is excluded from spacing - they will never be separated"), false);
        } else if (rules.pinned(ha, a) && rules.pinned(hb, b)) {
            source.sendSuccess(() -> Component.literal(
                    "both are pinned - neither can be moved, so a collision is left alone"), false);
        } else {
            String reason = rules.priority(ha, a) != rules.priority(hb, b) ? "by priority"
                    : (SHConfig.sizeDecidesWhoMoves.get() && halfA != halfB ? "because it is the smaller one"
                    : "by seed tie-break");
            source.sendSuccess(() -> Component.literal(
                    "on a collision, " + mover + " is the one that gets moved aside (" + reason + ")"), false);
        }
        source.sendSuccess(() -> Component.literal(String.format(
                "movement budget: %d blocks configured, hard engine ceiling %d blocks",
                Math.min(rules.maxShift(ha, a), rules.maxShift(hb, b)),
                Displacer.SAFE_CHUNK_RADIUS * 16)), false);
        return required;
    }

    /** The learned footprint cache - the numbers the separation distances are actually built on. */
    private static int sizes(CommandSourceStack source) {
        Map<String, Integer> sizes = StructureSizes.snapshot();
        if (sizes.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "No footprints measured yet. Sizes are learned as structures generate; until then "
                    + SHConfig.assumedHalfSpan.get() + " blocks is assumed for each."), false);
            return 0;
        }
        List<Map.Entry<String, Integer>> rows = new ArrayList<>(sizes.entrySet());
        rows.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
        source.sendSuccess(() -> Component.literal("measured half-spans (blocks), largest first:"), false);
        for (Map.Entry<String, Integer> row : rows) {
            String line = row.getKey() + ": " + row.getValue();
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return rows.size();
    }

    private SHCommand() {}
}
