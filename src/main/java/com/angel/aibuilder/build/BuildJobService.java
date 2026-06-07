package com.angel.aibuilder.build;

import com.angel.aibuilder.AiBuilderMod;
import com.angel.aibuilder.ai.AiCompletion;
import com.angel.aibuilder.ai.AiProvider;
import com.angel.aibuilder.ai.AiRequestOptions;
import com.angel.aibuilder.codex.CodexLocalClient;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class BuildJobService {
    private static final OpenRouterClient OPENROUTER_CLIENT = new OpenRouterClient();
    private static final CodexLocalClient CODEX_CLIENT = new CodexLocalClient();
    private static final BlockSpec AIR = new BlockSpec("minecraft:air", Map.of());
    private static final Map<UUID, Integer> ACTIVE_GENERATIONS = new ConcurrentHashMap<>();

    private BuildJobService() {
    }

    public static void start(ServerPlayer player, BuildSelection selection, String userPrompt, AiRequestOptions options) {
        startWithPrompt(player, selection, options, PromptFactory.create(selection, userPrompt), Map.of(), true);
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
        beginGeneration(player.getUUID());
        CompletableFuture.supplyAsync(() -> {
            String prompt = null;
            AiCompletion completion = null;
            String code = null;
            try {
                prompt = requestPrompt;
                completion = complete(options, prompt);
                code = ResponseParser.extractCode(completion.text());
                BuildDebugFiles.writeLast(prompt, completion.text(), code);
                BuildPlan plan = JsBuildRunner.run(code, selection.width(), selection.depth(), lines);
                return new Result(code, plan, completion.usageSummary(), null);
            } catch (Exception e) {
                BuildDebugFiles.writeLast(prompt, completion == null ? null : completion.text(), code);
                return new Result(null, null, "", e);
            } finally {
                endGeneration(player.getUUID());
            }
        }).thenAccept(result -> server.execute(() -> {
            ServerPlayer currentPlayer = server.getPlayerList().getPlayer(player.getUUID());
            if (currentPlayer == null) {
                return;
            }
            if (result.error != null) {
                AiBuilderMod.LOGGER.error("AI build failed", result.error);
                currentPlayer.sendSystemMessage(Component.literal("Minedit failed: " + result.error.getMessage()).withStyle(ChatFormatting.RED));
                return;
            }
            if (result.usageSummary != null && !result.usageSummary.isBlank()) {
                currentPlayer.sendSystemMessage(Component.literal(result.usageSummary).withStyle(ChatFormatting.AQUA));
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
        return ACTIVE_GENERATIONS.values().stream().mapToInt(Integer::intValue).sum();
    }

    public static boolean hasActiveGenerationFor(UUID playerId) {
        return ACTIVE_GENERATIONS.getOrDefault(playerId, 0) > 0;
    }

    private static void beginGeneration(UUID playerId) {
        ACTIVE_GENERATIONS.merge(playerId, 1, Integer::sum);
    }

    private static void endGeneration(UUID playerId) {
        ACTIVE_GENERATIONS.computeIfPresent(playerId, (id, count) -> count <= 1 ? null : count - 1);
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

    private static AiCompletion complete(AiRequestOptions options, String prompt) throws Exception {
        if (options.provider() == AiProvider.CODEX_LOCAL) {
            return CODEX_CLIENT.complete(options.codexUrl(), options.model(), options.effort(), prompt);
        }
        return OPENROUTER_CLIENT.complete(options.openRouterApiKey(), options.model(), options.effort(), prompt);
    }

    private record Result(String code, BuildPlan plan, String usageSummary, Exception error) {
    }
}
