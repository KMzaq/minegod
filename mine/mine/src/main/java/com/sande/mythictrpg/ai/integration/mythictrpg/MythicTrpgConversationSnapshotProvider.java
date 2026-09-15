package com.sande.mythictrpg.ai.integration.mythictrpg;

import com.sande.mythictrpg.ai.AiDialogueModels;
import com.sande.mythictrpg.ai.context.ConversationGameSnapshotProvider;
import com.sande.mythictrpg.ai.context.GameConversationSnapshot;
import com.sande.mythictrpg.ai.relationship.AdditionalRelationshipAxesProvider;
import com.sande.mythictrpg.ai.relationship.CurrentEmotion;
import com.sande.mythictrpg.ai.relationship.DefaultRelationshipContextInterpreter;
import com.sande.mythictrpg.ai.relationship.DefaultRelationshipStateResolver;
import com.sande.mythictrpg.ai.relationship.EmotionSnapshotProvider;
import com.sande.mythictrpg.ai.relationship.LegacyEmotionSnapshotProvider;
import com.sande.mythictrpg.ai.relationship.RelationshipAxes;
import com.sande.mythictrpg.ai.relationship.RelationshipDataService;
import com.sande.mythictrpg.ai.relationship.RelationshipExampleRetriever;
import com.sande.mythictrpg.ai.relationship.RelationshipMetrics;
import com.sande.mythictrpg.ai.proposal.QuestRewardContextProvider;
import com.sande.mythictrpg.ai.tone.SocialAuthorityContextProvider;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythQueryService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * MythicTRPG's outer adapter. It reads canonical player affinity and Minecraft observations, then gives the AI a
 * bounded value snapshot. Audience membership is taken from the supplied session and is never discovered here.
 */
public final class MythicTrpgConversationSnapshotProvider implements ConversationGameSnapshotProvider {
    private final MinecraftServer server;
    private final PlayerMythQueryService playerProfiles;
    private final AdditionalRelationshipAxesProvider additionalAxes;
    private final EmotionSnapshotProvider emotions;
    private final QuestRewardContextProvider questRewardContext;
    private final SocialAuthorityContextProvider socialAuthority;
    private final RelationshipExampleRetriever relationshipTags = new RelationshipExampleRetriever(
            new DefaultRelationshipStateResolver());
    private final DefaultRelationshipContextInterpreter relationshipInterpreter = new DefaultRelationshipContextInterpreter();

    public MythicTrpgConversationSnapshotProvider(MinecraftServer server) {
        this(server, PlayerMythDataService.get(server), AdditionalRelationshipAxesProvider.none(),
                new LegacyEmotionSnapshotProvider(RelationshipDataService.get(server)), QuestRewardContextProvider.none(),
                SocialAuthorityContextProvider.none());
    }

    /** Convenience integration constructor for a game-owned Quest/Reward context provider. */
    public MythicTrpgConversationSnapshotProvider(MinecraftServer server, QuestRewardContextProvider questRewardContext) {
        this(server, PlayerMythDataService.get(server), AdditionalRelationshipAxesProvider.none(),
                new LegacyEmotionSnapshotProvider(RelationshipDataService.get(server)), questRewardContext,
                SocialAuthorityContextProvider.none());
    }

    /** Convenience integration constructor for game-owned quest/reward and social-authority snapshots. */
    public MythicTrpgConversationSnapshotProvider(MinecraftServer server, QuestRewardContextProvider questRewardContext,
            SocialAuthorityContextProvider socialAuthority) {
        this(server, PlayerMythDataService.get(server), AdditionalRelationshipAxesProvider.none(),
                new LegacyEmotionSnapshotProvider(RelationshipDataService.get(server)), questRewardContext, socialAuthority);
    }

    public MythicTrpgConversationSnapshotProvider(MinecraftServer server, PlayerMythQueryService playerProfiles,
            AdditionalRelationshipAxesProvider additionalAxes, EmotionSnapshotProvider emotions) {
        this(server, playerProfiles, additionalAxes, emotions, QuestRewardContextProvider.none());
    }

    /**
     * The gameplay owner may supply Quest/Reward catalogues, balance constraints, and prior validator feedback here.
     * This adapter only relays immutable data and never queries a Quest/Reward implementation itself.
     */
    public MythicTrpgConversationSnapshotProvider(MinecraftServer server, PlayerMythQueryService playerProfiles,
            AdditionalRelationshipAxesProvider additionalAxes, EmotionSnapshotProvider emotions,
            QuestRewardContextProvider questRewardContext) {
        this(server, playerProfiles, additionalAxes, emotions, questRewardContext, SocialAuthorityContextProvider.none());
    }

    public MythicTrpgConversationSnapshotProvider(MinecraftServer server, PlayerMythQueryService playerProfiles,
            AdditionalRelationshipAxesProvider additionalAxes, EmotionSnapshotProvider emotions,
            QuestRewardContextProvider questRewardContext, SocialAuthorityContextProvider socialAuthority) {
        this.server = Objects.requireNonNull(server, "server");
        this.playerProfiles = Objects.requireNonNull(playerProfiles, "playerProfiles");
        this.additionalAxes = Objects.requireNonNull(additionalAxes, "additionalAxes");
        this.emotions = Objects.requireNonNull(emotions, "emotions");
        this.questRewardContext = Objects.requireNonNull(questRewardContext, "questRewardContext");
        this.socialAuthority = Objects.requireNonNull(socialAuthority, "socialAuthority");
    }

