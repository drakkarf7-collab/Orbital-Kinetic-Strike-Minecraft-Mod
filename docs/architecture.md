# Architecture v2 — "Orbital Kinetic Strike" Mod

> Technical document accompanying `specification.md` (v2).
> The specification defines **what**, this document defines **how**.
> Starting point: the v1 codebase in `orbital-strike/`. We **evolve** it (see §14, migration).
> Target: Fabric · Minecraft 1.21.1 · Java 21 · Yarn `1.21.1+build.3` · Loom 1.14 · Gradle 9.2 · package `fr.orbitalstrike`.

---

## 1. Overview

```
                         ┌───────────────────────────── SERVER ─────────────────────────────┐
  RequestStrike (C2S) ──►│ SkillRegistry ─► KineticStrikeSkill.validate()  (permissions,    │
                         │                                                  claims, cooldown,│
                         │                                                  limits)         │
                         │ StrikeManager ─► StrikeSession (state machine, durations in ticks)│
                         │      │                ├─► StrikeTimeline (data/…/strike_timeline/*.json)
                         │      │                ├─► ImpactDamage (impact + shockwave)      │
                         │      │                └─► CraterPipeline                         │
                         │      │                      terrain sample (main thread)         │
                         │      │                      ─► CraterPlanner (async)             │
                         │      │                      ─► RegionSnapshot (undo, on disk)    │
                         │      │                      ─► SectionWriter (per-tick budget)   │
                         │      │                      ─► EjectaEntity · TreeFeller · Cooling
                         │      └─► StrikeLog                                               │
                         └─────────────┬────────────────────────────────────────────────────┘
                                       │ S2C : CinematicStart, Phase, SkyStreak, Impact, Shockwave, FxEvent, CooldownSync
                         ┌─────────────▼───────────────────────────── CLIENT ────────────────┐
                         │ CinematicPlayer ◄── assets/…/cinematics/kinetic_strike.json       │
                         │   ├─ SPACE shots ─► SpaceRenderer3D (scene, models, planet shaders)
                         │   └─ WORLD shots ─► WorldCamera (detached camera) + WorldFx       │
                         │ FxPipeline (HDR: bloom, god rays, blur, flare, tone mapping, grain)
                         │ WorldFx: SkyStreak, ImpactFlash, Decals, DustRing, SmokeColumn    │
                         │ AudioDirector: EFX low-pass, sound delay, near/far variants       │
                         │ TargetingHud + TerrainDecal · UI kit (menus, config) · Accessibility
                         └───────────────────────────────────────────────────────────────────┘
```

**Principles (carried over from v1, strengthened):**
1. **The server decides**, the client stages the presentation. The client never modifies the world.
2. **Durations come from the server.** Client cinematics are described in normalized time (0 to 1 per shot) and stretch to match the received duration, staying synchronized regardless of speed.
3. **Data-driven staging.** Camera paths, events, and transitions are described in JSON and hot-reloaded (F3+T). Staging can be tuned without recompiling.
4. **Everything heavy is asynchronous or spread across ticks**: crater computation runs off the main thread, block writing follows a per-tick time budget, and the undo snapshot is compressed in a background task.
5. **A compatibility layer isolates optional mods** (Iris, Sodium, Mod Menu, permissions, claims): zero hard dependencies.

---

## 2. Directory Layout

