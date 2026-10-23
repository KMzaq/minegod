package com.sande.mythictrpg.accessory;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Equipment references reward entitlements, never physical replacement copies. Cooldowns use UTC milliseconds. */
public final class AccessoryState extends SavedData {
    private static final int MAX_PLAYERS=16384,MAX_COOLDOWNS=1024;
    private static final Factory<AccessoryState> FACTORY=new Factory<>(AccessoryState::new,AccessoryState::load);
    private final Map<UUID,Account> accounts=new LinkedHashMap<>();
    private CompoundTag rejected;
    private static final class Account {
        UUID first,second; ResourceLocation linkTarget; long lastHealTick=-1,lastAirJump=-100;int airJumps;
        final Map<String,Long> cooldowns=new LinkedHashMap<>(); final List<Healing> healing=new ArrayList<>();
    }
    private record Healing(UUID entitlement,double amountPerTick,int remainingTicks){}
    public static AccessoryState get(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("Accessory state requires server thread");return server.overworld().getDataStorage().computeIfAbsent(FACTORY,"mythictrpg_accessories");}
    public boolean isReady(){return rejected==null;}
    private Account account(UUID player,boolean create){
        if(!isReady()||player==null)return null;var value=accounts.get(player);
        if(value==null&&create&&accounts.size()<MAX_PLAYERS){value=new Account();accounts.put(player,value);setDirty();}return value;
    }
    public UUID equipped(UUID player,int slot){var a=account(player,false);return a==null?null:slot==0?a.first:slot==1?a.second:null;}
    public boolean equip(UUID player,int slot,UUID entitlement){
        if(slot<0||slot>1)return false;var a=account(player,true);if(a==null)return false;
        UUID other=slot==0?a.second:a.first;if(entitlement!=null&&entitlement.equals(other))return false;
        if(slot==0)a.first=entitlement;else a.second=entitlement;setDirty();return true;
    }
    public Optional<ResourceLocation> linkTarget(UUID player){var a=account(player,false);return a==null?Optional.empty():Optional.ofNullable(a.linkTarget);}
    public boolean linkTarget(UUID player,ResourceLocation id){var a=account(player,true);if(a==null)return false;a.linkTarget=id;setDirty();return true;}
    private static String key(UUID entitlement,AccessoryEffect.Kind kind){return entitlement+"/"+kind.name();}
    public long remainingMillis(UUID player,UUID entitlement,AccessoryEffect.Kind kind,long now){var a=account(player,false);return a==null?0:Math.max(0,a.cooldowns.getOrDefault(key(entitlement,kind),0L)-now);}
    public boolean canTrigger(UUID player,UUID entitlement,AccessoryEffect.Kind kind,long now,long duration){
        if(entitlement==null||now<0||duration<0||duration>31536000000L||now>Long.MAX_VALUE-duration)return false;
        var a=account(player,true);return a!=null&&remainingMillis(player,entitlement,kind,now)==0
                &&(a.cooldowns.containsKey(key(entitlement,kind))||a.cooldowns.values().stream().filter(end->end>now).count()<MAX_COOLDOWNS);
    }
    public boolean trigger(UUID player,UUID entitlement,AccessoryEffect.Kind kind,long now,long duration){
        if(!canTrigger(player,entitlement,kind,now,duration))return false;
        var a=account(player,true);if(a==null)return false;
        a.cooldowns.entrySet().removeIf(e->e.getValue()<=now);
        if(a.cooldowns.size()>=MAX_COOLDOWNS&&!a.cooldowns.containsKey(key(entitlement,kind)))return false;
        if(duration>0)a.cooldowns.put(key(entitlement,kind),now+duration);setDirty();return true;
    }
    public boolean startHealing(UUID player,UUID entitlement,double total,int ticks){
        var a=account(player,true);if(a==null||a.healing.size()>=16||entitlement==null||!Double.isFinite(total)||total<=0||total>1_000_000||ticks<1||ticks>72000)return false;
        a.healing.add(new Healing(entitlement,total/ticks,ticks));setDirty();return true;
    }
    public boolean canStartHealing(UUID player){var a=account(player,true);return a!=null&&a.healing.size()<16;}
    public long lastTick(UUID player){var a=account(player,false);return a==null?-1:a.lastHealTick;}
    public int usedAirJumps(UUID player){var a=account(player,false);return a==null?0:a.airJumps;}
    public void grounded(UUID player,long tick){var a=account(player,true);if(a!=null&&(a.airJumps!=0||a.lastAirJump>tick-3)){a.airJumps=0;a.lastAirJump=Math.max(-100,tick-3);setDirty();}}
    public boolean jump(UUID player,long tick,int limit){var a=account(player,true);if(a==null||limit<1||a.airJumps>=limit||tick<0||tick-a.lastAirJump<3)return false;a.airJumps++;a.lastAirJump=tick;setDirty();return true;}
    public double tickHealing(UUID player,long gameTick,boolean alive){
        var a=account(player,true);if(a==null||gameTick<0||gameTick<=a.lastHealTick)return 0;
        a.lastHealTick=gameTick;setDirty();if(!alive){if(!a.healing.isEmpty()){a.healing.clear();setDirty();}return 0;}
        if(a.healing.isEmpty())return 0;double amount=0;var next=new ArrayList<Healing>();
        for(var h:a.healing){amount+=h.amountPerTick();if(h.remainingTicks()>1)next.add(new Healing(h.entitlement(),h.amountPerTick(),h.remainingTicks()-1));}
        a.healing.clear();a.healing.addAll(next);setDirty();return amount;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){
        if(!isReady())return rejected.copy();tag.putInt("dataVersion",1);ListTag players=new ListTag();
        accounts.forEach((id,a)->{
            CompoundTag p=new CompoundTag();p.putUUID("playerId",id);if(a.first!=null)p.putUUID("first",a.first);if(a.second!=null)p.putUUID("second",a.second);
            p.putString("linkTarget",a.linkTarget==null?"":a.linkTarget.toString());p.putLong("lastHealTick",a.lastHealTick);
            p.putInt("airJumps",a.airJumps);p.putLong("lastAirJump",a.lastAirJump);
            ListTag cooldowns=new ListTag();a.cooldowns.forEach((key,end)->{CompoundTag c=new CompoundTag();c.putString("key",key);c.putLong("end",end);cooldowns.add(c);});p.put("cooldowns",cooldowns);
            ListTag healing=new ListTag();a.healing.forEach(h->{CompoundTag v=new CompoundTag();v.putUUID("entitlement",h.entitlement());v.putDouble("amountPerTick",h.amountPerTick());v.putInt("remainingTicks",h.remainingTicks());healing.add(v);});p.put("healing",healing);players.add(p);
        });tag.put("players",players);return tag;
    }
    static AccessoryState load(CompoundTag tag,HolderLookup.Provider registries){
        var state=new AccessoryState();try{
            fields(tag,Set.of("dataVersion","players"),Set.of());if(!tag.contains("dataVersion",Tag.TAG_INT)||tag.getInt("dataVersion")!=1)throw new IllegalArgumentException("Accessory dataVersion");
            for(Tag entry:list(tag,"players",MAX_PLAYERS)){
                CompoundTag p=(CompoundTag)entry;fields(p,Set.of("playerId","linkTarget","lastHealTick","cooldowns","healing","airJumps","lastAirJump"),Set.of("first","second"));
                if(!p.hasUUID("playerId")||!p.contains("linkTarget",Tag.TAG_STRING)||!p.contains("lastHealTick",Tag.TAG_LONG)||p.getLong("lastHealTick")< -1)throw new IllegalArgumentException("Accessory account fields");
                Account a=new Account();a.first=uuid(p,"first");a.second=uuid(p,"second");if(a.first!=null&&a.first.equals(a.second))throw new IllegalArgumentException("One entitlement in two slots");
                String link=p.getString("linkTarget");a.linkTarget=link.isEmpty()?null:ResourceLocation.parse(link);a.lastHealTick=p.getLong("lastHealTick");
                if(!p.contains("airJumps",Tag.TAG_INT)||!p.contains("lastAirJump",Tag.TAG_LONG)||p.getInt("airJumps")<0||p.getInt("airJumps")>16||p.getLong("lastAirJump")< -100)throw new IllegalArgumentException("Accessory air jumps");a.airJumps=p.getInt("airJumps");a.lastAirJump=p.getLong("lastAirJump");
                for(Tag row:list(p,"cooldowns",MAX_COOLDOWNS)){var c=(CompoundTag)row;fields(c,Set.of("key","end"),Set.of());String key=c.getString("key");if(!c.contains("key",Tag.TAG_STRING)||!c.contains("end",Tag.TAG_LONG)||c.getLong("end")<0||key.length()>128||!validCooldownKey(key)||a.cooldowns.putIfAbsent(key,c.getLong("end"))!=null)throw new IllegalArgumentException("Accessory cooldown");}
                for(Tag row:list(p,"healing",16)){var h=(CompoundTag)row;fields(h,Set.of("entitlement","amountPerTick","remainingTicks"),Set.of());double amount=h.getDouble("amountPerTick");int ticks=h.getInt("remainingTicks");if(!h.hasUUID("entitlement")||!h.contains("amountPerTick",Tag.TAG_DOUBLE)||!h.contains("remainingTicks",Tag.TAG_INT)||!Double.isFinite(amount)||amount<=0||amount>1_000_000||ticks<1||ticks>72000)throw new IllegalArgumentException("Accessory healing");a.healing.add(new Healing(h.getUUID("entitlement"),amount,ticks));}
                if(state.accounts.putIfAbsent(p.getUUID("playerId"),a)!=null)throw new IllegalArgumentException("Duplicate accessory account");
            }
        }catch(RuntimeException bad){state.accounts.clear();state.rejected=tag.copy();MythicTrpg.LOGGER.error("Accessory state rejected; original data retained",bad);}return state;
    }
    private static boolean validCooldownKey(String key){try{int slash=key.indexOf('/');UUID.fromString(key.substring(0,slash));AccessoryEffect.Kind.valueOf(key.substring(slash+1));return true;}catch(RuntimeException bad){return false;}}
    private static UUID uuid(CompoundTag tag,String key){if(!tag.contains(key))return null;if(!tag.hasUUID(key))throw new IllegalArgumentException("Accessory UUID field");return tag.getUUID(key);}
    private static ListTag list(CompoundTag tag,String key,int cap){if(!(tag.get(key)instanceof ListTag value)||value.size()>cap||!value.isEmpty()&&value.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("Accessory list "+key);return value;}
    private static void fields(CompoundTag tag,Set<String> required,Set<String> optional){if(!tag.getAllKeys().containsAll(required)||tag.getAllKeys().stream().anyMatch(k->!required.contains(k)&&!optional.contains(k)))throw new IllegalArgumentException("Accessory saved fields");}
}
