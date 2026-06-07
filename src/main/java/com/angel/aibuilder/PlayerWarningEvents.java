package com.angel.aibuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

public class PlayerWarningEvents {
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        player.sendSystemMessage(Component.literal("Minedit is a work in progress. Expect things to break.").withStyle(ChatFormatting.GOLD));
        player.sendSystemMessage(Component.literal("AI requests use your configured provider, OpenRouter or a local Codex bridge, and may cause charges or consume limits.").withStyle(ChatFormatting.YELLOW));
        player.sendSystemMessage(Component.literal("Use Minedit at your own risk; the author is not responsible for unexpected costs, world changes, or other side effects.").withStyle(ChatFormatting.RED));
    }
}
