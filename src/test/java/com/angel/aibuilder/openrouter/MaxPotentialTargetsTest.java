package com.angel.aibuilder.openrouter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaxPotentialTargetsTest {
    @Test
    void maxPotentialAddsGroundedTargetsForTheSelection() {
        MaxPotentialTargets targets = MaxPotentialTargets.forFootprint(20, 20);

        String rules = targets.promptRules();

        assertEquals(14, targets.xSpan());
        assertEquals(14, targets.zSpan());
        assertEquals(13, targets.height());
        assertEquals(1600, targets.blockBudget());
        assertTrue(rules.contains("Max Potential construction contract:"));
        assertTrue(rules.contains("use at least 14 of 20 blocks on X and at least 14 of 20 blocks on Z"));
        assertTrue(rules.contains("isolated distant blocks"));
        assertTrue(rules.contains("sealed invisible filler"));
    }

    @Test
    void tinySelectionsReceiveAchievableMinimumTargets() {
        MaxPotentialTargets targets = MaxPotentialTargets.forFootprint(1, 1);
        String rules = targets.promptRules();

        assertTrue(rules.contains("use at least 1 of 1 blocks on X and at least 1 of 1 blocks on Z"));
        assertTrue(rules.contains("reach at least 4 blocks above"));
        assertTrue(rules.contains("at least 16 non-air blocks"));
    }
}
