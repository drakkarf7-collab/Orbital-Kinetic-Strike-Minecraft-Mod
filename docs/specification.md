# Specification v2 — "Orbital Kinetic Strike" Mod

> **Version 2: "production" quality.** This document replaces v1.
> Read alongside `architecture.md` (v2), which describes the **how**.
> The v1 mod already exists in `orbital-strike/`: the goal is to evolve it, not rewrite everything from scratch.
>
> **Absolute rule (unchanged):** **original and standalone** creation. The project implements its own algorithms, shaders, textures, sounds, models, and game mechanics. External resources are permitted only if they are **public domain or under a free license**, with full attribution in `CREDITS.txt`.

---

## 0. Target Quality Bar

The player should feel like they are watching **a movie trailer, not a mod**. Specifically:

1. **Every second is staged.** There is no technical "dead air": every phase has its own framing, motion, lighting, and sound.
2. **Silence is the signature.** You see the impact, wait one or two seconds in complete silence, and then the sound arrives. Every other design choice serves this moment.
3. **Nothing looks flat.** Everything is lit, shaded, and emissive where appropriate (bloom), and transitions use fades, flashes, or camera moves—never hard cuts.
4. **The world keeps the scar.** The crater, felled trees, ash, and cooling molten rock should make players want to come back and look at the aftermath.
5. **Zero compromise on performance.** No TPS drops, and no noticeable FPS drops on mid-range hardware.
6. **"Product" polish.** Clean menus, full settings, accessibility, subtitles, and tested compatibility.

---

## 1. v1 Assessment (In-Game Playtest of 2026-11-11)

| Area | What works | What is missing or broken |
|---|---|---|
| Flow | Red targeting ring, countdown, phase sequence, return to ground | Hard cuts between shots, no transitions; Release and Flight pass too fast to read |
| Cinematics | Earth, gas giant, ring, needle, and trail displayed | 2D render projected onto the HUD: no true depth, no bloom, no atmosphere; **bug**: the polar cap looks like a white moon stuck onto Earth; **bug**: lit ring segment colors overflow into magenta and yellow (unclamped values > 1) |
| Screen effects | Shader loads without errors | Desaturation, flash, and distortion barely or not perceptible in-game |
| Crater | Bowl, lava, magma, thermal conduit, fire, spire; 20 TPS at power 5 | Rim shaving leaves stepped stone walls; no ejecta, no scorched ground around it, no felled trees, no lingering smoke; spire is just a 1×1 block column |
| Audio | Silence and distance-delayed sound work | Provisional Minecraft sounds; no filtering (muffling), no layering |
| UI | Functional menu, configurable keybind | Vanilla Minecraft buttons, no visual identity; no configuration screen |
| Real world | — | The needle is **never seen in the real sky** above the target: this is the most missed shot |

---

## 2. General Information

| Item | Value |
|---|---|
| Name | Orbital Kinetic Strike |
| Mod ID | `orbital_strike` |
| Loader / version | Fabric · Minecraft 1.21.1 · Java 21 |
| Required dependency | Fabric API |
| Optional dependencies | Mod Menu (config screen), Fabric Permissions API (permissions) |
| Compatibility targets | Sodium, Iris (with and without shader packs), Lithium, dedicated server |
| Languages | `fr_fr`, `en_us` (all keys, including subtitles) |

---

## 3. Art Direction

**Tone:** cold, realistic sci-fi in space, followed by scorching violence on the ground. Space is **midnight blue and cyan**; impact is **white then incandescent orange**.

| Element | Colors | Rule |
|---|---|---|
| Space | Bluish black `#05070D`, warm white stars | Always a diffuse Milky Way: never a flat black background |
| Accelerator | Dark steel, **cyan** coils `#5FE6FF` | Lit parts have bloom; metal has specular highlights |
| Needle | Blue-grey tungsten, heated tip **orange → white** | Heat increases with velocity |
| Re-entry plasma | Orange `#FF8A2A` → purple `#B05CFF` at the edges | Sheath around the needle, with sparks |
| Impact | Pure white → yellow → orange `#FF5A1F` | Flash illuminates the terrain |
| Molten rock | Luminous orange-red, cooling to dark grey | Visible at night from over 100 blocks away |
| UI | Translucent dark panels, **amber** borders `#E0A030`, crisp text | Minecraft typography, chamfered corners, smooth animations |

