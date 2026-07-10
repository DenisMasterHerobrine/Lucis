package dev.denismasterherobrine.lucis.light.runtime;

import java.util.concurrent.CompletableFuture;

public interface LucisLightPublisher {
    CompletableFuture<Void> lucis$publish(LucisRelightResult result);

    void lucis$onRuntimeChange();

    void lucis$requestRuntimeDrain();
}
