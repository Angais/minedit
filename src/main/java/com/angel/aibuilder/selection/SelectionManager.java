package com.angel.aibuilder.selection;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class SelectionManager {
    private static final Map<UUID, BlockPos> FIRST = new HashMap<>();
    private static final Map<UUID, BuildSelection> SELECTIONS = new HashMap<>();
    private static final Map<UUID, BuildSelection> PREVIOUS_SELECTIONS = new HashMap<>();
    private static final Map<UUID, BlockPos> CLIENT_FIRST = new HashMap<>();
    private static final Map<UUID, BuildSelection> CLIENT_SELECTIONS = new HashMap<>();
    private static final Map<UUID, BuildSelection> CLIENT_PREVIOUS_SELECTIONS = new HashMap<>();

    private SelectionManager() {
    }

    public static SelectionClick selectServer(UUID playerId, BlockPos pos) {
        return select(FIRST, SELECTIONS, PREVIOUS_SELECTIONS, playerId, pos);
    }

    public static SelectionClick selectClient(UUID playerId, BlockPos pos) {
        return select(CLIENT_FIRST, CLIENT_SELECTIONS, CLIENT_PREVIOUS_SELECTIONS, playerId, pos);
    }

    private static SelectionClick select(Map<UUID, BlockPos> firstMap, Map<UUID, BuildSelection> selectionMap, Map<UUID, BuildSelection> previousMap, UUID playerId, BlockPos pos) {
        BlockPos first = firstMap.remove(playerId);
        if (first == null) {
            rememberPrevious(selectionMap, previousMap, playerId);
            firstMap.put(playerId, pos.immutable());
            selectionMap.remove(playerId);
            return new SelectionClick(true, null);
        }

        BuildSelection selection = new BuildSelection(first, pos.immutable());
        selectionMap.put(playerId, selection);
        return new SelectionClick(false, selection);
    }

    public static Optional<BuildSelection> selection(UUID playerId) {
        return Optional.ofNullable(SELECTIONS.get(playerId));
    }

    public static Optional<BuildSelection> clientSelection(UUID playerId) {
        return Optional.ofNullable(CLIENT_SELECTIONS.get(playerId));
    }

    public static void setServer(UUID playerId, BuildSelection selection) {
        set(FIRST, SELECTIONS, PREVIOUS_SELECTIONS, playerId, selection);
    }

    public static void setClient(UUID playerId, BuildSelection selection) {
        set(CLIENT_FIRST, CLIENT_SELECTIONS, CLIENT_PREVIOUS_SELECTIONS, playerId, selection);
    }

    private static void set(Map<UUID, BlockPos> firstMap, Map<UUID, BuildSelection> selectionMap, Map<UUID, BuildSelection> previousMap, UUID playerId, BuildSelection selection) {
        firstMap.remove(playerId);
        rememberPrevious(selectionMap, previousMap, playerId);
        selectionMap.put(playerId, selection);
    }

    public static Optional<BuildSelection> restoreServer(UUID playerId) {
        return restore(FIRST, SELECTIONS, PREVIOUS_SELECTIONS, playerId);
    }

    private static Optional<BuildSelection> restore(Map<UUID, BlockPos> firstMap, Map<UUID, BuildSelection> selectionMap, Map<UUID, BuildSelection> previousMap, UUID playerId) {
        BuildSelection previous = previousMap.get(playerId);
        if (previous == null) {
            return Optional.empty();
        }

        firstMap.remove(playerId);
        BuildSelection current = selectionMap.put(playerId, previous);
        if (current == null || current.equals(previous)) {
            previousMap.remove(playerId);
        } else {
            previousMap.put(playerId, current);
        }
        return Optional.of(previous);
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

    private static void rememberPrevious(Map<UUID, BuildSelection> selectionMap, Map<UUID, BuildSelection> previousMap, UUID playerId) {
        BuildSelection current = selectionMap.get(playerId);
        if (current != null) {
            previousMap.put(playerId, current);
        }
    }

    public record SelectionClick(boolean firstCorner, BuildSelection selection) {
    }
}
