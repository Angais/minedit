package com.angel.aibuilder.openrouter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelReasoningTest {
    // Real shapes as of 2026-07: Fable has no none/minimal and tops out at max,
    // GPT-5.5 tops out at xhigh but can disable reasoning entirely.
    private static final OpenRouterClient.ModelReasoning FABLE =
            new OpenRouterClient.ModelReasoning(List.of("max", "xhigh", "high", "medium", "low"), "medium", true);
    private static final OpenRouterClient.ModelReasoning GPT_5_5 =
            new OpenRouterClient.ModelReasoning(List.of("xhigh", "high", "medium", "low", "none"), "medium", false);

    @Test
    void reportsSupportedEfforts() {
        assertTrue(FABLE.supports("max"));
        assertFalse(GPT_5_5.supports("max"));
        assertFalse(FABLE.supports("none"));
    }

    @Test
    void mapsMaxDownToXhigh() {
        assertEquals("xhigh", GPT_5_5.nearestEffort("max"));
    }

    @Test
    void mapsDisabledReasoningUpToLowestSupportedLevel() {
        assertEquals("low", FABLE.nearestEffort("none"));
        assertEquals("low", FABLE.nearestEffort("minimal"));
    }

    @Test
    void prefersHigherEffortOnDistanceTies() {
        OpenRouterClient.ModelReasoning gappy =
                new OpenRouterClient.ModelReasoning(List.of("high", "low"), "high", false);
        assertEquals("high", gappy.nearestEffort("medium"));
    }

    @Test
    void fallsBackToDefaultWhenSupportedLevelsAreUnrecognized() {
        OpenRouterClient.ModelReasoning exotic =
                new OpenRouterClient.ModelReasoning(List.of("turbo"), "turbo", false);
        assertEquals("turbo", exotic.nearestEffort("max"));
    }
}
