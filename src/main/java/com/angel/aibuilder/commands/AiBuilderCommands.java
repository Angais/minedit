package com.angel.aibuilder.commands;

import com.angel.aibuilder.build.BuildJobService;
import com.angel.aibuilder.build.BuildQueue;
import com.angel.aibuilder.build.BuildUndoManager;
import com.angel.aibuilder.config.AiBuilderSettings;
import com.angel.aibuilder.selection.BuildSelection;
import com.angel.aibuilder.selection.SelectionManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.util.Set;

public class AiBuilderCommands {
    private static final Set<String> EFFORTS = Set.of("none", "low", "medium", "high", "xhigh");

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

        event.getDispatcher().register(Commands.literal("effort")
                .then(Commands.argument("effort", StringArgumentType.word())
                        .executes(ctx -> {
                            String effort = StringArgumentType.getString(ctx, "effort").trim().toLowerCase();
                            if (!EFFORTS.contains(effort)) {
                                ctx.getSource().sendFailure(Component.literal("Effort must be one of: none, low, medium, high, xhigh."));
                                return 0;
                            }
                            try {
                                AiBuilderSettings.setEffort(effort);
                                ctx.getSource().sendSuccess(() -> Component.literal("OpenRouter reasoning effort set to " + effort).withStyle(ChatFormatting.GREEN), false);
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
                                ctx.getSource().sendSuccess(() -> Component.literal("OpenRouter model set to " + model).withStyle(ChatFormatting.GREEN), false);
                            } catch (IOException e) {
                                ctx.getSource().sendFailure(Component.literal("Could not save model: " + e.getMessage()));
                            }
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("build")
                .then(Commands.argument("prompt", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            String apiKey = AiBuilderSettings.apiKey();
                            if (apiKey.isEmpty()) {
                                ctx.getSource().sendFailure(Component.literal("Set your OpenRouter key first with /apikey <key>."));
                                return 0;
                            }

                            BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                            if (selection == null) {
                                ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                return 0;
                            }

                            String prompt = StringArgumentType.getString(ctx, "prompt");
                            String model = AiBuilderSettings.model();
                            String effort = AiBuilderSettings.effort();
                            ctx.getSource().sendSuccess(() -> Component.literal("Minedit: asking " + model + " (" + effort + ") to build...").withStyle(ChatFormatting.YELLOW), false);
                            BuildJobService.start(player, selection, prompt, apiKey, model, effort);
                            return 1;
                        })));

        event.getDispatcher().register(Commands.literal("edit")
                .then(Commands.literal("set")
                        .then(Commands.literal("quickeffort")
                                .then(Commands.argument("effort", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String effort = StringArgumentType.getString(ctx, "effort").trim().toLowerCase();
                                            if (!EFFORTS.contains(effort)) {
                                                ctx.getSource().sendFailure(Component.literal("Quick edit effort must be one of: none, low, medium, high, xhigh."));
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
                                    String apiKey = AiBuilderSettings.apiKey();
                                    if (apiKey.isEmpty()) {
                                        ctx.getSource().sendFailure(Component.literal("Set your OpenRouter key first with /apikey <key>."));
                                        return 0;
                                    }

                                    BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                                    if (selection == null) {
                                        ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                        return 0;
                                    }

                                    String prompt = StringArgumentType.getString(ctx, "prompt");
                                    String model = AiBuilderSettings.model();
                                    String effort = AiBuilderSettings.quickEffort();
                                    ctx.getSource().sendSuccess(() -> Component.literal("Minedit: asking " + model + " (" + effort + ") for a quick edit...").withStyle(ChatFormatting.YELLOW), false);
                                    BuildJobService.quickEdit(player, selection, prompt, apiKey, model, effort);
                                    return 1;
                                })))
                .then(Commands.argument("prompt", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            String apiKey = AiBuilderSettings.apiKey();
                            if (apiKey.isEmpty()) {
                                ctx.getSource().sendFailure(Component.literal("Set your OpenRouter key first with /apikey <key>."));
                                return 0;
                            }

                            BuildSelection selection = SelectionManager.selection(player.getUUID()).orElse(null);
                            if (selection == null) {
                                ctx.getSource().sendFailure(Component.literal("Select two footprint corners first by right-clicking blocks with a stick."));
                                return 0;
                            }

                            String prompt = StringArgumentType.getString(ctx, "prompt");
                            String model = AiBuilderSettings.model();
                            String effort = AiBuilderSettings.effort();
                            ctx.getSource().sendSuccess(() -> Component.literal("Minedit: asking " + model + " (" + effort + ") to edit...").withStyle(ChatFormatting.YELLOW), false);
                            BuildJobService.edit(player, selection, prompt, apiKey, model, effort);
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
}
