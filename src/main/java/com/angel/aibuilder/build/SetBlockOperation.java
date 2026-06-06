package com.angel.aibuilder.build;

public record SetBlockOperation(int x, int y, int z, BlockSpec block) implements BuildOperation {
}
