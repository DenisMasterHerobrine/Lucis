package dev.denismasterherobrine.lucisrevisited.light.engine;

public final class LucisServices {
    private static volatile LucisEngineController controller = new LucisEngineController();

    private LucisServices() {
    }

    public static LucisEngineController controller() {
        return controller;
    }

    public static synchronized void resetController() {
        controller.shutdown();
        controller = new LucisEngineController();
    }
}
