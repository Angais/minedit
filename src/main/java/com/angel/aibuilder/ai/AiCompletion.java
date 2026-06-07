package com.angel.aibuilder.ai;

public record AiCompletion(String text, String usageSummary) {
    public boolean hasUsageSummary() {
        return usageSummary != null && !usageSummary.isBlank();
    }
}
