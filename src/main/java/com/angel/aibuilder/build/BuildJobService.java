package com.angel.aibuilder.build;

import com.angel.aibuilder.AiBuilderMod;
import com.angel.aibuilder.ai.AiCompletion;
import com.angel.aibuilder.ai.CancellationToken;
import com.angel.aibuilder.ai.AiProvider;
import com.angel.aibuilder.ai.AiRequestOptions;
import com.angel.aibuilder.codex.CodexLocalClient;
import com.angel.aibuilder.cursor.CursorLocalClient;
import com.angel.aibuilder.debug.BuildDebugFiles;
import com.angel.aibuilder.js.JsBuildRunner;
import com.angel.aibuilder.openrouter.OpenRouterClient;
import com.angel.aibuilder.openrouter.PromptFactory;
import com.angel.aibuilder.openrouter.ResponseParser;
import com.angel.aibuilder.selection.BuildSelection;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class BuildJobService {
    private static final OpenRouterClient OPENROUTER_CLIENT = new OpenRouterClient();
    private static final CodexLocalClient CODEX_CLIENT = new CodexLocalClient();
    private static final CursorLocalClient CURSOR_CLIENT = new CursorLocalClient();
    private static final BlockSpec AIR = new BlockSpec("minecraft:air", Map.of());
    private static final Map<UUID, List<ActiveGeneration>> ACTIVE_GENERATIONS = new ConcurrentHashMap<>();
    private static final List<BuildStage> BUILD_STAGES = List.of(
            new BuildStage(
                    "foundation and frame",
                    "Create the ground/foundation, floor plates, main footprint, structural supports, rough room layout, and any key massing needed for the final build.",
                    """
                            - Focus on the build's readable footprint and skeleton only.
                            - Interpret the requested build type first. For statues, monuments, fountains, terrain features, vehicles, pixel art, or decorative objects, treat this as base/skeleton/silhouette planning and do not force house-like rooms, doors, stairs, or furniture.
                            - Place a deliberate foundation/ground plane, main floors, support posts/columns, room boundaries, tower level markers, and any basement/raised platform structure.
                            - Reserve comfortable interior volumes now: normal rooms and halls need at least 3 clear air blocks above the walkable floor. If a floor is y=F, reserve y=F+1..F+3 as empty headroom and put ceilings/beams/upper floors at y=F+4 or higher.
                            - Do not subdivide the footprint into tiny furnished rooms. Normal rooms should have at least a 4x4 clear interior area after walls, with 5x5 or larger preferred. Use fewer/larger rooms, open plans, alcoves, or lofts instead of unusable closets.
                            - Reserve clear entrance positions, stairwell shafts, atriums, courtyards, or central paths with air where later stages will add doors and stairs.
                            - If the build has multiple floors, reserve a real vertical route now: stairs need enough horizontal run plus bottom/top landing space, while ladders need a clear shaft and standing cells at both ends. If the footprint is tight, plan ladders or exterior stair towers instead of oversized stairs. Use spiral stairs only when you can reserve a true 3x3+ shaft with player headroom and landings.
                            - Do not add furniture, decorations, random plants, or final roof detail in this stage.
                            - If the prompt needs water/lava, build only the basin/channel structure now; leave fluid placement for a later detail/correction stage unless containment is already complete.
                            """
            ),
            new BuildStage(
                    "walls, openings, doors, and windows",
                    "Build the main wall surfaces, exterior depth, columns, doorways, gates, windows, frames, arches, and entrance readability.",
                    """
                            - Use the foundation/frame from stage 1. Do not rebuild the whole floor or support skeleton.
                            - Add walls with depth: pillars, trim, material variation, buttresses, frames, or recesses where appropriate.
                            - Keep the reserved 3-block interior headroom clear. Wall trim, crossbeams, and logs may frame room edges, but should not run through room centers, main paths, doorways, or stair approaches at head height.
                            - Pillars/posts should reach from the floor/foundation to the ceiling beam, upper floor, balcony, porch roof, or roof they support. Do not make supports that stop one or two blocks too low.
                            - Add doors/gates and window glass/panes with correct orientation and clear air around them.
                            - Cap every real door opening with an intentional lintel, arch, beam, full-width transom, awning, or matching wall/trim. A door uses y=D and y=D+1; the header row at y=D+2 must be filled across the full door width. Do not clear y=D+2 for a doorway unless you immediately refill it with header/transom blocks.
                            - Glass panes, iron bars, fences, walls, and chains are valid, but they must connect correctly. Give them same-level neighboring connector blocks or solid frame blocks, or set explicit connection states so they visually attach instead of rendering as tiny isolated slivers.
                            - For panes, use helper functions and explicit connection states when useful. In an opening on a fixed-z wall, panes usually need east/west connections like {east: "true", west: "true"}; in an opening on a fixed-x wall, panes usually need north/south connections like {north: "true", south: "true"}. A one-block-wide transom can use a pane if it connects to side frames at the same y; otherwise use full glass/stained_glass or solid trim.
                            - Leave a clear sightline through every window. Do not place solid trim, posts, shelves, furniture, or decor directly in front of panes/glass; place sills and flower boxes below the glass instead.
                            - Keep entrances reachable and unblocked on both sides. Door controls, if any, should work from inside and outside.
                            - Leave stair/ladders/corridors open for the next vertical-access stage.
                            - For non-enterable builds, use this stage for surface depth, silhouette, relief, openings that fit the object, material contrast, and structural detail instead of fake house doors/windows.
                            - Do not fully furnish rooms yet.
                            """
            ),
            new BuildStage(
                    "roof, ceilings, stairs, and vertical access",
                    "Add roof structures, ceilings, upper floors, stairs/ladders, railings, balconies, tower access, and safe vertical movement.",
                    """
                            - Build roofs carefully with correct stair/slab orientation, no inverted accidental slopes, and no roof air gaps.
                            - For stair roofs, face each stair row toward the roof ridge/center; do not face roof stairs toward the outside edge unless that is intentionally decorative.
                            - For entrance/porch steps and awnings, face stairs back toward the building/door so the low lip points outward.
                            - Add ceilings or upper floor slabs where rooms need separation, without making interiors too low or dark. If the walkable floor is y=F, ceilings, heavy beams, and upper floor blocks belong at y=F+4 or higher over normal rooms.
                            - Add staircases, ladders, landings, railings, trapdoors, or validated 3x3+ spiral access so floors and towers are reachable.
                            - Before placing stairs, check that the route has enough run, a reachable bottom approach, a reachable top landing, and 3 clear air blocks of headroom along the usable path. If that does not fit, use a ladder shaft, exterior stair tower, or fewer floors instead.
                            - Spiral stairs are only acceptable when every step has reachable approach space, 3 clear air blocks above the step, a clear turn/center area, and landings at both ends. If the shaft is smaller than 3x3 or headroom is uncertain, use a ladder instead.
                            - Do not leave stairs behind fences, furniture, windows, posts, walls, or voids where the player cannot enter the first step or exit the last step.
                            - Keep paths at least 3 clear air blocks tall by default, and keep doorways/stairs/ladders/landings clear.
                            - Towers must receive floors/landings and access, not remain hollow tubes.
                            - For non-enterable builds, use this stage for top caps, overhangs, supports, crowns, mechanical parts, silhouette refinement, and safe contained fluids instead of irrelevant ceilings or stair access.
                            - Do not add most furniture or exterior landscaping yet unless needed to support access.
                            """
            ),
            new BuildStage(
                    "interior lighting and furniture",
                    "Make the inside usable, lit, and purposeful with rooms, fixtures, furniture, storage, beds, work areas, and interior detail.",
                    """
                            - Focus on interiors only. Do not rewrite exterior walls or roofs unless fixing a small problem.
                            - If the requested build is not meant to be enterable, treat this as a close-detail and lighting pass instead: add surface relief, accent lights, signs, props, material variation, base detail, and readable features rather than rooms or furniture.
                            - Every enclosed room, tower level, corridor, basement, attic, and dark corner needs appropriate lighting.
                            - Prefer ceiling chains with lanterns, wall torches, chandeliers, hidden sea lantern/glowstone, or style-matching lamps over random floor lights.
                            - Add functional furniture and room purposes: tables, chairs, beds, counters, shelves, storage, workstations, rugs, railings, partitions, or themed props.
                            - Do not spend all detail on the lower floor. Upper floors, lofts, attics, balconies, and tower rooms need their own lights plus several fitting details such as beds, desks, shelves, storage, seating, rugs, lamps, banners, signs, plants, or workstations.
                            - Every normal room or floor zone should include lighting plus several different detail categories: furniture/use, storage/shelves, wall trim/decor, floor detail/rugs, ceiling detail/beams/lights, and small props. Avoid rooms that are just one bed, one chest, or a single bookshelf against blank walls.
                            - Break up large blank walls and floors with beams, trim, shelves, counters, lamps, signs, banners, plants, rugs, mixed floor patterns, alcoves, or material variation while keeping paths usable.
                            - Match furniture to room size. If a room is smaller than a comfortable 4x4 clear interior, treat it as storage, a closet, or an alcove with wall-mounted/simple details; do not force a table, bed, or chairs into it.
                            - Keep at least one usable path and several standing spaces in every furnished room. Furniture must not consume the whole room or block doorways, stairs, ladders, landings, or windows.
                            - Do not place shelves, barrels, bookshelves, tables, potted plants, or lamps directly in front of windows unless they sit below the glass and do not block the view.
                            - Keep main paths, stairs, ladders, landings, and doors usable. Do not block door swings, stair approaches, ladder exits, or landings with furniture.
                            """
            ),
            new BuildStage(
                    "exterior detail and landscaping",
                    "Add exterior polish, paths, fences, signs, gardens, plants, terrain patches, water features, trim, and other final visible detail.",
                    """
                            - Focus on exterior detail and surroundings that fit inside the selected footprint.
                            - Add paths to entrances, porches, railings, fences, lamps, banners, flowerbeds, bushes, trees, rocks, benches, or themed objects as appropriate.
                            - Plants and flowers must sit on valid support such as grass_block, dirt, podzol, moss_block, farmland, sand, or another suitable support.
                            - If adding water/lava/fountains, fully contain fluid with a basin, rim, and catch area so it does not overflow outside the selected footprint.
                            - Do not overcrowd entrances or block paths with plants, fences, or fountains.
                            """
            ),
            new BuildStage(
                    "final corrections and polish",
                    "Patch mistakes and add final coherent polish after mentally inspecting the whole staged build.",
                    """
                            - Do a targeted final pass only. Do not rebuild the whole structure.
                            - Fix blocked doors, bad door orientation, missing lintel/header blocks directly above upper door halves, isolated tiny panes/fences/walls in mostly-air openings, missing exits, unreachable stairs/ladders, missing stair landings, stair routes with no approach or no exit, roof gaps, accidental wall/floor holes, unsupported fragile blocks, unsafe fluids, floating decorations, dark rooms, sparse/blank rooms, under-lit or under-decorated upper floors, low/cramped headroom, unusably tiny rooms, pillars that stop too low, blocked window views, empty tower levels, and confusing shapes.
                            - Add small missing details only where the build still looks sparse: extra trim, lights, furniture, supports, railings, path markers, or coherent accents.
                            - Use air sparingly for corrections, openings, and path clearance.
                            - Make sure the final result matches the user's prompt and looks like one coherent style.
                            """
            )
    );

    private BuildJobService() {
    }

    public static void start(ServerPlayer player, BuildSelection selection, String userPrompt, AiRequestOptions options) {
        startWithPrompt(player, selection, options, PromptFactory.create(selection, userPrompt), Map.of(), true);
    }

    public static Path exportBuildPrompt(BuildSelection selection, String userPrompt) throws IOException {
        return BuildDebugFiles.writeExportPrompt(PromptFactory.create(selection, userPrompt));
    }

    public static void importBuild(ServerPlayer player, BuildSelection selection, String responseOrCode) {
        MinecraftServer server = player.level().getServer();
        UUID playerId = player.getUUID();
        CompletableFuture.supplyAsync(() -> {
            String code = null;
            try {
                code = ResponseParser.extractCode(responseOrCode);
                BuildDebugFiles.writeLast("", responseOrCode, code);
                BuildPlan plan = JsBuildRunner.run(code, selection.width(), selection.depth());
                return new Result(code, plan, "", "", null);
            } catch (Exception e) {
                BuildDebugFiles.writeLast("", responseOrCode, code);
                return new Result(null, null, "", "", e);
            }
        }).thenAccept(result -> server.execute(() -> {
            ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerId);
            if (currentPlayer == null) {
                return;
            }
            if (result.error != null) {
                AiBuilderMod.LOGGER.error("Imported Minedit build failed", result.error);
                currentPlayer.sendSystemMessage(Component.literal("Minedit import failed: " + result.error.getMessage()).withStyle(ChatFormatting.RED));
                return;
            }
            List<BuildOperation> operations = withInitialClear((ServerLevel) currentPlayer.level(), selection, result.plan.operations());
            currentPlayer.sendSystemMessage(Component.literal("Minedit import: queued " + operations.size() + " operations. Build mode will clear existing blocks in the selected footprint first.").withStyle(ChatFormatting.GREEN));
            BuildQueue.enqueue(new QueuedBuild(currentPlayer, selection, operations));
        }));
    }

    public static void stagedBuild(ServerPlayer player, BuildSelection selection, String userPrompt, AiRequestOptions options) {
        MinecraftServer server = player.level().getServer();
        UUID playerId = player.getUUID();
        ServerLevel level = (ServerLevel) player.level();
        AtomicReference<BuildUndoManager.Snapshot> snapshotRef = new AtomicReference<>();
        AtomicBoolean clearQueued = new AtomicBoolean(false);
        List<BuildOperation> clearOperations = withInitialClear(level, selection, List.of());
        player.sendSystemMessage(Component.literal("Minedit stages: starting " + BUILD_STAGES.size() + " focused build stages with " + options.targetDescription() + ".").withStyle(ChatFormatting.YELLOW));

        ActiveGeneration generation = beginGeneration(playerId);
        CompletableFuture.supplyAsync(() -> {
            generation.token().attachCurrentThread();
            StringBuilder debugPrompts = new StringBuilder();
            StringBuilder debugResponses = new StringBuilder();
            StringBuilder combinedCode = new StringBuilder();
            List<StageArtifact> completedStages = new ArrayList<>();
            try {
                for (int i = 0; i < BUILD_STAGES.size(); i++) {
                    generation.token().throwIfCancelled();
                    BuildStage stage = BUILD_STAGES.get(i);
                    int stageNumber = i + 1;
                    String prompt = PromptFactory.stagedBuild(
                            selection,
                            userPrompt,
                            stageNumber,
                            BUILD_STAGES.size(),
                            stage.name(),
                            stage.goal(),
                            stage.rules(),
                            stageContext(completedStages)
                    );
                    debugPrompts.append("\n\n===== STAGE ").append(stageNumber).append(": ").append(stage.name()).append(" =====\n\n").append(prompt);
                    sendProgress(server, playerId, "Minedit stages: generating stage " + stageNumber + "/" + BUILD_STAGES.size() + " - " + stage.name() + "...");

                    AiCompletion completion = complete(options, prompt, generation.token(), message -> sendProgress(server, playerId, "Minedit stages " + stageNumber + "/" + BUILD_STAGES.size() + ": " + message));
                    generation.token().throwIfCancelled();
                    debugResponses.append("\n\n===== STAGE ").append(stageNumber).append(": ").append(stage.name()).append(" =====\n\n").append(completion.text());
                    if (completion.usageSummary() != null && !completion.usageSummary().isBlank()) {
                        sendMessage(server, playerId, "Stage " + stageNumber + " usage: " + completion.usageSummary(), ChatFormatting.AQUA);
                    }
                    if (completion.pendingUsageId() != null && !completion.pendingUsageId().isBlank()) {
                        scheduleOpenRouterUsageFetch(server, playerId, options, completion.pendingUsageId());
                    }

                    String code = ResponseParser.extractCode(completion.text());
                    BuildPlan plan = JsBuildRunner.run(code, selection.width(), selection.depth(), Map.of());
                    if (!combinedCode.isEmpty()) {
                        combinedCode.append("\n\n");
                    }
                    combinedCode.append("// stage ").append(stageNumber).append(": ").append(stage.name()).append("\n").append(code);
                    completedStages.add(new StageArtifact(stage.name(), code));

                    List<BuildOperation> operations = plan.operations();
                    server.execute(() -> {
                        if (generation.token().isCancelled()) {
                            return;
                        }
                        ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerId);
                        if (currentPlayer == null) {
                            return;
                        }
                        if (operations.isEmpty()) {
                            currentPlayer.sendSystemMessage(Component.literal("Minedit stages: stage " + stageNumber + "/" + BUILD_STAGES.size() + " produced no operations: " + stage.name() + ".").withStyle(ChatFormatting.YELLOW));
                            return;
                        }

                        BuildUndoManager.Snapshot snapshot = snapshotRef.get();
                        if (snapshot == null) {
                            snapshot = BuildUndoManager.beginSnapshot(currentPlayer, level);
                            snapshotRef.set(snapshot);
                        }
                        if (clearQueued.compareAndSet(false, true)) {
                            currentPlayer.sendSystemMessage(Component.literal("Minedit stages: queued initial footprint clear.").withStyle(ChatFormatting.GREEN));
                            BuildQueue.enqueue(new QueuedBuild(currentPlayer, selection, clearOperations, snapshot));
                        }
                        currentPlayer.sendSystemMessage(Component.literal("Minedit stages: queued stage " + stageNumber + "/" + BUILD_STAGES.size() + " - " + stage.name() + " (" + operations.size() + " operations).").withStyle(ChatFormatting.GREEN));
                        BuildQueue.enqueue(new QueuedBuild(currentPlayer, selection, operations, snapshot));
                    });
                }

                BuildDebugFiles.writeLast(debugPrompts.toString(), debugResponses.toString(), combinedCode.toString());
                return new StepResult("Minedit stages: all " + BUILD_STAGES.size() + " stages generated. Queued block placement may still be running.", null);
            } catch (Exception e) {
                BuildDebugFiles.writeLast(debugPrompts.toString(), debugResponses.toString(), combinedCode.toString());
                return new StepResult("", stoppedException(generation, e));
            } finally {
                generation.token().detachCurrentThread();
                endGeneration(generation);
            }
        }).thenAccept(result -> server.execute(() -> {
            ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerId);
            if (currentPlayer == null) {
                return;
            }
            if (result.error != null) {
                if (isStopped(result.error)) {
                    currentPlayer.sendSystemMessage(Component.literal("Minedit stages stopped.").withStyle(ChatFormatting.YELLOW));
                    return;
                }
                AiBuilderMod.LOGGER.error("AI staged build failed", result.error);
                currentPlayer.sendSystemMessage(Component.literal("Minedit stages failed: " + result.error.getMessage()).withStyle(ChatFormatting.RED));
                return;
            }
            if (result.summary != null && !result.summary.isBlank()) {
                currentPlayer.sendSystemMessage(Component.literal(result.summary).withStyle(ChatFormatting.GREEN));
            }
        }));
    }

    public static void agentBuild(ServerPlayer player, BuildSelection selection, String userPrompt, AiRequestOptions options) {
        MinecraftServer server = player.level().getServer();
        UUID playerId = player.getUUID();
        ActiveGeneration generation = beginGeneration(playerId);
        CompletableFuture.supplyAsync(() -> {
            generation.token().attachCurrentThread();
            String prompt = null;
            AiCompletion completion = null;
            String code = null;
            try {
                generation.token().throwIfCancelled();
                prompt = PromptFactory.create(selection, userPrompt);
                completion = agentBuildCompletion(
                        options,
                        prompt,
                        selection.width(),
                        selection.depth(),
                        generation.token(),
                        message -> sendProgress(server, playerId, message)
                );
                generation.token().throwIfCancelled();
                code = ResponseParser.extractCode(completion.text());
                BuildDebugFiles.writeLast(prompt, completion.text(), code);
                BuildPlan plan = JsBuildRunner.run(code, selection.width(), selection.depth(), Map.of());
                return new Result(code, plan, completion.usageSummary(), completion.pendingUsageId(), null);
            } catch (Exception e) {
                BuildDebugFiles.writeLast(prompt, completion == null ? null : completion.text(), code);
                return new Result(null, null, "", "", stoppedException(generation, e));
            } finally {
                generation.token().detachCurrentThread();
                endGeneration(generation);
            }
        }).thenAccept(result -> server.execute(() -> {
            ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerId);
            if (currentPlayer == null) {
                return;
            }
            if (result.error != null) {
                if (isStopped(result.error)) {
                    currentPlayer.sendSystemMessage(Component.literal("Minedit agent stopped.").withStyle(ChatFormatting.YELLOW));
                    return;
                }
                AiBuilderMod.LOGGER.error("AI agent build failed", result.error);
                currentPlayer.sendSystemMessage(Component.literal("Minedit agent failed: " + result.error.getMessage()).withStyle(ChatFormatting.RED));
                return;
            }
            if (generation.token().isCancelled()) {
                currentPlayer.sendSystemMessage(Component.literal("Minedit agent stopped.").withStyle(ChatFormatting.YELLOW));
                return;
            }
            if (result.usageSummary != null && !result.usageSummary.isBlank()) {
                currentPlayer.sendSystemMessage(Component.literal(result.usageSummary).withStyle(ChatFormatting.AQUA));
            }
            List<BuildOperation> operations = withInitialClear((ServerLevel) currentPlayer.level(), selection, result.plan.operations());
            currentPlayer.sendSystemMessage(Component.literal("Minedit agent: queued " + operations.size() + " operations. Build mode will clear existing blocks in the selected footprint first.").withStyle(ChatFormatting.GREEN));
            BuildQueue.enqueue(new QueuedBuild(currentPlayer, selection, operations));
        }));
    }

    public static void agentStepByStepBuild(ServerPlayer player, BuildSelection selection, String userPrompt, AiRequestOptions options) {
        MinecraftServer server = player.level().getServer();
        UUID playerId = player.getUUID();
        ServerLevel level = (ServerLevel) player.level();
        BuildUndoManager.Snapshot snapshot = BuildUndoManager.beginSnapshot(player, level);
        List<BuildOperation> clearOperations = withInitialClear(level, selection, List.of());
        AtomicBoolean clearQueued = new AtomicBoolean(false);
        player.sendSystemMessage(Component.literal("Minedit tool agent: waiting for Codex to place the first batch before clearing the footprint.").withStyle(ChatFormatting.YELLOW));

        ActiveGeneration generation = beginGeneration(playerId);
        CompletableFuture.supplyAsync(() -> {
            generation.token().attachCurrentThread();
            String prompt = null;
            AiCompletion completion = null;
            StringBuilder combinedCode = new StringBuilder();
            try {
                generation.token().throwIfCancelled();
                prompt = PromptFactory.create(selection, userPrompt);
                completion = agentStepByStepCompletion(
                        options,
                        prompt,
                        selection.width(),
                        selection.depth(),
                        generation.token(),
                        message -> sendProgress(server, playerId, message),
                        batch -> {
                            if (generation.token().isCancelled()) {
                                return;
                            }
                            String code = ResponseParser.extractCode(batch.code());
                            BuildPlan plan = JsBuildRunner.run(code, selection.width(), selection.depth(), Map.of());
                            if (!combinedCode.isEmpty()) {
                                combinedCode.append("\n\n");
                            }
                            combinedCode.append("// step ").append(batch.id()).append("\n").append(code);
                            server.execute(() -> {
                                if (generation.token().isCancelled()) {
                                    return;
                                }
                                ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerId);
                                if (currentPlayer == null) {
                                    return;
                                }
                                if (clearQueued.compareAndSet(false, true)) {
                                    currentPlayer.sendSystemMessage(Component.literal("Minedit tool agent: queued initial footprint clear.").withStyle(ChatFormatting.GREEN));
                                    BuildQueue.enqueue(new QueuedBuild(currentPlayer, selection, clearOperations, snapshot));
                                }
                                String batchSummary = batch.summary() == null || batch.summary().isBlank() ? "Codex step " + batch.id() : batch.summary();
                                currentPlayer.sendSystemMessage(Component.literal("Minedit tool agent: queued " + batchSummary + " (" + plan.operations().size() + " operations).").withStyle(ChatFormatting.GREEN));
                                if (!plan.operations().isEmpty()) {
                                    BuildQueue.enqueue(new QueuedBuild(currentPlayer, selection, plan.operations(), snapshot));
                                }
                            });
                        }
                );
                generation.token().throwIfCancelled();
                BuildDebugFiles.writeLast(prompt, completion.text(), combinedCode.toString());
                return new StepResult(completion.usageSummary(), null);
            } catch (Exception e) {
                BuildDebugFiles.writeLast(prompt, completion == null ? combinedCode.toString() : completion.text(), combinedCode.toString());
                return new StepResult("", stoppedException(generation, e));
            } finally {
                generation.token().detachCurrentThread();
                endGeneration(generation);
            }
        }).thenAccept(result -> server.execute(() -> {
            ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerId);
            if (currentPlayer == null) {
                return;
            }
            if (result.error != null) {
                if (isStopped(result.error)) {
                    currentPlayer.sendSystemMessage(Component.literal("Minedit tool agent stopped.").withStyle(ChatFormatting.YELLOW));
                    return;
                }
                AiBuilderMod.LOGGER.error("AI step-by-step agent build failed", result.error);
                currentPlayer.sendSystemMessage(Component.literal("Minedit tool agent failed: " + result.error.getMessage()).withStyle(ChatFormatting.RED));
                return;
            }
            if (generation.token().isCancelled()) {
                currentPlayer.sendSystemMessage(Component.literal("Minedit tool agent stopped.").withStyle(ChatFormatting.YELLOW));
                return;
            }
            if (result.summary != null && !result.summary.isBlank()) {
                currentPlayer.sendSystemMessage(Component.literal(result.summary).withStyle(ChatFormatting.AQUA));
            }
            currentPlayer.sendSystemMessage(Component.literal("Minedit tool agent: generation finished. Queued block placement may still be running.").withStyle(ChatFormatting.GREEN));
        }));
    }

    public static void edit(ServerPlayer player, BuildSelection selection, String userPrompt, AiRequestOptions options) {
        ExistingStructureScanner.BuildCode buildCode = ExistingStructureScanner.compile((ServerLevel) player.level(), selection);
        startWithPrompt(player, selection, options, PromptFactory.edit(selection, buildCode.quickContext(), userPrompt), buildCode.lineMap(), false);
    }

    public static void quickEdit(ServerPlayer player, BuildSelection selection, String userPrompt, AiRequestOptions options) {
        ExistingStructureScanner.BuildCode buildCode = ExistingStructureScanner.compile((ServerLevel) player.level(), selection);
        startWithPrompt(player, selection, options, PromptFactory.quickEdit(selection, buildCode.quickContext(), userPrompt), buildCode.lineMap(), false);
    }

    private static void startWithPrompt(ServerPlayer player, BuildSelection selection, AiRequestOptions options, String requestPrompt, Map<Integer, ExistingStructureScanner.Line> lines, boolean clearBeforeBuild) {
        MinecraftServer server = player.level().getServer();
        UUID playerId = player.getUUID();
        ActiveGeneration generation = beginGeneration(playerId);
        CompletableFuture.supplyAsync(() -> {
            generation.token().attachCurrentThread();
            String prompt = null;
            AiCompletion completion = null;
            String code = null;
            try {
                generation.token().throwIfCancelled();
                prompt = requestPrompt;
                completion = complete(options, prompt, generation.token(), message -> sendProgress(server, playerId, message));
                generation.token().throwIfCancelled();
                code = ResponseParser.extractCode(completion.text());
                BuildDebugFiles.writeLast(prompt, completion.text(), code);
                BuildPlan plan = JsBuildRunner.run(code, selection.width(), selection.depth(), lines);
                return new Result(code, plan, completion.usageSummary(), completion.pendingUsageId(), null);
            } catch (Exception e) {
                BuildDebugFiles.writeLast(prompt, completion == null ? null : completion.text(), code);
                return new Result(null, null, "", "", stoppedException(generation, e));
            } finally {
                generation.token().detachCurrentThread();
                endGeneration(generation);
            }
        }).thenAccept(result -> server.execute(() -> {
            ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerId);
            if (currentPlayer == null) {
                return;
            }
            if (result.error != null) {
                if (isStopped(result.error)) {
                    currentPlayer.sendSystemMessage(Component.literal("Minedit stopped.").withStyle(ChatFormatting.YELLOW));
                    return;
                }
                AiBuilderMod.LOGGER.error("AI build failed", result.error);
                currentPlayer.sendSystemMessage(Component.literal("Minedit failed: " + result.error.getMessage()).withStyle(ChatFormatting.RED));
                return;
            }
            if (generation.token().isCancelled()) {
                currentPlayer.sendSystemMessage(Component.literal("Minedit stopped.").withStyle(ChatFormatting.YELLOW));
                return;
            }
            if (result.usageSummary != null && !result.usageSummary.isBlank()) {
                currentPlayer.sendSystemMessage(Component.literal(result.usageSummary).withStyle(ChatFormatting.AQUA));
            }
            if (result.pendingUsageId != null && !result.pendingUsageId.isBlank()) {
                scheduleOpenRouterUsageFetch(server, currentPlayer.getUUID(), options, result.pendingUsageId);
            }
            List<BuildOperation> operations = clearBeforeBuild
                    ? withInitialClear((ServerLevel) currentPlayer.level(), selection, result.plan.operations())
                    : result.plan.operations();
            String message = "Minedit: queued " + operations.size() + " operations.";
            if (clearBeforeBuild) {
                message += " Build mode will clear existing blocks in the selected footprint first.";
            }
            currentPlayer.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GREEN));
            BuildQueue.enqueue(new QueuedBuild(currentPlayer, selection, operations));
        }));
    }

    public static int activeGenerationCount() {
        return ACTIVE_GENERATIONS.values().stream().mapToInt(List::size).sum();
    }

    public static boolean hasActiveGenerationFor(UUID playerId) {
        return !ACTIVE_GENERATIONS.getOrDefault(playerId, List.of()).isEmpty();
    }

    public static int cancelGenerations(UUID playerId) {
        List<ActiveGeneration> generations = ACTIVE_GENERATIONS.getOrDefault(playerId, List.of());
        for (ActiveGeneration generation : generations) {
            generation.cancel();
        }
        return generations.size();
    }

    private static ActiveGeneration beginGeneration(UUID playerId) {
        ActiveGeneration generation = new ActiveGeneration(playerId);
        ACTIVE_GENERATIONS.computeIfAbsent(playerId, id -> new CopyOnWriteArrayList<>()).add(generation);
        return generation;
    }

    private static void endGeneration(ActiveGeneration generation) {
        ACTIVE_GENERATIONS.computeIfPresent(generation.playerId(), (id, generations) -> {
            generations.remove(generation);
            return generations.isEmpty() ? null : generations;
        });
    }

    private static void sendProgress(MinecraftServer server, UUID playerId, String message) {
        sendMessage(server, playerId, message, ChatFormatting.LIGHT_PURPLE);
    }

    private static void sendMessage(MinecraftServer server, UUID playerId, String message, ChatFormatting formatting) {
        server.execute(() -> {
            ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerId);
            if (currentPlayer != null) {
                currentPlayer.sendSystemMessage(Component.literal(message).withStyle(formatting));
            }
        });
    }

    private static void scheduleOpenRouterUsageFetch(MinecraftServer server, UUID playerId, AiRequestOptions options, String generationId) {
        if (options.provider() != AiProvider.OPENROUTER || options.openRouterApiKey().isBlank()) {
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return OPENROUTER_CLIENT.waitForCostSummary(options.openRouterApiKey(), generationId, Duration.ofSeconds(90)).orElse("");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
        }).thenAccept(summary -> server.execute(() -> {
            if (summary == null || summary.isBlank()) {
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                player.sendSystemMessage(Component.literal(summary).withStyle(ChatFormatting.AQUA));
            }
        }));
    }

    private static List<BuildOperation> withInitialClear(ServerLevel level, BuildSelection selection, List<BuildOperation> operations) {
        int maxRelativeY = level.getMaxY() - 1 - selection.baseY();
        if (maxRelativeY < 0) {
            return operations;
        }

        List<BuildOperation> withClear = new ArrayList<>(operations.size() + 1);
        withClear.add(new FillOperation(
                0, 0, 0,
                selection.width() - 1, maxRelativeY, selection.depth() - 1,
                AIR,
                new FillOptions("clear", Map.of())
        ));
        withClear.addAll(operations);
        return List.copyOf(withClear);
    }

    private static AiCompletion complete(AiRequestOptions options, String prompt, CancellationToken token, java.util.function.Consumer<String> progress) throws Exception {
        if (options.provider() == AiProvider.CODEX_LOCAL) {
            return CODEX_CLIENT.complete(options.codexUrl(), options.model(), options.effort(), prompt, token);
        }
        if (options.provider() == AiProvider.CURSOR) {
            return CURSOR_CLIENT.complete(options.codexUrl(), options.model(), options.effort(), prompt, token);
        }
        return OPENROUTER_CLIENT.complete(options.openRouterApiKey(), options.model(), options.effort(), prompt, options.streaming(), token, progress);
    }

    private static AiCompletion agentBuildCompletion(AiRequestOptions options, String prompt, int width, int depth, CancellationToken token, java.util.function.Consumer<String> progress) throws Exception {
        if (options.provider() == AiProvider.CODEX_LOCAL) {
            return CODEX_CLIENT.agentBuild(options.codexUrl(), options.model(), options.effort(), prompt, width, depth, token, progress);
        }
        if (options.provider() == AiProvider.CURSOR) {
            return CURSOR_CLIENT.agentBuild(options.codexUrl(), options.model(), options.effort(), prompt, width, depth, token, progress);
        }
        throw new IOException("Agent build only works with a local agent provider.");
    }

    private static AiCompletion agentStepByStepCompletion(AiRequestOptions options, String prompt, int width, int depth, CancellationToken token, java.util.function.Consumer<String> progress, java.util.function.Consumer<AgentStepBatch> batchConsumer) throws Exception {
        if (options.provider() == AiProvider.CODEX_LOCAL) {
            return CODEX_CLIENT.agentStepByStepBuild(options.codexUrl(), options.model(), options.effort(), prompt, width, depth, token, progress, batch ->
                    batchConsumer.accept(new AgentStepBatch(batch.id(), batch.code(), batch.operationCount(), batch.nonAirBlocks(), batch.summary())));
        }
        if (options.provider() == AiProvider.CURSOR) {
            return CURSOR_CLIENT.agentStepByStepBuild(options.codexUrl(), options.model(), options.effort(), prompt, width, depth, token, progress, batch ->
                    batchConsumer.accept(new AgentStepBatch(batch.id(), batch.code(), batch.operationCount(), batch.nonAirBlocks(), batch.summary())));
        }
        throw new IOException("Step-by-step agent build only works with a local agent provider.");
    }

    private static Exception stoppedException(ActiveGeneration generation, Exception error) {
        if (generation.token().isCancelled() || error instanceof InterruptedException) {
            return new InterruptedException("Minedit job was stopped.");
        }
        return error;
    }

    private static boolean isStopped(Exception error) {
        return error instanceof InterruptedException || (error.getMessage() != null && error.getMessage().contains("stopped"));
    }

    private static String stageContext(List<StageArtifact> stages) {
        if (stages.isEmpty()) {
            return "No previous stages yet. This is the first stage.";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < stages.size(); i++) {
            StageArtifact stage = stages.get(i);
            builder.append("Previous stage ").append(i + 1).append(" - ").append(stage.name()).append(":\n");
            builder.append(truncate(stage.code(), 4500)).append("\n\n");
        }
        return builder.toString();
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        int head = Math.max(0, maxChars / 2);
        int tail = Math.max(0, maxChars - head - 80);
        return text.substring(0, head)
                + "\n/* ... previous stage code truncated for prompt size ... */\n"
                + text.substring(text.length() - tail);
    }

    private record Result(String code, BuildPlan plan, String usageSummary, String pendingUsageId, Exception error) {
    }

    private record StepResult(String summary, Exception error) {
    }

    private record BuildStage(String name, String goal, String rules) {
    }

    private record StageArtifact(String name, String code) {
    }

    private record AgentStepBatch(int id, String code, int operationCount, int nonAirBlocks, String summary) {
    }

    private record ActiveGeneration(UUID playerId, CancellationToken token) {
        private ActiveGeneration(UUID playerId) {
            this(playerId, new CancellationToken());
        }

        private void cancel() {
            token.cancel();
        }
    }
}
