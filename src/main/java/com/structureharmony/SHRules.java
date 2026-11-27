package com.structureharmony;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parses the config selector lists once and answers "what is the radius / priority / margin
 * for this structure". Specificity order: exact id > tag > namespace > "*" > hard default.
 */
public final class SHRules {

    private static volatile Parsed parsed;

    public static void invalidate() { parsed = null; }

    public static Parsed get() {
        Parsed p = parsed;
        if (p == null) {
            synchronized (SHRules.class) {
                p = parsed;
                if (p == null) {
                    p = new Parsed();
                    parsed = p;
                }
            }
        }
        return p;
    }

    /** One "selector=value" rule. */
    private record Rule(Kind kind, String key, int value) {
        enum Kind { EXACT, TAG, NAMESPACE, ANY }

        boolean matches(Holder<Structure> holder, ResourceLocation id) {
            return switch (kind) {
                case EXACT -> id.toString().equals(key);
                case NAMESPACE -> id.getNamespace().equals(key);
                case TAG -> {
                    ResourceLocation tagId = ResourceLocation.tryParse(key);
                    yield tagId != null && holder != null
                            && holder.is(TagKey.create(Registries.STRUCTURE, tagId));
                }
                case ANY -> true;
            };
        }

        int specificity() {
            return switch (kind) { case EXACT -> 3; case TAG -> 2; case NAMESPACE -> 1; case ANY -> 0; };
        }
    }

    private static List<Rule> parseRules(List<? extends String> raw) {
        List<Rule> out = new ArrayList<>();
        for (String entry : raw) {
            int eq = entry.lastIndexOf('=');
            if (eq <= 0) continue;
            String sel = entry.substring(0, eq).trim();
            int value;
            try { value = Integer.parseInt(entry.substring(eq + 1).trim()); }
            catch (NumberFormatException e) { continue; }
            out.add(toRule(sel, value));
        }
        return out;
    }

    private static Rule toRule(String sel, int value) {
        if (sel.equals("*")) return new Rule(Rule.Kind.ANY, "*", value);
        if (sel.startsWith("#")) return new Rule(Rule.Kind.TAG, sel.substring(1), value);
        if (sel.startsWith("@")) return new Rule(Rule.Kind.NAMESPACE, sel.substring(1), value);
        if (!sel.contains(":")) return new Rule(Rule.Kind.NAMESPACE, sel, value);
        return new Rule(Rule.Kind.EXACT, sel, value);
    }

    private static int resolve(List<Rule> rules, Holder<Structure> holder, ResourceLocation id, int fallback) {
        int best = fallback;
        int bestSpec = -1;
        for (Rule r : rules) {
            int spec = r.specificity();
            if (spec <= bestSpec) continue;
            if (r.matches(holder, id)) { best = r.value(); bestSpec = spec; }
        }
        return best;
    }

    private static boolean matchesAny(List<Rule> rules, Holder<Structure> holder, ResourceLocation id) {
        for (Rule r : rules) if (r.matches(holder, id)) return true;
        return false;
    }

