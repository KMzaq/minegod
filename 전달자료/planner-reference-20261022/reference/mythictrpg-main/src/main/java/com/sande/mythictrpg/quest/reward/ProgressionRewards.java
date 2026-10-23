package com.sande.mythictrpg.quest.reward;

import com.sande.mythictrpg.accessory.AccessoryDefinitions;
import com.sande.mythictrpg.skills.LearnedSkillDefinitions;
import com.sande.mythictrpg.specialskill.SpecialSkillDefinitions;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import net.minecraft.resources.ResourceLocation;

/** Common definition and provider-rank preflight for every existing reward-claim producer. */
public final class ProgressionRewards {
    private ProgressionRewards() { }
    public static String equipmentError(ResourceLocation godId,RewardEntry entry) {
        var god=GodDefinitionManager.INSTANCE.find(godId).orElse(null);
        if(god==null||god.rank().isEmpty())return null;
        var reward=entry instanceof NpcRewardEntry item?item:entry instanceof ResolvedLuckyRewardEntry lucky?lucky.selection().reward():null;
        if(reward==null)return null;
        var policy=com.sande.mythictrpg.power.CombatPowerPolicyManager.INSTANCE.current().policy();
        if(policy!=null&&policy.items().stream().anyMatch(rule->rule.ignored()&&rule.itemId().equals(reward.itemId())&&rule.components().equals(reward.components())))return null;
        var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(reward.itemId());
        int minimum=item instanceof net.minecraft.world.item.ArmorItem?1:
                item instanceof net.minecraft.world.item.SwordItem||item instanceof net.minecraft.world.item.ProjectileWeaponItem
                ||item instanceof net.minecraft.world.item.TridentItem||item instanceof net.minecraft.world.item.MaceItem?2:0;
        return god.rank().orElseThrow().ordinal()<minimum?"Provider rank cannot grant equipment "+reward.itemId():null;
    }
    public static String error(ResourceLocation godId,ProgressionRewardEntry entry) {
        var god=GodDefinitionManager.INSTANCE.find(godId).orElse(null);
        int minimum=switch(entry.kind()) { case LEARNED->1; case ACCESSORY->3; case SPECIAL->4; };
        if(god==null || god.rank().isEmpty()) return "Reward provider rank is not authored: "+godId;
        if(god.rank().orElseThrow().ordinal()<minimum) return "Provider rank cannot grant "+entry.kind();
        return definitionError(godId,entry);
    }
    public static String definitionError(ResourceLocation godId,ProgressionRewardEntry entry) {
        return switch(entry.kind()) {
            case LEARNED -> LearnedSkillDefinitions.INSTANCE.find(entry.definitionId()).isPresent()?null:"Unknown learned skill: "+entry.definitionId();
            case ACCESSORY -> AccessoryDefinitions.INSTANCE.find(entry.definitionId()).isPresent()?null:"Unknown accessory: "+entry.definitionId();
            case SPECIAL -> {
                var skill=SpecialSkillDefinitions.INSTANCE.find(entry.definitionId()).orElse(null);
                yield skill==null?"Unknown special skill: "+entry.definitionId():!skill.providerGodId().equals(godId)?"Special skill belongs to another provider":null;
            }
        };
    }
}
