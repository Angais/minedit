package com.angel.aibuilder.build;

import java.util.Map;

public record BlockSpec(String blockId, Map<String, String> states) {
}