```
orbital-strike/
├── build.gradle · gradle.properties · settings.gradle
├── art/                                   (sources, excluded from .jar)
│   ├── models/*.bbmodel                   detonator, ring, breech, needle, spire
│   ├── audio/                             projects and .wav renders before .ogg conversion
│   └── textures/                          high-resolution sources, generation scripts
├── tools/
│   ├── generate_textures.py               (v1, kept for procedural textures)
│   └── export_models.md                   Blockbench -> OBJ export procedure
├── src/main/java/fr/orbitalstrike/
│   ├── OrbitalStrike.java
│   ├── config/        ModConfig, ConfigManager, ServerLimits
│   ├── registry/      ModBlocks, ModItems, ModBlockEntities, ModEntities, ModSounds,
│   │                  ModParticles, ModDamageTypes, ModAttachments, ModItemGroup
│   ├── item/          OrbitalDetonatorItem, TungstenShardItem
│   ├── block/         TungstenBlock, SpireSegmentBlock, SpireBlockEntity,
│   │                  MoltenRockBlock (STAGE 0..3), CharredLogBlock, AshLayerBlock
│   ├── entity/        EjectaEntity
│   ├── skill/         Skill, SkillRegistry, SkillContext, KineticStrikeSkill
│   ├── strike/        StrikePhase, StrikeTimeline, TimelineLoader, StrikeSession,
│   │                  StrikeManager, ImpactDamage, StrikeLog
│   ├── crater/
│   │   ├── CraterProfile, CraterShape         (pure math, tested — enriched v1)
│   │   ├── TerrainSample                      (captures surface heights and blocks)
│   │   ├── CraterPlanner, CraterPlan          (async -> per-section operation list)
│   │   ├── pass/  Excavate, Terraces, CentralUplift, Skin, EjectaBlanket,
│   │   │          Scorch, TreeFell, Fluids, Spire, MoltenCore
│   │   ├── SectionWriter                      (direct writes into chunk sections)
│   │   ├── RegionSnapshot, UndoStore          (undo support)
│   │   └── CraterPipeline                     (orchestration and budgeting)
│   ├── protection/    ProtectionCheck (+ optional providers)
│   ├── cooldown/      CooldownStore
│   ├── network/       Payloads, ModNetworking, ProtocolVersion
│   ├── command/       StrikeCommand (strike, undo, preview, log, reload, debug)
│   └── compat/        PermissionsCompat, ClaimsCompat
├── src/main/resources/
│   ├── fabric.mod.json · orbital_strike.mixins.json
│   └── data/orbital_strike/
│       ├── strike_timeline/kinetic_strike.json   phase durations (server-side)
│       ├── damage_type/ · recipe/ · loot_table/ · tags/
│       └── structure/                            gametest templates
├── src/client/java/fr/orbitalstrike/client/
│   ├── OrbitalStrikeClient.java
│   ├── ClientConfig, ClientState, QualityPreset
│   ├── render/
│   │   ├── gl/        HdrFramebuffer, ShaderRegistry, FullscreenQuad, GlStateSnapshot
│   │   ├── mesh/      ObjLoader, Mesh (VertexBuffer), MeshLibrary, Icosphere
│   │   ├── pipeline/  FxPipeline, BloomPass, GodRaysPass, MotionBlurPass,
│   │   │              LensFlarePass, ChromaticPass, TonemapPass, FilmGrainPass
│   │   ├── space/     SpaceRenderer3D, SpaceScene, Planet, GasGiant, AcceleratorRing,
│   │   │              Breech, Needle, PlasmaSheath, Starfield, SunFlare
│   │   └── world/     WorldFx, SkyStreakRenderer, ImpactFlashRenderer, DustRingRenderer,
│   │                  TerrainDecalRenderer, SmokeColumn, TargetLaserRenderer
│   ├── cinematic/
│   │   ├── CinematicDefinition, Shot, Keyframe, Easing, CameraRig, Transition
│   │   ├── CinematicLoader (reload listener), CinematicPlayer, ShotContext
│   │   ├── WorldCamera                         (detached camera for WORLD shots)
│   │   └── PreviewController                   (/orbitalstrike preview command)
│   ├── audio/         AudioDirector, LowPassFilter (EFX), SoundLayers, DelayedSoundQueue
│   ├── hud/           TargetingHud, Rangefinder, CountdownHud, LetterboxHud, CaptionHud
│   ├── ui/            kit: Panel9, UiButton, UiSlider, UiToggle, UiCycle, UiTooltip,
│   │                  Anim (easing), UiSounds; screens: DetonatorScreen, ConfigScreen
│   ├── item/          DetonatorModelPredicates, DetonatorAnimState
│   ├── block/         SpireBlockEntityRenderer, EmissiveModelPlugin (FRAPI)
│   ├── entity/        EjectaEntityRenderer
│   ├── particle/      Ember, Ash, Debris, Smoke, Steam, Spark
│   ├── compat/        IrisCompat, SodiumCompat, ModMenuIntegration
│   └── mixin/         CameraMixin, GameRendererMixin, WorldRendererMixin,
│                      SoundSourceMixin, InGameHudMixin, HeldItemRendererMixin
├── src/client/resources/
│   ├── orbital_strike.client.mixins.json
│   └── assets/orbital_strike/
│       ├── cinematics/kinetic_strike.json      staging (shots, keyframes, events)
│       ├── models/mesh/*.obj                   ring, breech, needle (Blockbench exports)
│       ├── shaders/core/*.json|vsh|fsh         3D shaders (planet, atmosphere, ring…)
│       ├── shaders/program/*.json|vsh|fsh      FxPipeline passes
│       ├── textures/space/                     earth_day, earth_night, earth_clouds,
│       │                                       gas_giant, milky_way, lens_dirt, noise
│       ├── textures/gui/                       9-slice panels, icons, reticle
│       ├── sounds/*.ogg · sounds.json · lang/fr_fr.json, en_us.json
│       └── models/item, models/block, blockstates
├── src/test/java/…                             unit tests (without Minecraft)
└── src/gametest/java/fr/orbitalstrike/gametest/ Fabric gametests
```

