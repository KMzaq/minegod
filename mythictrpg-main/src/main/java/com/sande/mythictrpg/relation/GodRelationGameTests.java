package com.sande.mythictrpg.relation;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.engine.ConditionContexts;
import com.sande.mythictrpg.condition.engine.ConditionEngine;
import com.sande.mythictrpg.condition.engine.ConditionTreeParser;
import com.sande.mythictrpg.condition.registry.ConditionTypeRegistry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GodRelationGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";

    private GodRelationGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void directionalStateSymmetryPersistenceAndConditions(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        ResourceLocation nyxLike = id("relation_source_" + suffix);
        ResourceLocation olympianLike = id("relation_target_" + suffix);
        ResourceLocation directionalId = id("relation_directional_" + suffix);
        ResourceLocation conflictId = id("relation_conflict_" + suffix);
        ResourceLocation warId = id("relation_war_" + suffix);
        Set<GodRelationKey> keys = Set.of(new GodRelationKey(nyxLike, olympianLike),
                new GodRelationKey(olympianLike, nyxLike));
        Set<ResourceLocation> transitionIds = Set.of(directionalId, conflictId, warId);
        DynamicGodRelationState state = DynamicGodRelationState.get(server);

        try {
            GodRelationTransition directional = new GodRelationTransition(directionalId, nyxLike,
                    false, 1, "Establish directional aftermath", List.of(
                    new GodRelationTransitionChange(nyxLike, olympianLike, -350,
                            Set.of(GodRelationTag.FEARFUL, GodRelationTag.RESENTFUL), Set.of()),
                    new GodRelationTransitionChange(olympianLike, nyxLike, 0,
                            Set.of(GodRelationTag.INDIFFERENT), Set.of())));
            helper.assertValueEqual(apply(state, directional, server).status(),
                    GodRelationTransitionResult.Status.APPLIED, "directional transition");
            helper.assertTrue(state.find(nyxLike, olympianLike).orElseThrow().tags()
                    .contains(GodRelationTag.FEARFUL), "source fear tag");
            helper.assertTrue(!state.find(olympianLike, nyxLike).orElseThrow().tags()
                    .contains(GodRelationTag.FEARFUL), "fear must not mirror");
            helper.assertTrue(state.find(olympianLike, nyxLike).orElseThrow().tags()
                    .contains(GodRelationTag.INDIFFERENT), "reverse indifference tag");

            GodRelationTransition conflicting = new GodRelationTransition(conflictId, nyxLike,
                    false, 1, "Reject incompatible state", List.of(
                    new GodRelationTransitionChange(nyxLike, olympianLike, 0,
                            Set.of(GodRelationTag.ALLIED, GodRelationTag.HOSTILE), Set.of())));
            helper.assertValueEqual(apply(state, conflicting, server).status(),
                    GodRelationTransitionResult.Status.REJECTED, "incompatible transition");
            helper.assertValueEqual(state.applicationCount(conflictId), 0, "rejected transition count");

            GodRelationTransition war = new GodRelationTransition(warId, nyxLike,
                    false, 1, "Escalate a shared war state", List.of(
                    new GodRelationTransitionChange(nyxLike, olympianLike, -100,
                            Set.of(GodRelationTag.AT_WAR), Set.of()),
                    new GodRelationTransitionChange(olympianLike, nyxLike, 0,
                            Set.of(), Set.of(GodRelationTag.INDIFFERENT))));
            helper.assertValueEqual(apply(state, war, server).status(),
                    GodRelationTransitionResult.Status.APPLIED, "war transition");
            helper.assertTrue(state.find(nyxLike, olympianLike).orElseThrow().tags()
                    .contains(GodRelationTag.AT_WAR), "forward war state");
            helper.assertTrue(state.find(olympianLike, nyxLike).orElseThrow().tags()
                    .contains(GodRelationTag.AT_WAR), "war state must mirror");
            long revision = state.find(nyxLike, olympianLike).orElseThrow().revision();
            helper.assertValueEqual(apply(state, war, server).status(),
                    GodRelationTransitionResult.Status.REJECTED, "duplicate one-shot transition");
            helper.assertValueEqual(state.find(nyxLike, olympianLike).orElseThrow().revision(), revision,
                    "duplicate transition must not mutate state");

            String conditionJson = "{\"type\":\"mythictrpg:god_relation\",\"scope\":\"world\","
                    + "\"source_god\":\"" + nyxLike + "\",\"target_god\":\"" + olympianLike + "\","
                    + "\"maximum_score\":-400,\"required_tags\":[\"fearful\",\"at_war\"]}";
            var condition = new ConditionTreeParser(ConditionTypeRegistry.INSTANCE)
                    .parse(JsonParser.parseString(conditionJson));
            helper.assertValueEqual(ConditionEngine.INSTANCE.evaluate(condition,
                            ConditionContexts.forServer(server, Optional.empty())),
                    ConditionResult.MATCH, "God relation condition");

            CompoundTag saved = state.save(new CompoundTag(), server.registryAccess());
            DynamicGodRelationState loaded = DynamicGodRelationState.load(saved, server.registryAccess());
            helper.assertTrue(loaded.isReady(), "saved relation state reload");
            helper.assertValueEqual(loaded.find(olympianLike, nyxLike).orElseThrow().tags(),
                    state.find(olympianLike, nyxLike).orElseThrow().tags(), "persisted reverse tags");
            helper.assertValueEqual(loaded.applicationCount(warId), 1, "persisted application count");
            helper.assertValueEqual(GodRelationQueryService.contextFor(server,
                    List.of(nyxLike, olympianLike)).size(), 2, "bounded participant relation context");
        } finally {
            state.removeForTesting(keys, transitionIds);
        }
        helper.succeed();
    }

    private static GodRelationTransitionResult apply(DynamicGodRelationState state,
            GodRelationTransition transition, MinecraftServer server) {
        return GodRelationService.resolveForTesting(state, transition, true,
                server.overworld().getGameTime(), ignored -> true);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
