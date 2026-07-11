package com.angel.aibuilder.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportedBuildSizeTest {
    @Test
    void readsDimensionsFromExistingExportPromptFormat() {
        String prompt = """
                You are controlling a Minecraft builder API.
                The selected footprint is width=37 blocks on X and depth=24 blocks on Z.
                Height is unconstrained.
                """;

        ExportedBuildSize size = ExportedBuildSize.parse(prompt).orElseThrow();
        assertEquals(37, size.width());
        assertEquals(24, size.depth());
    }

    @Test
    void rejectsUnrelatedOrOverflowingText() {
        assertTrue(ExportedBuildSize.parse("function build(api) {}").isEmpty());
        assertTrue(ExportedBuildSize.parse("The selected footprint is width=999999999999 blocks on X and depth=2 blocks on Z.").isEmpty());
    }

    @Test
    void readsFootprintMetadataFromImportCode() {
        String code = """
                // minedit-footprint: width=45 depth=54
                function build(api) {
                  api.fill(0, 0, 0, api.width - 1, 0, api.depth - 1, 'stone');
                }
                """;

        ExportedBuildSize size = ExportedBuildSize.parseImport(code).orElseThrow();
        assertEquals(45, size.width());
        assertEquals(54, size.depth());
        assertEquals("// minedit-footprint: width=45 depth=54", size.metadataLine());
    }

    @Test
    void readsHardCodedLegacyDimensionsButDoesNotGuessParameterizedOnes() {
        ExportedBuildSize hardCoded = ExportedBuildSize.parseImport("""
                function build(api) {
                  var width = 18;
                  var depth = 27;
                }
                """).orElseThrow();
        assertEquals(18, hardCoded.width());
        assertEquals(27, hardCoded.depth());

        assertTrue(ExportedBuildSize.parseImport("""
                function build(api) {
                  var w = api.getWidth();
                  var d = api.getDepth();
                }
                """).isEmpty());
    }
}
