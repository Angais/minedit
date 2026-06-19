# Changelog

## 1.6.0

### Added

- `/openrouter provider list` lists the available inference-provider slugs for the selected OpenRouter model.
- `/openrouter provider <slug>` locks OpenRouter requests to one inference provider with fallbacks disabled.
- `/openrouter provider auto` restores OpenRouter's automatic provider routing.

## 1.5.1

### Added

- Cursor local bridge provider: `/provider cursor`, `/model list cursor`, and build/edit/staged/agent modes through the Cursor CLI.

### Changed

- Cursor CLI timeout raised from 10 minutes to 90 minutes (override with `MINEDIT_CURSOR_TIMEOUT_MS`).
- Mod-side Cursor request and agent job timeouts raised to match the longer CLI timeout.

## 1.5.0

### Added

- `/build export <prompt>` writes the exact normal build prompt to `config/minedit-debug/export-prompt.txt` for use with an external model.
- `/build import` reads `config/minedit-debug/import-build.js`, parses a full model response or raw `function build(api) { ... }` code, and queues it through the normal build path.
- GitHub Actions now uploads the built mod jar as an artifact.

### Changed

- `/effort max` and `/edit set quickeffort max` are now accepted for models that support max reasoning effort.

## 1.4.0

### Changed

- Door-header prompts are now explicit about Minecraft door height: doors occupy two blocks, and the row directly above the upper door half must be filled with a lintel, beam, wall/trim, or deliberately framed transom.
- Build, staged build, and Codex agent prompts now call out the exact bad pattern where a doorway is cleared through the header row and then only the two door blocks are replaced.

## 1.3.0

### Added

- `/streaming enabled|disabled` for OpenRouter streaming control.
- `/stop` to stop active generation work and queued placement jobs.
- Cancellation support for OpenRouter streams and Codex local agent jobs.

### Changed

- OpenRouter can now run in non-streaming mode while still reporting usage and cost.
- Stair orientation prompts now call out that stair `facing` is the high/full/back side, so entrance steps, awnings, and roof rows should usually face toward the building wall or roof ridge.
- Build prompts now require comfortable interior scale by default: 3 clear air blocks above floors, no unusably tiny furnished rooms, full-height supports, and unobstructed window views.
- Build prompts now require reachable vertical access: stairs need approach space, landing space, headroom, and enough horizontal run, otherwise models should use ladders, exterior stair towers, or fewer floors.
- Spiral stair prompts are stricter: models should only use them with a true 3x3+ shaft, clear turn space, landings, and player headroom over every step.
- Build prompts now classify the requested build type first, so house/interior rules are not forced onto statues, monuments, fountains, vehicles, terrain features, or decorative builds.
- Door and interior prompts now call out accidental air gaps above doors and under-lit or under-decorated upper floors.
- Glass pane, fence, wall, iron bar, and chain prompts now require visible connections or explicit connection states instead of isolated slivers in mostly-air openings.

## 1.2.0

Release-readiness update for public testing.

### Added

- `/build stages <prompt>` for multi-call staged generation:
  - foundation and frame
  - walls, openings, doors, and windows
  - roof, ceilings, stairs, and vertical access
  - interior lighting and furniture
  - exterior detail and landscaping
  - final corrections and polish
- Codex local agent mode with tool-driven `step-by-step` placement.
- OpenRouter streaming progress for builds/edits.
- OpenRouter usage and cost reporting, including BYOK upstream inference cost when available.
- `/status` command for provider/model/selection/generation/queue state.
- README release documentation, warnings, examples, and usage docs.

### Changed

- Normal build mode clears existing blocks in the selected footprint before placing the generated build.
- Build prompts now strongly emphasize interiors, lighting, furniture, roof correctness, paths, doors, block orientation, support-sensitive blocks, and fluid containment.
- Edit and quick edit modes use compact line-aware patches for more token-efficient changes.
- Codex bridge integration now supports dynamic tools for agent builds.

### Notes

- OpenRouter API keys are stored in plaintext at `config/minedit.properties`.
- Codex local bridge usage may consume Codex, ChatGPT, or OpenAI plan limits.
- This is still experimental. Back up worlds before testing.
