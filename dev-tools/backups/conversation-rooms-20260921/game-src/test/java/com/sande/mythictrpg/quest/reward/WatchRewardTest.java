package com.sande.mythictrpg.quest.reward;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import com.google.gson.JsonParser;
import java.util.*;

/** No Minecraft server, network, model or registry bootstrap. */
public final class WatchRewardTest {
    static int assertions;
    static final UUID A = new UUID(0,1), B = new UUID(0,2);
    static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mythictrpg",path); }
    static void check(boolean yes, String message) { assertions++; if (!yes) throw new AssertionError(message); }
    static RewardClaim claim(UUID player, String source, List<RewardEntry> auto, List<RewardChoiceOption> choices) {
        return new RewardClaim(UUID.randomUUID(),player,id("fortuna"),id(source),"시험",auto,choices,false,Optional.empty(),100);
    }
    public static void main(String[] args) {
        var reward = new WatchRewardEntry(id("fortuna"),"포르투나");
        check(RewardEntryCodec.load(RewardEntryCodec.save(reward)).equals(reward),"watch NBT roundtrip");
        check(RewardEntryCodec.parse(JsonParser.parseString("{\"type\":\"watch\",\"godId\":\"mythictrpg:fortuna\",\"displayName\":\"포르투나\"}").getAsJsonObject(),"test").equals(reward),"strict JSON type");
        var state = new RewardClaimState();
        var automatic = state.create(claim(A,"quest/a",List.of(reward),List.of()));
        check(!state.hasWatch(A,reward.godId()),"queued automatic claim is not ownership");
        state.markAutomaticGranted(automatic.claimId(),150);
        check(state.hasWatch(A,reward.godId()) && !state.hasWatch(B,reward.godId()),"only actual recipient");
        state.markAutomaticGranted(automatic.claimId(),200);
        var duplicate = state.create(claim(A,"quest/b",List.of(reward),List.of()));
        state.markAutomaticGranted(duplicate.claimId(),300);
        check(state.watchesFor(A).size()==1 && state.watchesFor(A).getFirst().grantGameTime()==150,"no stacking/reset");
        var choices = List.of(new RewardChoiceOption(id("watch"),"주시",List.of(reward)),
                new RewardChoiceOption(id("title"),"칭호",List.of(new TitleRewardEntry(id("title/test"),"시험"))));
        var choice = state.create(claim(B,"quest/choice",List.of(),choices));
        state.markAutomaticGranted(choice.claimId(),160);
        check(!state.hasWatch(B,reward.godId()),"unselected choice no entitlement");
        state.markSelected(choice.claimId(),id("title"),170);
        check(!state.hasWatch(B,reward.godId()),"different selection no entitlement");
        var choice2 = state.create(claim(B,"quest/choice2",List.of(),choices));
        state.markAutomaticGranted(choice2.claimId(),180); state.markSelected(choice2.claimId(),id("watch"),190);
        check(state.hasWatch(B,reward.godId()),"chosen actual recipient");
        var stored=state.save(new CompoundTag(),null);
        var loaded=RewardClaimState.load(stored,null);
        check(loaded.isWritable() && loaded.watchesFor(A).equals(state.watchesFor(A)) && loaded.watchesFor(B).equals(state.watchesFor(B)),"receipt and entitlement restart together");
        var old=new CompoundTag();old.putInt("dataVersion",1);old.put("claims",new net.minecraft.nbt.ListTag());
        check(RewardClaimState.load(old,null).isWritable(),"v1 migrates without inventing entitlement");
        // Receipt retention cannot erase a permanent acquired capability.
        stored.put("claims",new net.minecraft.nbt.ListTag());
        check(RewardClaimState.load(stored,null).hasWatch(A,reward.godId()),"pruned receipt retains ownership");
        var future=stored.copy();future.putInt("dataVersion",999);
        var rejected=RewardClaimState.load(future,null);
        check(!rejected.isWritable()&&rejected.save(new CompoundTag(),null).equals(future),"future schema preserved read-only");
        var missing=state.save(new CompoundTag(),null);missing.put("watches",new net.minecraft.nbt.ListTag());
        var corrupt=RewardClaimState.load(missing,null);
        check(!corrupt.isWritable()&&corrupt.save(new CompoundTag(),null).equals(missing),"delivered watch receipt without entitlement cannot silently load");
        var premature=new RewardClaimState();var pending=premature.create(claim(A,"quest/pending",List.of(),choices));
        try { premature.markSelected(pending.claimId(),id("watch"),100);throw new AssertionError("premature choice accepted"); }
        catch(IllegalStateException expected){check(!premature.hasWatch(A,reward.godId()),"choice before automatic claim processing rejected");}
        System.out.println("WatchRewardTest: " + assertions + " assertions passed");
    }
}
