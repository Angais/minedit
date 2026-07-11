package com.angel.aibuilder.client;

import com.angel.aibuilder.selection.BuildSelection;
import com.angel.aibuilder.selection.SelectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

public class SelectionParticleRenderer {
    private int tick;

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || ++tick % 8 != 0) {
            return;
        }

        SelectionManager.clientState(minecraft.player.getUUID()).ifPresent(state -> draw(state, minecraft.level));
    }

    private void draw(SelectionManager.SelectionState state, ClientLevel level) {
        BuildSelection selection = state.selection();
        int minX = selection.minX();
        int maxX = selection.maxX();
        int minZ = selection.minZ();
        int maxZ = selection.maxZ();
        double y = selection.baseY() + 1.08;

        for (int x = minX; x <= maxX; x++) {
            particle(level, x + 0.5, y, minZ + 0.5);
            particle(level, x + 0.5, y, maxZ + 0.5);
        }
        for (int z = minZ; z <= maxZ; z++) {
            particle(level, minX + 0.5, y, z + 0.5);
            particle(level, maxX + 0.5, y, z + 0.5);
        }

        cornerPost(level, new BlockPos(minX, selection.baseY(), minZ));
        cornerPost(level, new BlockPos(maxX, selection.baseY(), minZ));
        cornerPost(level, new BlockPos(minX, selection.baseY(), maxZ));
        cornerPost(level, new BlockPos(maxX, selection.baseY(), maxZ));
        orientationPost(level, selection, state.quarterTurns());
    }

    private void cornerPost(ClientLevel level, BlockPos pos) {
        for (int i = 1; i <= 5; i++) {
            particle(level, pos.getX() + 0.5, pos.getY() + i, pos.getZ() + 0.5);
        }
    }

    private void particle(ClientLevel level, double x, double y, double z) {
        level.addParticle(ParticleTypes.END_ROD, true, false, x, y, z, 0.0, 0.0, 0.0);
    }

    private void orientationPost(ClientLevel level, BuildSelection selection, int quarterTurns) {
        int x = switch (quarterTurns) {
            case 1, 2 -> selection.maxX();
            default -> selection.minX();
        };
        int z = switch (quarterTurns) {
            case 2, 3 -> selection.maxZ();
            default -> selection.minZ();
        };
        for (int i = 1; i <= 3; i++) {
            level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, true, false,
                    x + 0.5, selection.baseY() + i, z + 0.5,
                    0.0, 0.0, 0.0);
        }
    }
}
