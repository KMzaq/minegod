package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.network.AiActionConfirmationPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AiActionConfirmationTermsGameTests {
    @SuppressWarnings("unchecked")
    @GameTest(templateNamespace = "mythictrpg_action_confirmation", template = "empty")
    public static void authoredTermsRemainSeparateFromNarrativeAndRejectChangedTemplates(GameTestHelper helper) throws Exception {
        var manager = AiActionTemplateManager.INSTANCE;
        var field = AiActionTemplateManager.class.getDeclaredField("templates");
        field.setAccessible(true);
        var original = (Map<ResourceLocation, AiActionTemplate>) field.get(manager);
        var god = id("test:god");
        var template = id("test:offering");
        var proposal = new AiActionProposal(UUID.randomUUID(), UUID.randomUUID(), AiActionTypes.ITEM_REQUEST,
                god, UUID.randomUUID(), "무료 선물", "아무것도 소비하지 않음", Map.of("template_id", template.toString()));
        var item = new ItemRequestTemplate(template, god, id("minecraft:gold_ingot"), 3);
        try {
            manager.apply(Map.of(template, item), null, null);
            var terms = AiActionConfirmationTerms.capture(proposal);
            helper.assertTrue(terms.matches(proposal), "unchanged authored terms rejected");
            helper.assertTrue(terms.lines().getFirst().contains("minecraft:gold_ingot × 3"), "actual consumption missing");
            helper.assertTrue(terms.lines().stream().noneMatch(line -> line.contains("무료 선물")), "model narrative used as mechanics");
            manager.apply(Map.of(template, new ItemRequestTemplate(template, god, item.itemId(), 4)), null, null);
            helper.assertTrue(!terms.matches(proposal), "changed item count still authorized");
            manager.apply(Map.of(), null, null);
            helper.assertTrue(!terms.matches(proposal), "removed definition still authorized");
            reject(helper, () -> AiActionConfirmationTerms.capture(proposal), "missing template accepted");

            var blessing = new BlessingOfferTemplate(template, god, id("minecraft:luck"), 1200, 1);
            var blessingProposal = new AiActionProposal(UUID.randomUUID(), proposal.sessionId(), AiActionTypes.BLESSING_OFFER,
                    god, proposal.targetPlayerId(), "영구 가호", "영원히 유지", proposal.parameters());
            manager.apply(Map.of(template, blessing), null, null);
            var blessingTerms = AiActionConfirmationTerms.capture(blessingProposal);
            helper.assertTrue(blessingTerms.lines().getFirst().contains("minecraft:luck / 단계 2"), "effect/level absent");
            helper.assertTrue(blessingTerms.lines().get(1).contains("1200틱"), "actual duration absent");
            manager.apply(Map.of(template, new BlessingOfferTemplate(template, god, blessing.effectId(), 2400, 1)), null, null);
            helper.assertTrue(!blessingTerms.matches(blessingProposal), "changed blessing duration still authorized");

            var payload = new AiActionConfirmationPayload(proposal.proposalId(), proposal.actionType(),
                    proposal.title(), proposal.summary(), 60, terms.lines());
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            try {
                AiActionConfirmationPayload.STREAM_CODEC.encode(buffer, payload);
                helper.assertValueEqual(AiActionConfirmationPayload.STREAM_CODEC.decode(buffer), payload, "wire terms round trip");
            } finally { buffer.release(); }
            reject(helper, () -> new AiActionConfirmationPayload(proposal.proposalId(), proposal.actionType(), "", "", 60, List.of()), "empty terms accepted");
            reject(helper, () -> new AiActionConfirmationPayload(proposal.proposalId(), proposal.actionType(), "", "", 60, List.of("x".repeat(401))), "unbounded terms accepted");
            var malformed = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            try {
                malformed.writeUUID(proposal.proposalId()); malformed.writeResourceLocation(proposal.actionType());
                malformed.writeUtf(""); malformed.writeUtf(""); malformed.writeVarInt(60); malformed.writeVarInt(9);
                reject(helper, () -> AiActionConfirmationPayload.STREAM_CODEC.decode(malformed), "oversized wire count accepted");
            } finally { malformed.release(); }
        } finally { manager.apply(original, null, null); }
        helper.succeed();
    }

    private static ResourceLocation id(String value) { return ResourceLocation.parse(value); }
    private static void reject(GameTestHelper helper, Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        helper.fail(message);
    }
}
