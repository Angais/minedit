package com.angel.aibuilder.commands;

import com.angel.aibuilder.ai.AiProvider;
import com.angel.aibuilder.ai.AiRequestOptions;
import com.angel.aibuilder.build.BuildJobService;
import com.angel.aibuilder.build.BuildQueue;
import com.angel.aibuilder.build.BuildUndoManager;
import com.angel.aibuilder.codex.CodexLocalClient;
import com.angel.aibuilder.config.AiBuilderSettings;
import com.angel.aibuilder.cursor.CursorLocalClient;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class AiBuilderCommands {
    private static final String EFFORT_LIST = "none, minimal, low, medium, high, xhigh, max";
    private static final Set<String> EFFORTS = Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max");
    private static final Set<String> ENABLED_VALUES = Set.of("enabled", "enable", "on", "true", "yes");
    private static final Set<String> DISABLED_VALUES = Set.of("disabled", "disable", "off", "false", "no");
    private static final CodexLocalClient CODEX_CLIENT = new CodexLocalClient();
    private static final CursorLocalClient CURSOR_CLIENT = new CursorLocalClient();
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
                                } else if (provider == AiProvider.CURSOR) {
                                    ctx.getSource().sendSuccess(() -> Component.literal("Minedit provider set to Cursor local bridge. Start it with `npm --prefix bridge start`, then use /model list cursor and /model auto or another Cursor model id.").withStyle(ChatFormatting.GREEN), false);
                                } else {
                                    ctx.getSource().sendSuccess(() -> Component.literal("Minedit provider set to OpenRouter.").withStyle(ChatFormatting.GREEN), false);
                                }
                            } catch (IOException e) {
                                ctx.getSource().sendFailure(Component.literal("Could not save provider: " + e.getMessage()));
                            }
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("openrouter")
                .then(Commands.literal("provider")
                        .executes(ctx -> {
                            sendOpenRouterProvider(ctx.getSource());
                            return 1;
                        })
                        .then(Commands.literal("list")
                                .executes(ctx -> {
                                    listOpenRouterProviders(ctx.getSource());
                                    return 1;
                                }))
                        .then(Commands.literal("auto")
                                .executes(ctx -> setOpenRouterProvider(ctx.getSource(), "")))
                        .then(Commands.literal("default")
                                .executes(ctx -> setOpenRouterProvider(ctx.getSource(), "")))
                        .then(Commands.literal("reset")
                                .executes(ctx -> setOpenRouterProvider(ctx.getSource(), "")))
                        .then(Commands.argument("provider_slug", StringArgumentType.word())
                                .executes(ctx -> setOpenRouterProvider(ctx.getSource(), StringArgumentType.getString(ctx, "provider_slug"))))));

        event.getDispatcher().register(Commands.literal("codexurl")
                .then(Commands.argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            try {
                                String url = StringArgumentType.getString(ctx, "url");
                                AiBuilderSettings.setCodexUrl(url);
                                ctx.getSource().sendSuccess(() -> Component.literal("Minedit local bridge URL set to " + url).withStyle(ChatFormatting.GREEN), false);
                            } catch (IOException e) {
                                ctx.getSource().sendFailure(Component.literal("Could not save local bridge URL: " + e.getMessage()));
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
                .then(Commands.literal("list")
                        .then(Commands.argument("provider", StringArgumentType.word())
                                .executes(ctx -> {
                                    listModels(ctx.getSource(), StringArgumentType.getString(ctx, "provider"));
                                    return 1;
                                })))
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
                                            if (!isLocalAgentProvider(options.provider())) {
                                                ctx.getSource().sendFailure(Component.literal("Minedit step-by-step agent build only works with Codex local or Cursor. Use /provider codex-local or /provider cursor and start the bridge with `npm --prefix bridge start`."));
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
                                    if (!isLocalAgentProvider(options.provider())) {
                                        ctx.getSource().sendFailure(Component.literal("Minedit agent build only works with Codex local or Cursor. Use /provider codex-local or /provider cursor and start the bridge with `npm --prefix bridge start`."));
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
        if (provider == AiProvider.CODEX_LOCAL || provider == AiProvider.CURSOR) {
            return new AiRequestOptions(provider, "", AiBuilderSettings.codexUrl(), model, effort, streaming, "");
        }

        String apiKey = AiBuilderSettings.apiKey();
        if (apiKey.isEmpty()) {
            source.sendFailure(Component.literal("Set your OpenRouter key first with /apikey <key>, or use /provider codex-local or /provider cursor."));
            return null;
        }
        return new AiRequestOptions(provider, apiKey, "", model, effort, streaming, AiBuilderSettings.openRouterProvider());
    }

    private static int setOpenRouterProvider(CommandSourceStack source, String providerSlug) {
        String normalized = providerSlug == null ? "" : providerSlug.trim().toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty() && !normalized.matches("[a-z0-9][a-z0-9._/-]*")) {
            source.sendFailure(Component.literal("Invalid OpenRouter provider slug. Use /openrouter provider list to see valid slugs for the current model."));
            return 0;
        }
        try {
            AiBuilderSettings.setOpenRouterProvider(normalized);
            if (normalized.isEmpty()) {
                source.sendSuccess(() -> Component.literal("Minedit OpenRouter inference provider set to automatic routing.").withStyle(ChatFormatting.GREEN), false);
            } else {
                source.sendSuccess(() -> Component.literal("Minedit OpenRouter inference provider locked to " + normalized + ". Requests will not fall back to another provider.").withStyle(ChatFormatting.GREEN), false);
                if (AiProvider.fromId(AiBuilderSettings.provider()).orElse(AiProvider.OPENROUTER) != AiProvider.OPENROUTER) {
                    source.sendSuccess(() -> Component.literal("This setting will apply after /provider openrouter.").withStyle(ChatFormatting.YELLOW), false);
                }
            }
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal("Could not save OpenRouter provider: " + e.getMessage()));
            return 0;
        }
    }

    private static void sendOpenRouterProvider(CommandSourceStack source) {
        String provider = AiBuilderSettings.openRouterProvider();
        String label = provider.isBlank() ? "automatic routing" : provider + " (no fallback)";
        source.sendSuccess(() -> Component.literal("Minedit OpenRouter inference provider: " + label + ".").withStyle(ChatFormatting.GRAY), false);
    }

    private static void listOpenRouterProviders(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        String apiKey = AiBuilderSettings.apiKey();
        String model = AiBuilderSettings.model();
        source.sendSuccess(() -> Component.literal("Minedit: checking OpenRouter providers for " + model + "...").withStyle(ChatFormatting.YELLOW), false);
        CompletableFuture.supplyAsync(() -> {
            try {
                return OPENROUTER_CLIENT.listProvidersForModel(apiKey, model);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).thenAccept(providers -> server.execute(() -> sendOpenRouterProviderList(source, model, providers)))
                .exceptionally(error -> {
                    server.execute(() -> source.sendFailure(Component.literal("OpenRouter provider list failed: " + rootMessage(error))));
                    return null;
                });
    }

    private static void sendOpenRouterProviderList(CommandSourceStack source, String model, List<OpenRouterClient.ProviderEndpoint> providers) {
        if (providers.isEmpty()) {
            source.sendFailure(Component.literal("OpenRouter returned no providers for " + model + "."));
            return;
        }
        source.sendSuccess(() -> Component.literal("OpenRouter providers for " + model + ":").withStyle(ChatFormatting.GOLD), false);
        StringBuilder chunk = new StringBuilder();
        for (OpenRouterClient.ProviderEndpoint provider : providers) {
            String next = provider.slug() + " (" + provider.name() + ")";
            if (!chunk.isEmpty() && chunk.length() + next.length() + 2 > 230) {
                String text = chunk.toString();
                source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GRAY), false);
                chunk.setLength(0);
            }
            if (!chunk.isEmpty()) {
                chunk.append(", ");
            }
            chunk.append(next);
        }
        if (!chunk.isEmpty()) {
            String text = chunk.toString();
            source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GRAY), false);
        }
        source.sendSuccess(() -> Component.literal("Choose one with /openrouter provider <slug>, or use /openrouter provider auto.").withStyle(ChatFormatting.GREEN), false);
    }

    private static boolean isLocalAgentProvider(AiProvider provider) {
        return provider == AiProvider.CODEX_LOCAL || provider == AiProvider.CURSOR;
    }

    private static void listModels(CommandSourceStack source, String providerId) {
        AiProvider provider = AiProvider.fromId(providerId).orElse(null);
        if (provider == null) {
            source.sendFailure(Component.literal("Provider must be one of: " + AiProvider.ids() + "."));
            return;
        }
        if (provider != AiProvider.CURSOR) {
            source.sendFailure(Component.literal("/model list currently only supports cursor."));
            return;
        }

        MinecraftServer server = source.getServer();
        String url = AiBuilderSettings.codexUrl();
        source.sendSuccess(() -> Component.literal("Minedit: checking Cursor models through local bridge at " + url + "...").withStyle(ChatFormatting.YELLOW), false);
        CompletableFuture.supplyAsync(() -> {
            try {
                return CURSOR_CLIENT.listModels(url);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).thenAccept(models -> server.execute(() -> sendCursorModelList(source, models)))
                .exceptionally(error -> {
                    server.execute(() -> source.sendFailure(Component.literal("Cursor model list failed: " + rootMessage(error))));
                    return null;
                });
    }

    private static void sendCursorModelList(CommandSourceStack source, List<CursorLocalClient.Model> models) {
        if (models.isEmpty()) {
            source.sendFailure(Component.literal("Cursor returned no models."));
            return;
        }

        source.sendSuccess(() -> Component.literal("Cursor models (" + models.size() + " ids):").withStyle(ChatFormatting.GOLD), false);
        StringBuilder chunk = new StringBuilder();
        for (CursorLocalClient.Model model : models) {
            String next = model.id();
            if (!chunk.isEmpty() && chunk.length() + next.length() + 2 > 230) {
                String text = chunk.toString();
                source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GRAY), false);
                chunk.setLength(0);
            }
            if (!chunk.isEmpty()) {
                chunk.append(", ");
            }
            chunk.append(next);
        }
        if (!chunk.isEmpty()) {
            String text = chunk.toString();
            source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GRAY), false);
        }
    }

    private static void sendStatus(CommandSourceStack source) {
        AiProvider provider = AiProvider.fromId(AiBuilderSettings.provider()).orElse(AiProvider.OPENROUTER);
        String model = AiBuilderSettings.model();
        String effort = AiBuilderSettings.effort();
        String quickEffort = AiBuilderSettings.quickEffort();
        boolean streaming = AiBuilderSettings.streaming();
        String openRouterProvider = AiBuilderSettings.openRouterProvider();
        String codexUrl = AiBuilderSettings.codexUrl();
        boolean hasOpenRouterKey = !AiBuilderSettings.apiKey().isEmpty();

        source.sendSuccess(() -> Component.literal("Minedit status").withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("Provider: " + provider.displayName() + " (" + provider.id() + ")").withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Model: " + model).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Reasoning effort: " + effort).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Quick edit effort: " + quickEffort).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("OpenRouter inference provider: " + (openRouterProvider.isBlank() ? "automatic" : openRouterProvider + " (no fallback)")).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("OpenRouter streaming: " + (streaming ? "enabled" : "disabled")).withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("OpenRouter key: " + (hasOpenRouterKey ? "saved" : "not set")).withStyle(hasOpenRouterKey ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.literal("Local bridge URL: " + codexUrl).withStyle(ChatFormatting.GRAY), false);
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
