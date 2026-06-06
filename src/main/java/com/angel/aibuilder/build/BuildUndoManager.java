package com.angel.aibuilder.build;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

public final class BuildUndoManager {
    private static final int RESTORE_PER_TICK = 768;
    private static final Map<UUID, Snapshot> SNAPSHOTS = new HashMap<>();
    private static final Queue<RestoreJob> RESTORES = new ArrayDeque<>();

    private BuildUndoManager() {
    }

    public static Snapshot beginSnapshot(ServerPlayer player, ServerLevel level) {
        Snapshot snapshot = new Snapshot(player.getUUID(), level);
        SNAPSHOTS.put(player.getUUID(), snapshot);
        return snapshot;
    }

    public static boolean resetLastBuild(ServerPlayer player) {
        Snapshot snapshot = SNAPSHOTS.remove(player.getUUID());
        if (snapshot == null || snapshot.originalStates.isEmpty()) {
            return false;
        }
        RESTORES.add(new RestoreJob(player.getUUID(), snapshot.level, snapshot.originalStates));
        return true;
    }

    static void tickRestores() {
        RestoreJob job = RESTORES.peek();
        if (job != null && job.tick()) {
            RESTORES.poll();
        }
    }

    public static final class Snapshot {
        private final UUID playerId;
        private final ServerLevel level;
        private final Map<BlockPos, BlockState> originalStates = new HashMap<>();

        private Snapshot(UUID playerId, ServerLevel level) {
            this.playerId = playerId;
            this.level = level;
        }

        public void capture(BlockPos pos) {
            BlockPos immutable = pos.immutable();
            originalStates.computeIfAbsent(immutable, level::getBlockState);
        }

        public int size() {
            return originalStates.size();
        }
    }

    private static final class RestoreJob {
        private final UUID playerId;
        private final ServerLevel level;
        private final Queue<Map.Entry<BlockPos, BlockState>> states;
        private int restored;

        private RestoreJob(UUID playerId, ServerLevel level, Map<BlockPos, BlockState> states) {
            this.playerId = playerId;
            this.level = level;
            this.states = new ArrayDeque<>(states.entrySet());
        }

        private boolean tick() {
            int budget = RESTORE_PER_TICK;
            while (budget-- > 0 && !states.isEmpty()) {
                Map.Entry<BlockPos, BlockState> entry = states.poll();
                if (level.isInWorldBounds(entry.getKey())) {
                    level.setBlock(entry.getKey(), entry.getValue(), 3);
                    restored++;
                }
            }

            if (states.isEmpty()) {
                Optional.ofNullable(level.getServer().getPlayerList().getPlayer(playerId))
                        .ifPresent(player -> player.sendSystemMessage(Component.literal("Minedit: reset build restored " + restored + " blocks.")));
                return true;
            }
            return false;
        }
    }
}
