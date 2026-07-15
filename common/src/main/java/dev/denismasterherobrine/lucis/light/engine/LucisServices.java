package dev.denismasterherobrine.lucis.light.engine;

public final class LucisServices {
    private static volatile LucisEngineController controller;

    private LucisServices() {
    }

    public static LucisEngineController controller() {
        LucisEngineController current = controller;
        if (current == null) {
            synchronized (LucisServices.class) {
                current = controller;
                if (current == null) {
                    current = new LucisEngineController();
                    controller = current;
                }
            }
        }
        return current;
    }

    public static synchronized void resetController() {
        shutdownController();
        controller = new LucisEngineController();
    }

    public static synchronized void shutdownController() {
        LucisEngineController current = controller;
        if (current != null) {
            current.shutdown();
            controller = null;
        }
    }
}
