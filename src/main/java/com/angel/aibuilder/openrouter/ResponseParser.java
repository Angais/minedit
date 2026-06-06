package com.angel.aibuilder.openrouter;

public final class ResponseParser {
    private ResponseParser() {
    }

    public static String extractCode(String response) {
        String trimmed = response.trim();
        String codeTag = between(trimmed, "<code>", "</code>");
        if (codeTag != null) {
            trimmed = codeTag.trim();
        }

        int fence = trimmed.indexOf("```");
        if (fence >= 0) {
            int lineEnd = trimmed.indexOf('\n', fence);
            int endFence = trimmed.indexOf("```", lineEnd + 1);
            if (lineEnd >= 0 && endFence > lineEnd) {
                trimmed = trimmed.substring(lineEnd + 1, endFence).trim();
            }
        }

        trimmed = stripFenceLines(trimmed);
        String buildFunction = extractBuildFunction(trimmed);
        return buildFunction != null ? buildFunction : trimmed;
    }

    private static String stripFenceLines(String text) {
        StringBuilder builder = new StringBuilder();
        for (String line : text.split("\\R")) {
            String stripped = line.trim();
            if (!stripped.startsWith("```")) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString().trim();
    }

    private static String extractBuildFunction(String text) {
        int functionIndex = text.indexOf("function build");
        if (functionIndex < 0) {
            return null;
        }

        int braceIndex = text.indexOf('{', functionIndex);
        if (braceIndex < 0) {
            return null;
        }

        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;
        for (int i = braceIndex; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (inSingle || inDouble) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(functionIndex, i + 1).trim();
                }
            }
        }
        return null;
    }

    private static String between(String text, String start, String end) {
        int startIndex = text.indexOf(start);
        if (startIndex < 0) {
            return null;
        }
        int contentStart = startIndex + start.length();
        int endIndex = text.indexOf(end, contentStart);
        if (endIndex < 0) {
            return null;
        }
        return text.substring(contentStart, endIndex);
    }
}
