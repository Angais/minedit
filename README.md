# Minedit

Minedit is an experimental NeoForge mod for building and editing Minecraft structures with AI models through OpenRouter or a local Codex bridge.

Select a footprint with a stick, describe what you want, and Minedit asks a model to generate compact builder code that places blocks in the selected area. It also supports editing existing builds with compact line-aware patches.

## Status and Risk

Minedit is a work in progress. Expect things to break.

This mod sends prompts to the provider you configure. OpenRouter requests use the API key you configure. Codex local bridge requests use your local Codex/OpenAI login and may consume Codex or ChatGPT limits. Depending on your provider, model, account, and usage, requests may cost money or consume plan limits. You are responsible for all usage and charges caused by your configured provider. Use this mod at your own risk. The author is not responsible for unexpected costs, world changes, broken builds, or other side effects.

Your OpenRouter API key is stored in plaintext in your Minecraft game directory at `config/minedit.properties`. It is not stored per-world. Do not share this file, screenshots of it, modpacks containing it, or support logs that include it.

Back up worlds before testing large builds or edits.

## Requirements

- Minecraft `26.1.2`
- NeoForge `26.1.2.73`
- Java 25 for development/building
- Either an OpenRouter API key, or Node.js 18+ plus the Codex CLI for the local Codex bridge

## Installation

You can either download a prebuilt jar from the GitHub Releases page or build it yourself.

### Download a Release

1. Download the latest jar from the [Minedit releases page](https://github.com/Angais/minedit/releases).

2. Copy the jar into your Minecraft `mods` folder.

3. Start the NeoForge profile.

### Build from Source

1. Build the jar:

   ```sh
   ./gradlew build
   ```

2. Copy the jar from `build/libs/` into your Minecraft `mods` folder.

3. Start the NeoForge profile.

## Basic Use

Minedit uses OpenRouter by default.

1. Set your OpenRouter key:

   ```mcfunction
   /apikey <your-openrouter-key>
   ```

2. Optionally choose a model:

   ```mcfunction
   /model openai/gpt-5.5
   ```

3. Select two X/Z footprint corners by right-clicking blocks with a stick.

4. Build something:

   ```mcfunction
   /build small stone watchtower with a peaked roof
   ```

Minedit uses the selected X/Z area as the footprint. Height is not capped by the selection.

## Local Codex Bridge

You can use a local Codex bridge instead of OpenRouter. This keeps the Minecraft mod simple: Minecraft calls a small localhost HTTP server, and that server talks to `codex app-server`.

Requirements:

- Node.js 18+
- Codex CLI installed and logged in
- This repository or source zip available locally, because the bridge code lives in `bridge/`

Run this once in a terminal if Codex is not logged in:

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

Codex model ids usually do not use the OpenRouter `openai/` prefix. The bridge will strip `openai/` automatically, so `openai/gpt-5.5` becomes `gpt-5.5`, but setting `/model gpt-5.5` is clearer when using Codex.

## Examples

Model output depends on the model, effort setting, selected footprint, and surrounding world state. These examples show one generated build and three follow-up edits.

Build generation:

```mcfunction
/build a cute house
```

![Generated cute house](docs/examples/01-build-cute-house.png)

Edit or quick edit:

```mcfunction
/edit quick make the walls red please
```

![Quick edit changing the walls red](docs/examples/02-quick-edit-red-walls.png)

Edit or quick edit:

```mcfunction
/edit quick can you please change the wood for stone? a cool one
```

![Quick edit changing wood details to stone](docs/examples/03-quick-edit-stone-wood.png)

Edit or quick edit:

```mcfunction
/edit quick don't really like those plants outside, can you remove them?
```

![Quick edit removing outside plants](docs/examples/04-quick-edit-remove-plants.png)

## Editing

Use `/edit` to modify the selected area based on its current blocks:

```mcfunction
/edit make the roof steeper and add windows
```

Use quick edit for small targeted patches:

```mcfunction
/edit quick remove the flower and change the oak planks to spruce
```

Quick edit uses a compact line-aware representation of the current build, so models can emit small patches like `api.replaceLine(...)`, `api.clearLine(...)`, `api.set(...)`, or `api.fill(...)` instead of rebuilding the whole structure.

## Settings Commands

Set the normal build/edit model:

```mcfunction
/model <model-id>
```

Default model:

```text
openai/gpt-5.5
```

Provider commands:

```mcfunction
/provider openrouter
/provider codex-local
/codexurl http://127.0.0.1:8765
/codex status
/status
```

`/status` shows the current provider, selected model, normal reasoning effort, quick edit reasoning effort, key/bridge configuration, current selection, active AI generations, and queued block placement jobs.

After OpenRouter builds/edits, Minedit prints a usage line when OpenRouter returns generation metadata: input tokens, reasoning tokens, output tokens, cost/usage, finish reason, and generation id. Minedit only displays those usage fields and does not print account IDs or API key details.

Set normal reasoning effort:

```mcfunction
/effort none
/effort minimal
/effort low
/effort medium
/effort high
/effort xhigh
```

Default normal effort:

```text
medium
```

Set quick edit reasoning effort:

```mcfunction
/edit set quickeffort low
```

Default quick edit effort:

```text
low
```

Settings are saved in `config/minedit.properties`. The OpenRouter API key in that file is plaintext and belongs to the whole Minecraft game directory/profile, not a single world. The Codex bridge URL and provider selection are also stored there. If you used an older build, Minedit will try to read the legacy `config/aibuilder.properties` file.

## Reset Commands

Undo the last generated build/edit for your player:

```mcfunction
/reset build
```

Clear the current selection:

```mcfunction
/reset selection
```

## Debug Files

When a model response fails, Minedit writes debug files to:

```text
config/minedit-debug/
```

Useful files:

- `last-prompt.txt`
- `last-response.txt`
- `last-build.js`

## Notes on Generated Builds

Minedit prompts models to avoid common Minecraft placement problems such as unsupported plants, inverted roofs, stair orientation mistakes, roof gaps, and fragile blocks without support. It also checks Minecraft block survival rules before placing blocks, so unsupported fragile blocks may be skipped.

Model output is still imperfect. Use `/reset build` and world backups while testing.

## Credits and Third-Party Technology

- Built with the NeoForge MDK template. The template files are MIT licensed by the NeoForged project; see `TEMPLATE_LICENSE.txt`.
- Uses NeoForge for Minecraft mod loading and APIs.
- Bundles Mozilla Rhino `1.8.0` as the JavaScript runtime through NeoForge Jar-in-Jar. Rhino is licensed under the Mozilla Public License 2.0: https://www.mozilla.org/MPL/2.0/
- Uses OpenRouter's OpenAI-compatible chat completions API.
- Optionally uses the OpenAI Codex app-server through the local `bridge/` helper.
