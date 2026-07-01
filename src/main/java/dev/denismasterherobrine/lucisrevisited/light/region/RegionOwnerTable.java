package dev.denismasterherobrine.lucisrevisited.light.region;

import java.util.concurrent.ConcurrentHashMap;

public final class RegionOwnerTable {
    private final ConcurrentHashMap<Long, Long> owners = new ConcurrentHashMap<>();

    public boolean tryAcquire(long regionKey, long ownerId) {
        return owners.putIfAbsent(regionKey, ownerId) == null;
    }

    public void release(long regionKey, long ownerId) {
        owners.remove(regionKey, ownerId);
    }

    public void clear() {
        owners.clear();
    }
}
