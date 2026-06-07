package com.angel.aibuilder.ai;

public record AiRequestOptions(AiProvider provider, String openRouterApiKey, String codexUrl, String model, String effort) {
    public String targetDescription() {
        return switch (provider) {
            case OPENROUTER -> model + " via OpenRouter";
            case CODEX_LOCAL -> model + " via Codex bridge at " + codexUrl;
        };
    }
}