    @Override
    public GameConversationSnapshot capture(AiDialogueModels.SessionSnapshot session, String triggeringParticipantId) {
        Objects.requireNonNull(session, "session");
        if (triggeringParticipantId == null || triggeringParticipantId.isBlank()) {
            throw new IllegalArgumentException("triggeringParticipantId must not be blank");
        }
        return new GameConversationSnapshot(relationships(session), gameState(session, triggeringParticipantId),
                questRewardContext.capture(session, triggeringParticipantId),
                socialAuthority.capture(session, triggeringParticipantId));
    }

    private Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationships(
            AiDialogueModels.SessionSnapshot session) {
        Map<String, Map<String, AiDialogueModels.RelationshipContext>> byPlayer = new LinkedHashMap<>();
        for (AiDialogueModels.Participant player : session.participants()) {
            if (player.kind() != AiDialogueModels.ParticipantKind.PLAYER || player.playerId() == null
                    || player.state() == AiDialogueModels.ParticipantState.OUTSIDE) {
                continue;
            }
            Map<String, AiDialogueModels.RelationshipContext> byGod = new LinkedHashMap<>();
            for (AiDialogueModels.Participant divine : session.participants()) {
                if (divine.kind() != AiDialogueModels.ParticipantKind.DIVINE || divine.godId() == null) {
                    continue;
                }
                RelationshipMetrics metrics = relationship(player.playerId(), divine.godId().toString());
                CurrentEmotion emotion = emotions.find(player.playerId().toString(), divine.godId().toString())
                        .map(snapshot -> new CurrentEmotion(snapshot.intensities())).orElseGet(CurrentEmotion::calm);
                byGod.put(divine.godId().toString(), new AiDialogueModels.RelationshipContext(metrics, emotion,
                        relationshipTags.tags(metrics).stream().map(Enum::name).sorted().toList(),
                        relationshipInterpreter.describe(metrics)));
            }
            byPlayer.put(player.participantId(), Map.copyOf(byGod));
        }
        return Map.copyOf(byPlayer);
    }

    private RelationshipMetrics relationship(UUID playerId, String npcId) {
        int canonicalAffinity = playerProfiles.find(playerId).map(profile -> profile.affinities()
                .getOrDefault(net.minecraft.resources.ResourceLocation.parse(npcId), 0)).orElse(0);
        Map<String, Integer> extra = additionalAxes.axes(playerId.toString(), npcId);
        if (extra == null) {
            extra = Map.of();
        }
        return new RelationshipMetrics(normalize(canonicalAffinity, RelationshipMetrics.MIN_SIGNED,
                RelationshipMetrics.MAX_SIGNED), axis(extra, RelationshipAxes.TRUST, RelationshipMetrics.MIN_SIGNED,
                RelationshipMetrics.MAX_SIGNED), axis(extra, RelationshipAxes.RESPECT, RelationshipMetrics.MIN_SIGNED,
                RelationshipMetrics.MAX_SIGNED), axis(extra, RelationshipAxes.CAUTION, RelationshipMetrics.MIN_CAUTION,
                RelationshipMetrics.MAX_CAUTION));
    }

    private static int axis(Map<String, Integer> axes, String id, int min, int max) {
        Integer value = axes.get(id);
        return value == null ? 0 : normalize(value, min, max);
    }

    /**
     * The game affinity may use a wider scale (for example 1000 in a condition). This is a read-only prompt view,
     * not a mutation or secondary persistence format.
     */
    private static int normalize(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private Map<String, Object> gameState(AiDialogueModels.SessionSnapshot session, String triggeringParticipantId) {
        AiDialogueModels.Participant triggering = session.participants().stream()
                .filter(participant -> participant.participantId().equals(triggeringParticipantId)).findFirst().orElse(null);
        ServerPlayer player = triggering == null || triggering.playerId() == null
                ? null : server.getPlayerList().getPlayer(triggering.playerId());
        if (player == null) {
            return Map.of("available", false, "authority", "No current player snapshot is available.");
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("available", true);
        state.put("dimension", player.level().dimension().location().toString());
        state.put("x", Math.floor(player.getX()));
        state.put("y", Math.floor(player.getY()));
        state.put("z", Math.floor(player.getZ()));
        state.put("dayTime", player.level().getDayTime());
        state.put("raining", player.level().isRaining());
        state.put("thundering", player.level().isThundering());
        state.put("health", player.getHealth());
        state.put("maxHealth", player.getMaxHealth());
        state.put("mainHandItem", BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString());
        state.put("nearbyEntities", player.level().getEntities(player, player.getBoundingBox().inflate(8.0),
                entity -> entity != player).stream().limit(12).map(MythicTrpgConversationSnapshotProvider::entitySummary)
                .toList());
        state.put("authority", "Minecraft server state is authoritative. The LLM cannot mutate it.");
        return Map.copyOf(state);
    }

    private static String entitySummary(Entity entity) {
        return entity.getName().getString() + " [" + entity.getType() + "]";
    }
}
