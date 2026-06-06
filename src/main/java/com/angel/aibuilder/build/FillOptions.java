package com.angel.aibuilder.build;

import java.util.Map;

public record FillOptions(String mode, Map<String, String> states) {
    public static FillOptions replace(Map<String, String> states) {
        return new FillOptions("replace", states);
    }
}
