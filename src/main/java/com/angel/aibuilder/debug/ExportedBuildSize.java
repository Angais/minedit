package com.angel.aibuilder.debug;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ExportedBuildSize(int width, int depth) {
    private static final Pattern PROMPT_FOOTPRINT = Pattern.compile(
            "selected\\s+footprint\\s+is\\s+width=(\\d+)\\s+blocks\\s+on\\s+X\\s+and\\s+depth=(\\d+)\\s+blocks\\s+on\\s+Z",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern IMPORT_FOOTPRINT = Pattern.compile(
            "minedit-footprint\\s*:\\s*width\\s*=\\s*(\\d+)\\s*(?:[,;]|\\s)+\\s*depth\\s*=\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern IMPORT_COMPACT_FOOTPRINT = Pattern.compile(
            "minedit-footprint\\s*:\\s*(\\d+)\\s*[x×]\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern WIDTH_VARIABLE = Pattern.compile(
            "\\b(?:var|let|const)\\s+(?:w|width)\\s*=\\s*(\\d+)\\s*;",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DEPTH_VARIABLE = Pattern.compile(
            "\\b(?:var|let|const)\\s+(?:d|depth)\\s*=\\s*(\\d+)\\s*;",
            Pattern.CASE_INSENSITIVE
    );

    public ExportedBuildSize {
        if (width < 1 || depth < 1) {
            throw new IllegalArgumentException("Exported build dimensions must be positive.");
        }
    }

    public static Optional<ExportedBuildSize> parse(String prompt) {
        return parseWithPattern(prompt, PROMPT_FOOTPRINT);
    }

    public static Optional<ExportedBuildSize> parseImport(String importCode) {
        Optional<ExportedBuildSize> metadata = parseWithPattern(importCode, IMPORT_FOOTPRINT);
        if (metadata.isPresent()) {
            return metadata;
        }
        metadata = parseWithPattern(importCode, IMPORT_COMPACT_FOOTPRINT);
        if (metadata.isPresent()) {
            return metadata;
        }

        // Some older/manual responses include the original prompt before the function.
        metadata = parse(importCode);
        if (metadata.isPresent()) {
            return metadata;
        }

        // A hard-coded w/d pair is exact. api.getWidth()/getDepth() is deliberately not
        // guessed: parameterized code contains no record of the dimensions it was made for.
        if (importCode == null || importCode.isBlank()) {
            return Optional.empty();
        }
        Matcher width = WIDTH_VARIABLE.matcher(importCode);
        Matcher depth = DEPTH_VARIABLE.matcher(importCode);
        if (!width.find() || !depth.find()) {
            return Optional.empty();
        }
        return size(width.group(1), depth.group(1));
    }

    public String metadataLine() {
        return "// minedit-footprint: width=" + width + " depth=" + depth;
    }

    private static Optional<ExportedBuildSize> parseWithPattern(String text, Pattern pattern) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return size(matcher.group(1), matcher.group(2));
    }

    private static Optional<ExportedBuildSize> size(String width, String depth) {
        try {
            return Optional.of(new ExportedBuildSize(
                    Integer.parseInt(width),
                    Integer.parseInt(depth)
            ));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