---

## 3. Rendering: Technical Foundation

### 3.1 Shaders
- **3D Shaders** (planet, atmosphere, gas giant, emissive ring, plasma, sky streak, decal, dust ring): registered via `CoreShaderRegistrationCallback` (fabric-rendering-v1), each with its own vertex format. `ShaderProgram` instances are stored in `ShaderRegistry`.
- **Fullscreen passes**: we **no longer** use `PostEffectProcessor`, which is too limited (`float` uniforms only, single 8-bit target). It is replaced by `FxPipeline`, which manages its own programs and render targets and supports `vec2`, `vec3`, and `mat4` uniforms.
- All colors sent to vertices are **clamped to [0, 1]**. This fixes the v1 magenta ring bug: excess brightness goes through HDR rather than vertex color overflow.

### 3.2 HDR and FxPipeline
```
World or SPACE scene render ──► HdrFramebuffer (RGBA16F + depth)
   └► GodRaysPass (when a light source is active)
   └► BloomPass: threshold -> 6 downsamples -> 6 upsamples (tent filter)
   └► MotionBlurPass (during acceleration: directional blur from camera velocity)
   └► LensFlarePass (sun or impact, with lens dirt texture)
   └► ChromaticPass + vignette + desaturation + flash
   └► TonemapPass (approximate ACES) + FilmGrainPass ──► main framebuffer (RGBA8)
```
- `HdrFramebuffer`: a custom framebuffer (GL30) backed by a `GL_RGBA16F` texture, created and resized alongside the window.
- Integration: a mixin in `GameRenderer.render` redirects the world render into the HDR target **only when an effect is active**. At all other times, the vanilla path remains untouched (zero overhead).
- `QualityPreset` enables or disables individual passes and adjusts bloom mip sizes.
- **Iris**: when `IrisCompat.shaderPackInUse()` is true, the custom HDR target is bypassed. Only compatible passes (flash, desaturation) remain, applied after the shader pack.

### 3.3 3D Models
- Models are authored in Blockbench, exported as **OBJ** (procedure in `tools/export_models.md`), loaded by `ObjLoader`, and uploaded once into a `VertexBuffer` (`Mesh`).
- Groups whose names start with `emissive_` are drawn separately with the emissive shader; these feed the bloom pass.
- `AcceleratorRing` renders **a single module** instanced N times (simple instancing, one matrix per module). Each module receives a `Charge` uniform (0 to 1) controlling its illumination.
- `Icosphere` (subdivision level 5) is used for planets: no pole pinching. This fixes the v1 polar cap artifact.

### 3.4 Planet Shaders (Principles)
| Shader | Inputs | Computation |
|---|---|---|
| `planet_earth` | day (RGB + ocean mask in A), night, clouds, sun direction, time | `N·L` for day/night blend (smooth transition); city lights only on the night side; clouds scrolling in longitude over time; specular highlight on the ocean mask |
| `atmosphere` | radius, color, sun | Sphere 2.5% larger, additive blending, intensity `pow(1 - N·V, 3)` modulated by lighting (blue on the day side, orange at the terminator) |
| `gas_giant` | band texture, noise, sun, ring plane | Latitude-dependent UV offset (bands moving at different speeds), rotating storm vortex, analytical **ring shadow** (ray toward the sun intersected with the ring plane) |
| `emissive` | color, HDR intensity, `Charge` | Output > 1 into the HDR target (drives bloom) |
| `plasma` | animated noise, speed | Fresnel sheath, orange -> purple gradient, noise scrolling along the axis |

