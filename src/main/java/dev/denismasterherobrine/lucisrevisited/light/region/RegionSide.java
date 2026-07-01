package dev.denismasterherobrine.lucisrevisited.light.region;

public enum RegionSide {
    WEST,
    EAST,
    NORTH,
    SOUTH;

    public RegionSide opposite() {
        return switch (this) {
            case WEST -> EAST;
            case EAST -> WEST;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
        };
    }
}
