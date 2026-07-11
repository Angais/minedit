package com.angel.aibuilder.build;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class BuildRotationTest {
    @Test
    void rotatesGeometryAndDirectionalBlockStatesClockwise() {
        BlockSpec block = new BlockSpec("minecraft:oak_stairs", Map.of(
                "facing", "north",
                "axis", "x",
                "north", "side",
                "rotation", "15",
                "shape", "ascending_west"
        ));
        List<BuildOperation> operations = BuildRotation.rotate(
                List.of(new SetBlockOperation(1, 4, 2, block)),
                4,
                3,
                1
        );

        SetBlockOperation rotated = assertInstanceOf(SetBlockOperation.class, operations.getFirst());
        assertEquals(0, rotated.x());
        assertEquals(1, rotated.z());
        assertEquals(4, rotated.y());
        assertEquals("east", rotated.block().states().get("facing"));
        assertEquals("z", rotated.block().states().get("axis"));
        assertEquals("side", rotated.block().states().get("east"));
        assertEquals("3", rotated.block().states().get("rotation"));
        assertEquals("ascending_north", rotated.block().states().get("shape"));
    }

    @Test
    void rotatesFillCornersAndKeepsRailShapesCanonical() {
        BlockSpec rail = new BlockSpec("minecraft:rail", Map.of("shape", "north_east"));
        FillOperation fill = new FillOperation(0, 0, 0, 2, 1, 1, rail, FillOptions.replace(rail.states()));

        FillOperation rotated = assertInstanceOf(FillOperation.class,
                BuildRotation.rotate(List.of(fill), 3, 2, 2).getFirst());

        assertEquals(2, rotated.x1());
        assertEquals(1, rotated.z1());
        assertEquals(0, rotated.x2());
        assertEquals(0, rotated.z2());
        assertEquals("south_west", rotated.block().states().get("shape"));
    }

    @Test
    void fullTurnLeavesOperationsUnchanged() {
        SetBlockOperation operation = new SetBlockOperation(2, 3, 4, new BlockSpec("minecraft:stone", Map.of()));
        assertEquals(List.of(operation), BuildRotation.rotate(List.of(operation), 8, 8, 4));
    }
}