---

## 4. Cinematics

### 4.1 Data Format (`assets/orbital_strike/cinematics/kinetic_strike.json`)
```json
{
  "id": "kinetic_strike",
  "shots": [
    {
      "id": "ascent", "phase": "ASCENT", "type": "world",
      "camera": {
        "frame": "player",
        "keys": [
          { "t": 0.0, "pos": [0, 1.6, -2], "look": [0, 1.6, 6], "fov": 70, "ease": "in_cubic" },
          { "t": 0.6, "pos": [0, 140, 0], "look": [0, 0, 0], "fov": 80, "ease": "in_out_sine" }
        ]
      },
      "events": [
        { "t": 0.0,  "sound": "orbital_strike:ascent" },
        { "t": 0.55, "fx": "cloud_layer_pass" },
        { "t": 0.7,  "switch_to": "space:earth_orbit", "transition": { "type": "atmosphere_fade", "len": 0.3 } }
      ],
      "letterbox": true
    }
  ]
}
```
- **Reference frames** (`frame`): `player`, `target`, `space` (space scene coordinates), `needle` (tracks the needle).
- **Keyframes**: interpolated with **Catmull-Rom** for position and slerp for orientation, with named easings (`linear`, `in_cubic`, `out_expo`, `in_out_sine`, …). Roll and FOV are animatable.
- **Events**: sound, effect (`fx` = WorldFx or SpaceScene identifier), scene switch, slow motion (`time_scale`), director caption.
- **Transitions**: `fade_black`, `fade_white`, `whip_pan`, `atmosphere_fade`, `flash_cut`.
- Synchronization with the server uses the `phase` field: each shot stretches across the duration of its phase (`Phase.durationTicks`).

### 4.2 Playback
- `CinematicLoader`: client reload listener that validates the JSON and logs a readable error on failure.
- `CinematicPlayer`: receives `CinematicStart` followed by `Phase` packets, selects the matching shot, computes `t = (now - start + tickDelta) / duration`, and applies the camera, events, and transitions.
- `SpaceRenderer3D`: during a SPACE shot, `WorldRendererMixin` **cancels world rendering** (HEAD, cancellable) and draws `SpaceScene` into the HDR target with its own view and projection matrices. The vanilla HUD is hidden (`InGameHudMixin`).
- `WorldCamera`: during a WORLD shot, `CameraMixin` overrides position and orientation and forces third-person view so the player body remains rendered. Frustum culling follows automatically.
  **Fallback**: if a trajectory point lies outside client-loaded chunks, the shot is reframed from the player's position.
- `PreviewController`: `/orbitalstrike preview <shot> [loop]` plays a single shot without triggering a real strike, with a debug overlay (time, current keyframe, dotted trajectory).

---

## 5. World Effects (`render/world/`)

| Class | Technique |
|---|---|
| `SkyStreakRenderer` | Additive ribbon in the real sky (`WorldRenderEvents.AFTER_TRANSLUCENT`), depth-tested, with a blazing head and fading trail. Triggered on `SkyStreak` receipt (striker **and** observers) |
| `TargetLaserRenderer` | Thin beam from the sky down to the target during lock-on |
| `ImpactFlashRenderer` | Light column, additive hemisphere, plus a light source for god rays; surrounding terrain is "lit" by a decaying post-process tint around the impact |
| `TerrainDecalRenderer` | **Screen-space decal**: renders a bounding cylinder and reconstructs world position from the depth buffer in the fragment shader to project the targeting ring, cracks, and scorch marks. **Fallback**: a ribbon conforming to the heightmap |
| `DustRingRenderer` | Meshed ring conforming to the terrain (heights sampled from the client heightmap), using an animated dust shader; radius and opacity driven by `Shockwave` |
| `SmokeColumn` | Layered particle emitter (stem then cap), lit by sky and impact lighting; 30 to 60 s duration; capped by quality preset |

---

## 6. Audio (`client/audio/`)

