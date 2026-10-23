package com.sande.mythictrpg.specialskill;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Real-time cooldowns and attempt-specific usage survive reconnect, item copies and server shutdown. */
public final class SpecialSkillState extends SavedData {
    public enum Result { READY, SUCCESS, COOLDOWN, RAID_LIMIT, DUPLICATE, UNAVAILABLE, INVALID }
    private record Key(UUID player,ResourceLocation skill) { }
    private static final class Usage {
        long nextAllowedMillis,lastTick=-1;
        final Map<UUID,Integer> raids=new LinkedHashMap<>();
    }
    private static final Factory<SpecialSkillState> FACTORY=new Factory<>(SpecialSkillState::new,SpecialSkillState::load);
    private final Map<Key,Usage> usages=new LinkedHashMap<>();
    private CompoundTag rejected;
    public static SpecialSkillState get(MinecraftServer server) {
        if(!server.isSameThread())throw new IllegalStateException("Special skills require server thread");
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY,"mythictrpg_special_skills");
    }
    public boolean isReady() { return rejected==null; }
    public long remainingMillis(UUID player,ResourceLocation skill,long now) {
        var use=usages.get(new Key(player,skill)); return use==null?0:Math.max(0,use.nextAllowedMillis-now);
    }
    public int raidUses(UUID player,ResourceLocation skill,UUID attempt) {
        var use=usages.get(new Key(player,skill)); return use==null?0:use.raids.getOrDefault(attempt,0);
    }
    public Result canUse(UUID player,ResourceLocation skill,UUID attempt,SpecialSkillDefinition.UseLimits limits,long now,long tick) {
        if(!isReady())return Result.UNAVAILABLE;
        if(player==null||skill==null||attempt==null||now<0||tick<0||now>Long.MAX_VALUE-limits.cooldownSeconds()*1000L)return Result.INVALID;
        var use=usages.get(new Key(player,skill));
        if(use==null)return usages.size()<65536?Result.READY:Result.UNAVAILABLE;
        if(use.lastTick>=tick)return Result.DUPLICATE;
        if(use.nextAllowedMillis>now)return Result.COOLDOWN;
        if(limits.perRaidUses()>0&&use.raids.getOrDefault(attempt,0)>=limits.perRaidUses())return Result.RAID_LIMIT;
        if(limits.perRaidUses()>0&&!use.raids.containsKey(attempt)&&use.raids.size()>=512)return Result.UNAVAILABLE;
        return Result.READY;
    }
    public Result commit(UUID player,ResourceLocation skill,UUID attempt,SpecialSkillDefinition.UseLimits limits,long now,long tick) {
        var result=canUse(player,skill,attempt,limits,now,tick); if(result!=Result.READY)return result;
        var use=usages.computeIfAbsent(new Key(player,skill),ignored->new Usage());
        if(limits.perRaidUses()>0)use.raids.merge(attempt,1,Math::addExact);
        if(limits.cooldownSeconds()>0)use.nextAllowedMillis=now+limits.cooldownSeconds()*1000L;
        use.lastTick=tick; setDirty(); return Result.SUCCESS;
    }
    /** Terminal attempts cannot be re-entered, so only their obsolete per-attempt counters may be pruned. */
    public void pruneTerminalAttempts(Set<UUID> terminal) {
        boolean changed=false; for(var use:usages.values())changed|=use.raids.keySet().removeIf(terminal::contains);
        if(changed)setDirty();
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        if(rejected!=null)return rejected.copy();
        tag.putInt("dataVersion",1); var rows=new ListTag();
        usages.forEach((key,use)-> {
            var row=new CompoundTag(); row.putUUID("player",key.player()); row.putString("skill",key.skill().toString());
            row.putLong("nextAllowedMillis",use.nextAllowedMillis); row.putLong("lastTick",use.lastTick);
            var raids=new ListTag(); use.raids.forEach((id,count)-> { var r=new CompoundTag();r.putUUID("id",id);r.putInt("count",count);raids.add(r); });
            row.put("raids",raids);rows.add(row);
        });tag.put("usages",rows);return tag;
    }
    static SpecialSkillState load(CompoundTag tag,HolderLookup.Provider registries) {
        var state=new SpecialSkillState();
        try {
            fields(tag,"dataVersion","usages");
            if(!tag.contains("dataVersion",Tag.TAG_INT)||tag.getInt("dataVersion")!=1)throw new IllegalArgumentException("Unsupported special skill save");
            for(var raw:list(tag,"usages",65536)) {
                var row=(CompoundTag)raw; fields(row,"player","skill","nextAllowedMillis","lastTick","raids");
                if(!row.hasUUID("player")||!row.contains("skill",Tag.TAG_STRING)||!row.contains("nextAllowedMillis",Tag.TAG_LONG)
                        ||!row.contains("lastTick",Tag.TAG_LONG)||row.getLong("nextAllowedMillis")<0||row.getLong("lastTick")< -1)
                    throw new IllegalArgumentException("Invalid special skill usage");
                String id=row.getString("skill"); if(!id.contains(":")||id.length()>256)throw new IllegalArgumentException("Invalid skill ID");
                var use=new Usage();use.nextAllowedMillis=row.getLong("nextAllowedMillis");use.lastTick=row.getLong("lastTick");
                for(var value:list(row,"raids",512)) {
                    var raid=(CompoundTag)value; fields(raid,"id","count");
                    if(!raid.hasUUID("id")||!raid.contains("count",Tag.TAG_INT)||raid.getInt("count")<1||raid.getInt("count")>100
                            ||use.raids.putIfAbsent(raid.getUUID("id"),raid.getInt("count"))!=null)throw new IllegalArgumentException("Invalid duplicate raid usage");
                }
                if(state.usages.putIfAbsent(new Key(row.getUUID("player"),ResourceLocation.parse(id)),use)!=null)
                    throw new IllegalArgumentException("Duplicate special skill usage");
            }
        }catch(RuntimeException error) { state.usages.clear();state.rejected=tag.copy();MythicTrpg.LOGGER.error("Special skill save rejected; original preserved",error); }
        return state;
    }
    private static ListTag list(CompoundTag tag,String key,int max) {
        if(!(tag.get(key)instanceof ListTag list)||list.size()>max||!list.isEmpty()&&list.getElementType()!=Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid special skill list "+key);return list;
    }
    private static void fields(CompoundTag tag,String... keys) { if(!tag.getAllKeys().equals(Set.of(keys)))throw new IllegalArgumentException("Invalid special skill fields"); }
}
