package com.angel.aibuilder.build;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

public class BuildQueue {
    private static final Queue<QueuedBuild> BUILDS = new ArrayDeque<>();

    public static void enqueue(QueuedBuild build) {
        BUILDS.add(build);
    }

    public static void cancelBuilds(UUID playerId) {
        BUILDS.removeIf(build -> build.isFor(playerId));
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        BuildUndoManager.tickRestores();
        QueuedBuild build = BUILDS.peek();
        if (build != null && build.tick()) {
            BUILDS.poll();
        }
    }
}
