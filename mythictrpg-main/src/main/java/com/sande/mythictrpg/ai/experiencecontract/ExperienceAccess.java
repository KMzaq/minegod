package com.sande.mythictrpg.ai.experiencecontract;

import com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext;
import com.sande.mythictrpg.ai.server.AiConversationRuntimeService;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.gameplay.watch.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Sole production lease issuer: identities/audience come from the CURRENT game conversation, not AI IDs. */
public final class ExperienceAccess {
    private ExperienceAccess() {}
    public static CompletableFuture<ExperienceLease> request(ServerPlayer player, ConversationMemoryContext expected) {
        if (!player.server.isSameThread()) throw new IllegalStateException("game context required");
        var runtime = GodWatchRuntime.current(player.server);
        if (runtime == null || expected == null || expected.readOnly() || !sessionCurrent(player, expected))
            return CompletableFuture.completedFuture(ExperienceLease.unavailable("OFF_OR_NO_GAME_SCOPE"));
        var gateway = runtime.gateway(); var audience = audience(expected);
        var result = new CompletableFuture<ExperienceLease>();
        var relationship = relationship(player, expected);
        try {
            gateway.read(audience).whenComplete((snapshot, failure) -> player.server.execute(() -> {
                if (result.isDone()) return;
                try {
                if (failure != null || GodWatchRuntime.current(player.server) != runtime || !sessionCurrent(player, expected)
                        || !gateway.current(snapshot, audience)) { result.complete(ExperienceLease.unavailable("UNAVAILABLE_OR_STALE")); return; }
                var view = ExperienceProjection.project(snapshot.view(), expected.playerId(), relationship);
                result.complete(new ExperienceLease(view, included -> {
                    if (!sessionCurrent(player, expected) || GodWatchRuntime.current(player.server) != runtime
                            || !relationship.equals(relationship(player, expected))) return false;
                    var subset = snapshot.view().proofs().stream().filter(p -> included.contains(p.id())).toList();
                    return gateway.current(new AsyncGodWatch.ReadSnapshot(audience,
                            new WatchContract.View(true, "READY", subset), snapshot.eligibilityRefs()), audience);
                }, ExperienceRoomEvidence.capture(snapshot)));
                } catch (RuntimeException invalid) { result.complete(ExperienceLease.unavailable("WATCH_UNAVAILABLE")); }
            }));
        } catch (RuntimeException unavailable) { result.complete(ExperienceLease.unavailable("WATCH_UNAVAILABLE")); }
        // Optional experience lookup cannot indefinitely delay the existing dialogue path; late results are never installed.
        return result.completeOnTimeout(ExperienceLease.unavailable("QUERY_TIMEOUT"), 500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    private static boolean sessionCurrent(ServerPlayer player, ConversationMemoryContext c) {
        return player.server.getPlayerList().getPlayer(player.getUUID()) == player
                && AiConversationRuntimeService.INSTANCE.memoryContextCurrent(player, c);
    }
    private static WatchContract.Audience audience(ConversationMemoryContext c) {
        return new WatchContract.Audience(c.worldId(), new WatchContract.Key(c.godId(), c.playerId()), c.audience(),
                new WatchContract.Ref(c.interactionId() + "/" + c.generation(), 1));
    }
    private static ExperienceView.Relationship relationship(ServerPlayer player, ConversationMemoryContext c) {
        var data = PlayerMythDataService.get(player.server);
        if (!data.isReady()) return ExperienceView.Relationship.UNKNOWN;
        return data.find(c.playerId()).map(p -> new ExperienceView.Relationship(true,
                p.affinities().getOrDefault(ResourceLocation.parse(c.godId()), 0))).orElse(ExperienceView.Relationship.UNKNOWN);
    }
}
