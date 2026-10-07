package com.sande.mythictrpg.recording.channel.mixin;

import java.util.*;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;
import org.spongepowered.asm.service.MixinService;

/** Optional capture adapters: a changed/missing signature disables capture, never makes gameplay depend on the archive. */
public final class ChannelCaptureMixinPlugin implements IMixinConfigPlugin {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("MythicTRPG/ChannelCapture");
    private static final Map<String, List<String>> METHODS = Map.of(
        "PublicChatCaptureMixin", List.of("broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
                "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/network/chat/ChatType$Bound;)V"),
        "PrivateMessageCaptureMixin", List.of("sendMessage(Lnet/minecraft/commands/CommandSourceStack;Ljava/util/Collection;Lnet/minecraft/network/chat/PlayerChatMessage;)V"),
        "TeamMessageCaptureMixin", List.of("sendMessage(Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/scores/PlayerTeam;Ljava/util/List;Lnet/minecraft/network/chat/PlayerChatMessage;)V"),
        "CommandOriginCaptureMixin", List.of("performUnsignedChatCommand(Ljava/lang/String;)V", "performSignedChatCommand(Lnet/minecraft/network/protocol/game/ServerboundChatCommandSignedPacket;Lnet/minecraft/network/chat/LastSeenMessages;)V"),
        "PacketReceiptCaptureMixin", List.of("send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V"),
        "FtbGuiMessageCaptureMixin", List.of("lambda$handle$0(Lnet/minecraft/server/level/ServerPlayer;Ldev/ftb/mods/ftbteams/net/SendMessageMessage;Ldev/ftb/mods/ftbteams/api/Team;)V"),
        "FtbRedirectChatCaptureMixin", List.of("lambda$chatReceived$6(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/Component;Ldev/ftb/mods/ftbteams/api/Team;)Ldev/architectury/event/EventResult;"),
        "FtbCommandMessageCaptureMixin", List.of("lambda$register$28(Lcom/mojang/brigadier/context/CommandContext;)I")
    );
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        String name = mixin.substring(mixin.lastIndexOf('.') + 1);
        try {
            if (name.startsWith("Ftb") && !supportedFtb()) { LOG.warn("Channel capture {} disabled: requires FTB Teams 2101.1.11 and Architectury 13.0.11", name); return false; }
            var required = METHODS.get(name);
            if (required == null) { LOG.warn("Channel capture {} disabled: unregistered signature", name); return false; }
            ClassNode node = MixinService.getService().getBytecodeProvider().getClassNode(target);
            boolean matched = required.stream().allMatch(signature -> node.methods.stream().anyMatch(method -> (method.name + method.desc).equals(signature)));
            if (!matched) LOG.warn("Channel capture {} disabled: unsupported target signature", name);
            return matched;
        } catch (Exception | LinkageError unavailable) {
            LOG.warn("Channel capture {} disabled: optional target unavailable ({})", name, unavailable.getClass().getSimpleName()); return false;
        }
    }
    private static boolean supportedFtb() {
        var mods = FMLLoader.getLoadingModList();
        return mods != null && mods.getMods().stream().anyMatch(mod -> mod.getModId().equals("ftbteams") && mod.getVersion().toString().equals("2101.1.11"))
                && mods.getMods().stream().anyMatch(mod -> mod.getModId().equals("architectury") && mod.getVersion().toString().equals("13.0.11"));
    }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        LOG.info("Applied optional channel capture adapter {}", mixin.substring(mixin.lastIndexOf('.') + 1));
    }
}
