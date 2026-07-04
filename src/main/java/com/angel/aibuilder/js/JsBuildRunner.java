package com.angel.aibuilder.js;

import com.angel.aibuilder.build.BlockSpec;
import com.angel.aibuilder.build.BuildPlan;
import com.angel.aibuilder.build.ExistingStructureScanner;
import com.angel.aibuilder.build.FillOperation;
import com.angel.aibuilder.build.FillOptions;
import com.angel.aibuilder.build.SetBlockOperation;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class JsBuildRunner {
    private static final int MAX_RUNTIME_SECONDS = 30;
    private static final long MAX_RUNTIME_NANOS = TimeUnit.SECONDS.toNanos(MAX_RUNTIME_SECONDS);
    private static final SandboxContextFactory FACTORY = new SandboxContextFactory();

    private JsBuildRunner() {
    }

    public static BuildPlan run(String code, int width, int depth) {
        return run(code, width, depth, Map.of());
    }

    public static BuildPlan run(String code, int width, int depth, Map<Integer, ExistingStructureScanner.Line> lines) {
        SandboxContext context = (SandboxContext) FACTORY.enterContext();
        try {
            context.deadlineNanos = System.nanoTime() + MAX_RUNTIME_NANOS;
            Scriptable scope = context.initSafeStandardObjects();
            BuilderApi api = new BuilderApi(width, depth, lines);
            Object wrappedApi = Context.javaToJS(api, scope);
            ScriptableObject.putProperty(scope, "api", wrappedApi);

            context.evaluateString(scope, code, "ai-builder.js", 1, null);
            Object build = ScriptableObject.getProperty(scope, "build");
            if (!(build instanceof org.mozilla.javascript.Function function)) {
                throw new IllegalArgumentException("AI code must define function build(api).");
            }
            function.call(context, scope, scope, new Object[]{wrappedApi});
            return api.plan;
        } catch (ScriptTimeoutError e) {
            throw new IllegalStateException("AI build code exceeded the " + MAX_RUNTIME_SECONDS + "s execution limit.");
        } finally {
            Context.exit();
        }
    }

    // AI-generated code is untrusted. It runs with safe standard objects only, no access to
    // Java classes outside this mod, a wall-clock deadline, and a BuildPlan operation cap.
    // Do not replace this with Context.enter()/initStandardObjects(): that exposes LiveConnect
    // and reopens the java.lang.Runtime escape.
    private static final class SandboxContextFactory extends ContextFactory {
        @Override
        protected Context makeContext() {
            SandboxContext context = new SandboxContext(this);
            // Instruction observation requires interpreted mode.
            context.setInterpretedMode(true);
            context.setInstructionObserverThreshold(10_000);
            context.setClassShutter(className -> className.startsWith("com.angel.aibuilder."));
            return context;
        }

        @Override
        protected void observeInstructionCount(Context context, int instructionCount) {
            if (context instanceof SandboxContext sandbox && System.nanoTime() >= sandbox.deadlineNanos) {
                // An Error so script-level try/catch cannot swallow the deadline.
                throw new ScriptTimeoutError();
            }
        }
    }

    private static final class SandboxContext extends Context {
        private long deadlineNanos = Long.MAX_VALUE;

        private SandboxContext(ContextFactory factory) {
            super(factory);
        }
    }

    private static final class ScriptTimeoutError extends Error {
    }

    public static final class BuilderApi {
        public final int width;
        public final int depth;
        private final BuildPlan plan = new BuildPlan();
        private final Map<Integer, ExistingStructureScanner.Line> lines;

        public BuilderApi(int width, int depth) {
            this(width, depth, Map.of());
        }

        public BuilderApi(int width, int depth, Map<Integer, ExistingStructureScanner.Line> lines) {
            this.width = width;
            this.depth = depth;
            this.lines = lines;
        }

        public int getWidth() {
            return width;
        }

        public int getDepth() {
            return depth;
        }

        public void set(double x, double y, double z, String blockId) {
            set(x, y, z, blockId, null);
        }

        public void set(double x, double y, double z, String blockId, Object states) {
            plan.add(new SetBlockOperation(floor(x), floor(y), floor(z), new BlockSpec(normalizeBlockId(blockId), stringMap(states))));
        }

        public void fill(double x1, double y1, double z1, double x2, double y2, double z2, String blockId) {
            fill(x1, y1, z1, x2, y2, z2, blockId, null);
        }

        public void fill(double x1, double y1, double z1, double x2, double y2, double z2, String blockId, Object options) {
            Map<String, String> optionMap = stringMap(options);
            String mode = optionMap.getOrDefault("mode", "replace");
            optionMap.remove("mode");
            Map<String, String> states = new HashMap<>();
            if (options instanceof NativeObject nativeObject) {
                Object statesObject = nativeObject.get("states", nativeObject);
                if (statesObject == Scriptable.NOT_FOUND) {
                    statesObject = nativeObject.get("blockStates", nativeObject);
                }
                if (statesObject != Scriptable.NOT_FOUND) {
                    states.putAll(stringMap(statesObject));
                }
            }
            if (states.isEmpty()) {
                states.putAll(optionMap);
            }

            plan.add(new FillOperation(
                    floor(x1), floor(y1), floor(z1),
                    floor(x2), floor(y2), floor(z2),
                    new BlockSpec(normalizeBlockId(blockId), states),
                    new FillOptions(mode, states)
            ));
        }

        public void replaceLine(double lineNumber, String blockId) {
            replaceLine(lineNumber, blockId, null);
        }

        public void replaceLine(double lineNumber, String blockId, Object states) {
            ExistingStructureScanner.Line line = line(lineNumber);
            if (line == null) {
                return;
            }

            BlockSpec block = new BlockSpec(normalizeBlockId(blockId), stringMap(states));
            if (line.singleBlock()) {
                plan.add(new SetBlockOperation(line.x1(), line.y1(), line.z1(), block));
            } else {
                plan.add(new FillOperation(line.x1(), line.y1(), line.z1(), line.x2(), line.y2(), line.z2(), block, FillOptions.replace(block.states())));
            }
        }

        public void clearLine(double lineNumber) {
            replaceLine(lineNumber, "air");
        }

        private ExistingStructureScanner.Line line(double lineNumber) {
            return lines.get((int) Math.floor(lineNumber));
        }

        private static int floor(double value) {
            return (int) Math.floor(value);
        }

        private static String normalizeBlockId(String blockId) {
            String trimmed = blockId.trim();
            return trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
        }

        private static Map<String, String> stringMap(Object object) {
            Map<String, String> map = new HashMap<>();
            if (!(object instanceof NativeObject nativeObject)) {
                return map;
            }
            for (Object id : nativeObject.getIds()) {
                String key = String.valueOf(id);
                Object value = nativeObject.get(key, nativeObject);
                if (value != null && value != Scriptable.NOT_FOUND && !(value instanceof NativeObject)) {
                    map.put(key, Context.toString(value));
                }
            }
            return map;
        }
    }
}
