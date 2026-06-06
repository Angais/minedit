package com.angel.aibuilder.debug;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BuildDebugFiles {
    private static final Path DIR = FMLPaths.CONFIGDIR.get().resolve("minedit-debug");

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
}
