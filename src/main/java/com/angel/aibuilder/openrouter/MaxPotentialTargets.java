package com.angel.aibuilder.openrouter;

record MaxPotentialTargets(int width, int depth, int xSpan, int zSpan, int height, long blockBudget) {
    private static final String RULES = """
            Max Potential construction contract:
            - First classify the requested subject internally. Apply building/interior requirements only to enterable architecture. For statues, monuments, vehicles, terrain, pixel art, abstract forms, and decorative objects, prioritize a strong three-dimensional silhouette, proportion, surface relief, support, material transitions, lighting, and recognizable features instead.
            - Treat the scale, density, distribution, and quality targets below as requirements, not upper limits and not optional suggestions.
            - Coherent composition span target: use at least %d of %d blocks on X and at least %d of %d blocks on Z with substantial connected construction or deliberately integrated surroundings.
            - Vertical scale target: reach at least %d blocks above the selected ground level when compatible with the requested subject. Prefer meaningful roofs, towers, canopy, terrain variation, silhouette, or layered features over a thin decorative spike.
            - Purposeful construction budget: aim for at least %d non-air blocks beyond a trivial flat ground plane. This is a grounded construction target, not a placement cap. Blocks should contribute to visible form, structure, usable space, surface depth, furnishing, lighting, or coherent landscaping.
            - Distribute meaningful construction and detail across the front, rear, both sides, lower portions, and upper portions. Do not polish only one facade or one floor.
            - Maintain quality at the target scale. Repetition may establish rhythm, but vary major sections and add secondary forms, transitions, trim, supports, openings, props, or landscaping appropriate to the subject.
            - Do not use isolated distant blocks, thin tendrils, disconnected markers, or an oversized empty platform to fake footprint or bounding-box utilization.
            - Do not satisfy the construction budget with sealed invisible filler, unnecessarily solid interiors, repeated buried layers, inaccessible block masses, or blocks hidden beneath a ground plane.
            - Do not distort an intentionally narrow or compact subject merely to hit both span targets. Instead, use a connected plinth, base, terrain, path, courtyard, supporting forms, or other compositionally justified surroundings.
            - Before returning code, internally check the build against every target. If a target is missed, revise the design rather than merely claiming it is satisfied.
            """;

    static MaxPotentialTargets forFootprint(int width, int depth) {
        if (width < 1 || depth < 1) {
            throw new IllegalArgumentException("Footprint dimensions must be positive.");
        }
        long area = Math.multiplyExact((long) width, depth);
        // Leave composition margins while still requiring the model to engage with most
        // of the selected area. Four purposeful blocks per footprint column gives the
        // model a grounded density target without prescribing a solid cuboid.
        int xSpan = requiredSpan(width);
        int zSpan = requiredSpan(depth);
        int height = Math.max(4, Math.min(24, (int) Math.ceil(Math.sqrt(area) * 0.65)));
        long blockBudget = Math.max(16L, Math.multiplyExact(area, 4L));
        return new MaxPotentialTargets(width, depth, xSpan, zSpan, height, blockBudget);
    }

    String promptRules() {
        return RULES.formatted(xSpan, width, zSpan, depth, height, blockBudget);
    }

    private static int requiredSpan(int dimension) {
        return Math.max(1, (int) Math.ceil(dimension * 0.70));
    }
}