**Staging rules:**
- Transitions use fades, white flashes, **whip-pans** (blurred pan), or layer crossings (clouds, atmosphere). No hard cuts.
- The camera always has subtle motion (slow drift or slight shake), except during the silence, where it locks still to heighten tension.
- Cinema letterbox bars **slide** in and out (0.4 s) instead of popping in.
- Short shots (under 1 s) must only serve as visual impacts, never to convey information.

---

## 4. Full Storyboard (1x speed, ~27 s to impact)

There are two shot types: **WORLD** (the real Minecraft world, camera detached from the player) and **SPACE** (3D scene rendered by the mod).

| # | Shot | Type | Duration | Visuals | Effects | Audio |
|---|---|---|---|---|---|---|
| 0 | **Arming** | Player | while aiming | Custom reticle, rangefinder (distance, altitude, radius), **ground-projected ring** (decal, not particles) conforming to terrain | Ring pulses and turns red if target is invalid (protected area, out of range) | Looping arming hum, beep when target changes |
| 1 | **Lock-On** | Player | 1.5 s | Ring contracts then locks; a **thin targeting laser** descends from the sky onto the target; HUD types "TARGET LOCKED" and coordinates letter by letter | Slight zoom (FOV −5) | Mechanical lock + high beep |
| 2 | **Countdown** | Player | 5 s | Large stylized digits (scanline effect); sky darkens above the target; nearby animals flee | Ambient wind gradually fades | Rising-tension countdown, deep pulse |
| 3 | **Ascent** | WORLD → SPACE | 3 s | Camera leaves the player, rockets upward, **pierces the cloud layer**, sky turns from blue to black, Earth appears below | Speed streaks, fade through atmosphere | Rising whoosh, then vacuum of space |
| 4 | **Transfer** | SPACE | 1.5 s | Whip-pan toward the gas giant, stretched stars | Radial motion blur | Deep whoosh |
| 5 | **Ring** | SPACE | 3 s | Animated gas giant (scrolling bands, storm), **modeled accelerator ring** (modules, struts, coils) powering up module by module, ring shadow cast on planet, sun glint | Bloom on coils, sun lens flare | Electrical buildup synced to each module |
| 6 | **Loading** | SPACE | 2 s | Close-up on the breech: needle (detailed model, fins, heat-shield tip) slides into place, clamps snap shut | Sparks, small steam vent | Metallic slide, heavy clack, hiss |
| 7 | **Acceleration** | SPACE | 4 s | Chase camera behind the needle; coils ignite **just ahead of it**; FOV widens with speed; at peak charge, a light wave sweeps the entire ring | Motion blur, trail with bloom | Rising whine reaching the edge of audible pitch |
| 8 | **Release** | SPACE | 1.5 s | **0.5 s slow-motion** at detachment, then straight-line ejection toward Earth | Flash, luminous shock cone | Half-second of silence, then crisp detonation |
| 9 | **Transit** | SPACE | 2.5 s | Earth grows larger (day/night, city lights, clouds); atmospheric entry: orange and purple **plasma sheath**, sparks | Heat glow, increasing chromatic aberration | Swelling re-entry roar |
| 10 | **Sky Fall** | WORLD | 1.5 s | Wide shot from the ground near the target: a **blinding falling streak** tears across the real Minecraft sky, leaving a fading trail | World-rendered luminous trail | Sharp audio cutoff |
| 11 | **Silence** | Player | 1.5 s | Return to player view; world is desaturated; a bright point grows in the sky | Frozen camera, vignette | **Low-pass muffling** of all sounds, faint tinnitus, heartbeat |
| 12 | **Impact** | Player | instant | Pure white frame, then a pillar of light; **hemispheric flash** illuminates terrain; glowing cracks spread across the ground; first ejecta launch | Extreme bloom, god rays from impact | Still completely silent |
| 13 | **Sound Arrival** | Player | distance-based | Sound arrives at the speed of sound (minimum 0.3 s delay) | **Directional** shake (pushed away from impact) | Air tear, boom; the farther the player, the deeper and more muffled the sound |
| 14 | **Shockwave** | Player | 3 s | **Modeled dust ring** hugs terrain and expands; trees topple; leaves strip off; water splashes; mobs are flung | Screen heat ripple as the wave passes | Rolling thunder, snapping wood |
| 15 | **Fallout** | World | 30 to 60 s | Mushroom **smoke column** above the crater; falling ash and embers; sky tinted orange near the crater | Orange ground glow at night | Magma crackle, hot wind |
| 16 | **Scar** | World | permanent | Crater evolves over 10 min: molten, then glowing, then cooled; spire glows then cools; rain raises steam | — | Spire hum, steam hiss in rain |

