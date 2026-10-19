package com.sande.mythictrpg.client.raid.trial;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.raid.trial.TrialRaidEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = MythicTrpg.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TrialRaidClientRegistration {
    private TrialRaidClientRegistration() { }

    @SubscribeEvent public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(SealedGuardianModel.LAYER, SealedGuardianModel::createLayer);
    }

    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(TrialRaidEntities.SEALED_GUARDIAN.get(), SealedGuardianRenderer::new);
    }
}
