package com.angel.aibuilder.build;

import java.util.ArrayList;
import java.util.List;

public final class BuildPlan {
    private final List<BuildOperation> operations = new ArrayList<>();

    public void add(BuildOperation operation) {
        operations.add(operation);
    }

    public List<BuildOperation> operations() {
        return List.copyOf(operations);
    }
}
