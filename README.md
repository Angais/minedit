# Minedit

Minedit is an experimental NeoForge mod for building and editing Minecraft structures with AI models through OpenRouter or a local Codex bridge.

Select a footprint with a stick, describe what you want, and Minedit asks a model to generate compact builder code that places blocks in the selected area. It can also edit existing builds with compact line-aware patches, generate builds in focused stages, and run a Codex-powered agent mode.

## Status and Risk

Minedit is a work in progress. Expect things to break.

This mod sends prompts to the provider you configure. OpenRouter requests use the API key you configure. Codex local bridge requests use your local Codex/OpenAI login and may consume Codex, ChatGPT, or OpenAI plan limits. Depending on your provider, model, account, and usage, requests may cost money or consume plan limits. You are responsible for all usage and charges caused by your configured provider. Use this mod at your own risk. The author is not responsible for unexpected costs, world changes, broken builds, broken saves, or other side effects.

Your OpenRouter API key is stored in plaintext in your Minecraft game directory at `config/minedit.properties`. It is not stored per-world. Do not share this file, screenshots of it, modpacks containing it, or support logs that include it.

Back up worlds before testing large builds, staged builds, agent builds, or edits.

## Requirements

- Minecraft `26.1.2`
- NeoForge `26.1.2.73`
- Java 25 for development/building
- OpenRouter API key for OpenRouter mode
- Node.js 18+ and the Codex CLI for local Codex bridge mode

## Installation

You can either download a prebuilt jar from the GitHub Releases page or build it yourself.

### Download a Release

