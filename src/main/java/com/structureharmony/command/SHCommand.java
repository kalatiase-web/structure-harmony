package com.structureharmony.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.structureharmony.SHConfig;
import com.structureharmony.SHRules;
import com.structureharmony.SHStats;
import com.structureharmony.StructureHarmony;
import com.structureharmony.spacing.SpacingEngine;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
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

    /** Proof that structures are still generating, and how much they are losing. */
    private static int report(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(String.format(
                "hooks: spacing %s, blending %s",
                SHStats.isSpacingHookLive() ? "active" : "not seen yet",
                SHStats.isBlendHookLive() ? "active" : "not seen yet")), false);

        Map<String, SHStats.Entry> snapshot = SHStats.snapshot();
        if (snapshot.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "No structure placements seen yet this session. Generate some new chunks first."), false);
            return 0;
        }

        List<Map.Entry<String, SHStats.Entry>> rows = new ArrayList<>(snapshot.entrySet());
        rows.sort((a, b) -> Integer.compare(b.getValue().attempts().get(), a.getValue().attempts().get()));

        source.sendSuccess(() -> Component.literal("structure: placed / attempts (rejected, fail-open)"), false);
        for (Map.Entry<String, SHStats.Entry> row : rows) {
            SHStats.Entry e = row.getValue();
            String line = String.format("%s: %d / %d (rejected %d, fail-open %d)",
                    row.getKey(), e.placed().get(), e.attempts().get(), e.rejected().get(), e.failOpen().get());
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return rows.size();
    }

    private static int why(CommandSourceStack source, ResourceLocation id) {
        List<SHStats.Rejection> history = SHStats.history().stream()
                .filter(r -> r.structure().equals(id.toString()))
                .toList();
        if (history.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    id + " has not been rejected recently (or debug.historySize is 0)."), false);
            return 0;
        }
        int from = Math.max(0, history.size() - 10);
        for (SHStats.Rejection r : history.subList(from, history.size())) {
            String line = String.format("chunk %d,%d (block %d,%d): yielded to %s at %.1f chunks, needs %.0f",
                    r.chunkX(), r.chunkZ(), r.chunkX() * 16, r.chunkZ() * 16,
                    r.yieldedTo(), r.distance(), r.required());
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return history.size();
    }

    private static int distance(CommandSourceStack source, ResourceLocation a, ResourceLocation b) {
        ServerLevel level = source.getLevel();
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Holder<Structure> ha = registry.getHolder(net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE, a)).orElse(null);
        Holder<Structure> hb = registry.getHolder(net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE, b)).orElse(null);
        if (ha == null || hb == null) {
            source.sendFailure(Component.literal("Unknown structure id: " + (ha == null ? a : b)));
            return 0;
        }

        SHRules.Parsed rules = SHRules.get();
        long seed = level.getSeed();
        int pair = rules.pairDistance(ha, a, hb, b);
        int ra = rules.radius(ha, a);
        int rb = rules.radius(hb, b);
        int required = pair >= 0 ? pair : ra + rb + SHConfig.extraBuffer.get();
        int order = rules.compareOrder(ha, a, hb, b, seed);
        String loser = order < 0 ? a.toString() : b.toString();

        source.sendSuccess(() -> Component.literal(String.format(
                "%s (radius %d, priority %d) vs %s (radius %d, priority %d)",
                a, ra, rules.priority(ha, a), b, rb, rules.priority(hb, b))), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "required distance: %d chunks (%d blocks)%s", required, required * 16,
                pair >= 0 ? " [pair override]" : "")), false);
        source.sendSuccess(() -> Component.literal(
                rules.spacingIgnored(ha, a) || rules.spacingIgnored(hb, b)
                        ? "one of these is excluded from spacing - they will never be separated"
                        : "on a collision, " + loser + " is the one that yields"), false);
        StructureHarmony.LOG.debug("[StructureHarmony] /distance {} {} -> {}", a, b, required);
        return required;
    }

    private SHCommand() {}
}
