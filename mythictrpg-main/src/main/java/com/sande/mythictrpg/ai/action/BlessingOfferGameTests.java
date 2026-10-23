package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.quest.reward.BlessingEffectApplication;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.UUID;

@GameTestHolder("mythictrpg_blessings")
@PrefixGameTestTemplate(false)
public final class BlessingOfferGameTests {
    private BlessingOfferGameTests() { }

    @GameTest(templateNamespace = "mythictrpg_blessings", template = "empty")
    public static void authoredAiCapsAndHiddenApplicationReportActualOutcome(GameTestHelper helper) {
        ResourceLocation id = ResourceLocation.parse("mythictrpg:test/blessing_offer");
        ResourceLocation god = ResourceLocation.parse("mythictrpg:fortuna");
        ResourceLocation strength = ResourceLocation.withDefaultNamespace("strength");
        ResourceLocation resistance = ResourceLocation.withDefaultNamespace("resistance");
        new BlessingOfferTemplate(id, god, strength, 100, 16);
        new BlessingOfferTemplate(id, god, resistance, 100, 3);
        expectRejected(helper, () -> new BlessingOfferTemplate(id, god, strength, 100, 17), "AI accepted strength XVIII");
        expectRejected(helper, () -> new BlessingOfferTemplate(id, god, resistance, 100, 4), "AI accepted resistance V");
        var player = helper.makeMockServerPlayerInLevel();
        var manager = AiActionTemplateManager.INSTANCE;
        var previous = manager.prepare(player.server.getResourceManager(), null);
        try {
            manager.apply(Map.of(id, new BlessingOfferTemplate(id, god, strength, 100, 2)), null, null);
            var context = new AiActionContext(player.server, player);
            var proposal = new AiActionProposal(UUID.randomUUID(), UUID.randomUUID(), AiActionTypes.BLESSING_OFFER,
                    god, player.getUUID(), "Blessing", "Authored temporary effect", Map.of("template_id", id.toString()));
            var definition = BlessingOfferAiAction.definition();
            helper.assertTrue(definition.validator().validate(context, proposal).accepted(), "authored AI blessing rejected");
            player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 20, 4));
            var outcome = definition.executor().execute(context, proposal);
            helper.assertTrue(outcome.executed(), "stored hidden effect was reported as an execution failure");
            helper.assertValueEqual(outcome.details().get("application"), BlessingEffectApplication.Result.DEFERRED.name(),
                    "hidden effect was reported as already active");
            var repeated = definition.executor().execute(context, proposal);
            helper.assertValueEqual(repeated.details().get("application"), BlessingEffectApplication.Result.ALREADY_PRESENT.name(),
                    "identical repeated grant was not recognized");
            for (int i = 0; i < 20; i++) helper.assertTrue(player.getEffect(MobEffects.DAMAGE_BOOST).tick(player, () -> { }), "AI hidden fallback disappeared");
            helper.assertValueEqual(player.getEffect(MobEffects.DAMAGE_BOOST).getAmplifier(), 2, "AI strength III did not follow V");
            helper.assertValueEqual(player.getEffect(MobEffects.DAMAGE_BOOST).getDuration(), 80, "AI hidden timer did not elapse");
            helper.succeed();
        } finally {
            manager.apply(previous, null, null);
            player.server.getPlayerList().remove(player);
        }
    }

    private static void expectRejected(GameTestHelper helper, Runnable operation, String message) {
        try { operation.run(); helper.fail(message); } catch (IllegalArgumentException expected) { }
    }
}
