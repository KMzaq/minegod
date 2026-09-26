package com.sande.mythictrpg.gameplay.ledger.detail;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.ledger.*;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Read-only capture, no watch fan-out for newly added types until their disclosure policy is authored. */
public final class DetailCapture {
    private DetailCapture() {}
    public static boolean enabled(ServerPlayer player) {
        var r=ActionLedgerService.current(player.server);
        return r!=null && r.detailSettings().enabled() && r.detailSettings().routineDiagnostics() && r.ledger()!=null;
    }
    public static ActionRecord.Subject entity(Entity e) { return new ActionRecord.Subject("ENTITY",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),e.getUUID()); }
    public static ActionRecord.Subject block(BlockState s) { return new ActionRecord.Subject("BLOCK",BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString(),null); }
    public static MovementSamples.Point point(ServerPlayer p) { return new MovementSamples.Point(p.level().dimension().location().toString(),p.getX(),p.getY(),p.getZ(),p.server.overworld().getGameTime()); }
    public static UUID record(ServerPlayer player,BlockPos pos,ActionRecord.Subject subject,ActionRecord.Type type,String outcome,Map<String,String> payload) {
        return recordAt(player,player.serverLevel(),pos,subject,type,outcome,payload);
    }
    public static UUID recordAt(ServerPlayer player,net.minecraft.server.level.ServerLevel level,BlockPos pos,ActionRecord.Subject subject,ActionRecord.Type type,String outcome,Map<String,String> payload) {
        if(!enabled(player) && !RecordingPolicy.important(type)) return null;
        var r=ActionLedgerService.current(player.server);
        if(r == null || r.ledger() == null || !r.detailSettings().enabled()) return null;
        try {
            UUID id=UUID.randomUUID();
            var draft = new ActionRecord.Draft(id,r.captureSession(),r.nextCaptureOrder(),"mythictrpg:detail/"+type.name().toLowerCase(Locale.ROOT),1,
                    player.getUUID(),subject,System.currentTimeMillis(),level.getGameTime(),level.getDayTime(),
                    level.dimension().location().toString(),new ActionRecord.Position(pos.getX(),pos.getY(),pos.getZ()),type,outcome,payload,
                    "mythictrpg:admin_only_unprojected", new ActionRecord.Details(
                        level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse(null),
                        "AT_TRANSITION",null,Set.of(player.getUUID())));
            var receipt = r.ledger().submit(draft);
            com.sande.mythictrpg.gameplay.watch.GodWatchRuntime.observed(player.server,draft,receipt);
            return id; // Occurrence reference, NOT a durability acknowledgement.
        } catch(RuntimeException failure) { r.ledger().gap("DETAIL_CAPTURE_FAILED"); MythicTrpg.LOGGER.warn("Detail capture unavailable; game unchanged",failure); return null; }
    }
    public static final class Attempt {
        final UUID id; final ServerPlayer player; final BlockPos pos; final ActionRecord.Subject subject; final float health;
        boolean removed; String denied="";
        Attempt(UUID id,ServerPlayer p,BlockPos pos,ActionRecord.Subject s,float hp) {this.id=id;player=p;this.pos=pos;subject=s;health=hp;}
    }
    private static final ThreadLocal<Deque<Attempt>> BLOCK=new ThreadLocal<>(), ATTACK=new ThreadLocal<>();
    private static Deque<Attempt> stack(ThreadLocal<Deque<Attempt>> key) { var v=key.get();if(v==null){v=new ArrayDeque<>();key.set(v);}return v; }
    public static void beginBlock(ServerPlayer p,BlockPos pos,BlockState state) {
        if(!enabled(p)&&BLOCK.get()==null)return;
        var s=block(state); var id=record(p,pos,s,ActionRecord.Type.BLOCK_BREAK_ATTEMPT,"ATTEMPTED",Map.of("phase","destroyBlock"));
        if(id!=null || BLOCK.get()!=null) stack(BLOCK).push(new Attempt(id,p,pos.immutable(),s,0));
    }
    public static void cancelledBlock(ServerPlayer p,BlockPos pos,boolean cancelled) {
        var q=BLOCK.get();if(q!=null&&!q.isEmpty()&&q.peek().player==p&&q.peek().pos.equals(pos)&&cancelled)q.peek().denied="CANCELLED";
    }
    public static void removedBlock(ServerPlayer p,BlockPos pos,BlockState state,boolean removed) {
        if(!enabled(p)&&BLOCK.get()==null)return;
        var q=BLOCK.get(); var a=q==null||q.isEmpty()?null:q.peek();
        if(a!=null&&(a.player!=p||!a.pos.equals(pos)))a=null;
        if(a!=null)a.removed|=removed;
        if(removed)record(p,pos,block(state),ActionRecord.Type.BLOCK_REMOVED,"COMPLETED",Map.of("quantity","1","attempt_id",a==null||a.id==null?"UNLINKED":a.id.toString(),"result","block_removed_not_item_acquisition"));
    }
    public static void endBlock(ServerPlayer p,BlockPos pos,boolean returned) {
        var q=BLOCK.get(); if(q==null||q.isEmpty())return; var a=q.pop();if(q.isEmpty())BLOCK.remove();
        if(a.id==null||a.player!=p||!a.pos.equals(pos))return;
        record(p,pos,a.subject,ActionRecord.Type.BLOCK_BREAK_RESULT,a.removed?"REMOVED":a.denied.isEmpty()?"NOT_REMOVED":a.denied,
                Map.of("attempt_id",a.id.toString(),"method_return",Boolean.toString(returned),"result","not_item_acquisition"));
    }
    public static void beginAttack(ServerPlayer p,Entity target) {
        if(!enabled(p)&&ATTACK.get()==null)return;
        var s=entity(target);var id=record(p,target.blockPosition(),s,ActionRecord.Type.ATTACK_ATTEMPT,"ATTEMPTED",Map.of("phase","player_melee"));
        if(id!=null||ATTACK.get()!=null)stack(ATTACK).push(new Attempt(id,p,target.blockPosition(),s,target instanceof LivingEntity e?e.getHealth():Float.NaN));
    }
    public static void deniedAttack(ServerPlayer p,boolean cancelled,boolean accepted) {
        var q=ATTACK.get();if(q!=null&&!q.isEmpty()&&q.peek().player==p&&!accepted)q.peek().denied=cancelled?"CANCELLED":"DENIED_BY_ITEM";
    }
    public static void endAttack(ServerPlayer p,Entity target) {
        var q=ATTACK.get();if(q==null||q.isEmpty())return;var a=q.pop();if(q.isEmpty())ATTACK.remove();if(a.id==null||a.player!=p)return;
        boolean healthLoss=target instanceof LivingEntity e && e.getHealth()<a.health;
        record(p,target.blockPosition(),a.subject,ActionRecord.Type.ATTACK_RESULT,!a.denied.isEmpty()?a.denied:healthLoss?"HEALTH_LOSS_OBSERVED":"RETURNED_NO_CONFIRMED_HEALTH_LOSS",
                Map.of("attempt_id",a.id.toString(),"result","melee_return_not_kill_or_loot; damage has separate receipt"));
    }
    /** Exceptions may bypass RETURN. Expire unmatched trace state at tick boundary, never invent a result. */
    public static void clearIncomplete() {
        for(var key:List.of(BLOCK,ATTACK)) {var q=key.get();if(q!=null) for(var a:q){var r=ActionLedgerService.current(a.player.server);if(a.id!=null&&r!=null&&r.ledger()!=null)r.ledger().gap("INCOMPLETE_DETAIL_ATTEMPT");}key.remove();}
        var teleports=TELEPORT.get();if(teleports!=null&&!teleports.isEmpty()){var r=ActionLedgerService.current(teleports.peek().player().server);if(r!=null&&r.ledger()!=null)r.ledger().gap("INCOMPLETE_TELEPORT_TRACE");}TELEPORT.remove();
    }
    private record Teleport(ServerPlayer player,MovementSamples.Point from) {}
    private static final ThreadLocal<Deque<Teleport>> TELEPORT=new ThreadLocal<>();
    public static void beginTeleport(ServerPlayer p) {
        if(!enabled(p))return;var q=TELEPORT.get();if(q==null){q=new ArrayDeque<>();TELEPORT.set(q);}q.push(new Teleport(p,point(p)));
    }
    public static void endTeleport(ServerPlayer p) {
        var q=TELEPORT.get();if(q==null||q.isEmpty())return;var prior=q.pop();
        boolean nestedSamePlayer=q.stream().anyMatch(t->t.player()==p);
        if(q.isEmpty())TELEPORT.remove();if(prior.player()!=p||nestedSamePlayer)return;
        var to=point(p);record(p,p.blockPosition(),new ActionRecord.Subject("LOCATION",to.dimension(),null),ActionRecord.Type.TELEPORT_RESULT,
                to.samePosition(prior.from())?"NO_POSITION_CHANGE":"POSITION_CHANGED",Map.of("from_dimension",prior.from().dimension(),"from_xyz",prior.from().coordinates(),
                        "to_dimension",to.dimension(),"to_xyz",to.coordinates(),"scope","ServerPlayer.teleportTo/teleportRelative; not inferred from distance"));
    }
}
