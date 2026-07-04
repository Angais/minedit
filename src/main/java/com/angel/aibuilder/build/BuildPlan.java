package com.angel.aibuilder.build;

import java.util.ArrayList;
import java.util.List;

public final class BuildPlan {
    // Caps untrusted AI code so it cannot queue unbounded work against the server.
    private static final int MAX_OPERATIONS = 500_000;

    private final List<BuildOperation> operations = new ArrayList<>();

    public void add(BuildOperation operation) {
        if (operations.size() >= MAX_OPERATIONS) {
            throw new IllegalStateException("AI build code generated more than " + MAX_OPERATIONS + " operations.");
        }
        operations.add(operation);
    }

    public List<BuildOperation> operations() {
        return List.copyOf(operations);
    }
}