- **True low-pass filter**: `LowPassFilter` creates an OpenAL EFX filter (`EXTEfx.AL_FILTER_LOWPASS`, configuring `AL_LOWPASS_GAINHF`). `SoundSourceMixin` attaches it (`AL_DIRECT_FILTER`) to all sound sources **except the mod's own sounds**, then detaches it afterward. If EFX is unavailable (`ALC_EXT_EFX` missing), it falls back to v1 volume attenuation.
- `DelayedSoundQueue`: speed-of-sound delay (from v1). Selects `boom_near` or `boom_far` and scales filtering based on distance.
- `SoundLayers`: a staged sound can combine multiple layers (e.g., `spin` = whistle + rumble), each with volume and pitch curves driven by the shot's `t`.
- Panning: `sky_streak` plays as a positional source that **moves** (`MovingSoundInstance`) along the falling star's path.

---

## 7. Crater v2 (`crater/`)

### 7.1 Pipeline
```
IMPACT
 │ 1. TerrainSample (main thread, 1 to 3 ticks): surface height and block per column,
 │    log positions (for TreeFell), water and snow; loaded chunks only
 │ 2. CraterPlanner (dedicated executor, async) runs the passes:
 │       Excavate -> Terraces -> CentralUplift -> Skin -> EjectaBlanket (+ rays)
 │       -> Scorch -> TreeFell -> Fluids -> Spire -> MoltenCore
 │    and produces a CraterPlan: operations grouped by 16³ section, sorted center-outward
 │ 3. RegionSnapshot: copy of affected sections (block states + block entities),
 │    compressed and written in background to world/data/orbital_strike/undo/<id>.nbt.zst
 │ 4. SectionWriter (main thread, ms-per-tick time budget, not block count)
 │ 5. EjectaEntity spawned when writing begins; MoltenRock scheduled upon completion
```
- **Time budget**: `SectionWriter` stops as soon as `System.nanoTime()` exceeds the budget (`craterMsPerTick`, 6 ms by default). More reliable than a fixed block count.
- Passes are **pure**: they only read `TerrainSample` and write into `CraterPlan`, making them directly unit-testable.

### 7.2 Fast Writing
- Direct writes to `ChunkSection.setBlockState(x, y, z, state, false)` under section lock, followed per section by:
  - heightmap updates (`Heightmap.trackUpdate`);
  - `LightingProvider.checkBlock` on positions whose opacity or light emission changed;
  - removal and creation of affected block entities;
  - sending **one `ChunkDeltaUpdateS2CPacket` per section**;
  - `chunk.setNeedsSaving(true)`.
- `fastWrite=false` option: falls back to `world.setBlockState` with v1 flags (safety, compatibility).

### 7.3 Notable Passes
| Pass | Rule |
|---|---|
| `Terraces` | Beyond 0.7 r, the slope steps into 2 or 3 terraces with noise-modulated heights; the shaved rim wall becomes a sloped bank (at most 1 block rise per 1.5 horizontal blocks) instead of the v1 cliff |
| `CentralUplift` | Gaussian mound at the center (height ≈ 0.15 × depth) supporting the spire |
| `EjectaBlanket` | Thickness `e(d) = e0 · (r/d)³` from 1.25 r to 3 r; 8 to 12 **rays** (seed-derived angles) where the deposit is brighter and extends farther |
| `Scorch` | Up to 2.5 r: grass -> coarse dirt or ash layer, leaves -> air or fire, logs -> charred log; probability decreases with distance |
| `TreeFell` | Log detection (search capped at 64 blocks per tree); the tree is removed and placed horizontally pointing away from the impact (`axis` property); scattered foliage; max tree cap per strike |
| `MoltenCore` | `MoltenRockBlock[stage=0]` in the floor (n < 0.35), stages 1 to 2 around it; scheduled cooling (`scheduleBlockTick` with random delay) down to the final cooled block |

### 7.4 Ejecta
- `EjectaEntity`: lightweight entity with no AI. Carries a synced `BlockState`, a server-computed ballistic trajectory (deterministic initial velocity from the seed), and standard client interpolation.
- On landing, places a layer (or nothing if occupied), plays `debris_land`, and despawns.
- Cap: `maxEjecta` (60 by default). Rendered client-side with `BlockRenderManager` and per-entity rotation.