1. Download the latest jar from the [Minedit releases page](https://github.com/Angais/minedit/releases).
2. Copy the jar into your Minecraft `mods` folder.
3. Start the NeoForge profile.

### Build from Source

```sh
./gradlew build
```

Copy the jar from `build/libs/` into your Minecraft `mods` folder, then start the NeoForge profile.

## Quick Start

Minedit uses OpenRouter by default.

```mcfunction
/apikey <your-openrouter-key>
/model openai/gpt-5.5
```

Select two X/Z footprint corners by right-clicking blocks with a stick, then run:

```mcfunction
/build a detailed medieval blacksmith
```

Minedit uses the selected X/Z area as the footprint. Height is not capped by the selection.

## Build Modes

### Normal Build

One model call generates the whole build:

```mcfunction
/build a cute house
```

Build mode clears existing non-air blocks in the selected footprint before placing the generated structure.

### Staged Build

Several focused model calls build the structure in phases:

```mcfunction
/build stages a detailed wizard tower
```

The staged builder currently runs these stages:

1. Foundation and frame
2. Walls, openings, doors, and windows
3. Roof, ceilings, stairs, and vertical access
4. Interior lighting and furniture
5. Exterior detail and landscaping
6. Final corrections and polish

Each stage receives the previous stage code as context and should only output incremental work for the current stage. This usually costs more than `/build`, but it gives the model more focus per phase.

### Codex Agent Build

Codex agent modes only work with the local Codex bridge:

```mcfunction
/provider codex-local
/build agent <prompt>
/build agent step-by-step <prompt>
```

`/build agent <prompt>` asks Codex to draft, preview, and revise before Minecraft places the final build.

`/build agent step-by-step <prompt>` starts one Codex App Server agent turn with Minedit dynamic tools. Codex can call tools such as `place_step`, `render_preview`, `inspect_status`, and `finish_build` while it works. Minecraft applies placement steps as Codex emits them.

## Editing

Use `/edit` to modify the selected area based on its current blocks:

```mcfunction
/edit make the roof steeper and add windows
```

Use quick edit for small targeted patches:

```mcfunction
/edit quick remove the flower and change the oak planks to spruce
```

Normal edit and quick edit use a compact line-aware representation of the current build, so models can emit small patches like `api.replaceLine(...)`, `api.clearLine(...)`, `api.set(...)`, or `api.fill(...)` instead of rebuilding unchanged geometry.

Set quick edit reasoning effort:

```mcfunction
/edit set quickeffort low
```

## Local Codex Bridge

The local Codex bridge lets Minecraft talk to `codex app-server` through a localhost HTTP server.

Requirements:

- Node.js 18+
- Codex CLI installed and logged in
- This repository or source zip available locally, because the bridge code lives in `bridge/`

Log in once if needed:

```sh
codex login
```

Start the bridge from the repository:

```sh
npm --prefix bridge start
```

The bridge listens on:

```text
http://127.0.0.1:8765
```

Then in Minecraft:

```mcfunction
/provider codex-local
/codexurl http://127.0.0.1:8765
/codex status
/model gpt-5.5
```

Codex model ids usually do not use the OpenRouter `openai/` prefix. The bridge strips `openai/` automatically, so `openai/gpt-5.5` becomes `gpt-5.5`, but setting `/model gpt-5.5` is clearer when using Codex.

## Settings Commands

```mcfunction
/provider openrouter
/provider codex-local
/apikey <openrouter-key>
/codexurl http://127.0.0.1:8765
/codex status
/model <model-id>
/effort none
/effort minimal
/effort low
/effort medium
/effort high
/effort xhigh
/effort max
/streaming enabled
/streaming disabled
/stop
/status
/usage <openrouter-generation-id>
```

Defaults:

```text
provider: openrouter
model: openai/gpt-5.5
normal effort: medium
quick edit effort: low
OpenRouter streaming: enabled
```

`/streaming enabled` streams OpenRouter responses and shows progress/reasoning summaries when the provider sends them. `/streaming disabled` waits for the full response before showing usage and queueing placement.

`/stop` requests cancellation for your current Minedit generation and removes your queued block placement jobs. It can interrupt OpenRouter streams and queued placement immediately. Codex agent jobs are also cancelled through the local bridge when possible.

`/status` shows the current provider, selected model, normal reasoning effort, quick edit reasoning effort, streaming setting, key/bridge configuration, current selection, active AI generations, and queued block placement jobs.

Settings are saved in `config/minedit.properties`. The OpenRouter API key in that file is plaintext and belongs to the whole Minecraft game directory/profile, not a single world. The Codex bridge URL and provider selection are also stored there. If you used an older build, Minedit will try to read the legacy `config/aibuilder.properties` file.

## OpenRouter Usage and Cost

After OpenRouter builds/edits, Minedit prints usage data when available:

- input tokens
- reasoning tokens
- output tokens
- estimated cost or BYOK upstream inference cost
- model
- finish reason
- generation id

If OpenRouter's final generation metadata already includes cost, the first usage line includes it. Otherwise Minedit keeps checking in the background and sends a separate cost line when it becomes available. You can also run `/usage <generation-id>` to manually fetch the latest generation usage.

Minedit only displays usage fields. It does not print API keys.

OpenRouter streaming mode shows progress and provider-supplied reasoning summaries when available. It does not display raw hidden chain-of-thought.

## Reset Commands

Undo the last generated build/edit for your player:

```mcfunction
/reset build
```

Clear the current selection:

```mcfunction
/reset selection
```

## Examples

Model output depends on the model, effort setting, selected footprint, and surrounding world state. These examples show one generated build and three follow-up edits.

Build generation:

```mcfunction
/build a cute house
```

![Generated cute house](docs/examples/01-build-cute-house.png)

Quick edit:

```mcfunction
/edit quick make the walls red please
```

![Quick edit changing the walls red](docs/examples/02-quick-edit-red-walls.png)

Quick edit:

```mcfunction
/edit quick can you please change the wood for stone? a cool one
```

![Quick edit changing wood details to stone](docs/examples/03-quick-edit-stone-wood.png)

Quick edit:

```mcfunction
/edit quick don't really like those plants outside, can you remove them?
```

![Quick edit removing outside plants](docs/examples/04-quick-edit-remove-plants.png)

## Debug Files

When a model response fails, Minedit writes debug files to:

```text
config/minedit-debug/
```

Useful files:

- `last-prompt.txt`
- `last-response.txt`
- `last-build.js`

These files may contain your prompts and generated code. They should not contain API keys, but review them before sharing.

## Notes on Generated Builds

Minedit prompts models to avoid common Minecraft placement problems such as unsupported plants, inverted roofs, stair orientation mistakes, unreachable stairs or ladders, missing landings, roof gaps, trapped doors, missing lintels/header blocks above doors, isolated pane/fence/wall slivers, blocked paths, cramped rooms, low ceilings, short support pillars, blocked window views, empty rooms, under-decorated upper floors, unlit interiors, and fragile blocks without support. It also prompts models to treat non-enterable builds such as statues, monuments, fountains, terrain features, vehicles, and decorative objects differently from houses. It also checks Minecraft block survival rules before placing blocks, so unsupported fragile blocks may be skipped.

Model output is still imperfect. Use `/reset build` and world backups while testing.

## Credits and Third-Party Technology

- Built with the NeoForge MDK template. The template files are MIT licensed by the NeoForged project; see `TEMPLATE_LICENSE.txt`.
- Uses NeoForge for Minecraft mod loading and APIs.
- Bundles Mozilla Rhino `1.8.0` as the JavaScript runtime through NeoForge Jar-in-Jar. Rhino is licensed under the Mozilla Public License 2.0: https://www.mozilla.org/MPL/2.0/
- Uses OpenRouter's OpenAI-compatible chat completions API.
- Optionally uses the OpenAI Codex app-server through the local `bridge/` helper.

## License

Minedit is currently published as All Rights Reserved unless a separate license is added later. NeoForge MDK template files keep their original MIT license, documented in `TEMPLATE_LICENSE.txt`.
