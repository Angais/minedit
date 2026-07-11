package com.angel.aibuilder.selection;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class SelectionManager {
    private static final Map<UUID, BlockPos> FIRST = new HashMap<>();
    private static final Map<UUID, SelectionState> SELECTIONS = new HashMap<>();
    private static final Map<UUID, SelectionState> PREVIOUS_SELECTIONS = new HashMap<>();
    private static final Map<UUID, BlockPos> CLIENT_FIRST = new HashMap<>();
    private static final Map<UUID, SelectionState> CLIENT_SELECTIONS = new HashMap<>();
    private static final Map<UUID, SelectionState> CLIENT_PREVIOUS_SELECTIONS = new HashMap<>();

    private SelectionManager() {
    }

    public static SelectionClick selectServer(UUID playerId, BlockPos pos) {
        return select(FIRST, SELECTIONS, PREVIOUS_SELECTIONS, playerId, pos);
    }

    public static SelectionClick selectClient(UUID playerId, BlockPos pos) {
        return select(CLIENT_FIRST, CLIENT_SELECTIONS, CLIENT_PREVIOUS_SELECTIONS, playerId, pos);
    }

    private static SelectionClick select(Map<UUID, BlockPos> firstMap, Map<UUID, SelectionState> selectionMap, Map<UUID, SelectionState> previousMap, UUID playerId, BlockPos pos) {
        BlockPos first = firstMap.remove(playerId);
        if (first == null) {
            rememberPrevious(selectionMap, previousMap, playerId);
            firstMap.put(playerId, pos.immutable());
            selectionMap.remove(playerId);
            return new SelectionClick(true, null);
        }

        BuildSelection selection = new BuildSelection(first, pos.immutable());
        selectionMap.put(playerId, SelectionState.from(selection));
        return new SelectionClick(false, selection);
    }

    public static Optional<BuildSelection> selection(UUID playerId) {
        return state(playerId).map(SelectionState::selection);
    }

    public static Optional<BuildSelection> clientSelection(UUID playerId) {
        return clientState(playerId).map(SelectionState::selection);
    }

    public static Optional<SelectionState> state(UUID playerId) {
        return Optional.ofNullable(SELECTIONS.get(playerId));
    }

    public static Optional<SelectionState> clientState(UUID playerId) {
        return Optional.ofNullable(CLIENT_SELECTIONS.get(playerId));
    }

    public static void setServer(UUID playerId, BuildSelection selection) {
        setServer(playerId, SelectionState.from(selection));
    }

    public static void setClient(UUID playerId, BuildSelection selection) {
        setClient(playerId, SelectionState.from(selection));
    }

    public static void setServer(UUID playerId, SelectionState state) {
        set(FIRST, SELECTIONS, PREVIOUS_SELECTIONS, playerId, state);
    }

    public static void setClient(UUID playerId, SelectionState state) {
        set(CLIENT_FIRST, CLIENT_SELECTIONS, CLIENT_PREVIOUS_SELECTIONS, playerId, state);
    }

    private static void set(Map<UUID, BlockPos> firstMap, Map<UUID, SelectionState> selectionMap, Map<UUID, SelectionState> previousMap, UUID playerId, SelectionState state) {
        firstMap.remove(playerId);
        rememberPrevious(selectionMap, previousMap, playerId);
        selectionMap.put(playerId, state);
    }

    public static Optional<BuildSelection> restoreServer(UUID playerId) {
        return restore(FIRST, SELECTIONS, PREVIOUS_SELECTIONS, playerId);
    }

    private static Optional<BuildSelection> restore(Map<UUID, BlockPos> firstMap, Map<UUID, SelectionState> selectionMap, Map<UUID, SelectionState> previousMap, UUID playerId) {
        SelectionState previous = previousMap.get(playerId);
        if (previous == null) {
            return Optional.empty();
        }

        firstMap.remove(playerId);
        SelectionState current = selectionMap.put(playerId, previous);
        if (current == null || current.equals(previous)) {
            previousMap.remove(playerId);
        } else {
            previousMap.put(playerId, current);
        }
        return Optional.of(previous.selection());
    }

    public static void clearServer(UUID playerId) {
        rememberPrevious(SELECTIONS, PREVIOUS_SELECTIONS, playerId);
        FIRST.remove(playerId);
        SELECTIONS.remove(playerId);
    }

    public static void clearClient(UUID playerId) {
        rememberPrevious(CLIENT_SELECTIONS, CLIENT_PREVIOUS_SELECTIONS, playerId);
        CLIENT_FIRST.remove(playerId);
        CLIENT_SELECTIONS.remove(playerId);
    }

    private static void rememberPrevious(Map<UUID, SelectionState> selectionMap, Map<UUID, SelectionState> previousMap, UUID playerId) {
        SelectionState current = selectionMap.get(playerId);
        if (current != null) {
            previousMap.put(playerId, current);
        }
    }

    public record SelectionState(BuildSelection selection, int quarterTurns, int anchorX, int anchorZ) {
        public SelectionState {
            quarterTurns = Math.floorMod(quarterTurns, 4);
        }

        public static SelectionState from(BuildSelection selection) {
            int anchorX = selection.minX() + (selection.width() - 1) / 2;
            int anchorZ = selection.minZ() + (selection.depth() - 1) / 2;
            return new SelectionState(selection, 0, anchorX, anchorZ);
        }

        public SelectionState moved(int x, int y, int z) {
            return new SelectionState(selection.moved(x, y, z), quarterTurns, anchorX + x, anchorZ + z);
        }

        public SelectionState rotated(int turns) {
            boolean swapDimensions = Math.floorMod(turns, 2) == 1;
            int rotatedWidth = swapDimensions ? selection.depth() : selection.width();
            int rotatedDepth = swapDimensions ? selection.width() : selection.depth();
            BuildSelection rotated = BuildSelection.centeredAt(
                    new BlockPos(anchorX, selection.baseY(), anchorZ),
                    rotatedWidth,
                    rotatedDepth
            );
            return new SelectionState(rotated, quarterTurns + turns, anchorX, anchorZ);
        }

        public int sourceWidth() {
            return quarterTurns % 2 == 0 ? selection.width() : selection.depth();
        }

        public int sourceDepth() {
            return quarterTurns % 2 == 0 ? selection.depth() : selection.width();
        }
    }

    public record SelectionClick(boolean firstCorner, BuildSelection selection) {
    }
}
