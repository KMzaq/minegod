package com.sande.mythictrpg.interaction.context;

import com.sande.mythictrpg.condition.api.ConditionContext;
import com.sande.mythictrpg.condition.api.ConditionEnvironment;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.data.player.PlayerMythProfile;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.policy.CooldownView;
import com.sande.mythictrpg.interaction.policy.InteractionRuntimeView;
import com.sande.mythictrpg.interaction.rule.InteractionRuleManager;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record InteractionContext(
        InteractionSignal<?> signal,
        Set<UUID> nearbyPlayerIds,
        Optional<ConditionEnvironment> environment,
        ConditionContext conditionContext,
        Map<UUID, PlayerMythProfile> relevantPlayerProfiles,
        GodDefinitionManager.ProgressionSnapshot godSnapshot,
        InteractionRuleManager.RuleSnapshot ruleSnapshot,
        CooldownView cooldowns,
        InteractionRuntimeView runtime,
        boolean ready,
        Optional<ResourceLocation> unavailableReason
) {
    public InteractionContext {
        Objects.requireNonNull(signal, "signal");
        nearbyPlayerIds = Set.copyOf(Objects.requireNonNull(nearbyPlayerIds, "nearbyPlayerIds"));
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(conditionContext, "conditionContext");
        relevantPlayerProfiles = Map.copyOf(Objects.requireNonNull(
                relevantPlayerProfiles, "relevantPlayerProfiles"));
        Objects.requireNonNull(godSnapshot, "godSnapshot");
        Objects.requireNonNull(ruleSnapshot, "ruleSnapshot");
        Objects.requireNonNull(cooldowns, "cooldowns");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(unavailableReason, "unavailableReason");
        if (ready == unavailableReason.isPresent()) {
            throw new IllegalArgumentException("A ready context must not have an unavailable reason");
        }
    }

    public UUID initiatingPlayerId() {
        return signal.initiatingPlayerId();
    }

    public Optional<PlayerMythProfile> initiatingPlayerProfile() {
        return Optional.ofNullable(relevantPlayerProfiles.get(initiatingPlayerId()));
    }
}
