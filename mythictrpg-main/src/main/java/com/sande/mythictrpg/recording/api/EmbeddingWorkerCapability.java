package com.sande.mythictrpg.recording.api;

/** Object-identity capability; constructing one grants no authority. */
public final class EmbeddingWorkerCapability {
    private EmbeddingWorkerCapability() { }
    public static EmbeddingWorkerCapability unregistered() { return new EmbeddingWorkerCapability(); }
    @Override public String toString() { return "EmbeddingWorkerCapability[opaque]"; }
}
