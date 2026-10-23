package com.sande.mythictrpg.raid;

import com.sande.mythictrpg.content.*;
import com.sande.mythictrpg.condition.builtin.*;
import com.sande.mythictrpg.quest.reward.CurrencyRewardEntry;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.gametest.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import static com.sande.mythictrpg.content.ContentSystemsGameTests.*;

@GameTestHolder("mythictrpg_content_systems")
@PrefixGameTestTemplate(false)
public final class RaidCountermeasureGameTests {
    @GameTest(templateNamespace="mythictrpg_content_systems",template="empty",timeoutTicks=150)
    public static void actualDamageConsumesOnlyMatchingPreparedTool(GameTestHelper h) throws Exception {
        var channels=new ArrayList<EmbeddedChannel>();var player=connect(h,"ToolOwner",channels);
        var outsider=connect(h,"ToolOther",channels);
        var snapshot=ContentDefinitions.INSTANCE.snapshot(); var state=RaidState.get(player.server);
        RaidState.Attempt attempt=null;
        var mob=EntityType.ZOMBIE.create(h.getLevel());
        try {
            var raid=id("counter_raid");var arenaId=id("counter_arena");var feet=h.absolutePos(new net.minecraft.core.BlockPos(2,2,2));
            var at=Vec3.atBottomCenterOf(feet);player.setPos(at);outsider.setPos(at.add(0,0,1));
            var raidJson=com.google.gson.JsonParser.parseString(RaidGameTests.fixtureJson()).getAsJsonObject();
            raidJson.getAsJsonArray("arenas").set(0,new com.google.gson.JsonPrimitive(arenaId.toString()));
            var definition=RaidDefinition.parse(raid,raidJson);
            var components=new net.minecraft.nbt.CompoundTag();components.putInt("minecraft:custom_model_data",31);
            var rule=new ContentDefinitions.Countermeasure(id("counter"),raid,ResourceLocation.parse("minecraft:mob_attack"),
                    ResourceLocation.parse("minecraft:feather"),true,0.5,components);
            var prepared=new com.sande.mythictrpg.quest.reward.NpcRewardEntry(rule.item(),2,rule.components()).createStack(player.registryAccess());
            var field=ContentDefinitions.class.getDeclaredField("snapshot");field.setAccessible(true);
            field.set(ContentDefinitions.INSTANCE,new ContentDefinitions.Snapshot(Map.of(),Map.of(),Map.of(rule.id(),rule)));
            attempt=state.create(player.getUUID(),UUID.randomUUID(),definition,Set.of(player.getUUID()),player.server.overworld().getGameTime());
            attempt.status=RaidState.Status.ACTIVE;
            attempt.members.get(player.getUUID()).returnPoint=RaidState.ReturnPoint.of(player);
            attempt.arena=RaidDefinition.parseArena(arenaId,com.google.gson.JsonParser.parseString(
                    RaidGameTests.arenaJson(at,h.getLevel().dimension().location())).getAsJsonObject());
            mob.setPos(at.add(1,0,0));mob.setNoAi(true);h.getLevel().addFreshEntity(mob);
            mob.getPersistentData().putUUID(RaidRuntime.RAID_TAG,attempt.id);attempt.spawnedEntities.add(mob.getUUID());attempt.bossId=mob.getUUID();
            // Reloads must not replace a run's authored rules, including after SavedData reconstruction.
            field.set(ContentDefinitions.INSTANCE,ContentDefinitions.Snapshot.empty());
            var saved=state.save(new net.minecraft.nbt.CompoundTag(),h.getLevel().registryAccess());
            var restored=RaidState.load(saved,h.getLevel().registryAccess());
            check(restored.ready()&&restored.find(attempt.id).orElseThrow().countermeasures.equals(List.of(rule)),"frozen tool policy did not survive reload/restart");
            var old=saved.copy();old.getList("attempts",net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).remove("countermeasures");
            check(RaidState.load(old,h.getLevel().registryAccess()).ready(),"legacy raid snapshot was rejected");
            var corrupt=saved.copy();corrupt.getList("attempts",net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0)
                    .getList("countermeasures",net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).putDouble("multiplier",-1);
            check(!RaidState.load(corrupt,h.getLevel().registryAccess()).ready(),"corrupt frozen policy was accepted");
            player.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.FEATHER,2));
            var plain=new LivingDamageEvent.Pre(player,new DamageContainer(player.damageSources().mobAttack(mob),8));
            RaidCountermeasures.damage(plain);
            check(plain.getNewDamage()==8&&player.getOffhandItem().getCount()==2,"plain item impersonated an authored tool variant");
            player.setItemSlot(EquipmentSlot.OFFHAND,prepared.copy());
            var environmental=new LivingDamageEvent.Pre(player,new DamageContainer(player.damageSources().fall(),8));
            RaidCountermeasures.damage(environmental);
            check(environmental.getNewDamage()==8&&player.getOffhandItem().getCount()==2,"environment consumed a tool");
            outsider.setItemSlot(EquipmentSlot.OFFHAND,prepared.copy());
            var otherHit=new LivingDamageEvent.Pre(outsider,new DamageContainer(player.damageSources().mobAttack(mob),8));
            RaidCountermeasures.damage(otherHit);
            check(otherHit.getNewDamage()==8&&outsider.getOffhandItem().getCount()==2,"nonparticipant got tool protection");
            var hit=new LivingDamageEvent.Pre(player,new DamageContainer(player.damageSources().mobAttack(mob),8));
            RaidCountermeasures.damage(hit);
            check(hit.getNewDamage()==4&&player.getOffhandItem().getCount()==1,"matching tool did not apply/consume once");
            player.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.STICK));
            var noTool=new LivingDamageEvent.Pre(player,new DamageContainer(player.damageSources().mobAttack(mob),8));
            RaidCountermeasures.damage(noTool);check(noTool.getNewDamage()==8,"unprepared player got protection");
            player.setItemSlot(EquipmentSlot.OFFHAND,prepared.copy());player.setHealth(20);player.invulnerableTime=0;
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            // New real connections have sixty ticks of vanilla spawn protection; this fixture tests combat after admission.
            var spawnProtection=ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");spawnProtection.setAccessible(true);spawnProtection.setInt(player,0);
            check(player.hurt(player.damageSources().mobAttack(mob),8),"actual damage path rejected hit");
            check(player.getHealth()==16&&player.getOffhandItem().getCount()==1,"actual damage event did not apply countermeasure");
            h.succeed();
        } finally {
            var field=ContentDefinitions.class.getDeclaredField("snapshot");field.setAccessible(true);field.set(ContentDefinitions.INSTANCE,snapshot);
            if(attempt!=null){state.attempts.remove(attempt.id);state.changed();}
            if(mob!=null)mob.discard();disconnect(player,channels);disconnect(outsider,List.of());
        }
    }
}
