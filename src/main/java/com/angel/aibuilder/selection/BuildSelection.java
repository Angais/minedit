package com.angel.aibuilder.selection;

import net.minecraft.core.BlockPos;

public record BuildSelection(BlockPos first, BlockPos second) {
    public static BuildSelection centeredAt(BlockPos center, int width, int depth) {
        if (width < 1 || depth < 1) {
            throw new IllegalArgumentException("Selection dimensions must be positive.");
        }

        int minX = Math.subtractExact(center.getX(), (width - 1) / 2);
        int minZ = Math.subtractExact(center.getZ(), (depth - 1) / 2);
        int maxX = Math.addExact(minX, width - 1);
        int maxZ = Math.addExact(minZ, depth - 1);
        return new BuildSelection(
                new BlockPos(minX, center.getY(), minZ),
                new BlockPos(maxX, center.getY(), maxZ)
        );
    }

    public int minX() {
        return Math.min(first.getX(), second.getX());
    }

    public int maxX() {
        return Math.max(first.getX(), second.getX());
    }

    public int minZ() {
        return Math.min(first.getZ(), second.getZ());
    }

    public int maxZ() {
        return Math.max(first.getZ(), second.getZ());
    }

    public int baseY() {
        return Math.min(first.getY(), second.getY());
    }

    public int width() {
        return maxX() - minX() + 1;
    }

    public int depth() {
        return maxZ() - minZ() + 1;
    }

    public BuildSelection moved(int x, int y, int z) {
        return new BuildSelection(first.offset(x, y, z), second.offset(x, y, z));
    }

    public String coordinateArguments() {
        return minX() + " " + baseY() + " " + minZ() + " " + maxX() + " " + baseY() + " " + maxZ();
    }

    public String reuseCommand() {
        return "/selection set " + coordinateArguments();
    }

    public String shortSummary() {
        return width() + " x " + depth()
                + " at base Y " + baseY()
                + "; coords " + coordinateArguments();
    }
}
