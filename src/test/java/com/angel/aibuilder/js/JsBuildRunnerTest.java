package com.angel.aibuilder.js;

import com.angel.aibuilder.build.BuildOperation;
import com.angel.aibuilder.build.BuildPlan;
import com.angel.aibuilder.build.FillOperation;
import com.angel.aibuilder.build.SetBlockOperation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsBuildRunnerTest {
    private static final long TEST_DEADLINE_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    @Test
    void buildsPlanFromSetAndFill() {
        String code = """
                function build(api) {
                  api.set(1.9, 2, 3, 'stone');
                  api.fill(0, 0, 0, 2, 2, 2, 'minecraft:oak_planks', {mode: 'hollow'});
                }
                """;
        BuildPlan plan = JsBuildRunner.run(code, 16, 16);
        List<BuildOperation> operations = plan.operations();
        assertEquals(2, operations.size());

        SetBlockOperation set = assertInstanceOf(SetBlockOperation.class, operations.get(0));
        assertEquals(1, set.x());
        assertEquals("minecraft:stone", set.block().blockId());

        FillOperation fill = assertInstanceOf(FillOperation.class, operations.get(1));
        assertEquals("minecraft:oak_planks", fill.block().blockId());
        assertEquals("hollow", fill.options().mode());
    }

    @Test
    void exposesSelectionDimensionsToScript() {
        String code = """
                function build(api) {
                  api.set(api.width - 1, 0, api.depth - 1, 'stone');
                }
                """;
        BuildPlan plan = JsBuildRunner.run(code, 7, 9);
        SetBlockOperation set = assertInstanceOf(SetBlockOperation.class, plan.operations().get(0));
        assertEquals(6, set.x());
        assertEquals(8, set.z());
    }

    @Test
    void rejectsCodeWithoutBuildFunction() {
        assertThrows(IllegalArgumentException.class, () -> JsBuildRunner.run("var x = 1;", 16, 16));
    }

    @Test
    void sandboxDoesNotExposeJavaPackageRoots() {
        // Throws from inside the script if any LiveConnect entry point is reachable.
        String code = """
                function build(api) {
                  var roots = ['java', 'javax', 'Packages', 'org', 'com', 'net'];
                  for (var i = 0; i < roots.length; i++) {
                    if (typeof this[roots[i]] !== 'undefined') {
                      throw 'escape via ' + roots[i];
                    }
                  }
                  if (typeof getClass !== 'undefined') {
                    throw 'escape via getClass()';
                  }
                }
                """;
        assertDoesNotThrow(() -> JsBuildRunner.run(code, 16, 16));
    }

    @Test
    void sandboxBlocksRuntimeAccess() {
        String code = """
                function build(api) {
                  java.lang.Runtime.getRuntime().exec('echo escaped');
                }
                """;
        assertThrows(Exception.class, () -> JsBuildRunner.run(code, 16, 16));
    }

    @Test
    void sandboxBlocksReflectionEscapeThroughApiObject() {
        // The ClassShutter only exposes com.angel.aibuilder.*, so java.lang.Class members
        // must not be reachable from the wrapped api object.
        String code = """
                function build(api) {
                  var runtime = api.getClass().forName('java.lang.Runtime');
                  runtime.getMethod('getRuntime').invoke(null).exec('echo escaped');
                }
                """;
        assertThrows(Exception.class, () -> JsBuildRunner.run(code, 16, 16));
    }

    @Test
    void deadlineStopsRunawayScript() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> JsBuildRunner.run("function build(api) { while (true) {} }", 16, 16, Map.of(), TEST_DEADLINE_NANOS));
        assertTrue(error.getMessage().contains("execution limit"));
    }

    @Test
    void scriptCannotCatchTheDeadline() {
        String code = """
                function build(api) {
                  try {
                    while (true) {}
                  } catch (e) {
                    while (true) {}
                  }
                }
                """;
        assertThrows(IllegalStateException.class,
                () -> JsBuildRunner.run(code, 16, 16, Map.of(), TEST_DEADLINE_NANOS));
    }
}
