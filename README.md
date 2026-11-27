# Structure Harmony — Forge 1.20.1

Two features, no overlap with each other:

1. **Spacing** — stops structures from different mods (and vanilla) generating on top of or right next to each other.
2. **Terrain blending** — reshapes the ground around a structure so it meets the build, instead of floating over a slope or being swallowed by a mountainside. Terrain smoothing only: no support pillars, no walls extended down.

Built with Mixins because Forge 1.20.1 has no event for structure placement.

---

## Why this one actually spaces things out, and still spawns your structures

Those two complaints come from the same design mistake. Most spacing mods work by raising every structure's `spacing`/`separation` in its placement config, which does two bad things at once: it thins out *all* structures globally (your 1-in-200-chunks tower now appears once a region), and it still does nothing about overlap, because spacing is per-structure — a village and a modded tower have completely independent grids and happily land on the same chunk.

This mod does neither. It hooks `StructureManager#setStartForStructure`, the single point every structure passes through, and rejects **one candidate position** when it collides with something that outranks it. The structure keeps every other cell of its own placement grid, so its overall density is barely touched. Nothing is ever disabled, excluded from a biome, or removed from a structure set.

On top of that:

| Problem in other mods | What this does |
|---|---|
| Structures lost to "phantom" neighbours that would never have generated there | `checkBiomes` only yields to a neighbour whose biome list matches the biome at that spot |
| Results depend on which direction the player walked | Neighbour positions are computed **analytically** from each structure's own `StructurePlacement` + world seed, never from "what happens to be generated already". Same seed, same world, every time |
| Two structures both yield to each other and neither spawns | Resolution uses a **total order** (priority → seed-mixed hash → id). Exactly one side yields, always |
| A structure gets quietly starved to zero | Fail-open valve: once a structure has lost more than `failOpenRatio` of its candidates, it stops being rejected at all |
| No way to tell whether it's working | `/structureharmony report` and `/structureharmony why <id>` show attempts, placements, rejections and the exact chunk coords |
| Vanilla wins every fight | `preferModdedOverVanilla = true` by default: vanilla structures yield to modded ones |
| Progression-critical structures get moved | Strongholds, buried treasure (must match the map!), fortresses and end cities are exempt by default |

---

## Build

```
./gradlew build          # jar lands in build/libs/
./gradlew runClient      # test in-game
```
Requires JDK 17. Targets Forge 47.3.0 / MC 1.20.1 — bump `forge_version` in `gradle.properties` if your pack uses another build.

---

## Config — `config/structureharmony-common.toml`

Every `*Overrides` list shares one selector syntax, most specific wins:

```
"minecraft:village_plains=10"   exact structure
"@yourmod=8"                    every structure from a mod
"#yourmod:big_stuff=14"         every structure in a structure tag
"*=6"                           fallback
```

### `[spacing]`
- `defaultRadius = 4` — required gap between two structures is `radiusA + radiusB + extraBuffer` **in chunks**. Two defaults = 8 chunks = 128 blocks.
- `radiusOverrides` — per-structure. Mansion 12, villages 7, ruined portals 2 by default.
- `priorityOverrides` — higher wins the collision, the lower one yields. Vanilla gets −10 while `preferModdedOverVanilla` is on.
- `pairOverrides` — `"a|b=distance"` for one specific pair. `=0` lets a pair overlap freely (villages + outposts ship like this, since vanilla intends them to be close).
- `ignored` — takes no part in spacing at all.
- `failOpenRatio = 0.6` — set to `1.0` to disable the safety valve and enforce spacing strictly.

### `[blending]`
- `margin = 10` — how far the ground is feathered back to natural height (smoothstep, so no visible step or plateau rim).
- `anchorOffset = 0` — ground level is `box.minY + anchorOffset`. **This is the knob to turn first.** `-1`/`-2` if structures sit one course proud of the dirt, `+1` if the bottom row gets buried.
- `maxRaise = 10`, `maxLower = 20` — caps outside the footprint, so a cliff never gets levelled for 40 blocks.
- `clearInsideFully = true` — inside the footprint, `maxLower` is ignored and overhangs above the build are taken out too. This is what un-buries structures on mountains.
- `surfaceStructuresOnly = true` — only `SURFACE_STRUCTURES`-step structures, which keeps the smoother away from mineshafts and fortresses.
- `waterMode = SKIP_WATER_COLUMNS` — oceans left alone by default.
- `respectOtherStructures = true` — ground inside another structure's bounding box is never touched.

### Datapack tags (override these instead of the config if you ship a pack)
`#structureharmony:no_spacing` · `#structureharmony:never_reject` · `#structureharmony:no_smoothing` · `#structureharmony:terrain` · `#structureharmony:overgrowth` · `#structureharmony:never_replace`

---

## Commands (permission level 2)

```
/structureharmony report                 per-structure placed / attempts / rejected / fail-open
/structureharmony why <structure>        last 10 rejections with chunk + block coords
/structureharmony distance <a> <b>       effective required gap and which one yields
/structureharmony reset                  clear counters
/structureharmony reload                 re-read the config without restarting
```
`/sharmony` is an alias.

### Checking it works
1. New world, fly around generating chunks (or `/forceload`).
2. `/structureharmony report` — `placed` climbing for your structures means they're generating; `rejected` climbing means overlaps are being caught.
3. If one of your structures looks too rare: `/structureharmony why yourmod:thing` tells you exactly what it keeps yielding to. Then drop that neighbour's radius, raise your priority, or add a `pairOverrides` entry.

---

## Known limits — worth reading before you ship this in a pack

- **Already-generated chunks are untouched.** Both features only run as chunks generate. Existing worlds keep their existing overlaps.
- **Biome matching is approximate.** It samples the biome at sea level in the neighbour's chunk, not the exact position the neighbour would have anchored at. It errs toward *allowing* placement.
- **No cascade resolution.** If A yields to B and B later yields to C, A's rejection was unnecessary. Single-pass, by design — resolving cascades would reintroduce generation-order dependence.
- **The fail-open valve is order-dependent** by nature (it counts what actually happened). Counters are per-seed and reset on restart. Set `failOpenRatio = 1.0` for fully deterministic spacing.
- **Blending uses the structure's bounding box**, which for a build with a deep basement extends well below the visible floor. That's what `anchorOffset` and the per-structure overrides are for; `#structureharmony:no_smoothing` is the escape hatch.
- **Modded ground blocks**: new ground is built by copying the blocks already in the column, so it adapts to any biome. *Carving through* an unrecognised block is refused on purpose, so another mod's build is never eaten — if a modded stone won't carve, add it to `#structureharmony:terrain` (preferred) or set `carveUnknownBlocks = true`.
- **Mixin targets**: `ChunkGenerator#createStructures`, `ChunkGenerator#applyBiomeDecoration`, `StructureManager#setStartForStructure`. All three are soft (`defaultRequire: 0`) — if another mod replaces one, that feature goes quiet instead of crashing the game. Worth a sanity check against other worldgen-heavy mods in your pack.
- **Untested at runtime.** This has not been compiled against the Forge/MC artifacts or launched in-game yet — it needs a `./gradlew build` pass on your machine, and I'd expect a mapping or signature nit or two on first compile.
