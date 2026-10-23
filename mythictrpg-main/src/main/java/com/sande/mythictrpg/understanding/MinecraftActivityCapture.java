package com.sande.mythictrpg.understanding;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.activity.*;
import com.sande.mythictrpg.gameplay.ledger.*;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import com.sande.mythictrpg.gameplay.observation.MatureCropHarvestClassifier;
import com.sande.mythictrpg.raid.TrialRaidArenaService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** Opt-in self-analysis capture. Bounded positive facts; no AFK journal, no observer fan-out, no gameplay writes. */
@EventBusSubscriber(modid=MythicTrpg.MOD_ID)
public final class MinecraftActivityCapture {
    static final int FLUSH_TICKS=200;
    private static final Map<MinecraftServer,Map<UUID,Entry>> STATES=new IdentityHashMap<>();
    private record Site(long utc,long day,String dimension,String biome,ActionRecord.Position position) { }
    private record PendingSupport(SupportSignal signal,UUID run,long tick,Site site) { }
    private static final class Entry {
        final ServerPlayer player;
        final ActionLedgerService.Runtime runtime;
        final ActivityEvidence.Buffer buffer=new ActivityEvidence.Buffer();
        final EnumMap<ActivityEvidence.Signal,Site> sites=new EnumMap<>(ActivityEvidence.Signal.class);
        final EnumMap<SupportSignal.Kind,PendingSupport> support=new EnumMap<>(SupportSignal.Kind.class);
        long controlOrder=-1,lastFlush,nextProbe;
        int fish,sleep; boolean active,loading;
        String dimension,biome;long actionToken;
        Entry(ServerPlayer player,ActionLedgerService.Runtime runtime) {
            this.player=player;this.runtime=runtime;baseline();
        }
        void baseline() {
            fish=player.getStats().getValue(Stats.CUSTOM.get(Stats.FISH_CAUGHT));
            sleep=player.getStats().getValue(Stats.CUSTOM.get(Stats.SLEEP_IN_BED));
            dimension=player.level().dimension().location().toString();biome=biome(player);actionToken=player.getLastActionTime();
            lastFlush=player.server.overworld().getGameTime();
        }
    }
    private MinecraftActivityCapture() { }
    private static Entry entry(ServerPlayer p) {
        if(!p.server.isSameThread())return null;
        var r=ActionLedgerService.current(p.server);
        if(r==null||r.ledger()==null||r.ledger().status().state()!=AsyncActionLedger.State.READY)return null;
        var states=STATES.computeIfAbsent(p.server,k->new HashMap<>());
        var e=states.get(p.getUUID());
        if(e==null||e.player!=p||e.runtime!=r) {e=new Entry(p,r);states.put(p.getUUID(),e);}
        return e;
    }
    private static boolean current(Entry e) {
        return ActionLedgerService.current(e.player.server)==e.runtime
                &&STATES.getOrDefault(e.player.server,Map.of()).get(e.player.getUUID())==e
                &&e.player.server.getPlayerList().getPlayer(e.player.getUUID())==e.player;
    }
    /** Only an actually committed control record can enroll/pause capture. Delayed older reads cannot undo it. */
    public static void controlChanged(ServerPlayer player,ActionRecord control) {
        var e=entry(player);if(e==null||control==null)return;
        if(!control.worldId().equals(e.runtime.worldId())||!control.event().actorId().equals(player.getUUID())
                ||control.event().type()!=ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL||control.sequence()<=e.controlOrder)return;
        e.controlOrder=control.sequence();e.active="START".equals(control.event().payload().get("operation"));
        e.buffer.clear();e.sites.clear();e.support.clear();e.baseline();
    }
    public static boolean enrolled(ServerPlayer player) {
        var e=entry(player);
        return e!=null&&e.active&&!player.isCreative()&&!player.isSpectator()
                &&!player.level().dimension().equals(TrialRaidArenaService.DIMENSION);
    }
    public static long analysisStart(ServerPlayer player) {var e=entry(player);return e==null?-1:e.controlOrder;}
    /** Positive, bounded facts; consumers supply a game-confirmed run or an admitted dialogue turn. */
    public static void recordSupport(ServerPlayer player,SupportSignal.Kind kind,UUID run,String context) {
        if(!enrolled(player))return;
        var e=entry(player);var old=e.support.get(kind);
        String dimension=player.level().dimension().location().toString();
        if(old!=null&&(!Objects.equals(run,old.run())||!dimension.equals(old.site().dimension()))) {drain(e);old=null;}
        var pos=player.blockPosition();
        var site=new Site(System.currentTimeMillis(),player.level().getDayTime(),dimension,biome(player),new ActionRecord.Position(pos.getX(),pos.getY(),pos.getZ()));
        e.support.put(kind,new PendingSupport(new SupportSignal(kind,old==null?1:Math.min(1_000_000,old.signal().count()+1),context),run,
                player.server.overworld().getGameTime(),site));
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var server=event.getServer();if(server.getTickCount()%20!=0)return;
        for(var p:server.getPlayerList().getPlayers()) {
            try {
                var e=entry(p);if(e==null)continue;
                long now=server.overworld().getGameTime();
                if(e.controlOrder<0&&!e.loading&&now>=e.nextProbe) {
                    e.loading=true;e.nextProbe=now+100;
                    e.runtime.ledger().understandingControl(p.getUUID()).whenComplete((control,error)->server.execute(()->{
                        if(!current(e))return;
                        e.loading=false;
                        if(error==null) {
                            if(control==null&&e.controlOrder<0)e.controlOrder=0;
                            else controlChanged(p,control);
                        }
                    }));
                }
                sampleStats(e);
                String dimension=p.level().dimension().location().toString(),biome=biome(p);
                if(e.active) {
                    if(visitTransition(e.dimension,e.biome,dimension,biome,e.actionToken!=p.getLastActionTime(),p.isPassenger()||p.isSleeping()))
                        record(p,ActivityEvidence.Signal.BIOME_CHANGE,1);
                }
                // Reset/reconnect changes establish a new baseline, never retroactive history.
                e.dimension=dimension;e.biome=biome;e.actionToken=p.getLastActionTime();
                if(now<e.lastFlush||now-e.lastFlush>=FLUSH_TICKS)flush(p);
            } catch(RuntimeException failure) { failed(p,failure); }
        }
    }
    public static void record(ServerPlayer player,ActivityEvidence.Signal signal,int amount) {
        try {
            var e=entry(player);
            if(e==null||!e.active||amount<=0||player.isCreative()||player.isSpectator()
                    ||player.level().dimension().equals(TrialRaidArenaService.DIMENSION))return;
            String dimension=player.level().dimension().location().toString();
            // A compact interval must belong to one dimension, even if a portal is crossed between samples.
            if(e.sites.values().stream().anyMatch(site->!site.dimension().equals(dimension)))drain(e);
            long tick=player.server.overworld().getGameTime();
            e.buffer.add(signal,Math.min(1_000_000,amount),tick);
            var pos=player.blockPosition();
            e.sites.put(signal,new Site(System.currentTimeMillis(),player.level().getDayTime(),
                    player.level().dimension().location().toString(),biome(player),new ActionRecord.Position(pos.getX(),pos.getY(),pos.getZ())));
        } catch(RuntimeException failure) { failed(player,failure); }
    }
    public static void blockRemoved(ServerPlayer player,BlockState block,boolean success) {
        if(!success)return;
        // Immature crops are not evidence of harvesting or mining preference.
        if(crop(block)) {
            if(MatureCropHarvestClassifier.classify(block).isPresent())record(player,ActivityEvidence.Signal.HARVEST_CROP,1);
        } else record(player,ActivityEvidence.Signal.REMOVE_BLOCK,1);
    }
    public static void blockPlaced(ServerPlayer player,BlockState block) {
        record(player,crop(block)?ActivityEvidence.Signal.PLANT_CROP:ActivityEvidence.Signal.PLACE_BLOCK,1);
    }
    private static boolean crop(BlockState state) {
        return state.getBlock() instanceof CropBlock||state.getBlock() instanceof NetherWartBlock||state.getBlock() instanceof CocoaBlock;
    }
    @SubscribeEvent public static void crafted(PlayerEvent.ItemCraftedEvent event) {
        if(event.getEntity() instanceof ServerPlayer player)
            record(player,ActivityEvidence.Signal.CRAFT_ITEM,event.getCrafting().getCount());
    }
    /** Enqueue before PAUSE/logout/shutdown; original FIFO/quota/durable diagnostics remain in charge. */
    public static void flush(ServerPlayer player) {
        try {
        var e=STATES.getOrDefault(player.server,Map.of()).get(player.getUUID());
        if(e==null||e.player!=player)return;
        if(ActionLedgerService.current(player.server)!=e.runtime){e.buffer.clear();e.sites.clear();e.support.clear();return;}
        // Include the last sub-second stat delta on UI refresh, PAUSE, logout and orderly shutdown.
        sampleStats(e);
        drain(e);
        }catch(RuntimeException failure){failed(player,failure);}
    }
    private static void sampleStats(Entry e) {
        var p=e.player;
        int fish=p.getStats().getValue(Stats.CUSTOM.get(Stats.FISH_CAUGHT));
        int sleep=p.getStats().getValue(Stats.CUSTOM.get(Stats.SLEEP_IN_BED));
        if(e.active&&e.dimension.equals(p.level().dimension().location().toString())) {
            if(fish>e.fish)record(p,ActivityEvidence.Signal.FISH_CAUGHT,fish-e.fish);
            if(sleep>e.sleep)record(p,ActivityEvidence.Signal.SLEEP_IN_BED,sleep-e.sleep);
        }
        e.fish=fish;e.sleep=sleep;
    }
    private static void drain(Entry e) {
        var player=e.player;
        var rows=e.buffer.drain();e.lastFlush=player.server.overworld().getGameTime();
        for(var row:rows) {
            var site=e.sites.remove(row.signal());
            if(site==null||ActionLedgerService.current(player.server)!=e.runtime)continue;
            var type=ActionRecord.Type.PLAYER_ACTIVITY_FACT;
            var draft=new ActionRecord.Draft(UUID.randomUUID(),e.runtime.captureSession(),e.runtime.nextCaptureOrder(),
                    "mythictrpg:detail/player_activity_fact",1,player.getUUID(),new ActionRecord.Subject("ACTIVITY",ActivityEvidence.SUBJECT,null),
                    site.utc(),row.lastTick(),site.day(),site.dimension(),site.position(),type,"COMPLETED",row.payload(),
                    "mythictrpg:admin_only_unprojected",new ActionRecord.Details(site.biome().isEmpty()?null:site.biome(),"AT_TRANSITION",null,Set.of(player.getUUID())));
            e.runtime.ledger().submit(draft); // Rejection is a ledger gap, never a gameplay penalty or invented durable fact.
        }
        for(var pending:e.support.values()) {
            var site=pending.site();
            e.runtime.ledger().submit(new ActionRecord.Draft(UUID.randomUUID(),e.runtime.captureSession(),e.runtime.nextCaptureOrder(),
                    "mythictrpg:detail/player_support_signal",1,player.getUUID(),new ActionRecord.Subject("ACTIVITY",SupportSignal.SUBJECT,null),
                    site.utc(),pending.tick(),site.day(),site.dimension(),site.position(),ActionRecord.Type.PLAYER_SUPPORT_SIGNAL,"COMPLETED",
                    pending.signal().payload(),"mythictrpg:admin_only_unprojected",
                    new ActionRecord.Details(site.biome().isEmpty()?null:site.biome(),"AT_TRANSITION",pending.run(),Set.of(player.getUUID()))));
        }
        e.support.clear();
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if(event.getEntity() instanceof ServerPlayer p) {
            try {flush(p);}catch(RuntimeException failure){failed(p,failure);}
            var map=STATES.get(p.server);if(map!=null)map.remove(p.getUUID());
        }
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        for(var p:event.getServer().getPlayerList().getPlayers()) {
            try {flush(p);}catch(RuntimeException failure){failed(p,failure);}
        }
        STATES.remove(event.getServer());
    }
    public static SupportPresence presence(ServerPlayer p) {
        var activity=PlayerActivityService.INSTANCE.find(p.server,p.getUUID());
        return SupportPresence.resolve(activity.isPresent(),activity.map(a->a.state()==PlayerActivityState.IDLE).orElse(false),
                p.isSleeping(),p.fishing!=null,p.containerMenu!=p.inventoryMenu);
    }
    static boolean visitTransition(String priorDimension,String priorBiome,String dimension,String biome,boolean inputChanged,boolean passive) {
        return inputChanged&&!passive&&!priorBiome.isEmpty()&&!biome.isEmpty()
                &&priorDimension.equals(dimension)&&!priorBiome.equals(biome);
    }
    private static String biome(ServerPlayer p) {
        return p.level().getBiome(p.blockPosition()).unwrapKey().map(k->k.location().toString()).orElse("");
    }
    private static void failed(ServerPlayer player,RuntimeException failure) {
        var r=ActionLedgerService.current(player.server);
        if(r!=null&&r.ledger()!=null)r.ledger().gap("ACTIVITY_CAPTURE_FAILED");
        MythicTrpg.LOGGER.warn("Self activity capture unavailable; gameplay unchanged",failure);
    }
}
