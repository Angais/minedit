package com.angel.aibuilder.debug;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BuildDebugFiles {
    private static final Path DIR = FMLPaths.CONFIGDIR.get().resolve("minedit-debug");
    private static final Path EXPORT_PROMPT = DIR.resolve("export-prompt.txt");
    private static final Path IMPORT_BUILD = DIR.resolve("import-build.js");

    private BuildDebugFiles() {
    }

    public static void writeLast(String prompt, String response, String code) {
        try {
            Files.createDirectories(DIR);
            Files.writeString(DIR.resolve("last-prompt.txt"), prompt == null ? "" : prompt, StandardCharsets.UTF_8);
            Files.writeString(DIR.resolve("last-response.txt"), response == null ? "" : response, StandardCharsets.UTF_8);
            Files.writeString(DIR.resolve("last-build.js"), code == null ? "" : code, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Debug dumps should never make the build fail.
        }
    }

    public static Path writeExportPrompt(String prompt) throws IOException {
        Files.createDirectories(DIR);
        Files.writeString(EXPORT_PROMPT, prompt == null ? "" : prompt, StandardCharsets.UTF_8);
        if (!Files.exists(IMPORT_BUILD)) {
            Files.writeString(IMPORT_BUILD, "", StandardCharsets.UTF_8);
        }
        return EXPORT_PROMPT;
    }

    public static Path importBuildPath() {
        return IMPORT_BUILD;
    }

    public static String readImportBuild() throws IOException {
        return Files.readString(IMPORT_BUILD, StandardCharsets.UTF_8);
    }
}