**Modes:**
- **Full**: entire storyboard.
- **Short** (~10 s): shots 0, 1, 2 (shortened to 3 s), 9, 10, 11, and 12+.
- **Off**: shots 0, 1, 2, 11, and 12+.
- **Esc** skips directly to shot 11. Playback speed is adjustable from 0.5x to 2x.

---

## 5. Cinematics: Requirements

1. **True 3D rendering** inside the game's render pipeline, with view and projection matrices, depth buffer, and no more 2D HUD projection.
2. **Shader-driven planets**:
   - **Earth**: day texture, night texture (city lights) lit only on the dark side, separate cloud layer scrolling faster, specular sun reflection on oceans, and **atmosphere** halo (scattering, thicker at the limb).
   - **Gas giant**: animated bands (differential scrolling by latitude), rotating storm, cast ring shadow.
3. **3D Models** (authored in Blockbench, exported and loaded by the mod): accelerator ring (at least 48 instanced modules), breech, needle. Each model separates its **emissive parts** for bloom.
4. **Space sky**: skybox with high-resolution Milky Way, twinkling stars, sun with lens flare.
5. **HDR post-processing** (floating-point buffer):
   - multi-pass bloom;
   - tone mapping;
   - motion blur during acceleration;
   - subtle film grain;
   - chromatic aberration;
   - vignette;
   - lens flare.
6. **WORLD shots**: camera detaches and follows keyframes, the real world renders, and the player's body remains visible in place. If the target lies outside client render distance, the shot automatically reframes from the player.
7. **Data-driven cinematics** (JSON file): shots, camera keyframes (position, look target, roll, FOV, easing curves), synchronized events (sounds, effects). Must allow **iteration without recompiling** (reload via F3+T).
8. **Tuning tool**: `/orbitalstrike preview <shot>` loops a single shot with time and keyframe debug overlays for staging iteration.

---

## 6. Targeting, HUD, and UI

- **Custom reticle** while aiming, with a rangefinder: distance, elevation delta, and predicted crater radius.
- **Projected decal**: crater ring and shockwave radius are drawn **onto the terrain** from the depth buffer, conforming to slopes and walls.
- **Warnings**: out of range, on cooldown, protected area (claims), striker inside blast radius ("You are within the blast radius!").
- **Detonator Menu** redesigned with clear visual identity:
  - custom panels (9-slice), chamfered corners, amber borders;
  - open and hover animations (fade and slide, 150 ms);
  - click and hover sounds;
  - capability card with **animated preview** of crater size, tuning sliders (power, speed), and mode selector;
  - circular cooldown gauge.
- **Full config screen** (accessible from Mod Menu) with three presets: *Cinematic*, *Balanced*, *Performance*.

---

## 7. Crater v2

