package com.angel.aibuilder.build;

import com.angel.aibuilder.selection.BuildSelection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExistingStructureScanner {
    private static final int MAX_HEIGHT_TO_SCAN = 160;

    private ExistingStructureScanner() {
    }

    public static String scan(ServerLevel level, BuildSelection selection) {
        return compile(level, selection).fullContext();
    }

    public static BuildCode compile(ServerLevel level, BuildSelection selection) {
        Scan scan = read(level, selection);
        if (scan.nonAirBlocks == 0) {
            return new BuildCode("No non-air blocks found in the selected footprint above y=0.\n", "No numbered current lines; the selected footprint is empty.\n", List.of());
        }

        List<Cuboid> cuboids = compileCuboids(scan);
        List<Line> lines = new ArrayList<>();
        StringBuilder builder = new StringBuilder();
        StringBuilder quickBuilder = new StringBuilder();
        builder.append("Current structure as compact builder code. It includes every non-air block found, including terrain.\n");
        String summary = new StringBuilder("Selected footprint: width=").append(selection.width())
                .append(", depth=").append(selection.depth())
                .append(", scanned_height=").append(scan.height)
                .append(", non_air_blocks=").append(scan.nonAirBlocks)
                .append(", generated_api_calls=").append(cuboids.size())
                .append(".\n").toString();
        builder.append(summary);
        builder.append("This current(api) function recreates the current selected area relative to y=0:\n");
        builder.append("function current(api) {\n");
        quickBuilder.append("Current structure as numbered compact API lines. It includes every non-air block found, including terrain.\n");
        quickBuilder.append(summary);
        quickBuilder.append("Use line numbers with api.replaceLine(n, blockId, states?) or api.clearLine(n) for small edits.\n");
        for (int i = 0; i < cuboids.size(); i++) {
            int lineNumber = i + 1;
            Cuboid cuboid = cuboids.get(i);
            Line line = cuboid.toLine(lineNumber);
            lines.add(line);
            builder.append("  ").append(cuboid.toApiCall()).append('\n');
            quickBuilder.append("L").append(lineNumber).append(": ").append(cuboid.toApiCall()).append('\n');
        }
        builder.append("}\n");
        return new BuildCode(builder.toString(), quickBuilder.toString(), lines);
    }

    private static Scan read(ServerLevel level, BuildSelection selection) {
        int startY = selection.baseY();
        int endY = Math.min(level.getMaxY(), startY + MAX_HEIGHT_TO_SCAN);
        int maxRelativeY = -1;
        int nonAirBlocks = 0;
        String[][][] grid = new String[endY - startY + 1][selection.depth()][selection.width()];
        Map<String, StateSpec> states = new LinkedHashMap<>();

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = startY; y <= endY; y++) {
            int relativeY = y - startY;
            for (int z = 0; z < selection.depth(); z++) {
                for (int x = 0; x < selection.width(); x++) {
                    pos.set(selection.minX() + x, y, selection.minZ() + z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }

                    StateSpec spec = StateSpec.from(state);
                    grid[relativeY][z][x] = spec.key();
                    states.putIfAbsent(spec.key(), spec);
                    maxRelativeY = Math.max(maxRelativeY, relativeY);
                    nonAirBlocks++;
                }
            }
        }

        int height = Math.max(0, maxRelativeY + 1);
        return new Scan(grid, states, selection.width(), selection.depth(), height, nonAirBlocks);
    }

    private static List<Cuboid> compileCuboids(Scan scan) {
        List<Cuboid> cuboids = new ArrayList<>();
        boolean[][][] used = new boolean[scan.height][scan.depth][scan.width];

        for (int y = 0; y < scan.height; y++) {
            for (int z = 0; z < scan.depth; z++) {
                for (int x = 0; x < scan.width; x++) {
                    String key = scan.grid[y][z][x];
                    if (key == null || used[y][z][x]) {
                        continue;
                    }

                    Cuboid cuboid = grow(scan, used, key, x, y, z);
                    markUsed(used, cuboid);
                    cuboids.add(cuboid);
                }
            }
        }

        return cuboids;
    }

    private static Cuboid grow(Scan scan, boolean[][][] used, String key, int startX, int startY, int startZ) {
        int maxX = startX;
        while (maxX + 1 < scan.width && matches(scan, used, key, maxX + 1, startY, startZ)) {
            maxX++;
        }

        int maxZ = startZ;
        while (maxZ + 1 < scan.depth && rectangleMatches(scan, used, key, startX, maxX, startY, startY, maxZ + 1, maxZ + 1)) {
            maxZ++;
        }

        int maxY = startY;
        while (maxY + 1 < scan.height && rectangleMatches(scan, used, key, startX, maxX, maxY + 1, maxY + 1, startZ, maxZ)) {
            maxY++;
        }

        return new Cuboid(startX, startY, startZ, maxX, maxY, maxZ, scan.states.get(key));
    }

    private static boolean rectangleMatches(Scan scan, boolean[][][] used, String key, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (!matches(scan, used, key, x, y, z)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean matches(Scan scan, boolean[][][] used, String key, int x, int y, int z) {
        return !used[y][z][x] && key.equals(scan.grid[y][z][x]);
    }

    private static void markUsed(boolean[][][] used, Cuboid cuboid) {
        for (int y = cuboid.y1; y <= cuboid.y2; y++) {
            for (int z = cuboid.z1; z <= cuboid.z2; z++) {
                for (int x = cuboid.x1; x <= cuboid.x2; x++) {
                    used[y][z][x] = true;
                }
            }
        }
    }

    private record Scan(String[][][] grid, Map<String, StateSpec> states, int width, int depth, int height, int nonAirBlocks) {
    }

    private record Cuboid(int x1, int y1, int z1, int x2, int y2, int z2, StateSpec state) {
        private Line toLine(int number) {
            return new Line(number, x1, y1, z1, x2, y2, z2);
        }

        private String toApiCall() {
            if (x1 == x2 && y1 == y2 && z1 == z2) {
                return "api.set(" + x1 + "," + y1 + "," + z1 + "," + state.arguments() + ");";
            }
            return "api.fill(" + x1 + "," + y1 + "," + z1 + "," + x2 + "," + y2 + "," + z2 + "," + state.arguments() + ");";
        }
    }

    public record BuildCode(String fullContext, String quickContext, List<Line> lines) {
        public Map<Integer, Line> lineMap() {
            Map<Integer, Line> map = new LinkedHashMap<>();
            for (Line line : lines) {
                map.put(line.number(), line);
            }
            return map;
        }
    }

    public record Line(int number, int x1, int y1, int z1, int x2, int y2, int z2) {
        public boolean singleBlock() {
            return x1 == x2 && y1 == y2 && z1 == z2;
        }
    }

    private record StateSpec(String blockId, Map<String, String> states) {
        private static StateSpec from(BlockState state) {
            Block block = state.getBlock();
            String blockId = compactBlockId(BuiltInRegistries.BLOCK.getKey(block).toString());
            BlockState defaultState = block.defaultBlockState();
            Map<String, String> states = new LinkedHashMap<>();
            for (Property<?> property : state.getProperties()) {
                if (!sameValue(state, defaultState, property)) {
                    states.put(property.getName(), valueName(state, property));
                }
            }
            return new StateSpec(blockId, states);
        }

        private String key() {
            return blockId + states;
        }

        private String arguments() {
            if (states.isEmpty()) {
                return quote(blockId);
            }
            return quote(blockId) + "," + statesObject();
        }

        private String statesObject() {
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> entry : states.entrySet()) {
                if (!first) {
                    builder.append(",");
                }
                first = false;
                builder.append(entry.getKey()).append(":").append(quote(entry.getValue()));
            }
            builder.append("}");
            return builder.toString();
        }
    }

    private static String compactBlockId(String blockId) {
        return blockId.startsWith("minecraft:") ? blockId.substring("minecraft:".length()) : blockId;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean sameValue(BlockState state, BlockState defaultState, Property property) {
        return state.getValue(property).equals(defaultState.getValue(property));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends Comparable<T>> String valueName(BlockState state, Property property) {
        return property.getName(state.getValue(property));
    }
}
