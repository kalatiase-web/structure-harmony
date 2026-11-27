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
 * Parses the config selector lists once and answers "what is the radius / priority / shift limit
 * for this structure". Specificity order: exact id > tag > namespace > "*" > hard default.
 * All distances are in blocks.
 */
public final class SHRules {

    private static volatile Parsed parsed;

    public static void invalidate() { parsed = null; }

    public static Parsed get() {
        Parsed p = parsed;
        if (p == null) {
            synchronized (SHRules.class) {
                p = parsed;
                if (p == null) { p = new Parsed(); parsed = p; }
            }
        }
        return p;
    }

    private record Rule(Kind kind, String key, int value) {
        enum Kind { EXACT, TAG, NAMESPACE, ANY }

        boolean matches(@Nullable Holder<Structure> holder, ResourceLocation id) {
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

    private static Rule toRule(String sel, int value) {
        if (sel.equals("*")) return new Rule(Rule.Kind.ANY, "*", value);
        if (sel.startsWith("#")) return new Rule(Rule.Kind.TAG, sel.substring(1), value);
        if (sel.startsWith("@")) return new Rule(Rule.Kind.NAMESPACE, sel.substring(1), value);
        if (!sel.contains(":")) return new Rule(Rule.Kind.NAMESPACE, sel, value);
        return new Rule(Rule.Kind.EXACT, sel, value);
    }

    private static List<Rule> parseRules(List<? extends String> raw) {
        List<Rule> out = new ArrayList<>();
        for (String entry : raw) {
            int eq = entry.lastIndexOf('=');
            if (eq <= 0) continue;
            int value;
            try { value = Integer.parseInt(entry.substring(eq + 1).trim()); }
            catch (NumberFormatException e) { continue; }
            out.add(toRule(entry.substring(0, eq).trim(), value));
        }
        return out;
    }

    private static List<Rule> parseSelectors(List<? extends String> raw) {
        List<Rule> out = new ArrayList<>();
        for (String s : raw) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(toRule(t, 1));
        }
        return out;
    }

    private static int resolve(List<Rule> rules, @Nullable Holder<Structure> holder,
                               ResourceLocation id, int fallback) {
        int best = fallback;
        int bestSpec = -1;
        for (Rule r : rules) {
            int spec = r.specificity();
            if (spec <= bestSpec) continue;
            if (r.matches(holder, id)) { best = r.value(); bestSpec = spec; }
        }
        return best;
    }

    private static boolean matchesAny(List<Rule> rules, @Nullable Holder<Structure> holder, ResourceLocation id) {
        for (Rule r : rules) if (r.matches(holder, id)) return true;
        return false;
    }

    public static final class Parsed {
        private final List<Rule> radius    = parseRules(SHConfig.radiusOverrides.get());
        private final List<Rule> priority  = parseRules(SHConfig.priorityOverrides.get());
        private final List<Rule> shift     = parseRules(SHConfig.maxShiftOverrides.get());
        private final List<Rule> ignored   = parseSelectors(SHConfig.ignored.get());
        private final List<Rule> pinned    = parseSelectors(SHConfig.pinned.get());
        private final List<PairRule> pairs = parsePairs(SHConfig.pairOverrides.get());

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

        /** Radius in blocks. */
        public int radius(@Nullable Holder<Structure> holder, ResourceLocation id) {
            Integer cached = radiusCache.get(id.toString());
            if (cached != null) return cached;
            int v = Math.max(0, resolve(radius, holder, id, 0));
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

        /** Largest displacement in blocks this structure is allowed, before engine limits. */
        public int maxShift(@Nullable Holder<Structure> holder, ResourceLocation id) {
            return Math.max(0, resolve(shift, holder, id, SHConfig.maxShift.get()));
        }

        public boolean ignored(@Nullable Holder<Structure> holder, ResourceLocation id) {
            if (holder != null && holder.is(SHTags.NO_SPACING)) return true;
            return matchesAny(ignored, holder, id);
        }

        /** Never moved itself, but other structures still move away from it. */
        public boolean pinned(@Nullable Holder<Structure> holder, ResourceLocation id) {
            if (holder != null && holder.is(SHTags.NEVER_REJECT)) return true;
            return matchesAny(pinned, holder, id);
        }

        /** Required centre-to-centre distance in blocks between two structures. */
        public int requiredDistance(@Nullable Holder<Structure> ha, ResourceLocation a,
                                    @Nullable Holder<Structure> hb, ResourceLocation b) {
            int bestSpec = -1;
            int pair = -1;
            for (PairRule p : pairs) {
                boolean forward = p.a().matches(ha, a) && p.b().matches(hb, b);
                boolean reverse = p.a().matches(hb, b) && p.b().matches(ha, a);
                if (!forward && !reverse) continue;
                int spec = p.a().specificity() + p.b().specificity();
                if (spec > bestSpec) { bestSpec = spec; pair = p.distance(); }
            }
            if (pair >= 0) return pair;
            int sum = radius(ha, a) + radius(hb, b);
            return Math.max(SHConfig.minSeparation.get(), sum) + SHConfig.extraBuffer.get();
        }

        /**
         * Total, deterministic order. The structure with the LOWER order is the one that moves.
         * The seed is mixed into the tie-break so equal priorities are not settled alphabetically,
         * which would always favour whichever mod id happens to sort first.
         */
        public int compareOrder(@Nullable Holder<Structure> ha, ResourceLocation a,
                                @Nullable Holder<Structure> hb, ResourceLocation b, long seed) {
            int cmp = Integer.compare(priority(ha, a), priority(hb, b));
            if (cmp != 0) return cmp;
            cmp = Long.compare(tieBreak(a, seed), tieBreak(b, seed));
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