**Geology** (stylized yet believable):

| Feature | Description |
|---|---|
| Bowl | Parabolic profile, as in v1, with an irregular rim |
| **Terraces** | Walls step down in 2 or 3 slump terraces (no more stepped stone walls from v1 rim shaving) |
| **Central peak** | Gentle central uplift where the spire is embedded |
| **Ejecta blanket** | Debris layer tapering out to 3 r, with **rays** (radial streaks of lighter material) |
| **Scorch zone** | Up to 2.5 r: grass turns to coarse dirt or ash, leaves burn, trunks char |
| **Felled trees** | From 1.25 r to 2.5 r, trees are toppled **away from the impact** (horizontal logs, scattered foliage) |
| Water, snow, ice | Water flashes to steam; snow and ice melt; sand near the center vitrifies into stained glass |

**Molten rock** (new stateful block): progresses through 4 stages—**molten → incandescent → hot → cooled**. Emissive rendering, animated flowing texture, burns entities on contact, and melts nearby snow. Cools into basalt, obsidian, or blackstone depending on position over ~10 minutes (configurable).

**Physical ejecta**: on impact, debris blocks launch along ballistic arcs. Rendered as rotating blocks in mid-air and deposit their material on landing. Count is capped.

**Tungsten spire**: a true model (no longer a plain stack of blocks), tall and tapered, with **emissive grooves** that dim as they cool, steam in rain, and a proximity hum. Mined with a netherite pickaxe to yield **tungsten shards**.

**Administration**:
- `terrainDestruction` (yes/no);
- respect for **protected areas** (claims API if a claims mod is present; otherwise spawn protection);
- **undo**: `/orbitalstrike undo [id]` restores pre-strike blocks from an on-disk snapshot;
- strike log (who, where, when, power).

---

## 8. Items and Blocks

| Item / Block | Requirements |
|---|---|
| **Orbital Detonator** | 3D model (Blockbench): casing, safety lid, trigger button. The lid **opens while aiming**; the button depresses on launch; an indicator blinks on cooldown. Styled tooltip |
| Tungsten Block | Refined metallic texture, metallic sound, mineable only with a netherite pickaxe |
| Tungsten Spire | Tall model (3 to 5 visual blocks), emissive, block entity for glow and particles |
| Molten Rock | 4 stages, emissive, animated, contact damage, scheduled cooling |
| Charred Log, Ash Layer | Decorative strike-generated blocks (ash stacks like snow layers) |
| Tungsten Shard | Dropped by mining the spire; used to craft a cheaper Detonator (alternative recipe) |

---

## 9. Sound Design

All sounds are **original**. Each has **2 or 3 variants**, is mixed to a consistent loudness level, and includes a subtitle.

| Sound | Duration | Character & Layers |
|---|---|---|
| `arm_loop` | loop | Subtle electronic hum, gentle pulse |
| `lock` | 0.6 s | Mechanical latch + sharp beep |
| `countdown_tick` ×5 | 0.4 s | Beep rising a semitone each second, with sub-bass |
| `ascent` | 3 s | Rising rush, cutting off cleanly upon exiting atmosphere |
| `transfer` | 1.5 s | Deep, filtered whoosh |
| `ring_module` ×N | 0.2 s | Electrical snap played as each module lights up (slight pitch step) |
| `load_slide` / `load_clamp` | 1.2 s / 0.5 s | Metallic slide, then heavy clack and steam hiss |
| `spin` | 4 s | Rising whine layered with deep rumble |
| `release` | 1 s | Crisp detonation after half a second of void |
| `reentry` | 2.5 s | Swelling plasma roar |
| `sky_streak` | 1.5 s | Distant whistle panning across the stereo field |
| `hush` / `heartbeat` / `tinnitus` | 1.5 s | Sculpted silence: faint ringing, heartbeat |
| `crack` | 0.5 s | Air tear (near: sharp; far: muffled) |
| `boom_near` / `boom_far` | 3 s | Boom; far variant emphasizes lows and rolls off highs |
| `shockwave` | 4 s | Rolling rumble, snapping timber, debris |
| `debris_land` | 0.3 s | Debris impacts (multiple variants) |
| `magma_loop` | loop | Crackle, bubbling |
| `spire_hum` | loop | Deep metallic hum |
| `steam` | 1 s | Steam hiss (rain on hot rock) |
| `ui_hover` / `ui_click` / `ui_open` | short | Clean, restrained UI clicks |

