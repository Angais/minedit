package com.angel.aibuilder.build;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

public final class BuildUndoManager {
    private static final int RESTORE_PER_TICK = 768;
    // Bounded so repeated large builds cannot pin unlimited block state in memory.
    private static final int MAX_SNAPSHOTS_PER_PLAYER = 5;
    private static final Map<UUID, Deque<Snapshot>> SNAPSHOTS = new HashMap<>();
    private static final Queue<RestoreJob> RESTORES = new ArrayDeque<>();

    private BuildUndoManager() {
    }

    public static Snapshot beginSnapshot(ServerPlayer player, ServerLevel level) {
        Snapshot snapshot = new Snapshot(player.getUUID(), level);
        Deque<Snapshot> stack = SNAPSHOTS.computeIfAbsent(player.getUUID(), id -> new ArrayDeque<>());
        stack.push(snapshot);
        while (stack.size() > MAX_SNAPSHOTS_PER_PLAYER) {
            stack.removeLast();
        }
        return snapshot;
    }

    public static boolean resetLastBuild(ServerPlayer player) {
        // A build that is still placing into the same snapshot would fight the restore
        // block-by-block, so it has to be cancelled before the restore starts.
        BuildQueue.cancelBuilds(player.getUUID());
        Deque<Snapshot> stack = SNAPSHOTS.get(player.getUUID());
        while (stack != null && !stack.isEmpty()) {
            Snapshot snapshot = stack.pop();
            if (!snapshot.originalStates.isEmpty()) {
                RESTORES.add(new RestoreJob(player.getUUID(), snapshot.level, snapshot.originalStates));
                return true;
            }
        }
        SNAPSHOTS.remove(player.getUUID());
        return false;
    }

    public static int undoDepth(UUID playerId) {
        Deque<Snapshot> stack = SNAPSHOTS.get(playerId);
        return stack == null ? 0 : stack.size();
    }

    /** Snapshots and restores hold ServerLevel references; they must not outlive the server. */
    static void clearAll() {
        SNAPSHOTS.clear();
        RESTORES.clear();
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
