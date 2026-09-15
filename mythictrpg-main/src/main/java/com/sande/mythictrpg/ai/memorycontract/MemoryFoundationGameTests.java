package com.sande.mythictrpg.ai.memorycontract;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.action.AiActionGateway;
import com.sande.mythictrpg.ai.action.AiActionResult;
import com.sande.mythictrpg.ai.server.AiConversationRuntimeService;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.interaction.content.ValidatedInteractionContent;
import com.sande.mythictrpg.interaction.director.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import java.util.*;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MemoryFoundationGameTests {
    private static final String TEMPLATE="bastion/mobs/empty";
    private static final ResourceLocation GOD=ResourceLocation.parse("mythictrpg:demeter");
    @GameTest(templateNamespace="minecraft",template=TEMPLATE)
    @SuppressWarnings("removal")
    public static void memoryContextsInvalidateAcrossToggleAndBlockGameActions(GameTestHelper helper) {
        MemoryFoundationSettings.withModeForTest(MemoryFoundationSettings.Mode.RUMOR_TEST, () -> {
            var player=helper.makeMockServerPlayerInLevel(); var runtime=AiConversationRuntimeService.INSTANCE;
            UUID listener=UUID.randomUUID(); UUID interaction=UUID.randomUUID();
            var plan=new InteractionPlan(player.getUUID(),new InteractionAudience(player.getUUID(),Set.of(player.getUUID(),listener)),
                    InteractionMode.EXPLICIT,ResourceLocation.parse("mythictrpg:memory_test"),InteractionParticipants.primaryOnly(GOD),
                    PlanRevisionStamp.explicit(1),List.of());
            try {
                runtime.onInteractionStarted(player.server,interaction,plan,new ValidatedInteractionContent(List.of(),Set.of(GOD)));
                var context=runtime.memoryContext(player).orElseThrow();
                helper.assertTrue(context.audience().contains(listener),"group audience was lost");
                helper.assertTrue(context.readOnly(),"rumor mode must be read-only");
                helper.assertTrue(runtime.memoryContextCurrent(player,context),"fresh context rejected");
                var before=PlayerMythDataService.get(player.server).find(player.getUUID());
                var result=AiActionGateway.submit(player,GOD,"relationship_change","test","test",Map.of("affinity_delta","-50"));
                helper.assertValueEqual(result.status(),AiActionResult.Status.REJECTED,"rumor modified affinity");
                helper.assertTrue(result.reason().contains("dialogue-only"),"guard must reject before execution");
                helper.assertValueEqual(PlayerMythDataService.get(player.server).find(player.getUUID()),before,"profile mutated");
                float health=player.getHealth();
                var damage=AiActionGateway.submit(player,GOD,"player_damage","test","test",Map.of("template_id","mythictrpg:fortuna_playful_smack"));
                helper.assertValueEqual(damage.status(),AiActionResult.Status.REJECTED,"rumor damage accepted");
                helper.assertValueEqual(player.getHealth(),health,"health changed");
                runtime.setEnabled(player,false); helper.assertTrue(!runtime.memoryContextCurrent(player,context),"disabled context valid");
                runtime.setEnabled(player,true); helper.assertTrue(!runtime.memoryContextCurrent(player,context),"old generation revived");
                helper.assertValueEqual(runtime.memoryContext(player).orElseThrow().interactionId(),interaction,"game interaction id lost");
                MemoryFoundationSettings.withModeForTest(MemoryFoundationSettings.Mode.OFF,() -> helper.assertTrue(runtime.memoryContext(player).isEmpty(),"OFF exposed memory"));
            } finally {
                runtime.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
                if (player.server.getPlayerList().getPlayer(player.getUUID()) == player) {
                    player.server.getPlayerList().remove(player);
                }
            }
        });
        helper.succeed();
    }
}
