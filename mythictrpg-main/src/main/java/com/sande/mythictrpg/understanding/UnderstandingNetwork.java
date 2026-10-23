package com.sande.mythictrpg.understanding;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.function.Consumer;

@EventBusSubscriber(modid = MythicTrpg.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class UnderstandingNetwork {
    private static Consumer<UnderstandingPayload> receiver = ignored -> { };
    private UnderstandingNetwork() { }
    public static void receiver(Consumer<UnderstandingPayload> value) { receiver = java.util.Objects.requireNonNull(value); }
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("understanding-2");
        registrar.playToClient(UnderstandingPayload.TYPE, UnderstandingPayload.CODEC,
                (p, c) -> c.enqueueWork(() -> receiver.accept(p)));
        registrar.playToServer(UnderstandingRequest.TYPE, UnderstandingRequest.CODEC,
                (p, c) -> c.enqueueWork(() -> { if (c.player() instanceof ServerPlayer player) UnderstandingRuntime.receive(player, p); }));
    }
}
