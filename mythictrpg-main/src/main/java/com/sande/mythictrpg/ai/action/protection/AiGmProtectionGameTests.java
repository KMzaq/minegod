package com.sande.mythictrpg.ai.action.protection;

import com.sande.mythictrpg.godavatar.GodAvatarEntities;
import com.sande.mythictrpg.godavatar.GodAvatarEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Persistence, execution hooks and game-issued consequence receipts; no model requests. */
@GameTestHolder("mythictrpg_ai_gm_protection")
@PrefixGameTestTemplate(false)
public final class AiGmProtectionGameTests {
    private static final ResourceLocation A = ResourceLocation.parse("mythictrpg:protection_fixture_a");
    private static final ResourceLocation B = ResourceLocation.parse("mythictrpg:protection_fixture_b");

    @GameTest(templateNamespace = "mythictrpg_gm_protection", template = "empty")
    public static void aiDeathProtectionSurvivesSaveAndGodSessionChanges(GameTestHelper h) {
        UUID player = UUID.randomUUID(), other = UUID.randomUUID();
        var state = new AiHarmProtection();
        h.assertTrue(state.allows(player, A), "first coherent AI attack should not be disabled");
        state.recordDeath(player, A);
        h.assertFalse(state.allows(player, A), "same God can kill again after room/template replacement");
        h.assertFalse(state.allows(player, B), "other God bypasses victim-wide protection");
        h.assertTrue(state.allows(other, B), "victim protection leaked to another player");
        var loaded = AiHarmProtection.load(state.save(new CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
        h.assertFalse(loaded.allows(player, A), "restart lost victim protection");
        loaded.renewCombat(player, B);
        h.assertTrue(loaded.allows(player, B), "player's real new engagement cannot resume its opponent");
        h.assertFalse(loaded.allows(player, A), "new engagement also authorized unrelated God");
        var restarted = AiHarmProtection.load(loaded.save(new CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
        h.assertFalse(restarted.allows(player, B), "restart resurrected old renewed aggression");
        loaded.recordDeath(player, B);
        h.assertFalse(loaded.allows(player, B), "renewed combat enables an endless death loop");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_gm_protection", template = "empty")
    public static void malformedProtectionCannotEraseTheGuard(GameTestHelper h) {
        var state = new AiHarmProtection(); UUID player = UUID.randomUUID(); state.recordDeath(player, A);
        CompoundTag good = state.save(new CompoundTag(), h.getLevel().registryAccess());
        CompoundTag duplicate = good.copy();
        duplicate.getList("protectedPlayers", Tag.TAG_COMPOUND).add(duplicate.getList("protectedPlayers", Tag.TAG_COMPOUND).getCompound(0).copy());
        CompoundTag badType = good.copy(); ListTag strings = new ListTag(); strings.add(StringTag.valueOf("not a player")); badType.put("protectedPlayers", strings);
        CompoundTag unknown = good.copy(); unknown.putInt("schemaVersion", 900);
        for (CompoundTag bad : List.of(duplicate, badType, unknown)) {
            var loaded = AiHarmProtection.load(bad, h.getLevel().registryAccess());
            h.assertFalse(loaded.allows(player, B), "malformed save reopened AI damage");
            h.assertTrue(loaded.save(new CompoundTag(), h.getLevel().registryAccess()).equals(bad), "rejected save overwritten");
        }
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_gm_protection", template = "empty")
    public static void actualIncomingHookRejectsOtherGodAndPreservesVictimIdentity(GameTestHelper h) throws Exception {
        try (var connected = player(h, "HarmProtected")) {
            ServerPlayer victim = connected.player();
            var state = AiHarmProtection.get(victim.server); state.recordDeath(victim.getUUID(), A);
            GodAvatarEntity actor = new GodAvatarEntity(GodAvatarEntities.GOD_AVATAR.get(), h.getLevel());
            var field = GodAvatarEntity.class.getDeclaredField("godId"); field.setAccessible(true); field.set(actor, B);
            var incoming = new LivingIncomingDamageEvent(victim, new DamageContainer(victim.damageSources().mobAttack(actor), 2));
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(incoming);
            h.assertTrue(incoming.isCanceled(), "production event subscriber did not block the second God");
            AiHarmProtection.voluntaryAttack(victim, B);
            h.assertTrue(AiHarmProtection.damageAllowed(victim, B), "explicit successful-attack entry point rejected renewed opponent");
            h.assertFalse(AiHarmProtection.damageAllowed(victim, A), "other God gained renewed attack right");
            AiHarmProtection.endRenewedCombat(victim);
            h.assertFalse(AiHarmProtection.damageAllowed(victim, B), "logout preserves renewed attack right");
        }
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_gm_protection", template = "empty")
    public static void explicitProtectedMetadataBlocksForcedLoss(GameTestHelper h) {
        try (var connected = player(h, "ProtectedItems")) {
            for (String marker : List.of("important", "soulbound")) {
                var stack = new ItemStack(Items.PAPER); CompoundTag data = new CompoundTag(), metadata = new CompoundTag();
                metadata.putBoolean(marker, true); data.put(AiProtectedItems.CUSTOM_DATA_KEY, metadata);
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
                h.assertValueEqual(AiProtectedItems.rejection(connected.player(), stack).orElse(""), "PROTECTED_ITEM_METADATA", marker + " item accepted");
                h.assertValueEqual(stack.getCount(), 1, "read-only protection consumed an item");
            }
            var malformed = new ItemStack(Items.PAPER); CompoundTag data = new CompoundTag();
            data.putString(AiProtectedItems.CUSTOM_DATA_KEY, "yes"); malformed.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
            h.assertTrue(AiProtectedItems.rejection(connected.player(), malformed).isPresent(), "invalid metadata silently removed protection");
        }
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_gm_protection", template = "empty")
    public static void permanentLossRequiresDeliveredWarningResponseAndUnchangedTarget(GameTestHelper h) {
        try (var connected = player(h, "WarnedOwner"); var stranger = player(h, "WarnedOther")) {
            ServerPlayer owner = connected.player(); var gate = new AiConsequenceGate();
            var scope = new AiConsequenceGate.LossScope(owner.getUUID(), A, "block:overworld:0,64,0", "state-one", UUID.randomUUID(), 4, "지정한 시험 블록 1개가 영구 소실됩니다.");
            var options = List.of(new AiConsequenceGate.ResponseOption("defend", "방어하기"), new AiConsequenceGate.ResponseOption("continue", "위험을 감수하고 계속하기"));
            var token = gate.warn(owner, scope, options, (p, s, choice) -> choice.equals("defend")
                    ? AiConsequenceGate.ResponseResult.PREVENTED_LOSS : AiConsequenceGate.ResponseResult.CONSEQUENCE_STILL_VALID, () -> true);
            boolean delivered = false; Object packet;
            while ((packet = connected.channel().readOutbound()) != null) {
                if (packet instanceof ClientboundSystemChatPacket chat && chat.content().getString().contains("영구 손실 경고")) delivered = true;
                io.netty.util.ReferenceCountUtil.release(packet);
            }
            h.assertTrue(delivered, "game warning was never sent to the client channel");
            h.assertFalse(gate.consumeAuthorization(owner, token, scope, List.of(), () -> true), "silence counted as response opportunity");
            h.assertValueEqual(gate.respond(stranger.player(), token, "continue", scope), AiConsequenceGate.ResponseResult.UNRESOLVED, "other player answered owner's warning");
            h.assertValueEqual(gate.respond(owner, token, "continue", scope), AiConsequenceGate.ResponseResult.CONSEQUENCE_STILL_VALID, "explicit owner response not resolved");
            var changed = new AiConsequenceGate.LossScope(owner.getUUID(), A, scope.targetKey(), "state-two", scope.interactionId(), 4, scope.consequence());
            h.assertFalse(gate.consumeAuthorization(owner, token, changed, List.of(), () -> true), "target changed after warning");
            h.assertFalse(gate.consumeAuthorization(owner, token, scope, List.of(), () -> false), "live countermeasure recheck skipped");
            h.assertTrue(gate.consumeAuthorization(owner, token, scope, List.of(), () -> true), "valid receipt not consumed");
            h.assertFalse(gate.consumeAuthorization(owner, token, scope, List.of(), () -> true), "receipt replay allowed repeated destruction");
            var defended = gate.warn(owner, scope, options, (p, s, choice) -> AiConsequenceGate.ResponseResult.PREVENTED_LOSS, () -> true);
            gate.respond(owner, defended, "defend", scope);
            h.assertFalse(gate.consumeAuthorization(owner, defended, scope, List.of(), () -> true), "successful defense failed to prevent loss");
        }
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_gm_protection", template = "empty")
    public static void cursesNeedActualGameEnforcedRecovery(GameTestHelper h) {
        h.assertTrue(AiConsequenceGate.curseRejection(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0)).isEmpty(), "finite engine-enforced expiry rejected");
        h.assertTrue(AiConsequenceGate.curseRejection(new MobEffectInstance(MobEffects.WEAKNESS, -1, 0)).isPresent(), "permanent curse accepted without a recovery executor");
        h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_gm_protection",template="empty")
    public static void nestedContainersCannotHideImportantItems(GameTestHelper h) {
        try(var connected=player(h,"NestedProtection")) {
            var important=important();var box=new ItemStack(Items.SHULKER_BOX);
            box.set(DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(important)));
            var bundle=new ItemStack(Items.BUNDLE);
            bundle.set(DataComponents.BUNDLE_CONTENTS,new net.minecraft.world.item.component.BundleContents(List.of(box)));
            for(var stack:List.of(box,bundle)) {
                h.assertTrue(AiProtectedItems.rejection(connected.player(),stack).orElse("").startsWith("PROTECTED_CONTAINER_CONTENT"),"nested important item exposed");
                h.assertTrue(AiProtectedItems.rejection(h.getLevel(),stack).isPresent(),"world-container protection differs");
                h.assertValueEqual(stack.getCount(),1,"inspection mutated container");
            }
            var ordinary=new ItemStack(Items.SHULKER_BOX);
            ordinary.set(DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE))));
            h.assertTrue(AiProtectedItems.rejection(connected.player(),ordinary).isEmpty(),"ordinary container blocked");
        }h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_gm_protection",template="empty")
    public static void realDeathWithoutPossessionLossPersistsRepeatedHarmGuard(GameTestHelper h)throws Exception {
        boolean keep=h.getLevel().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY);
        try(var connected=player(h,"ActualDeath")) {
            h.getLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(false,h.getLevel().getServer());
            var p=connected.player();survival(p);var actor=actor(h,A);
            p.hurt(p.damageSources().mobAttack(actor),Float.MAX_VALUE);
            h.assertFalse(p.isAlive(),"empty-inventory real death did not occur");
            var state=AiHarmProtection.get(p.server);
            h.assertFalse(state.allows(p.getUUID(),B),"real death failed to protect against another God");
            var restored=AiHarmProtection.load(state.save(new CompoundTag(),p.registryAccess()),p.registryAccess());
            h.assertFalse(restored.allows(p.getUUID(),A),"save/load lost actual death protection");
        }finally{h.getLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(keep,h.getLevel().getServer());}
        h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_gm_protection",template="empty")
    public static void warningNeedsRealResponseAndCannotAuthorizeImportantLoss(GameTestHelper h)throws Exception {
        boolean keep=h.getLevel().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY);
        try(var connected=player(h,"DeathWarning")) {
            h.getLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(false,h.getLevel().getServer());
            var p=connected.player();survival(p);p.getInventory().setItem(0,new ItemStack(Items.STONE));var actor=actor(h,A);actor.setTarget(p);
            p.hurt(p.damageSources().mobAttack(actor),Float.MAX_VALUE);
            h.assertTrue(p.isAlive(),"first attack killed with possession loss before response");
            p.invulnerableTime=0;p.setHealth(20);p.hurt(p.damageSources().mobAttack(actor),Float.MAX_VALUE);
            h.assertTrue(p.isAlive(),"waiting/repeated damage counted as response");
            AiDeathLossGuard.voluntaryAttack(p,A); // same game-owned callback as successful direct player attack
            p.getInventory().setItem(1,important());p.invulnerableTime=0;p.setHealth(20);
            p.hurt(p.damageSources().mobAttack(actor),Float.MAX_VALUE);
            h.assertTrue(p.isAlive()&&p.getInventory().getItem(1).getCount()==1,"important possession lost despite protection");
            p.getInventory().setItem(1,ItemStack.EMPTY);p.invulnerableTime=0;p.setHealth(20);
            actor.setTarget(p);AiDeathLossGuard.warn(p,actor);AiDeathLossGuard.voluntaryAttack(p,A);
            p.hurt(p.damageSources().mobAttack(actor),Float.MAX_VALUE);
            h.assertFalse(p.isAlive(),"warned and voluntarily renewed fight cannot cause ordinary loss");
        }finally{h.getLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(keep,h.getLevel().getServer());}
        h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_gm_protection",template="empty")
    public static void confirmationCannotAuthorizeOtherGodPlayerOrLaterExecution(GameTestHelper h) {
        boolean keep=h.getLevel().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY);
        try(var first=player(h,"ScopeA");var second=player(h,"ScopeB")) {
            h.getLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(false,h.getLevel().getServer());
            var a=first.player();var b=second.player();survival(a);survival(b);
            a.getInventory().setItem(0,new ItemStack(Items.STONE));b.getInventory().setItem(0,new ItemStack(Items.STONE));var proposal=UUID.randomUUID();
            AiDeathLossGuard.withConfirmedRisk(a,A,proposal,()->AiDeathLossGuard.damage(a,A,proposal,()->{
                h.assertFalse(AiDeathLossGuard.preventDeath(a,A),"exact confirmed proposal rejected");
                h.assertTrue(AiDeathLossGuard.preventDeath(a,B),"other God borrowed confirmation");
                h.assertTrue(AiDeathLossGuard.preventDeath(b,A),"other player borrowed confirmation");return true;
            }));
            h.assertTrue(AiDeathLossGuard.preventDeath(a,A),"confirmation survived synchronous execution");
        }finally{h.getLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(keep,h.getLevel().getServer());}
        h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_gm_protection",template="empty")
    public static void importantTotemIsNotConsumedBeforeDeathGuard(GameTestHelper h)throws Exception {
        try(var fixture=player(h,"ProtectedTotem")) {
            var player=fixture.player();survival(player);var totem=new ItemStack(Items.TOTEM_OF_UNDYING);
            totem.set(DataComponents.CUSTOM_DATA,important().get(DataComponents.CUSTOM_DATA));
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,totem);
            player.hurt(player.damageSources().mobAttack(actor(h,A)),1000);
            h.assertTrue(player.isAlive()&&player.getOffhandItem().getCount()==1,"important totem consumed by AI hit");
        }h.succeed();
    }
    private static ItemStack important() {
        var stack=new ItemStack(Items.PAPER);var flags=new CompoundTag();flags.putBoolean("important",true);
        var data=new CompoundTag();data.put(AiProtectedItems.CUSTOM_DATA_KEY,flags);stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));return stack;
    }
    private static void survival(ServerPlayer player) {
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);player.getInventory().clearContent();
        player.totalExperience=0;player.experienceLevel=0;player.experienceProgress=0;player.setHealth(20);player.invulnerableTime=0;
        try{var field=ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");field.setAccessible(true);field.setInt(player,0);}
        catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
    }
    private static GodAvatarEntity actor(GameTestHelper h,ResourceLocation god)throws Exception {
        var actor=new GodAvatarEntity(GodAvatarEntities.GOD_AVATAR.get(),h.getLevel());
        var field=GodAvatarEntity.class.getDeclaredField("godId");field.setAccessible(true);field.set(actor,god);return actor;
    }
    private static Connected player(GameTestHelper helper, String name) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(), name), false);
        var p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        var channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        p.server.getPlayerList().placeNewPlayer(connection, p, cookie);
        return new Connected(p, channel);
    }
    private record Connected(ServerPlayer player, io.netty.channel.embedded.EmbeddedChannel channel) implements AutoCloseable {
        @Override public void close() { player.server.getPlayerList().remove(player); channel.finishAndReleaseAll(); }
    }
}
