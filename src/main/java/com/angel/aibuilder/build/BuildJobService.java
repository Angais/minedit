package com.angel.aibuilder.build;

import com.angel.aibuilder.AiBuilderMod;
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

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class BuildJobService {
    private static final OpenRouterClient CLIENT = new OpenRouterClient();

    private BuildJobService() {
    }

    public static void start(ServerPlayer player, BuildSelection selection, String userPrompt, String apiKey, String model, String effort) {
        startWithPrompt(player, selection, apiKey, model, effort, PromptFactory.create(selection, userPrompt), Map.of());
    }

    public static void edit(ServerPlayer player, BuildSelection selection, String userPrompt, String apiKey, String model, String effort) {
        ExistingStructureScanner.BuildCode buildCode = ExistingStructureScanner.compile((ServerLevel) player.level(), selection);
        startWithPrompt(player, selection, apiKey, model, effort, PromptFactory.edit(selection, buildCode.quickContext(), userPrompt), buildCode.lineMap());
    }

    public static void quickEdit(ServerPlayer player, BuildSelection selection, String userPrompt, String apiKey, String model, String effort) {
        ExistingStructureScanner.BuildCode buildCode = ExistingStructureScanner.compile((ServerLevel) player.level(), selection);
        startWithPrompt(player, selection, apiKey, model, effort, PromptFactory.quickEdit(selection, buildCode.quickContext(), userPrompt), buildCode.lineMap());
    }

    private static void startWithPrompt(ServerPlayer player, BuildSelection selection, String apiKey, String model, String effort, String requestPrompt, Map<Integer, ExistingStructureScanner.Line> lines) {
        MinecraftServer server = player.level().getServer();
        CompletableFuture.supplyAsync(() -> {
            String prompt = null;
            String response = null;
            String code = null;
            try {
                prompt = requestPrompt;
                response = CLIENT.complete(apiKey, model, effort, prompt);
                code = ResponseParser.extractCode(response);
                BuildDebugFiles.writeLast(prompt, response, code);
                BuildPlan plan = JsBuildRunner.run(code, selection.width(), selection.depth(), lines);
                return new Result(code, plan, null);
            } catch (Exception e) {
                BuildDebugFiles.writeLast(prompt, response, code);
                return new Result(null, null, e);
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
            currentPlayer.sendSystemMessage(Component.literal("Minedit: queued " + result.plan.operations().size() + " operations.").withStyle(ChatFormatting.GREEN));
            BuildQueue.enqueue(new QueuedBuild(currentPlayer, selection, result.plan.operations()));
        }));
    }

    private record Result(String code, BuildPlan plan, Exception error) {
    }
}