**Dynamic audio:**
- **Real low-pass filter** applied to all game sounds during silence (true muffling, not just volume reduction).
- Speed-of-sound delay based on distance, choosing near or far boom variants and increasing high-frequency roll-off with distance.
- True stereo panning for the falling star flyby.

---

## 10. World and Screen Visual Effects

| Effect | Where | Detail |
|---|---|---|
| Bloom | Screen (HDR) | On impact, molten rock, spire grooves, sky trail |
| God rays | Screen | Rays radiating from the impact point for 2 s |
| Flash | Screen + world | Full white for 1–2 frames, then decaying terrain illumination |
| Glowing cracks | World (decal) | Radiate outward from center, then fade |
| Dust ring | World (animated mesh) | Conforms to terrain, fades as it expands |
| Heat ripple | Screen | As the shockwave washes over the player |
| Smoke column | World | Layered lit particles forming a mushroom cloud for 30 to 60 s |
| Ash and embers | World | Ashfall around the crater, rising embers at night |
| Magma glow | World | Emissive blocks; dynamic light if a dynamic-lighting mod is installed |
| Camera shake | Camera | Directional, scaled by power and distance, user-adjustable |

---

## 11. Multiplayer and Server

- **The server owns everything**: validation, timeline, block edits, damage.
- Observers see lock-on (laser), countdown, falling streak in the sky, impact, delayed sound, and shockwave. Space cinematics are striker-only unless configured otherwise.
- Limits:
  - per-player cooldown **and** optional global cooldown;
  - maximum concurrent strikes on the server;
  - maximum power per permission node.
- Permissions (when Fabric Permissions API is present): `orbital_strike.use`, `orbital_strike.power.<n>`, `orbital_strike.bypass_cooldown`, `orbital_strike.admin`.
- Strike log in console and file, plus undo command.
- Vanilla-client fallback (server-only): strike still works via command with reduced vanilla particles and sounds.

---

## 12. Accessibility and Comfort

- **Photosensitivity mode**: removes white flashes and clamps rapid luminance spikes.
- **Shake intensity** slider (0 to 100%).
- **Reduced motion**: disables motion blur, uses fade-only transitions.
- **Subtitles** for all sounds, plus optional **director captions** ("The ring powers up…").
- Colorblind-safe HUD: status is never conveyed by color alone (shapes and text).
- Cinematic always skippable.

---

## 13. Configuration

- **Server** (`config/orbital_strike.json`): everything from v1, plus global cooldown, max concurrent strikes, claims handling, ejecta cap, tree felling toggle, scorch toggle, undo snapshot size limit, and logging.
- **Client** (`config/orbital_strike-client.json`, editable in-game):
  - quality (bloom, motion blur, particle budget);
  - photosensitivity, shake intensity, reduced motion;
  - director captions;
  - dedicated mod volume;
  - keybind, power, speed, mode.
- *Cinematic*, *Balanced*, and *Performance* presets configure everything in one click.

---

## 14. Performance Budgets

| Metric | Budget |
|---|---|
| Server during crater construction | ≤ 8 ms/tick average, no tick > 50 ms, at power 5 |
| Crater plan calculation | Off main thread (async) |
| Client: fullscreen post-processing | ≤ 2 ms GPU at 1080p on GTX 1060-class GPU |
| Client: space cinematics | ≥ 60 FPS on the same machine under *Balanced* preset |
| Particles | Global cap, scaled by Minecraft's Particles setting |
| Networking | Block updates batched per section; no packet > 1 MB |

