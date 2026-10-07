package com.sande.mythictrpg.ai.relationship;

import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythQueryService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.UUID;

/**
 * Compatibility access for AI-owned current emotion. Long-term affinity is deliberately read from the existing
 * {@code PlayerMythProfile}; this class is not an authoritative relationship store.
 */
public final class RelationshipDataService implements RelationshipProvider {
    private final MinecraftServer server;
    private final RelationshipRepository repository;
    private final PlayerMythQueryService playerProfiles;

    private RelationshipDataService(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
        this.repository = RelationshipRepository.get(server);
        this.playerProfiles = PlayerMythDataService.get(server);
    }

    public static RelationshipDataService get(MinecraftServer server) {
        return new RelationshipDataService(server);
    }

    @Override
    public RelationshipMetrics relationship(UUID playerId, ResourceLocation godId) {
        int canonicalAffinity = playerProfiles.find(playerId).map(profile -> profile.affinities()
                .getOrDefault(godId, 0)).orElse(0);
        return new RelationshipMetrics(normalize(canonicalAffinity, RelationshipMetrics.MIN_SIGNED,
                RelationshipMetrics.MAX_SIGNED), 0, 0, 0);
    }

    @Override
    public CurrentEmotion emotion(UUID playerId, ResourceLocation godId) {
        return repository.emotion(playerId, godId);
    }

    /**
     * Kept only as a source-compatible failure for old integrations. The gameplay owner must update its own
     * relationship data after validating an AI proposal; the AI no longer persists an affinity copy.
     */
    @Deprecated(forRemoval = false)
    public void setRelationship(UUID playerId, ResourceLocation godId, RelationshipMetrics metrics) {
        throw new UnsupportedOperationException("AI does not own relationship persistence; apply an approved "
                + "RelationshipChangeProposal through the RPG system");
    }

    public void setEmotion(UUID playerId, ResourceLocation godId, CurrentEmotion emotion) {
        requireServerThread();
        repository.putEmotion(new RelationshipKey(playerId, godId), Objects.requireNonNull(emotion, "emotion"));
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("AI relationship data may only be changed on the server thread");
        }
        if (!repository.isReady()) {
            throw new IllegalStateException("AI relationship repository is not writable: " + repository.rejectionReason());
        }
    }

    private static int normalize(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