    private static List<Rule> parseSelectors(List<? extends String> raw) {
        List<Rule> out = new ArrayList<>();
        for (String s : raw) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(toRule(t, 1));
        }
        return out;
    }

    /** Snapshot of the parsed config. Rebuilt on config reload. */
    public static final class Parsed {
        private final List<Rule> radius          = parseRules(SHConfig.radiusOverrides.get());
        private final List<Rule> priority        = parseRules(SHConfig.priorityOverrides.get());
        private final List<Rule> spacingIgnored  = parseSelectors(SHConfig.spacingIgnored.get());
        private final List<Rule> blendIgnored    = parseSelectors(SHConfig.blendIgnored.get());
        private final List<Rule> marginRules     = parseRules(SHConfig.marginOverrides.get());
        private final List<Rule> raiseRules      = parseRules(SHConfig.maxRaiseOverrides.get());
        private final List<Rule> lowerRules      = parseRules(SHConfig.maxLowerOverrides.get());
        private final List<Rule> anchorRules     = parseRules(SHConfig.anchorOffsetOverrides.get());
        private final List<PairRule> pairs       = parsePairs(SHConfig.pairOverrides.get());

        private final Map<String, Integer> radiusCache   = new ConcurrentHashMap<>();
        private final Map<String, Integer> priorityCache = new ConcurrentHashMap<>();

        private record PairRule(Rule a, Rule b, int distance) {}

        private static List<PairRule> parsePairs(List<? extends String> raw) {
            List<PairRule> out = new ArrayList<>();
            for (String entry : raw) {
                int eq = entry.lastIndexOf('=');
                if (eq <= 0) continue;
                String sel = entry.substring(0, eq);
                int bar = sel.indexOf('|');
                if (bar <= 0) continue;
                int dist;
                try { dist = Integer.parseInt(entry.substring(eq + 1).trim()); }
                catch (NumberFormatException e) { continue; }
                out.add(new PairRule(toRule(sel.substring(0, bar).trim(), dist),
                                     toRule(sel.substring(bar + 1).trim(), dist), dist));
            }
            return out;
        }

        public int radius(@Nullable Holder<Structure> holder, ResourceLocation id) {
            Integer cached = radiusCache.get(id.toString());
            if (cached != null) return cached;
            int v = Math.max(0, resolve(radius, holder, id, SHConfig.defaultRadius.get()));
            radiusCache.put(id.toString(), v);
            return v;
        }

        public int priority(@Nullable Holder<Structure> holder, ResourceLocation id) {
            Integer cached = priorityCache.get(id.toString());
            if (cached != null) return cached;
            int base = (SHConfig.preferModdedOverVanilla.get() && id.getNamespace().equals("minecraft")) ? -10 : 0;
            int v = resolve(priority, holder, id, base);
            priorityCache.put(id.toString(), v);
            return v;
        }

        public boolean spacingIgnored(@Nullable Holder<Structure> holder, ResourceLocation id) {
            if (holder != null && holder.is(SHTags.NO_SPACING)) return true;
            return matchesAny(spacingIgnored, holder, id);
        }

        public boolean neverReject(@Nullable Holder<Structure> holder) {
            return holder != null && holder.is(SHTags.NEVER_REJECT);
        }

        public boolean blendIgnored(@Nullable Holder<Structure> holder, ResourceLocation id) {
            if (holder != null && holder.is(SHTags.NO_SMOOTHING)) return true;
            return matchesAny(blendIgnored, holder, id);
        }

        public int margin(@Nullable Holder<Structure> h, ResourceLocation id) {
            return Math.max(0, resolve(marginRules, h, id, SHConfig.margin.get()));
        }

        public int maxRaise(@Nullable Holder<Structure> h, ResourceLocation id) {
            return Math.max(0, resolve(raiseRules, h, id, SHConfig.maxRaise.get()));
        }

        public int maxLower(@Nullable Holder<Structure> h, ResourceLocation id) {
            return Math.max(0, resolve(lowerRules, h, id, SHConfig.maxLower.get()));
        }

        public int anchorOffset(@Nullable Holder<Structure> h, ResourceLocation id) {
            return resolve(anchorRules, h, id, SHConfig.anchorOffset.get());
        }

        /** Required chunk distance between two structures, or -1 when no pair override applies. */
        public int pairDistance(@Nullable Holder<Structure> ha, ResourceLocation a,
                                @Nullable Holder<Structure> hb, ResourceLocation b) {
            int best = -1;
            int bestSpec = -1;
            for (PairRule p : pairs) {
                boolean forward = p.a().matches(ha, a) && p.b().matches(hb, b);
                boolean reverse = p.a().matches(hb, b) && p.b().matches(ha, a);
                if (!forward && !reverse) continue;
                int spec = p.a().specificity() + p.b().specificity();
                if (spec > bestSpec) { bestSpec = spec; best = p.distance(); }
            }
            return best;
        }

        /**
         * Total, deterministic order over structures. The structure with the LOWER order yields.
         * Seed is mixed into the tie-break so equal-priority collisions are not decided
         * alphabetically (which would always favour the same mod).
         */
        public int compareOrder(@Nullable Holder<Structure> ha, ResourceLocation a,
                               @Nullable Holder<Structure> hb, ResourceLocation b, long seed) {
            int cmp = Integer.compare(priority(ha, a), priority(hb, b));
            if (cmp != 0) return cmp;
            long ta = tieBreak(a, seed);
            long tb = tieBreak(b, seed);
            cmp = Long.compare(ta, tb);
            if (cmp != 0) return cmp;
            return a.toString().compareTo(b.toString());
        }

        private static long tieBreak(ResourceLocation id, long seed) {
            long h = seed ^ (id.toString().hashCode() * 0x9E3779B97F4A7C15L);
            h ^= (h >>> 33); h *= 0xFF51AFD7ED558CCDL;
            h ^= (h >>> 33); h *= 0xC4CEB9FE1A85EC53L;
            return h ^ (h >>> 33);
        }
    }

    private SHRules() {}
}
