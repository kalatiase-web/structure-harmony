# Structure Harmony — Forge 1.20.1

Stops structures from different mods (and vanilla) generating on top of each other. When two
collide, the higher priority one stays where it is and the lower priority one is **moved aside**
until they are at least 100 blocks apart. Structures are never deleted to resolve a conflict.

**[Download the latest jar](https://github.com/kalatiase-web/structure-harmony/releases/latest)** — drop it in `mods/`. Config is written to `config/structureharmony-common.toml` on first launch.

---

## Why "move it" and not "block it"

Every spacing mod I know of, including the first version of this one, resolves a collision by
cancelling the loser. That cannot be tuned into something acceptable, and here is why:

Vanilla gives each structure **exactly one candidate chunk per spacing cell**. For villages the
cell is 34×34 chunks. Cancel that candidate and the structure does not slide over to a nearby
chunk — the next chance is a whole spacing period away, so it is gone from the *entire region*.
Now multiply that by ~40 vanilla structure sets plus whatever a modpack adds: something
higher-priority is in range most of the time, so cancellations stack up and structures start
disappearing. That is the "it stopped my structures generating" failure, and it is a design
flaw, not a tuning problem.

So this version does not cancel. It translates the structure's pieces. `StructurePiece#move` is
an operation vanilla performs on itself — `JigsawPlacement` shifts whole piece trees, and
`PoolElementStructurePiece` overrides `move` to carry the jigsaw position and junctions along —
so villages, outposts and most modded structures translate intact rather than coming apart.

The move happens during `STRUCTURE_STARTS`, which is before `NOISE`. That ordering is free
value: `Beardifier` reads structure starts during noise generation, so any structure declaring
terrain adaptation gets **vanilla's own** terrain shaping applied at its new position.

## How a collision is resolved

1. Find every higher-priority structure candidate within the required distance, computed
   analytically from each structure's own `StructurePlacement` plus the world seed — not from
   "what happens to be generated already", so the result does not depend on which direction you
   walked.
2. Work out the direction straight away from the conflict, then try evenly spaced directions
   outward from it, smallest move first.
3. Accept the first move that clears every conflict, keeps the structure inside its own biome
   list, and needs no absurd vertical correction.
4. Apply a vertical correction from the ground-height difference between the old and new spot,
   so a structure moved sideways still meets the ground.
5. If nothing clears it, apply the best partial improvement and **generate the structure
   anyway**, counted as `unresolved`.

Priority ties are broken by a seed-derived hash, so the outcome is stable per world but not
biased toward whichever mod id sorts first.

---

## The one hard limit, stated plainly

`ChunkGenerator#createReferences` only looks **8 chunks** around a chunk when deciding which
structures that chunk has to place. A structure whose bounding box ends up further than that
from the chunk that owns it is never placed by the outer chunks — it generates cut in half.

So the ceiling on displacement is about 112 blocks minus the structure's own radius:

| Structure size | Roughly how far it can move |
|---|---|
| Small (fits in a chunk or two) | ~96 blocks — the full target is reachable |
| Medium (outpost, small ruin) | ~60–80 blocks |
| Large (village, mansion, monument) | ~16–32 blocks, sometimes nothing |

There is no way around this; the chunks beyond that radius are not even loaded at that
generation stage. What it means in practice: two small structures get properly separated, a
small structure next to a village gets pushed clear, but **two large structures on top of each
other mostly cannot be fixed** — they will be reported as `unresolved` and both will generate.
Raising `maxShift` past 112 does nothing.

If you would rather lose a structure than have an unresolved overlap, set
`cancelWhenCannotMove = true`. It is off by default on purpose.

---

## Config — `config/structureharmony-common.toml`

All distances are in **blocks**. Selector syntax, most specific wins:

```
"minecraft:village_plains=140"   exact structure
"@yourmod=120"                   every structure from a mod
"#yourmod:big_stuff=160"         every structure in a structure tag
"*=100"                          fallback
```

Key settings:

- `minSeparation = 100` — minimum centre-to-centre distance between two different structures.
- `radiusOverrides` — per-structure room requirement; required distance is
  `max(minSeparation, radiusA + radiusB) + extraBuffer`.
- `priorityOverrides` — higher stays put, lower moves. Vanilla gets −10 while
  `preferModdedOverVanilla` is on, so **your structures hold their ground and vanilla moves**.
- `pairOverrides` — `"a|b=blocks"` for one pair; `=0` lets a pair sit together (villages and
  outposts ship like this, since vanilla intends them to be close).
- `maxShift = 96` — displacement budget, capped by the engine limit above.
- `followTerrainHeight = true` / `maxVerticalShift = 24` — vertical correction after a sideways move.
- `keepBiome = true` — never nudge a structure into a biome its own biome list rejects.
- `cancelWhenCannotMove = false` — leave this off.
- `ignored` / `pinned` — take no part in spacing / never moved but others move away. Strongholds,
  mineshafts, buried treasure, fortresses and end cities are already exempt (buried treasure
  *must* stay put or its maps break).

Datapack tags: `#structureharmony:no_spacing`, `#structureharmony:never_reject`.

---

## Commands (permission level 2)

```
/structureharmony report                 per-structure attempts = placed + moved + unresolved
/structureharmony why <structure>        last 10 moves, with shift applied and distance achieved
/structureharmony distance <a> <b>       required gap, which one moves, and its movement budget
/structureharmony reset                  clear counters
/structureharmony reload                 re-read config without restarting
```
`/sharmony` is an alias.

`report` is the honesty check: `attempts` should equal `placed + moved + unresolved`, and the
`cancelled` column should stay absent. A non-zero `cancelled` is the only way this mod can cost
you a structure, and it only happens if you turned `cancelWhenCannotMove` on.

---

## Build

```
./gradlew build          # jar lands in build/libs/
./gradlew runClient      # test in-game
```
JDK 17, Forge 47.3.0 / MC 1.20.1. CI builds every push, boots a dedicated server, generates
spawn chunks, fails on any mixin error or resolver exception, and attaches the jar to a release.

---

## Known limits

- **Already-generated chunks are untouched.** Only newly generated chunks are affected.
- **Large structures barely move.** See the table above — this is an engine limit.
- `/locate structure` reports the position of the chunk that *owns* a structure, so after a
  displacement it can be off by up to the shift distance.
- **Biome matching is approximate.** One biome sample per candidate chunk at sea level. It errs
  toward allowing placement rather than moving things needlessly.
- **No cascade resolution.** If A moves away from B and B later moves too, A's move was computed
  against B's original spot. Single pass, by design — resolving cascades would reintroduce
  generation-order dependence.
- **Mixin targets** (`ChunkGenerator#createStructures`, `StructureManager#setStartForStructure`)
  are `require = 1`: if another mod overwrites one, the game fails at startup naming the method
  rather than loading and silently doing nothing.
- **Verified**: compiles, mixins apply, server boots and generates terrain, resolver does not
  throw. **Not verified**: how the separation feels in a populated modpack. That needs play-testing.
