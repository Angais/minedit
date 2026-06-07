package com.angel.aibuilder.openrouter;

import com.angel.aibuilder.selection.BuildSelection;

public final class PromptFactory {
    private static final String PHYSICS_AND_ORIENTATION_RULES = """
                Minecraft placement quality:
                - For roofs, stairs, slabs, trapdoors, doors, logs, panes, fences, and directional blocks, set explicit block states when orientation matters.
                - Be careful with roof direction: use stair facing/half/shape consistently so roofs slope outward, corners meet, and stairs are not inverted unless intentionally decorative.
                - Avoid roof air gaps. Overlap or cap ridges/corners with slabs, stairs, logs, full blocks, or matching trim.
                - Do not leave accidental empty holes in walls, floors, corners, or roof seams.
                - Place support blocks before dependent blocks.
                - Plants, flowers, saplings, crops, carpets, rails, ladders, torches, lanterns, buttons, pressure plates, vines, and similar fragile blocks need valid support. Put them on/against blocks that can hold them.
                - Do not place plants or flowers on stone, wood, glass, slabs, stairs, or air. Use grass_block, dirt, coarse_dirt, podzol, rooted_dirt, moss_block, farmland, sand, or other valid support as appropriate.
                - Hanging blocks like lanterns/chains should have a solid block, chain, or suitable support above when hanging=true; standing variants need support below.
                - If uncertain about a fragile decorative block's support rules, use a safer full-block decoration instead.
                - Fluid safety: if using water or lava, build a closed basin, channel, pipe, or retaining rim before placing fluid blocks.
                - Do not place water or lava source blocks at the selected footprint edge unless the user explicitly asks for a spill leaving the area.
                - For fountains, put a solid basin floor under the water and a rim high enough to keep water inside the selected footprint.
                - For falling water, include a catch basin below and close side gaps so the fluid cannot escape outside the build area.
                """;

    private PromptFactory() {
    }

    public static String create(BuildSelection selection, String userPrompt) {
        return """
                You are controlling a Minecraft builder API inside NeoForge Minecraft 26.1.2.
                Generate compact Rhino-compatible JavaScript. Do not describe the build outside code.

                The selected footprint is width=%d blocks on X and depth=%d blocks on Z.
                Height is unconstrained, but keep the build reasonable for an in-game personal mod.
                Coordinates are relative: x=0..width-1, z=0..depth-1, y=0 is the selected ground level.

                You have this API:
                - api.width, api.depth
                - api.getWidth(), api.getDepth()
                - api.set(x, y, z, blockId, states?)
                - api.fill(x1, y1, z1, x2, y2, z2, blockId, options?)
                - api.replaceLine(lineNumber, blockId, states?) changes the exact region from numbered current line L#
                - api.clearLine(lineNumber) replaces the exact region from numbered current line L# with air
                - options.mode can be "replace", "keep", "hollow", or "outline"
                - blockId examples: "stone_bricks", "oak_planks", "glass", "spruce_stairs"
                - states example: { facing: "north", half: "top" }

                Important:
                - Use loops, helper functions, symmetry, and fills. Be procedural and concise.
                - Build mode starts from a cleared blank volume in the selected footprint. Do not rely on existing cliffs, trees, terrain, or blocks inside it.
                - Place any ground plane, foundation, basin floor, supports, or terrain you need explicitly.
                - Stay inside x/z bounds. y can grow upward.
                %s
                - Return only one JavaScript function named build.
                - The function signature must be: function build(api) { ... }
                - Use ES5 JavaScript only: var, function, for loops, arrays, plain objects.
                - Do not use let, const, arrow functions, classes, template strings, async, await, import, export, require, fetch, Java classes, comments with markdown fences, or TypeScript.
                - Do not use trailing commas.
                - Return raw code only, no markdown, no prose.

                Good output shape:
                function build(api) {
                  var w = api.getWidth();
                  var d = api.getDepth();
                  api.fill(0, 0, 0, w - 1, 0, d - 1, "stone_bricks");
                  for (var x = 1; x < w - 1; x += 2) {
                    api.set(x, 1, 1, "lantern");
                  }
                }

                User request:
                %s
                """.formatted(selection.width(), selection.depth(), PHYSICS_AND_ORIENTATION_RULES, userPrompt);
    }

