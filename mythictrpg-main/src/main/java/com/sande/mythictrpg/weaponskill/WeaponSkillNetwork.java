package com.sande.mythictrpg.weaponskill;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jetbrains.annotations.Nullable;
import java.util.Objects;
import java.util.function.Consumer;

/** Dedicated protocol keeps client classes out of the dedicated-server loading path. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class WeaponSkillNetwork {
    private static Consumer<WeaponSkillSnapshot> receiver = ignored -> { };
    private WeaponSkillNetwork() { }

    public static void receiver(Consumer<WeaponSkillSnapshot> value) { receiver = Objects.requireNonNull(value); }

    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("weapon-skills-3");
        registrar.playToClient(WeaponSkillSnapshot.TYPE, WeaponSkillSnapshot.CODEC,
                (payload, context) -> context.enqueueWork(() -> receiver.accept(payload)));
        registrar.playToServer(WeaponSkillRequest.TYPE, WeaponSkillRequest.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player)
                        WeaponSkillRuntime.receive(player, payload.selectedSlot(), payload.expectedSkill());
                }));
    }

    public static void send(ServerPlayer player, double mana, int selectedSlot, ItemStack held,
            @Nullable ResourceLocation skillId, String skillName, int manaCost, int remainingTicks) {
        send(player,mana,200,selectedSlot,held,skillId,skillName,manaCost,remainingTicks);
    }
    public static void send(ServerPlayer player,double mana,double maximumMana,int selectedSlot,ItemStack held,
            @Nullable ResourceLocation skillId,String skillName,int manaCost,int remainingTicks) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Weapon skill sync requires server thread");
        PacketDistributor.sendToPlayer(player, new WeaponSkillSnapshot(mana, maximumMana, selectedSlot, held,
                skillId, skillName, manaCost, remainingTicks, WeaponSkillRuntime.actionLocked(player)));
    }
}
