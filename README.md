# Minedit

Minedit is an experimental NeoForge mod for building and editing Minecraft structures with OpenRouter models.

Select a footprint with a stick, describe what you want, and Minedit asks a model to generate compact builder code that places blocks in the selected area. It also supports editing existing builds with compact line-aware patches.

## Status and Risk

Minedit is a work in progress. Expect things to break.

This mod sends prompts to OpenRouter using the API key you configure. Depending on your OpenRouter account, model, and usage, requests may cost money. You are responsible for all usage and charges caused by your API key. Use this mod at your own risk. The author is not responsible for unexpected costs, world changes, broken builds, or other side effects.

Your OpenRouter API key is stored in plaintext in your Minecraft game directory at `config/minedit.properties`. It is not stored per-world. Do not share this file, screenshots of it, modpacks containing it, or support logs that include it.

Back up worlds before testing large builds or edits.

## Requirements

- Minecraft `26.1.2`
- NeoForge `26.1.2.73`
- Java 25 for development/building
- An OpenRouter API key

## Installation

1. Build the jar:

   ```sh
   ./gradlew build
   ```

2. Copy the jar from `build/libs/` into your Minecraft `mods` folder.

3. Start the NeoForge profile.

## Basic Use

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
/model <openrouter-model-id>
```

Default model:

```text
openai/gpt-5.5
```

Set normal reasoning effort:

```mcfunction
/effort none
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

Settings are saved in `config/minedit.properties`. The OpenRouter API key in that file is plaintext and belongs to the whole Minecraft game directory/profile, not a single world. If you used an older build, Minedit will try to read the legacy `config/aibuilder.properties` file.

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
