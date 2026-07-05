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
import java.util.concurrent.TimeUnit;

final class QueuedBuild {
    private static final long PROGRESS_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final int PROGRESS_MIN_BLOCKS = 10_000;

    private final UUID playerId;
    private final ServerLevel level;
    private final BuildSelection selection;
    private final Queue<BuildOperation> operations;
    private final BuildUndoManager.Snapshot snapshot;
    private final long estimatedBlocks;
    private long processed;
    private long lastProgressNanos = System.nanoTime();
    private FillCursor cursor;
    private int placed;
    private int skipped;
    private int riskyFluidSkipped;

    QueuedBuild(ServerPlayer player, BuildSelection selection, List<BuildOperation> operations) {
        this(player, selection, operations, BuildUndoManager.beginSnapshot(player, (ServerLevel) player.level()));
    }

    QueuedBuild(ServerPlayer player, BuildSelection selection, List<BuildOperation> operations, BuildUndoManager.Snapshot snapshot) {
        this.playerId = player.getUUID();
        this.level = (ServerLevel) player.level();
        this.selection = selection;
        this.operations = new ArrayDeque<>(operations);
        this.snapshot = snapshot;
        this.estimatedBlocks = estimateBlocks(operations);
    }

    boolean isFor(UUID playerId) {
        return this.playerId.equals(playerId);
    }

    boolean tick(int budget) {
        int remaining = budget;
        while (remaining > 0) {
            if (cursor != null) {
                remaining = cursor.place(level, selection, remaining);
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
                remaining--;
            } else if (operation instanceof FillOperation fill) {
                cursor = new FillCursor(fill);
            }
        }
        processed += budget - remaining;
        maybeSendProgress();
        return false;
    }

    private void maybeSendProgress() {
        if (estimatedBlocks < PROGRESS_MIN_BLOCKS) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastProgressNanos < PROGRESS_INTERVAL_NANOS) {
            return;
        }
        lastProgressNanos = now;
        // Fills that skip entirely consume less budget than their volume, so the
        // estimate can only overshoot; cap at 99% until the completion message.
        long percent = Math.min(99, processed * 100 / estimatedBlocks);
        notifyPlayer("Minedit: building... ~" + percent + "% (" + placed + " blocks placed so far).");
    }

    private static long estimateBlocks(List<BuildOperation> operations) {
        long total = 0;
        for (BuildOperation operation : operations) {
            if (operation instanceof FillOperation fill) {
                total += (long) (Math.abs(fill.x1() - fill.x2()) + 1)
                        * (Math.abs(fill.y1() - fill.y2()) + 1)
                        * (Math.abs(fill.z1() - fill.z2()) + 1);
            } else {
                total++;
            }
        }
        return total;
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
        private final BlockState state;
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
            this.state = BlockStateResolver.resolve(operation.block()).orElse(null);
            minX = Math.min(operation.x1(), operation.x2());
            maxX = Math.max(operation.x1(), operation.x2());
            minY = Math.min(operation.y1(), operation.y2());
            maxY = Math.max(operation.y1(), operation.y2());
            minZ = Math.min(operation.z1(), operation.z2());
            maxZ = Math.max(operation.z1(), operation.z2());
            x = minX;
            z = minZ;
            // An unresolvable block id skips the whole fill; the cursor must still finish
            // or it would stall the build queue forever.
            y = state == null ? maxY + 1 : minY;
        }

        private int place(ServerLevel level, BuildSelection selection, int budget) {
            lastPlaced = 0;
            lastSkipped = 0;
            if (state == null) {
                lastSkipped++;
                return budget - 1;
            }

            String mode = operation.options().mode();
            while (budget > 0 && !done()) {
                BlockPos pos = new BlockPos(selection.minX() + x, selection.baseY() + y, selection.minZ() + z);
                BlockState currentState = level.isInWorldBounds(pos) ? level.getBlockState(pos) : null;
                boolean boundary = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                boolean shouldPlace = switch (mode) {
                    case "hollow", "outline" -> boundary;
                    case "clear" -> currentState != null && !currentState.isAir();
                    default -> true;
                };

                if (shouldPlace && insideFootprint(x, z) && level.isInWorldBounds(pos)) {
                    if (!"keep".equals(mode) || currentState == null || currentState.isAir()) {
                        if (isUnsafeFluidPlacement(state, x, z)) {
                            QueuedBuild.this.riskyFluidSkipped++;
                            lastSkipped++;
                        } else if (canPlace(state, pos)) {
                            snapshot.capture(pos);
                            level.setBlock(pos, state, 3);
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
