package com.angel.aibuilder.build;

public record FillOperation(int x1, int y1, int z1, int x2, int y2, int z2, BlockSpec block, FillOptions options) implements BuildOperation {
}
