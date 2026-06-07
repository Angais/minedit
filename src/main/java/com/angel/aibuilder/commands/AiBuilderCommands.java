package com.angel.aibuilder.commands;

import com.angel.aibuilder.ai.AiProvider;
import com.angel.aibuilder.ai.AiRequestOptions;
import com.angel.aibuilder.build.BuildJobService;
import com.angel.aibuilder.build.BuildQueue;
import com.angel.aibuilder.build.BuildUndoManager;
import com.angel.aibuilder.codex.CodexLocalClient;
import com.angel.aibuilder.config.AiBuilderSettings;
import com.angel.aibuilder.selection.BuildSelection;
import com.angel.aibuilder.selection.SelectionManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class AiBuilderCommands {
    private static final Set<String> EFFORTS = Set.of("none", "minimal", "low", "medium", "high", "xhigh");
    private static final CodexLocalClient CODEX_CLIENT = new CodexLocalClient();

    @SubscribeEvent
    public void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("apikey")
                .then(Commands.argument("key", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            try {
                                AiBuilderSettings.setApiKey(StringArgumentType.getString(ctx, "key"));
                                ctx.getSource().sendSuccess(() -> Component.literal("OpenRouter API key saved.").withStyle(ChatFormatting.GREEN), false);
                            } catch (IOException e) {
                                ctx.getSource().sendFailure(Component.literal("Could not save API key: " + e.getMessage()));
                            }
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("provider")
                .then(Commands.argument("provider", StringArgumentType.word())
                        .executes(ctx -> {
                            String providerId = StringArgumentType.getString(ctx, "provider");
                            AiProvider provider = AiProvider.fromId(providerId).orElse(null);
                            if (provider == null) {
                                ctx.getSource().sendFailure(Component.literal("Provider must be one of: " + AiProvider.ids() + "."));
                                return 0;
                            }
                            try {
                                AiBuilderSettings.setProvider(provider.id());
                                if (provider == AiProvider.CODEX_LOCAL) {
                                    ctx.getSource().sendSuccess(() -> Component.literal("Minedit provider set to Codex local bridge. Start it with `npm --prefix bridge start`, then use /codex status.").withStyle(ChatFormatting.GREEN), false);
                                } else {
                                    ctx.getSource().sendSuccess(() -> Component.literal("Minedit provider set to OpenRouter.").withStyle(ChatFormatting.GREEN), false);
                                }
                            } catch (IOException e) {
                                ctx.getSource().sendFailure(Component.literal("Could not save provider: " + e.getMessage()));
                            }
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("codexurl")
                .then(Commands.argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            try {
                                String url = StringArgumentType.getString(ctx, "url");
                                AiBuilderSettings.setCodexUrl(url);
                                ctx.getSource().sendSuccess(() -> Component.literal("Minedit Codex bridge URL set to " + url).withStyle(ChatFormatting.GREEN), false);
                            } catch (IOException e) {
                                ctx.getSource().sendFailure(Component.literal("Could not save Codex bridge URL: " + e.getMessage()));
                            }
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("codex")
                .then(Commands.literal("status")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            MinecraftServer server = source.getServer();
                            String url = AiBuilderSettings.codexUrl();
                            String model = AiBuilderSettings.model();
                            source.sendSuccess(() -> Component.literal("Minedit: checking Codex bridge at " + url + "...").withStyle(ChatFormatting.YELLOW), false);
                            CompletableFuture.supplyAsync(() -> {
                                try {
                                    return CODEX_CLIENT.status(url, model);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }).thenAccept(status -> server.execute(() -> sendCodexStatus(source, status, model)))
                                    .exceptionally(error -> {
                                        server.execute(() -> source.sendFailure(Component.literal("Minedit Codex bridge error: " + rootMessage(error))));
                                        return null;
                                    });
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("effort")
                .then(Commands.argument("effort", StringArgumentType.word())
                        .executes(ctx -> {
                            String effort = StringArgumentType.getString(ctx, "effort").trim().toLowerCase();
                            if (!EFFORTS.contains(effort)) {
                                ctx.getSource().sendFailure(Component.literal("Effort must be one of: none, minimal, low, medium, high, xhigh."));
                                return 0;
                            }
                            try {
                                AiBuilderSettings.setEffort(effort);
                                ctx.getSource().sendSuccess(() -> Component.literal("Minedit reasoning effort set to " + effort).withStyle(ChatFormatting.GREEN), false);
                            } catch (IOException e) {
                                ctx.getSource().sendFailure(Component.literal("Could not save effort: " + e.getMessage()));
                            }
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("model")
                .then(Commands.argument("model", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            try {
                                String model = StringArgumentType.getString(ctx, "model");
                                AiBuilderSettings.setModel(model);
                                ctx.getSource().sendSuccess(() -> Component.literal("Minedit model set to " + model).withStyle(ChatFormatting.GREEN), false);
                            } catch (IOException e) {
                                ctx.getSource().sendFailure(Component.literal("Could not save model: " + e.getMessage()));
                            }
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("build")
                .then(Commands.argument("prompt", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            AiRequestOptions options = requestOptions(ctx.getSource(), false);
                            if (options == null) {
                                return 0;
                            }

                            BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                            if (selection == null) {
                                ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                return 0;
                            }

                            String prompt = StringArgumentType.getString(ctx, "prompt");
                            ctx.getSource().sendSuccess(() -> Component.literal("Minedit: asking " + options.targetDescription() + " (" + options.effort() + ") to build...").withStyle(ChatFormatting.YELLOW), false);
                            BuildJobService.start(player, selection, prompt, options);
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("edit")
                .then(Commands.literal("set")
                        .then(Commands.literal("quickeffort")
                                .then(Commands.argument("effort", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String effort = StringArgumentType.getString(ctx, "effort").trim().toLowerCase();
                                            if (!EFFORTS.contains(effort)) {
                                                ctx.getSource().sendFailure(Component.literal("Quick edit effort must be one of: none, minimal, low, medium, high, xhigh."));
                                                return 0;
                                            }
                                            try {
                                                AiBuilderSettings.setQuickEffort(effort);
                                                ctx.getSource().sendSuccess(() -> Component.literal("Minedit quick edit effort set to " + effort).withStyle(ChatFormatting.GREEN), false);
                                            } catch (IOException e) {
                                                ctx.getSource().sendFailure(Component.literal("Could not save quick edit effort: " + e.getMessage()));
                                            }
                                            return 1;
                                        }))))
                .then(Commands.literal("quick")
                        .then(Commands.argument("prompt", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    AiRequestOptions options = requestOptions(ctx.getSource(), true);
                                    if (options == null) {
                                        return 0;
                                    }

                                    BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                                    if (selection == null) {
                                        ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                        return 0;
                                    }

                                    String prompt = StringArgumentType.getString(ctx, "prompt");
                                    ctx.getSource().sendSuccess(() -> Component.literal("Minedit: asking " + options.targetDescription() + " (" + options.effort() + ") for a quick edit...").withStyle(ChatFormatting.YELLOW), false);
                                    BuildJobService.quickEdit(player, selection, prompt, options);
                                    return 1;
                                })))
                .then(Commands.argument("prompt", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            AiRequestOptions options = requestOptions(ctx.getSource(), false);
                            if (options == null) {
                                return 0;
                            }

                            BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                            if (selection == null) {
                                ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                return 0;
                            }

                            String prompt = StringArgumentType.getString(ctx, "prompt");
                            ctx.getSource().sendSuccess(() -> Component.literal("Minedit: asking " + options.targetDescription() + " (" + options.effort() + ") to edit...").withStyle(ChatFormatting.YELLOW), false);
                            BuildJobService.edit(player, selection, prompt, options);
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("reset")
                .then(Commands.literal("build")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            BuildQueue.cancelBuilds(player.getUUID());
                            if (BuildUndoManager.resetLastBuild(player)) {
                                ctx.getSource().sendSuccess(() -> Component.literal("Minedit: restoring previous build area...").withStyle(ChatFormatting.YELLOW), false);
                            } else {
                                ctx.getSource().sendFailure(Component.literal("Minedit: no generated build to reset."));
                            }
                            return 1;
                        }))
                .then(Commands.literal("selection")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            SelectionManager.clearServer(player.getUUID());
                            SelectionManager.clearClient(player.getUUID());
                            ctx.getSource().sendSuccess(() -> Component.literal("Minedit: selection cleared.").withStyle(ChatFormatting.GREEN), false);
                            return 1;
                        })));
    }

    private static AiRequestOptions requestOptions(CommandSourceStack source, boolean quick) {
        AiProvider provider = AiProvider.fromId(AiBuilderSettings.provider()).orElse(AiProvider.OPENROUTER);
        String model = AiBuilderSettings.model();
        String effort = quick ? AiBuilderSettings.quickEffort() : AiBuilderSettings.effort();
        if (provider == AiProvider.CODEX_LOCAL) {
            return new AiRequestOptions(provider, "", AiBuilderSettings.codexUrl(), model, effort);
        }

        String apiKey = AiBuilderSettings.apiKey();
        if (apiKey.isEmpty()) {
            source.sendFailure(Component.literal("Set your OpenRouter key first with /apikey <key>, or use /provider codex-local."));
            return null;
        }
        return new AiRequestOptions(provider, apiKey, "", model, effort);
    }

    private static void sendCodexStatus(CommandSourceStack source, CodexLocalClient.Status status, String currentModel) {
        if (status.needsLogin()) {
            source.sendFailure(Component.literal("Codex bridge connected, but Codex is not logged in. Run `codex login` in a terminal, then restart the bridge."));
            return;
        }
        source.sendSuccess(() -> Component.literal("Codex bridge connected. Auth: " + status.authLabel() + ". Models: " + status.modelCount() + ".").withStyle(ChatFormatting.GREEN), false);
        if (!status.defaultModel().isEmpty()) {
            source.sendSuccess(() -> Component.literal("Codex default model: " + status.defaultModel()).withStyle(ChatFormatting.GRAY), false);
        }
        if (status.currentModelAvailable()) {
            source.sendSuccess(() -> Component.literal("Current model works with Codex as " + status.normalizedCurrentModel() + ". Supported efforts: " + String.join(", ", status.supportedEfforts())).withStyle(ChatFormatting.GREEN), false);
        } else {
            source.sendFailure(Component.literal("Current model `" + currentModel + "` was not found in Codex. Try /model gpt-5.5."));
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }
}
