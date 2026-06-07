package com.angel.aibuilder.config;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class AiBuilderSettings {
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("minedit.properties");
    private static final Path LEGACY_FILE = FMLPaths.CONFIGDIR.get().resolve("aibuilder.properties");
    private static final Properties PROPERTIES = new Properties();
    private static boolean loaded;

    private AiBuilderSettings() {
    }

    public static synchronized String apiKey() {
        load();
        return PROPERTIES.getProperty("openrouter_api_key", "").trim();
    }

    public static synchronized String provider() {
        load();
        return PROPERTIES.getProperty("provider", "openrouter").trim();
    }

    public static synchronized String codexUrl() {
        load();
        return PROPERTIES.getProperty("codex_url", "http://127.0.0.1:8765").trim();
    }

    public static synchronized String model() {
        load();
        return PROPERTIES.getProperty("model", "openai/gpt-5.5").trim();
    }

    public static synchronized String effort() {
        load();
        return PROPERTIES.getProperty("effort", "medium").trim();
    }

    public static synchronized String quickEffort() {
        load();
        return PROPERTIES.getProperty("quick_effort", "low").trim();
    }

    public static synchronized void setApiKey(String key) throws IOException {
        load();
        PROPERTIES.setProperty("openrouter_api_key", key.trim());
        save();
    }

    public static synchronized void setProvider(String provider) throws IOException {
        load();
        PROPERTIES.setProperty("provider", provider.trim());
        save();
    }

    public static synchronized void setCodexUrl(String url) throws IOException {
        load();
        PROPERTIES.setProperty("codex_url", url.trim());
        save();
    }

    public static synchronized void setModel(String model) throws IOException {
        load();
        PROPERTIES.setProperty("model", model.trim());
        save();
    }

    public static synchronized void setEffort(String effort) throws IOException {
        load();
        PROPERTIES.setProperty("effort", effort.trim());
        save();
    }

    public static synchronized void setQuickEffort(String effort) throws IOException {
        load();
        PROPERTIES.setProperty("quick_effort", effort.trim());
        save();
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = Files.exists(FILE) ? FILE : LEGACY_FILE;
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            PROPERTIES.load(reader);
        } catch (IOException ignored) {
            // Commands will report save failures; load failure just falls back to defaults.
        }
    }

    private static void save() throws IOException {
        Files.createDirectories(FILE.getParent());
        try (Writer writer = Files.newBufferedWriter(FILE)) {
            PROPERTIES.store(writer, "Minedit settings");
        }
    }
}