### 7.5 Undo
- `UndoStore` retains the last N snapshots (configurable MB limit).
- `/orbitalstrike undo [id]` restores sections using the same `SectionWriter` and clears leftover ejecta and strike entities in the area.

---

## 8. Blocks, Items, Entities

| Element | Implementation |
|---|---|
| 3D Detonator | Blockbench item model (JSON) with `overrides` on two client predicates: `lid` (0 = closed, 1 = open while aiming) and `pressed` (launch pulse), registered via `ModelPredicateProviderRegistry`; `HeldItemRendererMixin` adds a subtle press motion in first person |
| Emissive rendering | `EmissiveModelPlugin` (Fabric `ModelLoadingPlugin`) wraps molten rock and spire segment models: quads using `_emissive`-tagged textures receive a FRAPI `emissive(true)` material (compatible with Indigo, and Sodium with FRAPI support) |
| Spire | `SpireSegmentBlock` (base, middle, tip, with seamlessly connecting models); the tip holds a `SpireBlockEntity` (decaying glow over time, steam in rain, proximity hum) rendered by `SpireBlockEntityRenderer` |
| Molten rock | `MoltenRockBlock` with `IntProperty STAGE` from 0 to 3; luminance 15, 12, 8, then 0; stage-based damage; per-stage animated texture; melts nearby snow |
| Ash | `AshLayerBlock` modeled after snow layers (1 to 8) |

---

## 9. Networking

| Payload | Direction | Contents |
|---|---|---|
| `RequestStrike` | C2S | target, power, speed, mode (v1) |
| `CinematicStart` | S2C | strikeId, cinematic id, seed, speed, mode, target, striker position |
| `Phase` | S2C | strikeId, phase, duration in ticks, role (striker or observer) (enriched v1) |
| `SkyStreak` | S2C | strikeId, sky entry point, impact point, duration (all players within 512 blocks) |
| `Impact` / `Shockwave` | S2C | v1, plus the seed (for identical cracks and rays across all clients) |
| `FxEvent` | S2C | effect identifier, position, `float[]` parameters (generic, for future effects) |
| `CooldownSync` / `ServerLimits` | S2C | cooldown, range, max allowed power for this player |
| `Handshake` | S2C on join | protocol version: on mismatch, reduced effects and a clear message |

All codecs remain hand-written (`PacketCodec.of`) and verified by round-trip unit tests.

---

## 10. UI (`client/ui`, `client/hud`)

- **Custom UI kit**:
  - `Panel9` (9-slice texture, chamfered corners, amber border);
  - `UiButton`, `UiSlider`, `UiToggle`, and `UiCycle`, with hover, pressed, and disabled states;
  - `Anim` (easings, 120 to 200 ms durations);
  - `UiSounds`.
- `DetonatorScreen`: capability card with an animated crater preview (scaled top-down circle and profile cross-section), circular cooldown gauge, sliders and mode selector, keybind capture (v1), conflict warning.
- `ConfigScreen`: *Visual*, *Audio*, *Comfort*, *Controls* tabs, plus presets. Accessible from Mod Menu (`ModMenuIntegration`, `modmenu` entrypoint, optional dependency).
- HUD:
  - `TargetingHud`: reticle and rangefinder;
  - `CountdownHud`: scanline digits with subtle pulse;
  - `LetterboxHud`: animated bars;
  - `CaptionHud`: director captions.

---

## 11. Compatibility (`compat/`)

| Mod | Detection | Behavior |
|---|---|---|
| Iris | `FabricLoader.isModLoaded("iris")` then `IrisApi.getInstance().isShaderPackInUse()` | No HDR target; reduced passes applied after the pack; SPACE shots rendered without the pack |
| Sodium | `isModLoaded("sodium")` | No reliance on vanilla chunk rendering; emissive rendering goes through FRAPI |
| Mod Menu | `modmenu` entrypoint | Opens `ConfigScreen` |
| Fabric Permissions API | `isModLoaded("fabric-permissions-api-v0")` | `orbital_strike.*` nodes; otherwise op permission level |
| Claims | Common protection API if present | `ProtectionCheck` rejects the target or excludes protected columns from the crater plan |
| Dynamic lighting | if present | Ejecta and the hot spire emit dynamic light |

