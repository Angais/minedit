package com.angel.aibuilder.build;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuildPlanTest {
    @Test
    void capsOperationsFromUntrustedCode() {
        BuildPlan plan = new BuildPlan();
        SetBlockOperation operation = new SetBlockOperation(0, 0, 0, new BlockSpec("minecraft:stone", Map.of()));
        for (int i = 0; i < 500_000; i++) {
            plan.add(operation);
        }
        assertThrows(IllegalStateException.class, () -> plan.add(operation));
        assertEquals(500_000, plan.operations().size());
    }
}
