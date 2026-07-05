package com.angel.aibuilder.build;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockStateResolver {
    // Generated builds repeat the same few block specs thousands of times; caching skips
    // the identifier parse, registry lookup, and property parsing on every placement.
    // Clearing the whole cache at the cap is intentional: real builds use well under a
    // hundred distinct specs, so eviction order is not worth the bookkeeping of an LRU.
    private static final int MAX_CACHE_SIZE = 4096;
    private static final Map<BlockSpec, Optional<BlockState>> CACHE = new ConcurrentHashMap<>();

    private BlockStateResolver() {
    }

    public static Optional<BlockState> resolve(BlockSpec spec) {
        Optional<BlockState> cached = CACHE.get(spec);
        if (cached != null) {
            return cached;
        }
        Optional<BlockState> resolved = resolveUncached(spec);
        if (CACHE.size() >= MAX_CACHE_SIZE) {
            CACHE.clear();
        }
        CACHE.put(spec, resolved);
        return resolved;
    }

    private static Optional<BlockState> resolveUncached(BlockSpec spec) {
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
