package com.sande.mythictrpg.gameplay.watch;

import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import com.sande.mythictrpg.quest.reward.RewardClaimState;
import static com.sande.mythictrpg.gameplay.watch.WatchContract.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Combines explicit development trials and actual acquired quest rewards, with shared occlusion. */
final class RewardWatchRules implements GameWatchGateway.Rules {
    final WatchTrialRules trials = new WatchTrialRules();
    final RewardWatchSettings settings;
    RewardWatchRules(RewardWatchSettings settings) { this.settings = settings; }
    static Ref disclosure(UUID player) { return new Ref("watch:owner/" + player, 1); }
    public Optional<Approval> approve(MinecraftServer server, UUID world, Key key) {
        var rule = settings.rule(key.godId());
        if (rule.isPresent()) {
            var acquired = RewardClaimState.get(server).watchesFor(key.playerId()).stream()
                    .filter(w -> w.godId().toString().equals(key.godId())).findFirst();
            if (acquired.isPresent()) return Optional.of(new Approval(world, key,
                    new Ref("watch:claim/" + acquired.get().claimId(), 1), new Ref("watch:quest_reward", 1), rule.get().policy()));
        }
        return trials.approve(server, world, key);
    }
    public Scene captureScene(MinecraftServer server, ActionRecord.Draft event) {
        Scene trial = trials.captureScene(server, event);
        Set<String> powers = new HashSet<>(), domains = new HashSet<>(), blocked = new HashSet<>();
        if (!trial.occluded()) { powers.addAll(trial.powers()); domains.addAll(trial.domains()); }
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(event.dimensionId())));
        // No quest/hidden advancement projection merely because the ledger recorded it.
        boolean privateEvent = event.position() == null || event.subject().kind().equals("QUEST") || event.subject().kind().equals("ADVANCEMENT")
                || "UNKNOWN".equals(event.payload().get("locationStatus"));
        for (var rule : settings.rules()) {
            String god = rule.policy().godId();
            boolean summary=event.type()==ActionRecord.Type.OBSERVED_ACTIVITY_SUMMARY;
            boolean acceptedType=summary ? god.equals(event.payload().get("observer_god"))
                    && rule.eventTypes().stream().anyMatch(t->t.name().equals(event.payload().get("activity"))) : rule.eventTypes().contains(event.type());
            if (!settings.enabled() || !acceptedType || settings.blocked(god, event)
                    || level == null || event.position() == null || rule.requiresSky() && !level.canSeeSky(new BlockPos(event.position().x(), event.position().y(), event.position().z()))) {
                blocked.add(god); continue;
            }
            powers.add(rule.policy().power()); domains.add(rule.policy().domain());
        }
        for (String god : trials.godsFor(event.actorId())) if (settings.blocked(god, event)) blocked.add(god);
        Map<Field, Visibility> fields = new EnumMap<>(Field.class);
        for (Field f : Field.values()) fields.put(f, new Visibility(!privateEvent, Map.of(event.actorId(), disclosure(event.actorId()))));
        // Trial and reward projections use the same private owner disclosure; neither grants public knowledge.
        return new Scene(event.occurrenceId(), event.captureSession(), event.captureOrder(), new Ref("watch:captured_scene", 1),
                powers, domains, false, privateEvent, fields, blocked);
    }
}
