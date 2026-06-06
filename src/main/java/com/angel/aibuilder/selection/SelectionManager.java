package com.angel.aibuilder.selection;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class SelectionManager {
    private static final Map<UUID, BlockPos> FIRST = new HashMap<>();
    private static final Map<UUID, BuildSelection> SELECTIONS = new HashMap<>();
    private static final Map<UUID, BlockPos> CLIENT_FIRST = new HashMap<>();
    private static final Map<UUID, BuildSelection> CLIENT_SELECTIONS = new HashMap<>();

    private SelectionManager() {
    }

    public static SelectionClick selectServer(UUID playerId, BlockPos pos) {
        return select(FIRST, SELECTIONS, playerId, pos);
    }

    public static SelectionClick selectClient(UUID playerId, BlockPos pos) {
        return select(CLIENT_FIRST, CLIENT_SELECTIONS, playerId, pos);
    }

    private static SelectionClick select(Map<UUID, BlockPos> firstMap, Map<UUID, BuildSelection> selectionMap, UUID playerId, BlockPos pos) {
        BlockPos first = firstMap.remove(playerId);
        if (first == null) {
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

    public static void clearServer(UUID playerId) {
        FIRST.remove(playerId);
        SELECTIONS.remove(playerId);
    }

    public static void clearClient(UUID playerId) {
        CLIENT_FIRST.remove(playerId);
        CLIENT_SELECTIONS.remove(playerId);
    }

    public record SelectionClick(boolean firstCorner, BuildSelection selection) {
    }
}
