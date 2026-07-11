package com.angel.aibuilder.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BuildRotation {
    private BuildRotation() {
    }

    public static List<BuildOperation> rotate(List<BuildOperation> operations, int sourceWidth, int sourceDepth, int quarterTurns) {
        if (sourceWidth < 1 || sourceDepth < 1) {
            throw new IllegalArgumentException("Build dimensions must be positive.");
        }
        int turns = Math.floorMod(quarterTurns, 4);
        if (turns == 0) {
            return List.copyOf(operations);
        }

        List<BuildOperation> rotated = new ArrayList<>(operations.size());
        for (BuildOperation operation : operations) {
            if (operation instanceof SetBlockOperation set) {
                Point point = rotatePoint(set.x(), set.z(), sourceWidth, sourceDepth, turns);
                rotated.add(new SetBlockOperation(point.x(), set.y(), point.z(), rotate(set.block(), turns)));
            } else if (operation instanceof FillOperation fill) {
                Point first = rotatePoint(fill.x1(), fill.z1(), sourceWidth, sourceDepth, turns);
                Point second = rotatePoint(fill.x2(), fill.z2(), sourceWidth, sourceDepth, turns);
                BlockSpec block = rotate(fill.block(), turns);
                rotated.add(new FillOperation(
                        first.x(), fill.y1(), first.z(),
                        second.x(), fill.y2(), second.z(),
                        block,
                        new FillOptions(fill.options().mode(), block.states())
                ));
            }
        }
        return List.copyOf(rotated);
    }

    private static Point rotatePoint(int x, int z, int width, int depth, int turns) {
        return switch (turns) {
            case 1 -> new Point(depth - 1 - z, x);
            case 2 -> new Point(width - 1 - x, depth - 1 - z);
            case 3 -> new Point(z, width - 1 - x);
            default -> new Point(x, z);
        };
    }

    private static BlockSpec rotate(BlockSpec block, int turns) {
        Map<String, String> states = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : block.states().entrySet()) {
            String property = entry.getKey();
            states.put(rotateDirection(property, turns), rotateStateValue(property, entry.getValue(), turns));
        }
        return new BlockSpec(block.blockId(), Map.copyOf(states));
    }

    private static String rotateStateValue(String property, String value, int turns) {
        String normalizedProperty = property.toLowerCase(Locale.ROOT);
        String normalizedValue = value.toLowerCase(Locale.ROOT);
        if ("axis".equals(normalizedProperty) && turns % 2 == 1) {
            return switch (normalizedValue) {
                case "x" -> "z";
                case "z" -> "x";
                default -> value;
            };
        }
        if ("rotation".equals(normalizedProperty)) {
            try {
                return Integer.toString(Math.floorMod(Integer.parseInt(normalizedValue) + turns * 4, 16));
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        if ("shape".equals(normalizedProperty)) {
            String shape = normalizedValue;
            for (int i = 0; i < turns; i++) {
                shape = rotateShapeClockwise(shape);
            }
            if (isDirectionalShape(normalizedValue)) {
                return shape;
            }
        }

        String direction = rotateDirection(normalizedValue, turns);
        if (!direction.equals(normalizedValue)) {
            return direction;
        }

        String[] parts = normalizedValue.split("_", -1);
        boolean changed = false;
        for (int i = 0; i < parts.length; i++) {
            String rotated = rotateDirection(parts[i], turns);
            changed |= !rotated.equals(parts[i]);
            parts[i] = rotated;
        }
        return changed ? String.join("_", parts) : value;
    }

    private static String rotateShapeClockwise(String shape) {
        return switch (shape) {
            case "north_south" -> "east_west";
            case "east_west" -> "north_south";
            case "ascending_north" -> "ascending_east";
            case "ascending_east" -> "ascending_south";
            case "ascending_south" -> "ascending_west";
            case "ascending_west" -> "ascending_north";
            case "north_east" -> "south_east";
            case "south_east" -> "south_west";
            case "south_west" -> "north_west";
            case "north_west" -> "north_east";
            default -> shape;
        };
    }

    private static boolean isDirectionalShape(String shape) {
        return switch (shape) {
            case "north_south", "east_west",
                    "ascending_north", "ascending_east", "ascending_south", "ascending_west",
                    "north_east", "south_east", "south_west", "north_west" -> true;
            default -> false;
        };
    }

    private static String rotateDirection(String direction, int turns) {
        String rotated = direction;
        for (int i = 0; i < turns; i++) {
            rotated = switch (rotated) {
                case "north" -> "east";
                case "east" -> "south";
                case "south" -> "west";
                case "west" -> "north";
                default -> rotated;
            };
        }
        return rotated;
    }

    private record Point(int x, int z) {
    }
}
