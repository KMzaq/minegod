package com.sande.mythictrpg.ai.api;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Optional response module registration; the game has no dependency on an LLM implementation. */
public final class RoomConversationEngineRouter {
    public static final RoomConversationEngineRouter INSTANCE = new RoomConversationEngineRouter();
    private RoomConversationEngine engine = new RoomConversationEngine() {
        @Override public CompletableFuture<Result> respond(Request request) { return CompletableFuture.completedFuture(Result.failed(request, "AI_UNAVAILABLE")); }
        @Override public CompletableFuture<SplitResult> chooseSplit(SplitRequest request) {
            return CompletableFuture.completedFuture(new SplitResult(request.roomId(), request.revision(), request.godId(), "", "", "AI_UNAVAILABLE"));
        }
    };
    private boolean installed;
    private RoomConversationEngineRouter() { }
    public synchronized RoomConversationEngine engine() { return engine; }
    public synchronized boolean available() { return installed; }
    public synchronized void install(RoomConversationEngine value) {
        Objects.requireNonNull(value);
        if (installed && engine != value) throw new IllegalStateException("Room AI engine already installed");
        engine = value; installed = true;
    }
}
