package com.angel.aibuilder.build;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Map;
import java.util.Optional;

public final class BlockStateResolver {
    private BlockStateResolver() {
    }

    public static Optional<BlockState> resolve(BlockSpec spec) {
        Identifier id;
        try {
            id = Identifier.parse(spec.blockId());
        } catch (Exception e) {
            return Optional.empty();
        }
        if (!BuiltInRegistries.BLOCK.containsKey(id)) {
            return Optional.empty();
        }

        Block block = BuiltInRegistries.BLOCK.getValue(id);
        BlockState state = block.defaultBlockState();
        for (Map.Entry<String, String> entry : spec.states().entrySet()) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property == null) {
                continue;
            }
            state = setProperty(state, property, entry.getValue());
        }
        return Optional.of(state);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends Comparable<T>> BlockState setProperty(BlockState state, Property property, String value) {
        Optional<T> parsed = property.getValue(value);
        return parsed.map(t -> state.setValue(property, t)).orElse(state);
    }
}
