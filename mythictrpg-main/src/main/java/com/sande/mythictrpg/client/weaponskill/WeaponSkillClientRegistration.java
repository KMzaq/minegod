package com.sande.mythictrpg.client.weaponskill;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.weaponskill.WeaponSkillNetwork;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

@EventBusSubscriber(modid = MythicTrpg.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class WeaponSkillClientRegistration {
    private WeaponSkillClientRegistration() { }
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> WeaponSkillNetwork.receiver(WeaponSkillClient::receive));
    }
    @SubscribeEvent public static void layers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR,
                ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "weapon_skill_hud"), WeaponSkillClient::render);
    }
}