All compatibility classes are loaded **only when the target mod is present** (no direct references elsewhere in the codebase).

---

## 12. Tests and Tools

- **Unit tests** (`src/test`): `CraterShape`, all planner passes (against a synthetic `TerrainSample`), `StrikeTimeline`, Catmull-Rom interpolation and easings, sound delay, network codecs.
- **Gametests** (`src/gametest`, `fabric-gametest` entrypoint):
  - power-1 crater on flat terrain (profile, terraces, spire);
  - molten rock cooling (accelerated);
  - protected area respected;
  - `undo` restoring identically (block-by-block comparison);
  - `terrainDestruction=false`;
  - performance: maximum tick time during crater writing.
- **Tools**:
  - `/orbitalstrike preview`;
  - `/orbitalstrike debug` (F3 lines: phase, shot, pass cost in ms, active particles);
  - profiling sections (`client.getProfiler().push("orbital_strike:fx")`).
- **Reference screenshots** (`art/reference/`): one image per shot, compared on every release.

---

## 13. Configuration (Fields Added to v1)

**Server:** `globalCooldownSeconds`, `maxConcurrentStrikes`, `craterMsPerTick`, `fastWrite`, `maxEjecta`, `treeFelling`, `scorch`, `respectClaims`, `undoMaxSnapshots`, `undoMaxMegabytes`, `strikeLog`.

**Client:** `quality` (cinematic, balanced, performance), `bloom`, `motionBlur`, `godRays`, `lensFlare`, `filmGrain`, `particleBudget`, `photosensitive`, `shakeIntensity`, `reducedMotion`, `directorCaptions`, `modVolume`, plus v1 fields (keybind, power, speed, mode).

---

## 14. Migration from v1

| v1 | v2 | Action |
|---|---|---|
| `strike/StrikeTimeline` (hardcoded durations) | `StrikeTimeline` + `TimelineLoader` (data JSON) | Keep the API, load durations from `data/` |
| `cinematic/CinematicDirector` + `SpaceRenderer` (2D in HUD) + scenes | `CinematicPlayer` + `SpaceRenderer3D` + JSON | **Replace**; keep framing ideas as a baseline for keyframes |
| `fx/PostEffects` (PostEffectProcessor) | `FxPipeline` (HDR) | Replace; keep the v1 shader algorithm as the basis for `ChromaticPass` |
| `fx/SoundDirector` | `AudioDirector` + `LowPassFilter` | Extend (keep speed-of-sound delay and scheduled queue) |
| `fx/CameraShake` | Directional `CameraShake` | Extend (add push vector) |
| `crater/CraterBuilder` | `CraterPipeline` (async planner + `SectionWriter`) | Replace; keep and enrich `CraterShape` and `CraterProfile` |
| `targeting/TargetingController` (particles) | `TargetingHud` + `TerrainDecalRenderer` | Keep targeting logic, replace display |
| `gui/SkillMenuScreen` | `DetonatorScreen` (UI kit) | Replace |
| Provisional `sounds.json` | Original sounds from §9 of `specification.md` | Replace `"type": "event"` entries |
| v1 Mixins (camera, render, sound) | v2 Mixins | `SoundSystemMixin` is replaced by `SoundSourceMixin` (EFX) |

**Fix right at milestone M1** (before major refactors):
1. Clamp all vertex colors to `[0, 1]` (magenta and yellow ring overflow).
2. Earth texture polar caps: smaller and noise-broken, and switch to icosphere.
3. Desaturation and vignette intensity during silence: make clearly visible (and verify by screenshot).
4. Rim shaving: sloped bank instead of the stepped cliff.
5. Transitions: at minimum black or white fades between shots and animated letterbox bars.

---

## 15. Conventions

- Java 21: `record` for immutable data and payloads, `sealed interface` for cinematic event types, `switch` over enums.
- No client class referenced from `main` (sources split by Loom).
- No magic numbers: every tunable value lives in config, cinematic JSON, or named constants.
- Every class has a header comment explaining its role; every shader documents its inputs at the top of the file.
- Every added asset has an entry in `CREDITS.txt`.
- **100% original and standalone creation (code, models, sounds, shaders, and textures generated for the project).**
