package com.angel.aibuilder.ai;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

public enum AiProvider {
    OPENROUTER("openrouter", "OpenRouter"),
    CODEX_LOCAL("codex-local", "Codex local bridge");

    private final String id;
    private final String displayName;

    AiProvider(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<AiProvider> fromId(String id) {
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(provider -> provider.id.equals(normalized)).findFirst();
    }

    public static String ids() {
        return Arrays.stream(values()).map(AiProvider::id).collect(Collectors.joining(", "));
    }
}
