package com.angel.aibuilder.openrouter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResponseParserTest {
    @Test
    void extractsCodeFromMarkdownFence() {
        String response = """
                Here is your build:

                ```javascript
                function build(api) { api.set(0, 0, 0, 'stone'); }
                ```

                Enjoy!
                """;
        assertEquals("function build(api) { api.set(0, 0, 0, 'stone'); }", ResponseParser.extractCode(response));
    }

    @Test
    void extractsCodeFromCodeTag() {
        String response = "<code>function build(api) { api.set(0, 0, 0, 'stone'); }</code>";
        assertEquals("function build(api) { api.set(0, 0, 0, 'stone'); }", ResponseParser.extractCode(response));
    }

    @Test
    void passesThroughBareCode() {
        String code = "function build(api) { api.set(0, 0, 0, 'stone'); }";
        assertEquals(code, ResponseParser.extractCode(code));
    }

    @Test
    void extractsBuildFunctionFromSurroundingProse() {
        String response = "Sure! function build(api) { api.set(0, 0, 0, 'stone'); } Let me know if you need changes.";
        assertEquals("function build(api) { api.set(0, 0, 0, 'stone'); }", ResponseParser.extractCode(response));
    }

    @Test
    void ignoresBracesInsideStringLiterals() {
        String response = "function build(api) { api.set(0, 0, 0, 'st}one'); var s = \"{\"; } trailing prose";
        assertEquals("function build(api) { api.set(0, 0, 0, 'st}one'); var s = \"{\"; }", ResponseParser.extractCode(response));
    }

    @Test
    void extractsFirstFenceEvenWithoutLanguageTag() {
        String response = "```\nfunction build(api) { api.fill(0,0,0,1,1,1,'stone'); }\n```";
        assertEquals("function build(api) { api.fill(0,0,0,1,1,1,'stone'); }", ResponseParser.extractCode(response));
    }
}
