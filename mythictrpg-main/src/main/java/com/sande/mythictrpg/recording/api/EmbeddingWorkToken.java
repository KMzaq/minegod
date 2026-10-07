package com.sande.mythictrpg.recording.api;

/** One leased native source, not an arbitrary write or archive capability. */
public final class EmbeddingWorkToken {
    private EmbeddingWorkToken() { }
    public static EmbeddingWorkToken unregistered() { return new EmbeddingWorkToken(); }
    @Override public String toString() { return "EmbeddingWorkToken[opaque]"; }
}
