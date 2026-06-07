package com.angel.aibuilder.build;

import com.angel.aibuilder.AiBuilderMod;
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

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class BuildJobService {
    private static final OpenRouterClient OPENROUTER_CLIENT = new OpenRouterClient();
    private static final CodexLocalClient CODEX_CLIENT = new CodexLocalClient();

    private BuildJobService() {
    }

    public static void start(ServerPlayer player, BuildSelection selection, String userPrompt, AiRequestOptions options) {
        startWithPrompt(player, selection, options, PromptFactory.create(selection, userPrompt), Map.of());
    }

    public static void edit(ServerPlayer player, BuildSelection selection, String userPrompt, AiRequestOptions options) {
        ExistingStructureScanner.BuildCode buildCode = ExistingStructureScanner.compile((ServerLevel) player.level(), selection);
        startWithPrompt(player, selection, options, PromptFactory.edit(selection, buildCode.quickContext(), userPrompt), buildCode.lineMap());
    }

    public static void quickEdit(ServerPlayer player, BuildSelection selection, String userPrompt, AiRequestOptions options) {
        ExistingStructureScanner.BuildCode buildCode = ExistingStructureScanner.compile((ServerLevel) player.level(), selection);
        startWithPrompt(player, selection, options, PromptFactory.quickEdit(selection, buildCode.quickContext(), userPrompt), buildCode.lineMap());
    }

    private static void startWithPrompt(ServerPlayer player, BuildSelection selection, AiRequestOptions options, String requestPrompt, Map<Integer, ExistingStructureScanner.Line> lines) {
        MinecraftServer server = player.level().getServer();
        CompletableFuture.supplyAsync(() -> {
            String prompt = null;
            String response = null;
            String code = null;
            try {
                prompt = requestPrompt;
                response = complete(options, prompt);
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

    private static String complete(AiRequestOptions options, String prompt) throws Exception {
        if (options.provider() == AiProvider.CODEX_LOCAL) {
            return CODEX_CLIENT.complete(options.codexUrl(), options.model(), options.effort(), prompt);
        }
        return OPENROUTER_CLIENT.complete(options.openRouterApiKey(), options.model(), options.effort(), prompt);
    }

    private record Result(String code, BuildPlan plan, Exception error) {
    }
}
