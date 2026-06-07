package com.angel.aibuilder.build;

import com.angel.aibuilder.selection.BuildSelection;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

final class QueuedBuild {
    private static final int BLOCKS_PER_TICK = 512;

    private final UUID playerId;
    private final ServerLevel level;
    private final BuildSelection selection;
    private final Queue<BuildOperation> operations;
    private final BuildUndoManager.Snapshot snapshot;
    private FillCursor cursor;
    private int placed;
    private int skipped;
    private int riskyFluidSkipped;

    QueuedBuild(ServerPlayer player, BuildSelection selection, List<BuildOperation> operations) {
        this.playerId = player.getUUID();
        this.level = (ServerLevel) player.level();
        this.selection = selection;
        this.operations = new ArrayDeque<>(operations);
        this.snapshot = BuildUndoManager.beginSnapshot(player, level);
    }

    boolean isFor(UUID playerId) {
        return this.playerId.equals(playerId);
    }

    boolean tick() {
        int budget = BLOCKS_PER_TICK;
        while (budget > 0) {
            if (cursor != null) {
                budget = cursor.place(level, selection, budget);
                placed += cursor.lastPlaced;
                skipped += cursor.lastSkipped;
                if (!cursor.done()) {
                    break;
                }
                cursor = null;
                continue;
            }

            BuildOperation operation = operations.poll();
            if (operation == null) {
                String message = "Minedit: build complete. Placed " + placed + " blocks, skipped " + skipped + ".";
                if (riskyFluidSkipped > 0) {
                    message += " Blocked " + riskyFluidSkipped + " edge fluid blocks to reduce overflow.";
                }
                notifyPlayer(message);
                return true;
            }

            if (operation instanceof SetBlockOperation set) {
                if (placeSet(set)) {
                    placed++;
                } else {
                    skipped++;
                }
                budget--;
            } else if (operation instanceof FillOperation fill) {
                cursor = new FillCursor(fill);
            }
        }
        return false;
    }

    private boolean placeSet(SetBlockOperation operation) {
        if (!insideFootprint(operation.x(), operation.z())) {
            return false;
        }
        Optional<BlockState> state = BlockStateResolver.resolve(operation.block());
        if (state.isEmpty()) {
            return false;
        }
        BlockPos pos = toWorld(operation.x(), operation.y(), operation.z());
        if (!level.isInWorldBounds(pos)) {
            return false;
        }
        if (isUnsafeFluidPlacement(state.get(), operation.x(), operation.z())) {
            riskyFluidSkipped++;
            return false;
        }
        if (!canPlace(state.get(), pos)) {
            return false;
        }
        snapshot.capture(pos);
        level.setBlock(pos, state.get(), 3);
        return true;
    }

    private boolean insideFootprint(int x, int z) {
        return x >= 0 && x < selection.width() && z >= 0 && z < selection.depth();
    }

    private BlockPos toWorld(int x, int y, int z) {
        return new BlockPos(selection.minX() + x, selection.baseY() + y, selection.minZ() + z);
    }

    private void notifyPlayer(String message) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }

    private final class FillCursor {
        private final FillOperation operation;
        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;
        private final int minZ;
        private final int maxZ;
        private int x;
        private int y;
        private int z;
        private int lastPlaced;
        private int lastSkipped;

        private FillCursor(FillOperation operation) {
            this.operation = operation;
            minX = Math.min(operation.x1(), operation.x2());
            maxX = Math.max(operation.x1(), operation.x2());
            minY = Math.min(operation.y1(), operation.y2());
            maxY = Math.max(operation.y1(), operation.y2());
            minZ = Math.min(operation.z1(), operation.z2());
            maxZ = Math.max(operation.z1(), operation.z2());
            x = minX;
            y = minY;
            z = minZ;
        }

        private int place(ServerLevel level, BuildSelection selection, int budget) {
            lastPlaced = 0;
            lastSkipped = 0;
            Optional<BlockState> state = BlockStateResolver.resolve(operation.block());
            if (state.isEmpty()) {
                lastSkipped++;
                z = maxZ + 1;
                return budget - 1;
            }

            while (budget > 0 && !done()) {
                BlockPos pos = new BlockPos(selection.minX() + x, selection.baseY() + y, selection.minZ() + z);
                BlockState currentState = level.isInWorldBounds(pos) ? level.getBlockState(pos) : null;
                boolean boundary = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                String mode = operation.options().mode();
                boolean shouldPlace = switch (mode) {
                    case "hollow", "outline" -> boundary;
                    case "clear" -> currentState != null && !currentState.isAir();
                    case "keep" -> true;
                    default -> true;
                };

                if (shouldPlace && insideFootprint(x, z) && level.isInWorldBounds(pos)) {
                    if (!"keep".equals(mode) || currentState == null || currentState.isAir()) {
                        if (isUnsafeFluidPlacement(state.get(), x, z)) {
                            QueuedBuild.this.riskyFluidSkipped++;
                            lastSkipped++;
                        } else if (canPlace(state.get(), pos)) {
                            snapshot.capture(pos);
                            level.setBlock(pos, state.get(), 3);
                            lastPlaced++;
                        } else {
                            lastSkipped++;
                        }
                    } else {
                        lastSkipped++;
                    }
                } else if (!"clear".equals(mode)) {
                    lastSkipped++;
                }

                advance();
                budget--;
            }
            return budget;
        }

        private void advance() {
            x++;
            if (x <= maxX) {
                return;
            }
            x = minX;
            z++;
            if (z <= maxZ) {
                return;
            }
            z = minZ;
            y++;
        }

        private boolean done() {
            return y > maxY;
        }
    }

    private boolean canPlace(BlockState state, BlockPos pos) {
        return state.isAir() || state.canSurvive(level, pos);
    }

    private boolean isUnsafeFluidPlacement(BlockState state, int x, int z) {
        return !state.getFluidState().isEmpty()
                && (x <= 0 || z <= 0 || x >= selection.width() - 1 || z >= selection.depth() - 1);
    }
}
