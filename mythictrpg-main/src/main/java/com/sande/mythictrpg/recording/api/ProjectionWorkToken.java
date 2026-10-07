package com.sande.mythictrpg.recording.api;

/** Opaque runtime-bound work lease, never a model alias or a reusable authorization from a file. */
public final class ProjectionWorkToken {
    private ProjectionWorkToken() { }
    public static ProjectionWorkToken unregistered() { return new ProjectionWorkToken(); }
    @Override public String toString() { return "ProjectionWorkToken[opaque]"; }
}
