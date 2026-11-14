# Contributor & Handoff Guide — Orbital Kinetic Strike (`orbital_strike`)

> **Version described:** `2.0.0-alpha.11` (Minecraft `1.21.1`, Fabric Loader `0.16.14`, Fabric API `0.116.7+1.21.1`, Java `21`)
> **Author:** DrakkarF7 — **License:** CC0-1.0 (Public Domain, open source, 100% AI-generated)
>
> This guide helps any developer or player understand the current mod architecture, what is already implemented and verified, and how to modify or extend the project cleanly.

---

## 1. Overview & Tech Stack

- **Loader & Mappings:** Fabric Loom `1.14.10`, Gradle `9.2.1`, Yarn `1.21.1+build.3`, `mod_id = "orbital_strike"`, package `fr.orbitalstrike`.
- **Strict client/server split (`splitEnvironmentSourceSets`):**
  - `src/main`: server logic, blocks, items, networking (`Payloads`), commands, timeline (`StrikeSession`), crater generator (`ImpactCraterGenerator`), persistent `undo`.
  - `src/client`: 3D HDR space engine (`SpaceRenderer3D` + `FxPipeline`), cinematic scenes (`src/client/java/fr/orbitalstrike/client/cinematic/scenes/`), world FX (`WorldFx`, `WorldCamera`), Minecraft post-processing (`PostEffects` / `orbital_strike_fx.fsh`), EFX audio (`SoundDirector`, `LowPassFilter`), GUI (`DetonatorScreen`), mixins, and integration tests (`SelfTest`, `CraterSyncTest`).
  - `src/test`: Minecraft-free JUnit 5 unit tests (`CraterShapeTest`, `MeshTest`, `StrikeTimelineTest`).

---

## 2. Current Project Status (`2.0.0-alpha.11`)

### Implemented and Verified

1. **Full server strike timeline (`StrikePhase` / `StrikeTimeline` / `StrikeSession`):**
   - In `FULL` mode (`x1` speed), **933 ticks (`46.65 s`) until impact**:
     - `MARK` (`38` ticks) + `COUNTDOWN` (`96` ticks): ground lock-on, cyan/white orbital targeting beam, real-world cinema camera (`WorldCamera.LAUNCH`) accelerating vertically up to `LIFTOFF_HEIGHT = 245.0`.
     - `ASCENT` (`104` ticks): vertical cloud breakout and Earth receding before the Milky Way and spiked sun (`AscentScene`, `sky.fsh`).
     - `TRANSFER` (`108` ticks): cyan/white hyperspace warp streaks arriving at Jupiter (`TransferScene`).
     - `RING` (`120`), `LOAD` (`88`), `SPIN` (`152`): 72-module segmented accelerator ring (`ring_module.obj`), extruded 3D `KINETIC / STRIKE` title, octagonal tungsten needle insertion (`needle.obj`), and sequential circular gate energization (`OrbitScene`).
     - `RELEASE` (`82`): needle travels **along the actual ring arc curve** (`ringPos` / `ringTangent` up to `A_EJECT = -1.35`), then breaks off strictly tangentially toward the upper-left past Jupiter with continuous trail and `RELEASE` title card.
     - `FLIGHT` (`124`): chase through debris field, `TRANSIT` title card, atmospheric reentry under plasma sheath (`plasma.fsh`), and glacier-white flash (`FlightScene`).
     - `STREAK` (`18` ticks) + `HUSH` (`2` ticks): seamless transition back to Earth sky with near-immediate vertical dive (`WorldFx.trackedHead`, `WorldCamera.SKY`) directly into detonation without dead air.
     - `IMPACT` (`1` tick) + `SHOCKWAVE` (`96` ticks) + `SETTLE`: immediate flash, flared tungsten spire with thermal conduits, molten fissured crater, and dual shockwave.
   - Alternative modes supported: `SHORT` (reentry `FLIGHT` onward) and `OFF` (no space cinematic).

2. **Recent Fixes & Optimizations Already Included:**
   - **GPU prewarm (`SpaceRenderer3D.warmUp` / `CinematicDirector.warmUpIfNeeded`)**: compiles shaders, loads `.obj` meshes, and generates mipmaps on world join to eliminate stalls when `ASCENT` begins.
   - **Clean black letterbox bars (`CinematicDirector.render`)**: depth test and depth write disabled around bar quads and `ctx.draw()`, preventing the hotbar from cutting through the bottom letterbox bar.
   - **Smooth decrescendo crater perimeter (`ImpactCraterGenerator`)**: removed raised dirt wall/waves at the rim; outer edge slopes smoothly down to `0` depth at `edgeRadius` using `Heightmap.Type.MOTION_BLOCKING_NO_LEAVES`, budget-synced per tick with zero client/server desync.