With **Iris** and an active shader pack: custom post-processing disables cleanly (or hooks after the pack), with no black screen or crash.

---

## 15. Art and Audio Assets: Production and Licensing

| Asset | Allowed Source |
|---|---|
| Planet textures (day, night, clouds, height, Milky Way) | Public domain (e.g., NASA imagery) or user-created, minimum 2048×1024, credited |
| 3D Models | Authored in Blockbench (`.bbmodel` sources tracked in `art/`) |
| Block & item textures | Hand-drawn or procedural, 16×16 or 32×32 consistent with Minecraft |
| Sounds | User-created/recorded, or freely licensed (CC0 or CC-BY) with credit |
| Fonts | Minecraft font only |

Source files (`.bbmodel`, audio projects, generator scripts) live in `art/` and are excluded from the `.jar`.

---

## 16. Software Quality

- Unit tests (crater geometry, timeline, keyframe interpolation, sound delay math).
- Fabric **gametests**: flat-terrain crater, rock cooling, protected zone respect, exact undo restoration, `terrainDestruction=false`.
- Shot preview command for visual validation, paired with a **reference screenshot checklist** refreshed each release.
- Clean automated build (`./gradlew build`) with zero compiler warnings.
- Clear, commented, unobfuscated code; no client class loaded on the server.

---

## 17. Acceptance Criteria

**v1 Fixes**
- [ ] No more "white moon" on Earth; no more overflowing ring colors (all colors clamped).
- [ ] Desaturation and flash clearly visible in-game.
- [ ] No more stepped walls: natural terraces and slopes.

**Staging**
- [ ] All 17 storyboard shots present in sequence with no hard cuts.
- [ ] Sky streak visible **in the real sky** from the ground (shot 10), for both striker and observers.
- [ ] Silence genuinely muffles sounds (low-pass), and sound arrives with accurate distance delay.
- [ ] Bloom, god rays, motion blur, and lens flare work and respect quality settings.

**World**
- [ ] Crater with terraces, central peak, ejecta, rays, scorch zone, and felled trees.
- [ ] Molten rock progresses through all 4 stages and cools.
- [ ] Smoke plume visible for at least 30 s.
- [ ] `/orbitalstrike undo` restores the prior terrain block-for-block.

**Product**
- [ ] 3D Detonator with animated lid.
- [ ] Menu and config screen with visual identity, animations, and UI sounds.
- [ ] Photosensitivity mode, shake slider, complete English subtitles.
- [ ] Performance budgets in §14 met and measured.
- [ ] Works with Sodium and Iris (with and without shader packs), on dedicated server and 2-player multiplayer.
- [ ] Complete `CREDITS.txt`: no asset without a clear license.

---

## 18. Roadmap

| Milestone | Scope | Expected Outcome |
|---|---|---|
| **M1. v1 Fixes** | Color clamping, polar cap, visible screen effects, terraces | Clean, stable v1 |
| **M2. Render Engine** | 3D pipeline, HDR target, bloom, tone mapping, model loading, planet shaders | Lit planet with bloom at 60 FPS |
| **M3. Data-Driven Cinematics** | Keyframe player, JSON file, preview command, transitions, animated letterbox | Entire SPACE storyboard tunable without recompiling |
| **M4. WORLD Shots** | Detached camera, cloud-piercing ascent, sky streak in the real sky | Shots 3 and 10 complete |
| **M5. Crater v2** | Async plan, terraces, ejecta, scorch zone, felled trees, molten rock, undo | Green gametests |
| **M6. Audio** | Final sounds, low-pass filter, near/far variants, stereo panning | Mix validated on headphones |
| **M7. UI** | Targeting HUD, decal, menu, config screen, 3D Detonator | "Product"-grade interface |
| **M8. Polish** | Accessibility, Sodium & Iris compatibility, performance benchmarks, reference shots | Ship-ready version 2.0.0 |
