package com.angel.aibuilder.selection;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public class SelectionEvents {
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !event.getItemStack().is(Items.STICK)) {
            return;
        }

        if (event.getLevel().isClientSide()) {
            SelectionManager.selectClient(event.getEntity().getUUID(), event.getPos());
        } else {
            SelectionManager.SelectionClick click = SelectionManager.selectServer(event.getEntity().getUUID(), event.getPos());
            if (click.firstCorner()) {
                event.getEntity().sendSystemMessage(Component.literal("Minedit: first corner selected at "
                        + event.getPos().getX() + " " + event.getPos().getY() + " " + event.getPos().getZ() + ".").withStyle(ChatFormatting.YELLOW));
            } else {
                BuildSelection selection = click.selection();
                event.getEntity().sendSystemMessage(Component.literal("Minedit: selection " + selection.shortSummary()
                        + ". Reuse: " + selection.reuseCommand()).withStyle(ChatFormatting.GREEN));
            }
        }
    }
}
