package com.sande.mythictrpg.quest.reward;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;
import static com.sande.mythictrpg.content.ContentSystemsGameTests.connect;
import static com.sande.mythictrpg.content.ContentSystemsGameTests.disconnect;

@GameTestHolder("mythictrpg_blessings")
@PrefixGameTestTemplate(false)
public final class OrdinaryPotionBlessingGameTests {
    @GameTest(templateNamespace="mythictrpg_blessings",template="empty",batch="ordinary_potion_blessings",timeoutTicks=300)
    public static void ordinaryPotionRefreshPreservesOwnedTailWithoutRestoringMilkRemoval(GameTestHelper helper) {
        var channels = new ArrayList<io.netty.channel.embedded.EmbeddedChannel>();
        ServerPlayer owner = connect(helper,"PotionOwner",channels), foreign = connect(helper,"PotionForeign",channels),
                removed = connect(helper,"PotionRemoved",channels);
        try {
            grant(helper,owner,"strength"); grant(helper,owner,"absorption"); grant(helper,removed,"strength");
            Items.MILK_BUCKET.finishUsingItem(new ItemStack(Items.MILK_BUCKET),removed.serverLevel(),removed);
            foreign.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST,-1,0));
            for (ServerPlayer player : List.of(owner,foreign,removed)) chain(player,MobEffects.DAMAGE_BOOST);
            chain(owner,MobEffects.ABSORPTION); owner.setAbsorptionAmount(1);
            owner.getEffect(MobEffects.DAMAGE_BOOST).getCures().clear();
            var foreignBefore = encode(foreign,MobEffects.DAMAGE_BOOST);
            var removedBefore = encode(removed,MobEffects.DAMAGE_BOOST);
            // Exercise the public subscribed tick boundary after ordinary PotionItem use, not the authored helper.
            for (ServerPlayer player : List.of(owner,foreign,removed)) NeoForge.EVENT_BUS.post(new PlayerTickEvent.Pre(player));
            helper.assertValueEqual(owner.getEffect(MobEffects.DAMAGE_BOOST).getDuration(),100,"normalization restarted/decremented the potion timer");
            helper.assertTrue(owner.getEffect(MobEffects.DAMAGE_BOOST).getCures().isEmpty(),"normalization changed external cure metadata");
            helper.assertValueEqual(owner.getAbsorptionAmount(),1.0F,"normalization refilled spent absorption");
            helper.assertValueEqual(encode(foreign,MobEffects.DAMAGE_BOOST),foreignBefore,"unowned external effect chain was rewritten");
            helper.assertValueEqual(encode(removed,MobEffects.DAMAGE_BOOST),removedBefore,"milk-suppressed chain was rewritten");
            for (Holder<MobEffect> effect : List.of(MobEffects.DAMAGE_BOOST,MobEffects.ABSORPTION)) {
                for (int tick=0;tick<100;tick++) {
                    var current = owner.getEffect(effect);
                    helper.assertTrue(current!=null && current.tick(owner,()->owner.forceAddEffect(current,null)),"ordinary potion lost surviving baseline at "+tick);
                }
                var current = owner.getEffect(effect);
                helper.assertTrue(current.isInfiniteDuration() && current.getAmplifier()==0,"ordinary potion did not restore strongest surviving owned baseline");
            }
            helper.assertValueEqual(owner.getAbsorptionAmount(),1.0F,"fallback refilled spent absorption");
            var claims = RewardClaimState.get(owner.server);
            var strength = ResourceLocation.withDefaultNamespace("strength");
            helper.assertTrue(claims.isBlessingSuppressed(removed.getUUID(),strength),"tick cleared milk suppression");
            removed.removeAllEffects(); NeoForge.EVENT_BUS.post(new PlayerTickEvent.Pre(removed));
            helper.assertFalse(removed.hasEffect(MobEffects.DAMAGE_BOOST),"tick re-created missing suppressed effect");
            owner.removeAllEffects(); NeoForge.EVENT_BUS.post(new PlayerTickEvent.Pre(owner));
            helper.assertFalse(owner.hasEffect(MobEffects.DAMAGE_BOOST),"tick defeated actual milk/removal policy");
            helper.succeed();
        } finally { disconnect(owner,channels); disconnect(foreign,List.of()); disconnect(removed,List.of()); }
    }
    private static void grant(GameTestHelper helper,ServerPlayer player,String effect) {
        var receipt = RewardClaimService.INSTANCE.issue(player,ResourceLocation.parse("mythictrpg:fortuna"),
                ResourceLocation.parse("test:ordinary_potion/"+UUID.randomUUID()),
                new ResolvedQuestReward("fixture",List.of(new BlessingRewardEntry(ResourceLocation.withDefaultNamespace(effect),-1,0)),List.of()));
        helper.assertTrue(receipt.succeeded(),"permanent fixture grant failed: "+receipt.reason());
    }
    private static void chain(ServerPlayer player,Holder<MobEffect> effect) {
        drink(player,effect,60,2); drink(player,effect,20,4); drink(player,effect,100,4);
    }
    private static void drink(ServerPlayer player,Holder<MobEffect> effect,int duration,int amplifier) {
        var potion = new ItemStack(Items.POTION);
        potion.set(DataComponents.POTION_CONTENTS,new PotionContents(Optional.empty(),Optional.empty(),List.of(new MobEffectInstance(effect,duration,amplifier))));
        Items.POTION.finishUsingItem(potion,player.serverLevel(),player);
    }
    private static net.minecraft.nbt.Tag encode(ServerPlayer player,Holder<MobEffect> effect) {
        return MobEffectInstance.CODEC.encodeStart(player.registryAccess().createSerializationContext(NbtOps.INSTANCE),player.getEffect(effect)).getOrThrow();
    }
}
