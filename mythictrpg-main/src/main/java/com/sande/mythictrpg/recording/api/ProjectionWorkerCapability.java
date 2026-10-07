package com.sande.mythictrpg.recording.api;

/** Identity-registered by the current game store; constructing a handle grants no authority. */
public final class ProjectionWorkerCapability {
    private ProjectionWorkerCapability() { }
    public static ProjectionWorkerCapability unregistered() { return new ProjectionWorkerCapability(); }
    @Override public String toString() { return "ProjectionWorkerCapability[opaque]"; }
}
