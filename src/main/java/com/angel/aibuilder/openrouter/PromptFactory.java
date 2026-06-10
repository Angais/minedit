package com.angel.aibuilder.openrouter;

import com.angel.aibuilder.selection.BuildSelection;

public final class PromptFactory {
    private static final String PHYSICS_AND_ORIENTATION_RULES = """
                Minecraft placement quality:
                - For roofs, stairs, slabs, trapdoors, doors, logs, panes, fences, and directional blocks, set explicit block states when orientation matters.
                - Doors and gates need deliberate facing, hinge, open, and powered states. Face them into the doorway correctly, put the hinge on the sensible wall side, and avoid doors that open into walls, furniture, stairs, or exterior trim.
                - Door openings should read from both outside and inside. If a door looks inverted from the approach side, flip facing or hinge rather than leaving it backwards.
                - Doorways, gates, arches, ladders, stairwells, and corridors need clear air space. Do not put roofs, slabs, carpets, lights, walls, furniture, fences, or decorations in the player's path through them.
                - Be careful with roof direction: use stair facing/half/shape consistently so roofs slope outward, corners meet, and stairs are not inverted unless intentionally decorative.
                - Stair facing rule: for Minecraft stairs, facing is the direction of the stair's high/full/back side; the low/open step is on the opposite side. Do not blindly use the outside direction.
                - Trapdoors need deliberate facing, half, and open states. Closed shutters, hatches, counters, and floor covers should close in the intended direction and not block entrances or paths by accident.
                - Slabs are visually half-height but still occupy a block cell. Use type/top/bottom/double intentionally; use double slabs or full blocks for solid-looking floors, walls, caps, and roof seams when half-slabs would leave visible air gaps.
                - Do not stack or mix slabs in ways that create accidental holes under walls, above ceilings, at roof edges, beside stairs, or around doorways.
                - Fences, walls, panes, and gates should connect logically. Use posts/corners/gates/adjacent blocks so railings and enclosures read as continuous, not as random isolated pieces.
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

    private static final String DETAILED_BUILD_RULES = """
                Default build quality:
                - Unless the user asks for something simple, minimalist, empty, abandoned, or rough, make the build rich and finished. "Compact code" means procedural helper functions and loops, not sparse geometry.
                - Use repeated helper functions to add many details efficiently: pillars, wall trim, beams, window frames, shelves, tables, chairs, beds, counters, rugs, lamps, railings, stair details, plants, banners, supports, and exterior landscaping.
                - Do not produce empty shells, empty towers, empty corridors, or plain unfurnished rooms. Every enclosed room or tower floor must have a visible purpose and at least a few appropriate details.
                - Give interiors real structure: floors, ceilings, partitions, stair/ladders, landings, room transitions, support columns, wall trim, windows, and reachable walkways.
                - Every room, hallway, basement, attic, tower level, and enclosed corner needs lighting. Prefer ceiling chains with lanterns, wall torches, chandeliers, lamps on supports, or hidden glowstone/sea_lantern fixtures that fit the style.
                - Large builds need multiple functional zones. Add bedrooms, storage, workshop, kitchen, throne room, library, guard room, balconies, tower rooms, or utility spaces as appropriate to the prompt.
                - Towers must not be hollow tubes. Add internal floors every 4-6 blocks, ladders/stairs or spiral access, slit windows, lights, railings, and small furniture/decor on each level.
                - Avoid lazy flat boxes. Walls should have depth, columns, buttresses, beams, windows, alcoves, material variation, trim, or relief that matches the style.
                - Keep pathways usable. Main routes should be at least 2 blocks tall and usually 2 blocks wide. Do not let fountains, walls, counters, furniture, plants, roofs, carpets, gates, or decorations block walking routes.
                - Doors, gates, arches, and entrances must be reachable, correctly oriented, visually aligned with the wall, and unblocked on both sides. If you place controls, put usable controls for both entry and exit.
                - Place furniture along walls or in clear zones. Leave central aisles and door swings clear, especially in small rooms and tower landings.
                - Set intentional states for stateful blocks: doors/gates/trapdoors should be open or closed deliberately; levers should be powered or unpowered deliberately; stairs/slabs/lanterns should have correct orientation/half/hanging states.
                - Prefer ceiling chains, hanging lanterns, wall torches, chandeliers, or integrated fixtures over random floor lighting unless floor lights are intentional and visually supported.
                - Keep the style consistent. Materials, roof shape, decorations, landscaping, and interior choices should look like one coherent build, not unrelated chunks.
                - Make shapes readable. Avoid confusing silhouettes, random blobs, unexplained protrusions, and decorative noise that makes the build hard to understand.
                - Before final code, mentally walk through the build from outside to inside and up every tower: doors work, rooms are lit, paths are clear, interiors are furnished, towers have floors/access, fluids are contained, roofs have no gaps, and the style is coherent.
                """;

    private PromptFactory() {
    }

    public static String create(BuildSelection selection, String userPrompt) {
        return """
                You are controlling a Minecraft builder API inside NeoForge Minecraft 26.1.2.
                Generate Rhino-compatible JavaScript. Do not describe the build outside code.

