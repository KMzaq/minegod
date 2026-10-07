package com.sande.mythictrpg.network;

import com.sande.mythictrpg.raid.RaidUiService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class RaidNetwork {
    private RaidNetwork() { }
    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(RaidPagePayload.TYPE, RaidPagePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientRaidBridge.accept(payload)));
        registrar.playToServer(RaidRequestPayload.TYPE, RaidRequestPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) RaidUiService.INSTANCE.receive(player, payload);
                }));
    }
}
