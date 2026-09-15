package com.sande.mythictrpg.interaction.context;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.condition.api.ConditionContext;
import com.sande.mythictrpg.condition.engine.ConditionContexts;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythProfile;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.interaction.policy.CooldownView;
import com.sande.mythictrpg.interaction.policy.InteractionRuntimeView;
import com.sande.mythictrpg.interaction.rule.InteractionRuleManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class InteractionContextFactory {
    private static final ResourceLocation GOD_DATA_UNAVAILABLE = id("god_data_unavailable");
    private static final ResourceLocation RULE_DATA_UNAVAILABLE = id("interaction_rule_data_unavailable");
    private static final ResourceLocation WORLD_DATA_UNAVAILABLE = id("world_data_unavailable");
    private static final ResourceLocation PLAYER_DATA_UNAVAILABLE = id("player_data_unavailable");
    private static final ResourceLocation INITIATOR_UNAVAILABLE = id("initiator_unavailable");

    private InteractionContextFactory() {
    }

    public static InteractionContext create(MinecraftServer server, InteractionSignal<?> signal,
            Set<UUID> nearbyPlayerIds, CooldownView cooldowns, InteractionRuntimeView runtime) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Interaction contexts may only be created on the server thread");
        }
        GodDefinitionManager.ProgressionSnapshot gods = GodDefinitionManager.INSTANCE.progressionSnapshot();
        InteractionRuleManager.RuleSnapshot rules = InteractionRuleManager.INSTANCE.snapshot();
        ConditionContext conditions = ConditionContexts.forServer(
                server, Optional.of(signal.initiatingPlayerId()), gods.definitions(), gods.generation() > 0);

        Set<UUID> relevantIds = new LinkedHashSet<>();
        relevantIds.add(signal.initiatingPlayerId());
        relevantIds.addAll(signal.involvedPlayerIds());
        relevantIds.addAll(nearbyPlayerIds);
        Map<UUID, PlayerMythProfile> profiles = new LinkedHashMap<>();
        relevantIds.forEach(playerId -> conditions.players().find(playerId)
                .ifPresent(profile -> profiles.put(playerId, profile)));

        Optional<ResourceLocation> unavailable = unavailableReason(
                gods, rules, conditions, profiles.containsKey(signal.initiatingPlayerId()), signal.mode());
        return new InteractionContext(signal, nearbyPlayerIds, conditions.environment(), conditions,
                profiles, gods, rules, cooldowns, runtime, unavailable.isEmpty(), unavailable);
    }

    private static Optional<ResourceLocation> unavailableReason(
            GodDefinitionManager.ProgressionSnapshot gods,
            InteractionRuleManager.RuleSnapshot rules,
            ConditionContext conditions,
            boolean initiatorPresent,
            InteractionMode mode) {
        if (gods.generation() <= 0 || !conditions.gods().isReady()) {
            return Optional.of(GOD_DATA_UNAVAILABLE);
        }
        if (mode == InteractionMode.SPONTANEOUS && rules.generation() <= 0) {
            return Optional.of(RULE_DATA_UNAVAILABLE);
        }
        if (!conditions.world().isReady()) {
            return Optional.of(WORLD_DATA_UNAVAILABLE);
        }
        if (!conditions.players().isReady()) {
            return Optional.of(PLAYER_DATA_UNAVAILABLE);
        }
        if (!initiatorPresent || conditions.environment().isEmpty()) {
            return Optional.of(INITIATOR_UNAVAILABLE);
        }
        return Optional.empty();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
