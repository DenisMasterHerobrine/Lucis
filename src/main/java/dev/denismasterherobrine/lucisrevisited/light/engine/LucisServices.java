package dev.denismasterherobrine.lucisrevisited.light.engine;

public final class LucisServices {
    private static final LucisEngineController CONTROLLER = new LucisEngineController();

    private LucisServices() {
    }

    public static LucisEngineController controller() {
        return CONTROLLER;
    }
}
