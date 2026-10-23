package com.sande.mythictrpg.accessory;

import com.sande.mythictrpg.control.CrowdControlService;
import com.sande.mythictrpg.data.god.*;
import com.sande.mythictrpg.quest.reward.*;
import com.sande.mythictrpg.quest.reward.blessing.GodRank;
import com.sande.mythictrpg.quest.reward.luck.*;
import com.sande.mythictrpg.skills.*;
import com.sande.mythictrpg.weaponskill.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.gametest.*;
import java.lang.reflect.Field;
import java.util.*;

@GameTestHolder("mythictrpg_accessories")
@PrefixGameTestTemplate(false)
public final class AccessoryGameplayGameTests{
    private static final ResourceLocation GOD=id("god"),MANA=id("mana"),LINK=id("link"),CLEANSE=id("cleanse"),EMERGENCY=id("emergency"),LEARNED=id("learned"),JUMP=id("jump");
    @GameTest(templateNamespace="mythictrpg_accessories",template="empty",batch="accessory_gameplay")
    public static void claimOwnedFreeSlotsStackAndNeverMintMana(GameTestHelper h){try(var f=new Fixture(h)){
        h.assertTrue(!AccessoryRuntime.equip(f.player,0,UUID.randomUUID()),"unowned accessory equipped");
        f.grant(MANA);f.grant(MANA);f.grant(EMERGENCY);
        var mana=f.owned(MANA);h.assertTrue(mana.size()==2,"separate legitimate rewards did not produce separate rights");
        h.assertTrue(AccessoryRuntime.equip(f.player,0,mana.get(0).id()),"slot1");h.assertTrue(!AccessoryRuntime.equip(f.player,1,mana.get(0).id()),"replacement copy doubled effect");
        h.assertTrue(AccessoryRuntime.equip(f.player,1,mana.get(1).id()),"slot2");
        h.assertTrue(AccessoryRuntime.maximumMana(f.player)==400&&SkillMana.mana(f.player)==200,"equip minted mana or stacking failed");
        h.assertTrue(AccessoryRuntime.effectiveLuckBonus(f.player)==2&&Math.abs(AccessoryRuntime.goldBonusFraction(f.player)-.1)<.000001,"stacked utility effects");
        h.assertTrue(AccessoryRuntime.fixedPower(f.player)==25,"power did not select highest two owned rights");
        SkillMana.refund(f.player,200);h.assertTrue(SkillMana.mana(f.player)==400,"expanded mana unavailable");
        AccessoryRuntime.equip(f.player,0,null);h.assertTrue(SkillMana.mana(f.player)==300,"decreased maximum not clamped");
        AccessoryRuntime.equip(f.player,0,mana.get(0).id());h.assertTrue(SkillMana.mana(f.player)==300,"reequip refilled mana");
        h.assertTrue(Math.abs(f.player.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(ResourceLocation.parse("mythictrpg:accessory_speed")).amount()-.02)<.00001,"stacked speed modifier missing");
        AccessoryRuntime.equip(f.player,0,null);AccessoryRuntime.equip(f.player,1,null);h.assertTrue(f.player.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(ResourceLocation.parse("mythictrpg:accessory_speed"))==null,"unequip retained speed");
    }h.succeed();}
    @GameTest(templateNamespace="mythictrpg_accessories",template="empty",batch="accessory_gameplay")
    public static void physicalReissuePreservesOriginalRightAndCooldown(GameTestHelper h){try(var f=new Fixture(h)){
        f.grant(CLEANSE);var right=f.owned(CLEANSE).get(0);AccessoryRuntime.equip(f.player,0,right.id());
        var state=AccessoryState.get(f.player.server);state.trigger(f.player.getUUID(),right.id(),AccessoryEffect.Kind.CLEANSE_CC,1000,300000);
        h.assertTrue(tokenCount(f.player)==1,"reward did not provide bound accessory token");
        for(int i=0;i<f.player.getInventory().getContainerSize();i++)if(f.player.getInventory().getItem(i).is(RewardAccessoryItems.TOKEN.get()))f.player.getInventory().setItem(i,net.minecraft.world.item.ItemStack.EMPTY);
        h.assertTrue(RewardAccessoryItems.restore(f.player,right.id())&&RewardAccessoryItems.restore(f.player,right.id()),"lost token was not restored");
        h.assertTrue(tokenCount(f.player)==0&&f.owned(CLEANSE).size()==1,"reissue minted a copy beside the equipped representation or another reward right");
        h.assertTrue(state.equipped(f.player.getUUID(),0).equals(right.id())&&state.remainingMillis(f.player.getUUID(),right.id(),AccessoryEffect.Kind.CLEANSE_CC,1000)==300000,"reissue reset equipment or cooldown");
        h.assertTrue(!RewardAccessoryItems.restore(f.player,UUID.randomUUID()),"unowned token restored");
    }h.succeed();}
    private static int tokenCount(ServerPlayer player){return java.util.stream.IntStream.range(0,player.getInventory().getContainerSize()).map(i->player.getInventory().getItem(i).is(RewardAccessoryItems.TOKEN.get())?player.getInventory().getItem(i).getCount():0).sum();}
    @GameTest(templateNamespace="mythictrpg_accessories",template="empty",batch="accessory_gameplay")
    public static void goldBonusFreezesOnceForAutomaticAndChoiceRewards(GameTestHelper h){try(var f=new Fixture(h)){
        f.grant(MANA);var ring=f.owned(MANA).get(0);AccessoryRuntime.equip(f.player,0,ring.id());
        var currency=com.sande.mythictrpg.economy.CurrencyService.INSTANCE;long before=currency.balance(f.player.server,f.player.getUUID());
        var source=id("gold_"+UUID.randomUUID());var reward=new ResolvedQuestReward("gold",List.of(new CurrencyRewardEntry(100)),List.of());
        var result=RewardClaimService.INSTANCE.issue(f.player,GOD,source,reward);h.assertTrue(result.succeeded(),"gold reward rejected: "+result.reason());
        h.assertTrue(currency.balance(f.player.server,f.player.getUUID())==before+105,"equipped five-percent bonus was not paid once");
        var state=RewardClaimState.get(f.player.server);var receipt=state.find(result.claimId()).orElseThrow();
        h.assertTrue(receipt.automaticRewards().get(0)instanceof ResolvedCurrencyRewardEntry value&&value.baseAmount()==100&&value.bonusAmount()==5&&value.amount()==105,"gold amount was not frozen in receipt");
        AccessoryRuntime.equip(f.player,0,null);RewardAccessoryItems.restore(f.player,ring.id());
        var replay=RewardClaimService.INSTANCE.issue(f.player,GOD,source,reward);h.assertTrue(replay.succeeded()&&currency.balance(f.player.server,f.player.getUUID())==before+105,"unequip/reissue/replay credited again");
        AccessoryRuntime.equip(f.player,0,ring.id());var option=id("gold_choice");var alternative=id("gold_other");
        var choices=new ResolvedQuestReward("gold choice",List.of(),List.of(new RewardChoiceOption(option,"Gold",List.of(new CurrencyRewardEntry(100))),new RewardChoiceOption(alternative,"Other gold",List.of(new CurrencyRewardEntry(50)))));
        var pending=RewardClaimService.INSTANCE.issue(f.player,GOD,id("gold_choices_"+UUID.randomUUID()),choices);h.assertTrue(pending.succeeded(),"choice issue rejected: "+pending.reason());
        var frozen=state.find(pending.claimId()).orElseThrow().choices().get(0).rewards().get(0);h.assertTrue(frozen instanceof ResolvedCurrencyRewardEntry value&&value.amount()==105,"choice bonus was not snapshotted before selection");
        AccessoryRuntime.equip(f.player,0,null);var chosen=RewardClaimService.INSTANCE.choose(f.player,pending.claimId(),option);h.assertTrue(chosen.succeeded()&&currency.balance(f.player.server,f.player.getUUID())==before+210,"changing gear changed frozen choice reward");
        RewardClaimService.INSTANCE.choose(f.player,pending.claimId(),option);h.assertTrue(currency.balance(f.player.server,f.player.getUUID())==before+210,"choice selection replay credited twice");
        AccessoryRuntime.equip(f.player,0,ring.id());currency.credit(f.player.server,f.player.getUUID(),100);h.assertTrue(currency.balance(f.player.server,f.player.getUUID())==before+310,"ordinary balance transfer received bonus");
        var refund=RewardClaimService.INSTANCE.issue(f.player,GOD,ResourceLocation.parse("mythictrpg:quest_refund/"+UUID.randomUUID()),reward);h.assertTrue(refund.succeeded()&&currency.balance(f.player.server,f.player.getUUID())==before+410,"quest refund received accessory bonus");
    }h.succeed();}
    @GameTest(templateNamespace="mythictrpg_accessories",template="empty",batch="accessory_gameplay")
    public static void activeLuckAndAccessoryLuckFeedActualFrozenLuckyReward(GameTestHelper h){
        Object previous=field(LuckDefinitions.INSTANCE,"snapshot");try(var f=new Fixture(h)){
            var pool=id("lucky_pool");field(LuckDefinitions.INSTANCE,"snapshot",new LuckDefinitions.Snapshot(Map.of(pool,new LuckyItemPool(pool,List.of(new LuckyItemCandidate("paper",new NpcRewardEntry(ResourceLocation.parse("minecraft:paper"),1),10,10,1,1,true)))),LuckModel.DEFAULT));
            f.grant(MANA);f.grant(MANA);f.grant(EMERGENCY);var rings=f.owned(MANA);AccessoryRuntime.equip(f.player,0,rings.get(0).id());AccessoryRuntime.equip(f.player,1,rings.get(1).id());
            f.player.addEffect(new MobEffectInstance(MobEffects.LUCK,2000,4));h.assertTrue(EffectiveLuck.level(f.player)==7,"buff and two accessories were not combined");
            var authored=new ResolvedQuestReward("lucky",List.of(new LuckyRewardEntry(pool)),List.of());
            var preview=RewardResolutionService.resolve(f.player,GOD,id("luck_preview"),authored);
            h.assertTrue(preview.automaticRewards().get(0)instanceof ResolvedLuckyRewardEntry value&&value.selection().level()==7&&value.selection().reward().count()==11,"real resolver ignored combined luck");
            f.player.addEffect(new MobEffectInstance(MobEffects.LUCK,2000,16));h.assertTrue(EffectiveLuck.level(f.player)==17,"combined luck exceeded current XVII cap");
            var source=id("luck_claim_"+UUID.randomUUID());var claim=RewardClaimService.INSTANCE.issue(f.player,GOD,source,authored);h.assertTrue(claim.succeeded(),"lucky claim rejected: "+claim.reason());
            var saved=RewardClaimState.get(f.player.server).find(claim.claimId()).orElseThrow().automaticRewards().get(0);
            h.assertTrue(saved instanceof ResolvedLuckyRewardEntry value&&value.selection().level()==17&&value.selection().reward().count()==13,"actual lucky receipt did not freeze effective luck");
            AccessoryRuntime.equip(f.player,0,null);AccessoryRuntime.equip(f.player,1,null);f.player.removeEffect(MobEffects.LUCK);
            h.assertTrue(EffectiveLuck.level(f.player)==0&&AccessoryRuntime.fixedPower(f.player)==25,"unequip retained luck or changed historical best-two fixed power");
            RewardClaimService.INSTANCE.issue(f.player,GOD,source,authored);h.assertValueEqual(RewardClaimState.get(f.player.server).find(claim.claimId()).orElseThrow().automaticRewards().get(0),saved,"changed luck rerolled settled reward");
            h.assertTrue(f.player.getInventory().countItem(net.minecraft.world.item.Items.PAPER)==13,"settled lucky reward was paid twice");
        }finally{field(LuckDefinitions.INSTANCE,"snapshot",previous);}h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_accessories",template="empty",batch="accessory_gameplay")
    public static void linkReducesOnlyOwnedLearnedOncePerCastAndMissRefundIsBounded(GameTestHelper h){try(var f=new Fixture(h)){
        f.grant(LINK);f.grant(LINK);f.learn();var rings=f.owned(LINK);AccessoryRuntime.equip(f.player,0,rings.get(0).id());AccessoryRuntime.equip(f.player,1,rings.get(1).id());
        h.assertTrue(AccessoryRuntime.linkTarget(f.player,LEARNED),"owned target could not be selected");
        var state=WeaponSkillState.get(f.player.server);long tick=SkillMana.now(f.player);
        state.trySpend(f.player.getUUID(),SkillExecutor.cooldownKey(SkillCastEvents.Source.LEARNED,LEARNED),0,400,tick-1);
        var mob=EntityType.ZOMBIE.create(h.getLevel());h.assertTrue(mob!=null,"target fixture");
        var context=new SkillCastEvents.Context(UUID.randomUUID(),f.player,SkillCastEvents.Source.WEAPON,id("weapon_cast"),40,100,true);
        AccessoryRuntime.started(new SkillCastEvents.Start(context));
        var accessoryState=AccessoryState.get(f.player.server);accessoryState.trigger(f.player.getUUID(),rings.get(0).id(),AccessoryEffect.Kind.CLEANSE_CC,1000,300000);
        AccessoryRuntime.equip(f.player,0,null);AccessoryRuntime.equip(f.player,1,null);AccessoryRuntime.linkTarget(f.player,null);
        long before=LearnedSkillRuntime.remainingTicks(f.player,LEARNED);for(int i=0;i<10;i++)AccessoryRuntime.hit(new SkillCastEvents.Hit(context,mob));
        h.assertTrue(LearnedSkillRuntime.remainingTicks(f.player,LEARNED)==before-80,"AOE/multihit triggered repeatedly or wrong base cooldown");
        h.assertTrue(accessoryState.remainingMillis(f.player.getUUID(),rings.get(0).id(),AccessoryEffect.Kind.CLEANSE_CC,1000)==300000,"link changed accessory cooldown");
        AccessoryRuntime.finished(new SkillCastEvents.Finished(context,10,false));AccessoryRuntime.hit(new SkillCastEvents.Hit(context,mob));
        h.assertTrue(LearnedSkillRuntime.remainingTicks(f.player,LEARNED)==before-80,"late hit retriggered finished cast");
        AccessoryRuntime.equip(f.player,0,rings.get(0).id());AccessoryRuntime.equip(f.player,1,rings.get(1).id());
        state.trySpend(f.player.getUUID(),"test:paid_miss",40,0,tick);double mana=SkillMana.mana(f.player);
        var miss=new SkillCastEvents.Context(UUID.randomUUID(),f.player,SkillCastEvents.Source.LEARNED,LEARNED,40,400,true);
        AccessoryRuntime.started(new SkillCastEvents.Start(miss));
        AccessoryRuntime.finished(new SkillCastEvents.Finished(miss,0,false));AccessoryRuntime.finished(new SkillCastEvents.Finished(miss,0,false));
        h.assertTrue(SkillMana.mana(f.player)==mana+20,"miss refund did not stack once or replay refunded twice");
        var buff=new SkillCastEvents.Context(UUID.randomUUID(),f.player,SkillCastEvents.Source.LEARNED,LEARNED,40,400,false);double after=SkillMana.mana(f.player);
        AccessoryRuntime.started(new SkillCastEvents.Start(buff));
        AccessoryRuntime.finished(new SkillCastEvents.Finished(buff,0,false));h.assertTrue(SkillMana.mana(f.player)==after,"non-attack got miss refund");mob.discard();
    }h.succeed();}
    @GameTest(templateNamespace="mythictrpg_accessories",template="empty",batch="accessory_gameplay")
    public static void cleansingUsesOneChargeAndNeverRemovesMechanics(GameTestHelper h){try(var f=new Fixture(h)){
        f.grant(CLEANSE);f.grant(CLEANSE);var rings=f.owned(CLEANSE);
        CrowdControlService.applyStun(f.player,100,true,false);h.assertTrue(CrowdControlService.isStunned(f.player),"unequipped accessory cleansed CC");
        AccessoryRuntime.equip(f.player,0,rings.get(0).id());h.assertTrue(!CrowdControlService.isStunned(f.player),"equipping ready amulet did not cleanse existing ordinary CC");
        AccessoryRuntime.equip(f.player,1,rings.get(1).id());
        long now=System.currentTimeMillis();var state=AccessoryState.get(f.player.server);
        h.assertTrue(state.remainingMillis(f.player.getUUID(),rings.get(0).id(),AccessoryEffect.Kind.CLEANSE_CC,now)>0
                &&state.remainingMillis(f.player.getUUID(),rings.get(1).id(),AccessoryEffect.Kind.CLEANSE_CC,now)==0,"one CC consumed both charges");
        CrowdControlService.applyStun(f.player,100,true,true);h.assertTrue(CrowdControlService.isStunned(f.player),"mechanic got cleansed");
        h.assertTrue(state.remainingMillis(f.player.getUUID(),rings.get(1).id(),AccessoryEffect.Kind.CLEANSE_CC,now)==0,"mechanic consumed charge");
        AccessoryRuntime.equip(f.player,1,rings.get(1).id());h.assertTrue(state.remainingMillis(f.player.getUUID(),rings.get(1).id(),AccessoryEffect.Kind.CLEANSE_CC,now)==0,"no-op equip consumed a charge");
        CrowdControlService.applyStun(f.player,100,true,false);h.assertTrue(CrowdControlService.isStunned(f.player),"ordinary cleanse erased mechanic source");
        h.assertTrue(state.remainingMillis(f.player.getUUID(),rings.get(1).id(),AccessoryEffect.Kind.CLEANSE_CC,System.currentTimeMillis())>0,"second ordinary CC did not use second charge");
        AccessoryRuntime.equip(f.player,0,null);AccessoryRuntime.equip(f.player,0,rings.get(0).id());h.assertTrue(!AccessoryRuntime.tryCleanse(f.player),"reequip reset cooldown or cleansed mechanic");
    }h.succeed();}
    @GameTest(templateNamespace="mythictrpg_accessories",template="empty",batch="accessory_gameplay")
    public static void finalDamageAndEmergencyThresholdUseActualHealth(GameTestHelper h){try(var f=new Fixture(h)){
        f.grant(MANA);f.grant(EMERGENCY);AccessoryRuntime.equip(f.player,0,f.owned(MANA).get(0).id());AccessoryRuntime.equip(f.player,1,f.owned(EMERGENCY).get(0).id());
        var enemy=EntityType.ZOMBIE.create(h.getLevel());h.assertTrue(enemy!=null,"enemy fixture");
        var pre=new LivingDamageEvent.Pre(f.player,new DamageContainer(f.player.damageSources().mobAttack(enemy),8));AccessoryRuntime.damage(pre);h.assertTrue(pre.getNewDamage()==7,"flat final reduction");
        var outgoing=new LivingDamageEvent.Pre(enemy,new DamageContainer(f.player.damageSources().playerAttack(f.player),8));AccessoryRuntime.damage(outgoing);h.assertTrue(outgoing.getNewDamage()==10,"flat outgoing bonus");
        f.player.setHealth(4);var hit=new LivingDamageEvent.Post(f.player,new DamageContainer(f.player.damageSources().mobAttack(enemy),2));AccessoryRuntime.damaged(hit);
        var state=AccessoryState.get(f.player.server);var ring=f.owned(EMERGENCY).get(0);h.assertTrue(state.remainingMillis(f.player.getUUID(),ring.id(),AccessoryEffect.Kind.EMERGENCY_HEAL,System.currentTimeMillis())>86390000,"crossing hit did not commit UTC24h cooldown");
        double heal=state.tickHealing(f.player.getUUID(),SkillMana.now(f.player)+1,true);h.assertTrue(Math.abs(heal-.5)<.00001,"emergency did not schedule gradual fraction-maxHP healing");
        AccessoryRuntime.equip(f.player,1,null);AccessoryRuntime.equip(f.player,1,ring.id());AccessoryRuntime.damaged(hit);
        h.assertTrue(Math.abs(state.tickHealing(f.player.getUUID(),SkillMana.now(f.player)+2,true)-.5)<.00001,"reequip duplicated active emergency heal");enemy.discard();
    }h.succeed();}
    @GameTest(templateNamespace="mythictrpg_accessories",template="empty",batch="accessory_jump_landing",timeoutTicks=100)
    public static void actualPlayerTicksRegenerateAndLandingRestoresExtraJumps(GameTestHelper h){
        var f=new Fixture(h);try{
            f.grant(JUMP);f.grant(JUMP);var rings=f.owned(JUMP);AccessoryRuntime.equip(f.player,0,rings.get(0).id());AccessoryRuntime.equip(f.player,1,rings.get(1).id());
            var floor=h.absolutePos(new net.minecraft.core.BlockPos(1,1,1));var previous=h.getLevel().getBlockState(floor);h.getLevel().setBlockAndUpdate(floor,net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            f.player.setNoGravity(true);f.player.setHealth(10);f.player.moveTo(floor.getX()+.5,floor.getY()+1,floor.getZ()+.5,0,0);f.player.setOnGround(true);f.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            f.player.doTick();float health=f.player.getHealth();h.assertTrue(Math.abs(health-10.2)<.001,"actual player tick did not regenerate stacked health");f.player.doTick();h.assertTrue(f.player.getHealth()==health,"duplicate tick accelerated passive regen");
            f.player.moveTo(floor.getX()+.5,floor.getY()+3,floor.getZ()+.5,0,0);f.player.setOnGround(false);
            h.assertTrue(AccessoryRuntime.jump(f.player),"first extra jump");h.assertTrue(!AccessoryRuntime.jump(f.player),"same tick jump replay");
            h.runAfterDelay(3,()->{try{
                h.assertTrue(AccessoryRuntime.jump(f.player),"second legitimate accessory did not add jump");
                h.runAfterDelay(3,()->{try{
                    h.assertTrue(!AccessoryRuntime.jump(f.player),"airborne charges exceeded equipped total");
                    // Embedded connections have no movement packets. Simulate a real downward collision,
                    // rather than setting onGround while no-gravity/zero movement can clear it in Player.tick.
                    f.player.setNoGravity(false);f.player.moveTo(floor.getX()+.5,floor.getY()+1.08,floor.getZ()+.5,0,0);f.player.setOnGround(false);
                    f.player.move(net.minecraft.world.entity.MoverType.SELF,new net.minecraft.world.phys.Vec3(0,-.2,0));
                    h.assertTrue(f.player.onGround()&&!f.player.level().noCollision(f.player.getBoundingBox().move(0,-.05,0)),"physical landing fixture did not collide with floor: y="+f.player.getY()+", onGround="+f.player.onGround());
                    f.player.setDeltaMovement(0,-.08,0);f.player.doTick();
                    h.assertTrue(AccessoryState.get(f.player.server).usedAirJumps(f.player.getUUID())==0,
                            "actual grounded tick did not reset air charges: onGround="+f.player.onGround()+", used="+AccessoryState.get(f.player.server).usedAirJumps(f.player.getUUID())+", now="+SkillMana.now(f.player)+", processed="+AccessoryState.get(f.player.server).lastTick(f.player.getUUID()));
                    f.player.moveTo(floor.getX()+.5,floor.getY()+3,floor.getZ()+.5,0,0);f.player.setOnGround(false);h.assertTrue(AccessoryRuntime.jump(f.player),"landing did not reset persisted air charges");
                    h.getLevel().setBlockAndUpdate(floor,previous);f.close();h.succeed();
                }catch(RuntimeException|Error failure){h.getLevel().setBlockAndUpdate(floor,previous);f.close();throw failure;}});
            }catch(RuntimeException|Error failure){h.getLevel().setBlockAndUpdate(floor,previous);f.close();throw failure;}});
        }catch(RuntimeException|Error failure){f.close();throw failure;}
    }

    private static final class Fixture implements AutoCloseable{
        final GameTestHelper h;final Object oldGods=field(GodDefinitionManager.INSTANCE,"snapshot"),oldLearned=field(LearnedSkillDefinitions.INSTANCE,"snapshot");
        final Map<ResourceLocation,AccessoryDefinition> old=AccessoryDefinitions.INSTANCE.snapshot();final ServerPlayer player;final EmbeddedChannel channel;
        Fixture(GameTestHelper h){this.h=h;
            var gods=GodDefinitionManager.INSTANCE.progressionSnapshot();var entries=new LinkedHashMap<>(gods.definitions());entries.put(GOD,new GodDefinition(2,Component.literal("Synthetic accessory giver"),id("origin"),id("faction"),Set.of(id("category")),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(GodRank.HIGH)));
            field(GodDefinitionManager.INSTANCE,"snapshot",new GodDefinitionManager.ProgressionSnapshot(entries,gods.unlockIndex(),gods.appearanceIndex(),gods.categoryIndex(),gods.generation()+1));
            AccessoryDefinitions.INSTANCE.apply(Map.of(MANA,definition(MANA,10,List.of(effect(AccessoryEffect.Kind.MAX_MANA,100),effect(AccessoryEffect.Kind.SPEED_FLAT,.01),effect(AccessoryEffect.Kind.LUCK,1),effect(AccessoryEffect.Kind.GOLD_BONUS_FRACTION,.05),effect(AccessoryEffect.Kind.DAMAGE_FLAT,2),effect(AccessoryEffect.Kind.DAMAGE_REDUCTION_FLAT,1))),
                    LINK,definition(LINK,4,List.of(effect(AccessoryEffect.Kind.LINK_KNOT,.1),effect(AccessoryEffect.Kind.MISS_REFUND,.25))),CLEANSE,definition(CLEANSE,3,List.of(new AccessoryEffect(AccessoryEffect.Kind.CLEANSE_CC,1,0,300000))),EMERGENCY,definition(EMERGENCY,15,List.of(new AccessoryEffect(AccessoryEffect.Kind.EMERGENCY_HEAL,.5,20,86400000))),JUMP,definition(JUMP,1,List.of(effect(AccessoryEffect.Kind.EXTRA_JUMPS,1),effect(AccessoryEffect.Kind.HEALTH_REGEN_PER_SECOND,2)))),null,null);
            field(LearnedSkillDefinitions.INSTANCE,"snapshot",Map.of(LEARNED,new LearnedSkillDefinition(LEARNED,"fixture learned",10,400,new WeaponSkillDefinition.SelfHeal(1),0)));
            var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(),"AccessoryOwner"),false);
            player=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channel=new EmbeddedChannel(connection);net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);player.server.getPlayerList().placeNewPlayer(connection,player,cookie);SkillMana.maximumProvider(AccessoryRuntime::maximumMana);
        }
        void grant(ResourceLocation id){var r=RewardClaimService.INSTANCE.issue(player,GOD,id("receipt_"+UUID.randomUUID()),new ResolvedQuestReward("fixture",List.of(new ProgressionRewardEntry(ProgressionRewardEntry.Kind.ACCESSORY,id)),List.of()));h.assertTrue(r.succeeded(),"accessory claim: "+r.reason());}
        void learn(){var r=RewardClaimService.INSTANCE.issue(player,GOD,id("learn_"+UUID.randomUUID()),new ResolvedQuestReward("fixture",List.of(new ProgressionRewardEntry(ProgressionRewardEntry.Kind.LEARNED,LEARNED)),List.of()));h.assertTrue(r.succeeded(),"learned claim: "+r.reason());}
        List<AccessoryRewardBridge.Entitlement> owned(ResourceLocation id){return AccessoryRuntime.entitlements(player).stream().filter(e->e.definitionId().equals(id)).toList();}
        @Override public void close(){player.server.getPlayerList().remove(player);channel.finishAndReleaseAll();AccessoryDefinitions.INSTANCE.apply(old,null,null);field(LearnedSkillDefinitions.INSTANCE,"snapshot",oldLearned);field(GodDefinitionManager.INSTANCE,"snapshot",oldGods);}
    }
    private static AccessoryDefinition definition(ResourceLocation id,double power,List<AccessoryEffect> effects){return new AccessoryDefinition(id,"fixture "+id.getPath(),ResourceLocation.parse("minecraft:amethyst_shard"),new CompoundTag(),power,effects);}
    private static AccessoryEffect effect(AccessoryEffect.Kind k,double v){return new AccessoryEffect(k,v,0,0);}
    private static ResourceLocation id(String path){return ResourceLocation.fromNamespaceAndPath("test","accessory_"+path);}
    private static Object field(Object owner,String name){try{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
    private static void field(Object owner,String name,Object value){try{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
}
