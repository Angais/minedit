package com.angel.aibuilder.ai;

public record AiRequestOptions(AiProvider provider, String openRouterApiKey, String codexUrl, String model, String effort, boolean streaming, String openRouterProvider, int maxCompletionTokens) {
    public String targetDescription() {
        return switch (provider) {
            case OPENROUTER -> model + " via OpenRouter" + (openRouterProvider.isBlank() ? "" : " provider " + openRouterProvider);
            case CODEX_LOCAL -> model + " via Codex bridge at " + codexUrl;
            case CURSOR -> model + " via Cursor bridge at " + codexUrl;
        };
    }
}