                The selected footprint is width=%d blocks on X and depth=%d blocks on Z.
                Height is unconstrained, but keep the build reasonable for an in-game personal mod.
                Coordinates are relative: x=0..width-1, z=0..depth-1, y=0 is the selected ground level.

                You have this API:
                - api.width, api.depth
                - api.getWidth(), api.getDepth()
                - api.set(x, y, z, blockId, states?)
                - api.fill(x1, y1, z1, x2, y2, z2, blockId, options?)
                - options.mode can be "replace", "keep", "hollow", or "outline"
                - blockId examples: "stone_bricks", "oak_planks", "glass", "spruce_stairs"
                - states example: { facing: "north", half: "top" }

                Important:
                - Use loops, helper functions, symmetry, and fills to create a detailed build efficiently. Do not reduce detail just to keep the code short.
                - Build mode starts from a cleared blank volume in the selected footprint. Do not rely on existing cliffs, trees, terrain, or blocks inside it.
                - Place any ground plane, foundation, basin floor, supports, or terrain you need explicitly.
                - Stay inside x/z bounds. y can grow upward.
                %s
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
                """.formatted(selection.width(), selection.depth(), PHYSICS_AND_ORIENTATION_RULES, DETAILED_BUILD_RULES, userPrompt);
    }

    public static String stagedBuild(BuildSelection selection, String userPrompt, int stageNumber, int stageCount, String stageName, String stageGoal, String stageRules, String previousStages) {
        return """
                You are generating stage %d of %d for a staged Minecraft build inside NeoForge Minecraft 26.1.2.
                Generate Rhino-compatible JavaScript for this stage only. Do not describe the build outside code.

                The selected footprint is width=%d blocks on X and depth=%d blocks on Z.
                Height is unconstrained, but keep the build reasonable for an in-game personal mod.
                Coordinates are relative: x=0..width-1, z=0..depth-1, y=0 is the selected ground level.

                You have this API:
                - api.width, api.depth
                - api.getWidth(), api.getDepth()
                - api.set(x, y, z, blockId, states?)
                - api.fill(x1, y1, z1, x2, y2, z2, blockId, options?)
                - options.mode can be "replace", "keep", "hollow", or "outline"
                - blockId examples: "stone_bricks", "oak_planks", "glass", "spruce_stairs"
                - states example: { facing: "north", half: "top" }

                Staged build contract:
                - Stage 1 starts from a cleared blank volume in the selected footprint. Later stages run on top of earlier stages.
                - Output only the incremental code for the current stage. Do not recreate previous stages unless this stage is explicitly correcting or replacing a small part.
                - Stay inside x/z bounds. y can grow upward.
                - Use loops, helper functions, symmetry, and fills to make this stage detailed without huge repetitive code.
                - Make this stage coherent with the earlier stages and the final requested build.
                - If you need an opening, walkway, stairwell, or door path, leave/clear the needed air in this stage or respect earlier reserved air.
                - Use "air" only for deliberate openings, corrections, stairwells, doorways, windows, or path clearance. Do not wipe whole previous stages.
                %s

                Overall user request:
                %s

                Previous staged code context:
                %s

                Current stage: %s
                Stage goal:
                %s

                Stage-specific instructions:
                %s

                Output rules:
                - Return only one JavaScript function named build.
                - The function signature must be: function build(api) { ... }
                - Use ES5 JavaScript only: var, function, for loops, arrays, plain objects.
                - Do not use let, const, arrow functions, classes, template strings, async, await, import, export, require, fetch, Java classes, comments with markdown fences, or TypeScript.
                - Do not use trailing commas.
                - Return raw code only, no markdown, no prose.
                """.formatted(stageNumber, stageCount, selection.width(), selection.depth(), PHYSICS_AND_ORIENTATION_RULES, userPrompt, previousStages, stageName, stageGoal, stageRules);
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