    public static String edit(BuildSelection selection, String existingStructure, String userPrompt) {
        return """
                You are editing an existing Minecraft structure inside NeoForge Minecraft 26.1.2.
                Generate compact Rhino-compatible JavaScript. Do not describe the build outside code.

                The selected footprint is width=%d blocks on X and depth=%d blocks on Z.
                Coordinates are relative: x=0..width-1, z=0..depth-1, y=0 is the selected ground/base level.

                You have this API:
                - api.width, api.depth
                - api.getWidth(), api.getDepth()
                - api.set(x, y, z, blockId, states?)
                - api.fill(x1, y1, z1, x2, y2, z2, blockId, options?)
                - options.mode can be "replace", "keep", "hollow", or "outline"

                Existing structure context:
                %s

                Editing rules:
                - Treat the numbered current lines as the compact source code representation of the selected area's current blocks.
                - Preserve useful existing structure unless the user asks to replace it.
                - Make targeted changes that satisfy the edit request.
                - You may use "air" to remove blocks.
                - Prefer api.replaceLine(...) and api.clearLine(...) when a numbered line exactly matches what should change.
                - For material swaps or removals, patch relevant numbered lines instead of rewriting unchanged geometry.
                - For additions or shape changes, use api.set/api.fill only for the changed/new blocks.
                %s
                - Output only the new build(api) function containing the changes to apply on top of the current world.
                - Do not recreate unchanged parts unless the user explicitly asks for a full rebuild.
                - Stay inside x/z bounds. y can grow upward.
                - Return only one JavaScript function named build.
                - The function signature must be: function build(api) { ... }
                - Use ES5 JavaScript only: var, function, for loops, arrays, plain objects.
                - Do not use let, const, arrow functions, classes, template strings, async, await, import, export, require, fetch, Java classes, comments with markdown fences, or TypeScript.
                - Do not use trailing commas.
                - Return raw code only, no markdown, no prose.

                User edit request:
                %s
                """.formatted(selection.width(), selection.depth(), existingStructure, PHYSICS_AND_ORIENTATION_RULES, userPrompt);
    }

    public static String quickEdit(BuildSelection selection, String existingStructure, String userPrompt) {
        return """
                You are making a quick, targeted Minecraft edit inside NeoForge Minecraft 26.1.2.
                Generate very compact Rhino-compatible JavaScript. Do not describe the edit outside code.

                The selected footprint is width=%d blocks on X and depth=%d blocks on Z.
                Coordinates are relative: x=0..width-1, z=0..depth-1, y=0 is the selected ground/base level.

                You have this API:
                - api.set(x, y, z, blockId, states?)
                - api.fill(x1, y1, z1, x2, y2, z2, blockId, options?)
                - api.replaceLine(lineNumber, blockId, states?) changes the exact region from numbered current line L#
                - api.clearLine(lineNumber) replaces the exact region from numbered current line L# with air

                Existing structure context:
                %s

                Quick edit rules:
                - Prefer api.replaceLine(...) and api.clearLine(...) whenever a numbered line exactly matches what should change.
                - For material swaps, call api.replaceLine for the relevant current lines instead of rewriting geometry.
                - For removal, call api.clearLine for the relevant current lines.
                - For small additions, use api.set or api.fill with only the new blocks.
                - Do not recreate unchanged parts.
                %s
                - Return only one JavaScript function named build.
                - The function signature must be: function build(api) { ... }
                - Use ES5 JavaScript only: var, function, for loops, arrays, plain objects.
                - Do not use let, const, arrow functions, classes, template strings, async, await, import, export, require, fetch, Java classes, comments with markdown fences, or TypeScript.
                - Return raw code only, no markdown, no prose.

                Good output shape:
                function build(api) {
                  api.clearLine(12);
                  api.replaceLine(4, "spruce_planks");
                  api.set(6, 5, 2, "lantern");
                }

                User quick edit request:
                %s
                """.formatted(selection.width(), selection.depth(), existingStructure, PHYSICS_AND_ORIENTATION_RULES, userPrompt);
    }
}