3. **Checks Passing on Current Build:**
   - `.\gradlew.bat test --rerun-tasks build`: **21 JUnit tests green** (`CraterShapeTest`: 11, `MeshTest`: 5, `StrikeTimelineTest`: 5; 0 failures, 0 skipped).
   - `.\gradlew.bat runSelftest`: **20 PNG captures** validated with 3D HDR rendering active (`01..20` in `run/selftest/`).
   - `.\gradlew.bat runWorldtest`: verified with 0 desynced blocks (`live_crater_sync.png`).

---

## 3. Directory Map

| Path | Contents |
|---|---|
| `src/main/java/fr/orbitalstrike/strike/` | Timeline (`StrikePhase`, `StrikeTimeline`, `StrikeSession`, `StrikeManager`), damage (`ImpactDamage`), ejecta, audit log |
| `src/main/java/fr/orbitalstrike/crater/` | Crater geometry and chunk/section generator (`ImpactCraterGenerator`, `CraterShape`, `CraterProfile`), `UndoStore` / `UndoSnapshot` |
| `src/main/java/fr/orbitalstrike/block/` & `item/` | Blocks (`TungstenBlock`, `MoltenRockBlock`, `ThermalConduitBlock`, `IonizedCrystalBlock`, `AshLayerBlock`), `OrbitalDetonatorItem`, `KineticCapsuleItem` |
| `src/client/java/fr/orbitalstrike/client/cinematic/` | `CinematicDirector`, `SpaceRenderer`, `Projector`, `WorldCamera`, scenes (`AscentScene`, `TransferScene`, `OrbitScene`, `FlightScene`) |
| `src/client/java/fr/orbitalstrike/client/render/` | 3D HDR OpenGL pipeline (`SpaceRenderer3D`, `FxPipeline`, `GlProgram`, `HdrFramebuffer`, `Mesh`, `ObjLoader`, `TitleMesh`) & world FX (`WorldFx`) |
| `src/client/java/fr/orbitalstrike/client/fx/` | `FxController`, `PostEffects`, `SoundDirector`, `LowPassFilter`, `CameraShake` |
| `src/main/resources/assets/orbital_strike/shaders/orbital/` | Space & bloom/ACES GLSL shaders (**strict pure ASCII required**, including inside comments) |
| `tools/` | Procedural Python asset generators (`generate_models.py`, `generate_textures.py`, `generate_sounds.py`, `import_nasa_textures.py`) |
| `docs/` | `specification.md`, `architecture.md` |

---

## 4. Important Engineering Rules

1. **Pure ASCII in shaders**: all `.fsh`/`.vsh` files in `shaders/orbital/` and `shaders/program/` must stay strictly ASCII (even in comments) to prevent GLSL compiler errors on strict GPU drivers.
2. **Two passes per frame (`COLLECT` then `OVERLAY`)**: every `CinematicScene.render(...)` must be purely functional based on `(t, time)` without mutating state between passes.
3. **OpenGL state (`GlStateSnapshot`)**: `SpaceRenderer3D` renders into a custom HDR framebuffer — always wrap with `GlStateSnapshot` and call `client.getFramebuffer().beginWrite(true)` before HUD rendering.
4. **Client/server isolation**: `src/main` must never import from `src/client`. Client code must never mutate world blocks directly.
5. **Minecraft vertex colors**: colors passed to `BufferBuilder` must stay clamped inside `[0, 1]`.

---

## 5. Ideas for Future Contributors

- **Data-driven cinematics (JSON)**: export space camera splines (`Ascent`, `Transfer`, `Orbit`, `Flight`) to JSON with Catmull-Rom interpolation and `/orbitalstrike preview`.
- **In-game Config Screen & Mod Menu integration**: edit client/server toggles (`photosensitive`, `bloom`, `motionBlur`, `godRays`, `shakeIntensity`, etc.) without editing JSON files manually.
- **Graphics quality presets** (`Low` / `Medium` / `High` / `Ultra`) controlling SSAA scale and bloom tap counts for lower-end GPUs.
- **Custom 3D Detonator & Spire models**: replace the 2D held Detonator sprite with an animated 3D model, or add animated spire core details via a `BlockEntityRenderer`.
- **Automated Fabric GameTests**: automated server tests for `MoltenRockBlock` full cooling, exact `undo` restoration, and `terrainDestruction = false`.

---

## 6. Build & Test Commands

```powershell
# Compile + run all 21 JUnit tests
.\gradlew.bat test --rerun-tasks build

# Launch dev client (Sodium + Iris loaded automatically)
.\gradlew.bat runClient

# Regenerate all 20 cinematic visual captures
.\gradlew.bat runSelftest

# Verify crater excavation & client/server sync in a live world
.\gradlew.bat runWorldtest
```
