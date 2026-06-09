package com.angel.aibuilder.commands;

import com.angel.aibuilder.ai.AiProvider;
import com.angel.aibuilder.ai.AiRequestOptions;
import com.angel.aibuilder.build.BuildJobService;
import com.angel.aibuilder.build.BuildQueue;
import com.angel.aibuilder.build.BuildUndoManager;
import com.angel.aibuilder.codex.CodexLocalClient;
import com.angel.aibuilder.config.AiBuilderSettings;
import com.angel.aibuilder.debug.BuildDebugFiles;
import com.angel.aibuilder.openrouter.OpenRouterClient;
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
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class AiBuilderCommands {
    private static final String EFFORT_LIST = "none, minimal, low, medium, high, xhigh, max";
    private static final Set<String> EFFORTS = Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max");
    private static final Set<String> ENABLED_VALUES = Set.of("enabled", "enable", "on", "true", "yes");
    private static final Set<String> DISABLED_VALUES = Set.of("disabled", "disable", "off", "false", "no");
    private static final CodexLocalClient CODEX_CLIENT = new CodexLocalClient();
    private static final OpenRouterClient OPENROUTER_CLIENT = new OpenRouterClient();

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
                                ctx.getSource().sendFailure(Component.literal("Effort must be one of: " + EFFORT_LIST + "."));
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

        event.getDispatcher().register(Commands.literal("streaming")
                .then(Commands.argument("enabled", StringArgumentType.word())
                        .executes(ctx -> {
                            String value = StringArgumentType.getString(ctx, "enabled").trim().toLowerCase();
                            Boolean streaming = parseEnabled(value);
                            if (streaming == null) {
                                ctx.getSource().sendFailure(Component.literal("Streaming must be enabled or disabled."));
                                return 0;
                            }
                            try {
                                AiBuilderSettings.setStreaming(streaming);
                                ctx.getSource().sendSuccess(() -> Component.literal("Minedit OpenRouter streaming " + (streaming ? "enabled" : "disabled") + ".").withStyle(ChatFormatting.GREEN), false);
                            } catch (IOException e) {
                                ctx.getSource().sendFailure(Component.literal("Could not save streaming setting: " + e.getMessage()));
                            }
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("stop")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    int generationCount = BuildJobService.cancelGenerations(player.getUUID());
                    int queuedCount = BuildQueue.cancelBuilds(player.getUUID());
                    if (generationCount == 0 && queuedCount == 0) {
                        ctx.getSource().sendSuccess(() -> Component.literal("Minedit: nothing active to stop.").withStyle(ChatFormatting.YELLOW), false);
                    } else {
                        ctx.getSource().sendSuccess(() -> Component.literal("Minedit: stop requested. Canceled " + generationCount + " active generation(s) and removed " + queuedCount + " queued placement job(s).").withStyle(ChatFormatting.YELLOW), false);
                    }
                    return 1;
                }));

        event.getDispatcher().register(Commands.literal("status")
                .executes(ctx -> {
                    sendStatus(ctx.getSource());
                    return 1;
                }));

        event.getDispatcher().register(Commands.literal("usage")
                .then(Commands.argument("generation_id", StringArgumentType.word())
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            MinecraftServer server = source.getServer();
                            String apiKey = AiBuilderSettings.apiKey();
                            if (apiKey.isBlank()) {
                                source.sendFailure(Component.literal("Set your OpenRouter key first with /apikey <key>."));
                                return 0;
                            }

                            String generationId = StringArgumentType.getString(ctx, "generation_id");
                            source.sendSuccess(() -> Component.literal("Minedit: checking OpenRouter usage for " + generationId + "...").withStyle(ChatFormatting.YELLOW), false);
                            CompletableFuture.supplyAsync(() -> {
                                try {
                                    return OPENROUTER_CLIENT.fetchUsageReport(apiKey, generationId);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }).thenAccept(report -> server.execute(() -> {
                                ChatFormatting style = report.hasCost() ? ChatFormatting.AQUA : ChatFormatting.YELLOW;
                                source.sendSuccess(() -> Component.literal(report.summary()).withStyle(style), false);
                                if (!report.hasCost()) {
                                    source.sendSuccess(() -> Component.literal("OpenRouter has not exposed final cost for this generation yet. Try /usage again in a bit.").withStyle(ChatFormatting.YELLOW), false);
                                }
                            })).exceptionally(error -> {
                                server.execute(() -> source.sendFailure(Component.literal("OpenRouter usage lookup failed: " + rootMessage(error))));
                                return null;
                            });
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("build")
                .then(Commands.literal("export")
                        .then(Commands.argument("prompt", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                                    if (selection == null) {
                                        ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                        return 0;
                                    }

                                    String prompt = StringArgumentType.getString(ctx, "prompt");
                                    try {
                                        Path path = BuildJobService.exportBuildPrompt(selection, prompt);
                                        Path importPath = BuildDebugFiles.importBuildPath();
                                        ctx.getSource().sendSuccess(() -> Component.literal("Minedit export written to " + path + ". Paste the model response or build(api) code into " + importPath + ", then run /build import.").withStyle(ChatFormatting.GREEN), false);
                                    } catch (IOException e) {
                                        ctx.getSource().sendFailure(Component.literal("Could not export Minedit prompt: " + e.getMessage()));
                                        return 0;
                                    }
                                    return 1;
                                })))
                .then(Commands.literal("import")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                            if (selection == null) {
                                ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                return 0;
                            }

                            Path importPath = BuildDebugFiles.importBuildPath();
                            String responseOrCode;
                            try {
                                responseOrCode = BuildDebugFiles.readImportBuild();
                            } catch (IOException e) {
                                ctx.getSource().sendFailure(Component.literal("Could not read Minedit import file " + importPath + ": " + e.getMessage()));
                                return 0;
                            }
                            if (responseOrCode.isBlank()) {
                                ctx.getSource().sendFailure(Component.literal("Minedit import file is empty. Paste the model response or build(api) code into " + importPath + "."));
                                return 0;
                            }

                            ctx.getSource().sendSuccess(() -> Component.literal("Minedit import: reading " + importPath + "...").withStyle(ChatFormatting.YELLOW), false);
                            BuildJobService.importBuild(player, selection, responseOrCode);
                            return 1;
                        })
                        .then(Commands.argument("code", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                                    if (selection == null) {
                                        ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                        return 0;
                                    }

                                    String responseOrCode = StringArgumentType.getString(ctx, "code");
                                    ctx.getSource().sendSuccess(() -> Component.literal("Minedit import: parsing pasted code...").withStyle(ChatFormatting.YELLOW), false);
                                    BuildJobService.importBuild(player, selection, responseOrCode);
                                    return 1;
                                })))
                .then(Commands.literal("agent")
                        .then(Commands.literal("step-by-step")
                                .then(Commands.argument("prompt", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            AiRequestOptions options = requestOptions(ctx.getSource(), false);
                                            if (options == null) {
                                                return 0;
                                            }
                                            if (options.provider() != AiProvider.CODEX_LOCAL) {
                                                ctx.getSource().sendFailure(Component.literal("Minedit step-by-step agent build only works with Codex local. Use /provider codex-local and start the bridge with `npm --prefix bridge start`."));
                                                return 0;
                                            }

                                            BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                                            if (selection == null) {
                                                ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                                return 0;
                                            }

                                            String prompt = StringArgumentType.getString(ctx, "prompt");
                                            ctx.getSource().sendSuccess(() -> Component.literal("Minedit tool agent: starting Codex tool-driven build with " + options.model() + " (" + options.effort() + ")...").withStyle(ChatFormatting.YELLOW), false);
                                            BuildJobService.agentStepByStepBuild(player, selection, prompt, options);
                                            return 1;
                                        })))
                        .then(Commands.argument("prompt", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    AiRequestOptions options = requestOptions(ctx.getSource(), false);
                                    if (options == null) {
                                        return 0;
                                    }
                                    if (options.provider() != AiProvider.CODEX_LOCAL) {
                                        ctx.getSource().sendFailure(Component.literal("Minedit agent build only works with Codex local. Use /provider codex-local and start the bridge with `npm --prefix bridge start`."));
                                        return 0;
                                    }

                                    BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                                    if (selection == null) {
                                        ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                        return 0;
                                    }

                                    String prompt = StringArgumentType.getString(ctx, "prompt");
                                    ctx.getSource().sendSuccess(() -> Component.literal("Minedit agent: starting Codex agent build with " + options.model() + " (" + options.effort() + ")...").withStyle(ChatFormatting.YELLOW), false);
                                    BuildJobService.agentBuild(player, selection, prompt, options);
                                    return 1;
                                })))
                .then(Commands.literal("stages")
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
                                    ctx.getSource().sendSuccess(() -> Component.literal("Minedit stages: asking " + options.targetDescription() + " (" + options.effort() + ") to build in focused stages...").withStyle(ChatFormatting.YELLOW), false);
                                    BuildJobService.stagedBuild(player, selection, prompt, options);
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
                                                ctx.getSource().sendFailure(Component.literal("Quick edit effort must be one of: " + EFFORT_LIST + "."));
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
        boolean streaming = AiBuilderSettings.streaming();
        if (provider == AiProvider.CODEX_LOCAL) {
            return new AiRequestOptions(provider, "", AiBuilderSettings.codexUrl(), model, effort, streaming);
        }

        String apiKey = AiBuilderSettings.apiKey();
        if (apiKey.isEmpty()) {
            source.sendFailure(Component.literal("Set your OpenRouter key first with /apikey <key>, or use /provider codex-local."));
            return null;
        }
        return new AiRequestOptions(provider, apiKey, "", model, effort, streaming);
    }

    private static void sendStatus(CommandSourceStack source) {
        AiProvider provider = AiProvider.fromId(AiBuilderSettings.provider()).orElse(AiProvider.OPENROUTER);
        String model = AiBuilderSettings.model();
        String effort = AiBuilderSettings.effort();
        String quickEffort = AiBuilderSettings.quickEffort();
        boolean streaming = AiBuilderSettings.streaming();
        String codexUrl = AiBuilderSettings.codexUrl();
        boolean hasOpenRouterKey = !AiBuilderSettings.apiKey().isEmpty();

        source.sendSuccess(() -> Component.literal("Minedit status").withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("Provider: " + provider.displayName() + " (" + provider.id() + ")").withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Model: " + model).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Reasoning effort: " + effort).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Quick edit effort: " + quickEffort).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("OpenRouter streaming: " + (streaming ? "enabled" : "disabled")).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("OpenRouter key: " + (hasOpenRouterKey ? "saved" : "not set")).withStyle(hasOpenRouterKey ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.literal("Codex bridge URL: " + codexUrl).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("AI generations in progress: " + BuildJobService.activeGenerationCount()).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Queued block placement jobs: " + BuildQueue.size()).withStyle(ChatFormatting.GRAY), false);

        if (source.getEntity() instanceof ServerPlayer player) {
            SelectionManager.selection(player.getUUID()).ifPresentOrElse(selection -> {
                String text = "Selection: "
                        + selection.width() + " x " + selection.depth()
                        + " footprint at base Y " + selection.baseY()
                        + " from X " + selection.minX() + ".." + selection.maxX()
                        + ", Z " + selection.minZ() + ".." + selection.maxZ();
                source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GREEN), false);
            }, () -> source.sendSuccess(() -> Component.literal("Selection: none").withStyle(ChatFormatting.YELLOW), false));

            source.sendSuccess(() -> Component.literal("Your AI generation: " + (BuildJobService.hasActiveGenerationFor(player.getUUID()) ? "yes" : "no")).withStyle(ChatFormatting.GRAY), false);
            source.sendSuccess(() -> Component.literal("Your queued block placement: " + (BuildQueue.hasBuildFor(player.getUUID()) ? "yes" : "no")).withStyle(ChatFormatting.GRAY), false);
        }
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

    private static Boolean parseEnabled(String value) {
        if (ENABLED_VALUES.contains(value)) {
            return true;
        }
        if (DISABLED_VALUES.contains(value)) {
            return false;
        }
        return null;
    }
}
