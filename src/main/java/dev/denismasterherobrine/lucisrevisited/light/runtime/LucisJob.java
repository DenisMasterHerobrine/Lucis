package dev.denismasterherobrine.lucisrevisited.light.runtime;

public record LucisJob(LucisJobPriority priority, Runnable task) implements Comparable<LucisJob> {
    @Override
    public int compareTo(LucisJob other) {
        return Integer.compare(priority.ordinal(), other.priority.ordinal());
    }
}
