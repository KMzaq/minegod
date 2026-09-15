package com.sande.mythictrpg.quest.structure;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.condition.api.ConditionContext;
import com.sande.mythictrpg.condition.api.ConditionEventContext;
import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.api.GodDefinitionView;
import com.sande.mythictrpg.condition.api.WorldStateView;
import com.sande.mythictrpg.condition.engine.ConditionEngine;
import com.sande.mythictrpg.condition.engine.ConditionTreeParser;
import com.sande.mythictrpg.condition.registry.ConditionTypeRegistry;
import com.sande.mythictrpg.data.player.PlayerMythProfile;
import com.sande.mythictrpg.data.player.PlayerMythQueryService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FreeStructureConditionGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private FreeStructureConditionGameTests() {}

    @GameTest(templateNamespace="minecraft", template=TEMPLATE)
    public static void structureConditionUsesAuthoritativeScoreAndFeatures(GameTestHelper helper) {
        UUID player = UUID.randomUUID(); ResourceLocation policy = id("fortuna_modern");
        StoredStructureEvaluation evaluation = new StoredStructureEvaluation("free:test", player, Set.of(player),
                policy, id("fortuna"), 82, 79, 91, 1000, "hash",
                Map.of("interior_quality", .72), "test");
        StructureEvaluationView structures = new StructureEvaluationView() {
            @Override public boolean isReady() { return true; }
            @Override public Optional<StoredStructureEvaluation> best(UUID target, ResourceLocation requested,
                    long age) { return target.equals(player) && requested.equals(policy) ? Optional.of(evaluation) : Optional.empty(); }
        };
        var node = new ConditionTreeParser(ConditionTypeRegistry.INSTANCE).parse(JsonParser.parseString("""
                {"type":"mythictrpg:structure_evaluated","scope":"player",
                 "policy":"mythictrpg:fortuna_modern","minimum_score":75,"minimum_build_score":70,
                 "minimum_features":{"interior_quality":0.6}}
                """));
        ConditionContext context = new ConditionContext(world(), players(), Set::of, gods(), Optional.of(player),
                Optional.empty(), Optional.<ConditionEventContext>empty(), structures);
        helper.assertValueEqual(ConditionEngine.INSTANCE.evaluate(node, context), ConditionResult.MATCH,
                "Qualifying structure evaluation did not match");
        helper.succeed();
    }

    @GameTest(templateNamespace="minecraft", template=TEMPLATE)
    public static void structureConditionRejectsInvalidThreshold(GameTestHelper helper) {
        try {
            new ConditionTreeParser(ConditionTypeRegistry.INSTANCE).parse(JsonParser.parseString("""
                    {"type":"mythictrpg:structure_evaluated","scope":"player",
                     "policy":"mythictrpg:fortuna_modern","minimum_score":101}
                    """));
            helper.fail("Out-of-range structure score was accepted"); return;
        } catch (RuntimeException expected) { }
        helper.succeed();
    }

    private static WorldStateView world() { return new WorldStateView() {
        @Override public int dataVersion() { return 1; }
        @Override public Set<ResourceLocation> unlockedGods() { return Set.of(); }
    }; }
    private static PlayerMythQueryService players() { return new PlayerMythQueryService() {
        @Override public boolean isReady() { return true; }
        @Override public Optional<PlayerMythProfile> find(UUID playerId) { return Optional.empty(); }
        @Override public Map<UUID, PlayerMythProfile> activeProfiles() { return Map.of(); }
    }; }
    private static GodDefinitionView gods() { return new GodDefinitionView() {
        @Override public boolean isReady() { return true; }
        @Override public Set<ResourceLocation> godsInCategory(ResourceLocation categoryId) { return Set.of(); }
    }; }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,path); }
}
