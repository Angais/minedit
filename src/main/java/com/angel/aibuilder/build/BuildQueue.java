package com.angel.aibuilder.build;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;
import java.util.UUID;

public class BuildQueue {
    // Total block budget per tick, split across all queued builds so one player's
    // megabuild cannot starve everyone else's placements.
    private static final int BLOCKS_PER_TICK = 512;
    private static final Queue<QueuedBuild> BUILDS = new ArrayDeque<>();

    public static void enqueue(QueuedBuild build) {
        BUILDS.add(build);
    }

    public static int cancelBuilds(UUID playerId) {
        int before = BUILDS.size();
        BUILDS.removeIf(build -> build.isFor(playerId));
        return before - BUILDS.size();
    }

    public static boolean hasBuildFor(UUID playerId) {
        return BUILDS.stream().anyMatch(build -> build.isFor(playerId));
    }

    public static int size() {
        return BUILDS.size();
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        BuildUndoManager.tickRestores();
        if (BUILDS.isEmpty()) {
            return;
        }
        int share = Math.max(1, BLOCKS_PER_TICK / BUILDS.size());
        Iterator<QueuedBuild> iterator = BUILDS.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().tick(share)) {
                iterator.remove();
            }
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        // Static state holds ServerLevel references; leaving it populated would leak the
        // whole level across world reloads in singleplayer.
        BUILDS.clear();
        BuildUndoManager.clearAll();
    }
}
