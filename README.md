# Entity Render Debugger

A Fabric client mod for **Minecraft 1.21.11** that keeps the game alive when entity or particle
rendering is broken — merged from *entity-renderer-debugger* (by Fallen_Breath) and
*particle-render-monitor*.

## What it does

Instead of closing the client, every interception point below turns the failure into a
**chat message + log entry + detailed report file on disk**:

### Entity rendering (`net.minecraft.class_898` / `EntityRenderManager`)
- **`method_3950` / `EntityRenderManager#shouldRender`** — reimplemented with a full guard:
  a missing renderer (vanilla NPE, no null check) or a throwing renderer reports and returns
  "not visible" instead of crashing. *Any* call attempt of `method_3950` can no longer crash.
- **`EntityRenderManager#getAndUpdateRenderState`** — vanilla wraps every failure into
  `CrashException "Extracting render state for an entity in world"`; the debugger substitutes a
  blank render state (all consumers of the blank state are guarded as well).
- **`EntityRenderManager#getLight` (`method_23839`)** — vanilla does
  `getRenderer(entity).getLight(...)` with no null check, and `GameRenderer#renderHand` calls it
  with the **local player** every frame, so a missing player renderer (startup/reload race) NPEs
  the whole frame there. The guard reports the incident and returns light 0 instead; every other
  `getLight` caller is covered too. The leash-holder lookups inside `EntityRenderer#shouldRender`
  and `#updateRenderState` are covered by the guards on those two methods.
- **`EntityRenderManager#render`** call sites (`WorldRenderer#pushEntityRenders`,
  GUI entity elements, item pickup particles, mob/trial spawner displays) — vanilla wraps every
  failure into `CrashException "Rendering entity in world"`; the failing entity is skipped instead.
- **Missing renderer detection** (from entity-renderer-debugger): both `getRenderer` overloads are
  monitored; reports entity type, owner mod, entity classes, display name, UUID, network id,
  dimension, position and full NBT.

### Particles (from particle-render-monitor)
- Failing particle **factories** (`createParticle`), **ticks** (`tickParticle`) and
  **renders** (billboard + whole sheet batches) skip the offending particle/batch instead of
  crashing ("Unable to create particle" / "Ticking Particle" / "Rendering Particle").
- **Missing sprite sets** and **null sprites** fall back to the atlas `missingno` sprite.

### Reporting
- Core info in the **client chat** (with hoverable full stack trace) and the **log**.
- A detailed report per incident is written to
  `<game dir>/entity_render_debugger/reports/<timestamp>_<category>_<subject>.txt`,
  containing environment info, subject details (incl. entity NBT), the full stack trace,
  a **root-cause analysis** (missing dependencies, binary incompatibilities, missing sprite
  JSONs, threading bugs, recursion, ...) and a mod attribution derived from the stack trace.

## Configuration

`config/entity_render_debugger.json`:

```json
{
  "enabled": true,
  "chatReports": true,
  "logStackTraces": true,
  "writeReports": true,
  "chatCooldownMs": 5000
}
```

## Building

```
./gradlew build
```

Requires Java 21. The jar lands in `build/libs/`.

## License

The Unlicense (public domain) — see [UNLICENSE](UNLICENSE).
