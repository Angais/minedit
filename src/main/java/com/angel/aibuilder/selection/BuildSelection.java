package com.angel.aibuilder.selection;

import net.minecraft.core.BlockPos;

public record BuildSelection(BlockPos first, BlockPos second) {
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
}
