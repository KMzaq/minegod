package com.sande.mythictrpg.data.player;

import com.sande.mythictrpg.data.god.GodDefinitionManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Objects;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** The sole mutation entry point for persistent encounter and identification knowledge. */
public final class PlayerGodKnowledgeService {
    public static final int MAX_ENCOUNTER_BATCH = 3;
    private final MinecraftServer server;
    private final PlayerMythDataService players;

    private PlayerGodKnowledgeService(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
        this.players = PlayerMythDataService.get(server);
    }

    public static PlayerGodKnowledgeService get(MinecraftServer server) {
        return new PlayerGodKnowledgeService(server);
    }

    public GodKnowledgeSnapshot snapshot(UUID playerId, ResourceLocation godId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(godId, "godId");
        return players.find(playerId)
                .map(profile -> new GodKnowledgeSnapshot(
                        profile.encounteredGods().contains(godId),
                        profile.identifiedGods().contains(godId)))
                .orElseGet(() -> new GodKnowledgeSnapshot(false, false));
    }

    public KnowledgeMutationResult recordEncounter(ServerPlayer player, ResourceLocation godId) {
        return recordEncounter(player.getUUID(), godId);
    }

    public KnowledgeMutationResult recordEncounter(UUID playerId, ResourceLocation godId) {
        EncounterBatchResult result = recordEncounters(playerId, Set.of(godId));
        return switch (result.status()) {
            case NEW_RECORDS -> KnowledgeMutationResult.NEW_RECORD;
            case ALREADY_RECORDED -> KnowledgeMutationResult.ALREADY_RECORDED;
            case UNKNOWN_GOD -> KnowledgeMutationResult.UNKNOWN_GOD;
            case INVALID_REQUEST, PLAYER_UNAVAILABLE -> throw new IllegalStateException(
                    "Unable to record encounter: " + result.status());
        };
    }

    public EncounterBatchResult recordEncounters(UUID playerId, Collection<ResourceLocation> godIds) {
        requireServerThread();
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(godIds, "godIds");
        LinkedHashSet<ResourceLocation> normalized = new LinkedHashSet<>();
        for (ResourceLocation godId : godIds) {
            if (godId == null) {
                return new EncounterBatchResult(EncounterBatchStatus.INVALID_REQUEST, Set.of(), 0);
            }
            normalized.add(godId);
        }
        Set<ResourceLocation> batch = Set.copyOf(normalized);
        if (batch.isEmpty() || batch.size() > MAX_ENCOUNTER_BATCH) {
            return new EncounterBatchResult(EncounterBatchStatus.INVALID_REQUEST, batch, 0);
        }
        if (batch.stream().anyMatch(godId -> GodDefinitionManager.INSTANCE.find(godId).isEmpty())) {
            return new EncounterBatchResult(EncounterBatchStatus.UNKNOWN_GOD, batch, 0);
        }
        var existing = players.find(playerId);
        if (existing.isEmpty()) {
            return new EncounterBatchResult(EncounterBatchStatus.PLAYER_UNAVAILABLE, batch, 0);
        }
        int additions = (int) batch.stream()
                .filter(godId -> !existing.orElseThrow().encounteredGods().contains(godId))
                .count();
        if (additions == 0) {
            return new EncounterBatchResult(EncounterBatchStatus.ALREADY_RECORDED, batch, 0);
        }
        if (!players.recordEncounteredGods(playerId, batch)) {
            return new EncounterBatchResult(EncounterBatchStatus.PLAYER_UNAVAILABLE, batch, 0);
        }
        return new EncounterBatchResult(EncounterBatchStatus.NEW_RECORDS, batch, additions);
    }

    public KnowledgeMutationResult identifyGod(ServerPlayer player, ResourceLocation godId) {
        return identifyGod(player.getUUID(), godId);
    }

    public KnowledgeMutationResult identifyGod(UUID playerId, ResourceLocation godId) {
        requireServerThread();
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(godId, "godId");
        if (GodDefinitionManager.INSTANCE.find(godId).isEmpty()) {
            return KnowledgeMutationResult.UNKNOWN_GOD;
        }
        if (!players.recordIdentifiedGod(playerId, godId)) {
            return KnowledgeMutationResult.ALREADY_RECORDED;
        }
        NeoForge.EVENT_BUS.post(new GodIdentifiedEvent(server, playerId, godId));
        return KnowledgeMutationResult.NEW_RECORD;
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Player God knowledge may only be changed on the server thread");
        }
    }
}
