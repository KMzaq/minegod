package com.sande.mythictrpg.rumor;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** Integration API for a FUTURE courier mod/entity and authored event producer. Never spawns an entity.
 * No AI Proposal, network packet or general chat log can call this service on behalf of gameplay. */
public final class CourierRumorService {
    private static final Map<MinecraftServer,CourierRumorService> INSTANCES=new IdentityHashMap<>();
    private record DeathAttempt(LivingDeathEvent event,long tick) {}
    private final MinecraftServer server;private final RumorSavedData data;private final CourierSettings settings;private final CourierEngine engine;
    private final Map<UUID,DeathAttempt> deaths=new LinkedHashMap<>();private long nextWarning;
    private CourierRumorService(MinecraftServer server,CourierSettings settings,RumorSavedData data) {
        this.server=server;this.settings=settings;this.data=data;
        engine=data.access(server,ledger->new CourierEngine(ledger,settings,new CourierEngine.Probe(){
            public boolean available(UUID courier,UUID subject){return liveCourier(courier)!=null;}
            public boolean receiverAvailable(String god){return GodDefinitionManager.INSTANCE.definitions().containsKey(ResourceLocation.parse(god));}
            public boolean witnessed(UUID courier,CourierEngine.Event event,CourierSettings.Rule rule){
                var bird=liveCourier(courier);var player=server.getPlayerList().getPlayer(event.subject());
                if(bird==null||player==null||player.isSpectator()||player.level()!=bird.level()||!bird.level().dimension().location().toString().equals(event.dimension()))return false;
                Vec3 point=Vec3.atCenterOf(new BlockPos(event.x(),event.y(),event.z()));double radius=rule.radius()*rule.radius();
                if(bird.distanceToSqr(player)>radius||bird.position().distanceToSqr(point)>radius)return false;
                var level=(ServerLevel)bird.level();
                return unobstructed(level,bird.getEyePosition(),player.getEyePosition(),bird)
                        &&unobstructed(level,bird.getEyePosition(),point,bird);
            }
        },()->server.overworld().getGameTime()));
    }
    public static void started(ServerStartedEvent event) {
        var server=event.getServer();requireThread(server);INSTANCES.remove(server);
        var settings=CourierSettings.load(server.getServerDirectory().resolve("config/mythictrpg/rumor-courier.json"));
        if(!settings.enabled()||MemoryFoundationSettings.mode()!=MemoryFoundationSettings.Mode.RUMOR_TEST)return;
        var data=RumorSavedData.get(server);if(data.ready())INSTANCES.put(server,new CourierRumorService(server,settings,data));
    }
    public static void stopped(ServerStoppedEvent event){requireThread(event.getServer());INSTANCES.remove(event.getServer());}
    private static void requireThread(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("courier game operation requires server thread");}
    private static CourierRumorService current(MinecraftServer server){requireThread(server);var r=INSTANCES.get(server);return r!=null&&r.data.ready()
            &&MemoryFoundationSettings.mode()==MemoryFoundationSettings.Mode.RUMOR_TEST?r:null;}
    private LivingEntity liveCourier(UUID id) {
        for(var level:server.getAllLevels()){
            var entity=level.getEntity(id);
            if(entity instanceof LivingEntity living&&living.isAlive()&&!living.isRemoved()
                    &&settings.courierTypes().contains(BuiltInRegistries.ENTITY_TYPE.getKey(living.getType()).toString()))return living;
        }return null;
    }
    private static boolean unobstructed(ServerLevel level,Vec3 from,Vec3 to,Entity observer) {
        // A ray through an unloaded intermediate chunk must not synchronously load it.
        int minX=BlockPos.containing(Math.min(from.x,to.x),0,0).getX()>>4;
        int maxX=BlockPos.containing(Math.max(from.x,to.x),0,0).getX()>>4;
        int minZ=BlockPos.containing(0,0,Math.min(from.z,to.z)).getZ()>>4;
        int maxZ=BlockPos.containing(0,0,Math.max(from.z,to.z)).getZ()>>4;
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)if(!level.getChunkSource().hasChunk(x,z))return false;
        return level.clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,observer)).getType()==HitResult.Type.MISS;
    }
    /** Called AFTER an authored future spawn event added its entity to the world. Idempotent repeat is rejected.
     * Existing active binding is never replaced; explicit new binding after confirmed death creates a new epoch. */
    public static boolean bindExisting(ServerPlayer subject,LivingEntity courier) {
        var r=current(subject.server);if(r==null||subject.server.getPlayerList().getPlayer(subject.getUUID())!=subject||r.liveCourier(courier.getUUID())!=courier)return false;
        return r.data.access(r.server,ledger->r.engine.bind(subject.getUUID(),courier.getUUID()));
    }
    /** An authoritative game producer has ALREADY approved this exact excerpt and audience for observation.
     * This is not an API for passing raw private dialogue, system prompts, hidden quest facts, or AI-generated events.
     * The producer owns sourceId/revision and must revoke the returned roots if that evidence is invalidated. */
    public static List<CourierEngine.CandidateInput> captureApproved(ServerPlayer subject,LivingEntity courier,
            UUID sourceId,long sourceRevision,ResourceLocation eventType,CourierSettings.Source source,BlockPos position,
            String disclosedExcerpt,Set<UUID> mentionedSubjects,Set<UUID> disclosureAudience) {
        var r=current(subject.server);if(r==null||subject.server.getPlayerList().getPlayer(subject.getUUID())!=subject||r.liveCourier(courier.getUUID())!=courier)return List.of();
        return r.data.access(r.server,ledger->{var binding=ledger.courier(subject.getUUID());if(binding==null||!binding.entity().equals(courier.getUUID()))return List.of();
            return r.engine.observe(new CourierEngine.Event(sourceId,sourceRevision,subject.getUUID(),mentionedSubjects,disclosureAudience,eventType.toString(),source,
                    subject.level().dimension().location().toString(),position.getX(),position.getY(),position.getZ(),disclosedExcerpt,System.currentTimeMillis(),r.server.overworld().getGameTime()));});
    }
    /** A delayed, bounded candidate is applied on the server thread, never treated as proof of observation. */
    public static boolean publishCandidate(MinecraftServer server,CourierEngine.Candidate candidate) {
        var r=current(server);return r!=null&&r.data.access(server,ledger->r.engine.publish(candidate));
    }
    public static Optional<RumorLedger.Courier> binding(MinecraftServer server,UUID subject) {
        var r=current(server);return r==null?Optional.empty():r.data.access(server,ledger->Optional.ofNullable(ledger.courier(subject)));
    }
    /** Actual game producer hook; observer identity is resolved from the existing binding, never chosen by AI. */
    static List<CourierEngine.CandidateInput> captureBound(ServerPlayer subject,UUID source,long revision,ResourceLocation type,
            CourierSettings.Source kind,BlockPos position,String excerpt,Set<UUID> audience) {
        var r=current(subject.server);if(r==null)return List.of();
        var binding=binding(subject.server,subject.getUUID()).orElse(null);
        var bird=binding==null||binding.blocked()?null:r.liveCourier(binding.entity());
        if(bird==null)return List.of();
        return captureApproved(subject,bird,source,revision,type,kind,position,excerpt,Set.of(subject.getUUID()),audience);
    }
    static String ruleId(MinecraftServer server,UUID root) {
        var r=current(server);if(r==null)return "";
        return r.data.access(server,l->{var e=l.evidence(root);return e==null||e.proof()==null?"":e.proof().ruleId();});
    }
    record DialogueWitness(CourierEngine.Event opening,UUID courier,UUID epoch,Set<String> rules) { }
    static DialogueWitness witnessDialogue(ServerPlayer player,UUID source,String text,Set<UUID> audience) {
        var r=current(player.server);if(r==null)return null;var p=player.blockPosition();
        var e=new CourierEngine.Event(source,1,player.getUUID(),Set.of(player.getUUID()),audience,"mythictrpg:ordinary_god_dialogue",
                CourierSettings.Source.DISCLOSED_DIALOGUE,player.level().dimension().location().toString(),p.getX(),p.getY(),p.getZ(),text,System.currentTimeMillis(),r.server.overworld().getGameTime());
        return r.data.access(r.server,l->{var binding=l.courier(player.getUUID());var rules=r.engine.witnessedRules(e);
            return binding==null||rules.isEmpty()?null:new DialogueWitness(e,binding.entity(),binding.epoch(),rules);});
    }
    static List<CourierEngine.CandidateInput> captureDialogue(ServerPlayer player,DialogueWitness witness,String exchange) {
        var r=current(player.server);if(r==null||witness==null)return List.of();var opening=witness.opening();
        if(!opening.subject().equals(player.getUUID())||!opening.dimension().equals(player.level().dimension().location().toString()))return List.of();
        long now=r.server.overworld().getGameTime();var p=player.blockPosition();
        return r.data.access(r.server,l->{var binding=l.courier(player.getUUID());
            if(binding==null||binding.blocked()||!binding.entity().equals(witness.courier())||!binding.epoch().equals(witness.epoch()))return List.of();
            var rules=r.settings.rules().stream().filter(rule->witness.rules().contains(rule.fingerprint())&&now>=opening.gameTick()
                    &&now-opening.gameTick()<=rule.maximumAgeTicks()).map(CourierSettings.Rule::fingerprint).collect(java.util.stream.Collectors.toUnmodifiableSet());
            return r.engine.observe(new CourierEngine.Event(opening.sourceId(),1,opening.subject(),opening.mentionedSubjects(),opening.audience(),opening.eventType(),opening.source(),opening.dimension(),
                    p.getX(),p.getY(),p.getZ(),exchange,System.currentTimeMillis(),now),rules);
        });
    }
    static boolean revokeUnpublished(MinecraftServer server,UUID root) {
        var r=current(server);return r!=null&&r.data.access(server,l->!l.claimed(root)&&l.revoke(root));
    }
    public static List<CourierEngine.CandidateInput> pendingCandidates(MinecraftServer server,int limit) {
        var r=current(server);return r==null?List.of():r.data.access(server,ledger->r.engine.pendingCandidates(limit));
    }
    public static boolean revoke(MinecraftServer server,UUID root) {
        var r=current(server);return r!=null&&r.data.access(server,ledger->ledger.revoke(root));
    }
    /** Record the cancelable attempt only. The final event state and actual death are checked at tick end. */
    public static void death(LivingDeathEvent event) {
        if(!(event.getEntity().level() instanceof ServerLevel level))return;var r=current(level.getServer());if(r==null)return;
        if(r.data.access(r.server,ledger->ledger.hasCourier(event.getEntity().getUUID()))&&r.deaths.size()<RumorLedger.LIMIT)
            r.deaths.putIfAbsent(event.getEntity().getUUID(),new DeathAttempt(event,r.server.overworld().getGameTime()));
    }
    /** Optional adapter for an entity's POST-confirmation lifecycle; never call from pre-death or unload. */
    public static boolean confirmDeath(MinecraftServer server,LivingEntity courier) {
        var r=current(server);if(r==null||courier.level().getServer()!=server||!confirmedDead(courier))return false;
        return r.data.access(server,ledger->r.engine.confirmedDeath(courier.getUUID()));
    }
    private static boolean confirmedDead(LivingEntity entity){return entity.isDeadOrDying()&&(entity.deathTime>0||entity.getRemovalReason()==Entity.RemovalReason.KILLED);}
    public static void tick(ServerTickEvent.Post event) {
        var r=current(event.getServer());if(r==null)return;long tick=r.server.overworld().getGameTime();
        r.data.access(r.server,ledger->{var iterator=r.deaths.entrySet().iterator();while(iterator.hasNext()){
            var pending=iterator.next();var attempt=pending.getValue();
            if(attempt.event().isCanceled()){iterator.remove();continue;}
            if(confirmedDead(attempt.event().getEntity())){r.engine.confirmedDeath(pending.getKey());iterator.remove();}
            else if(tick-attempt.tick()>2)iterator.remove();
        }r.engine.deliver();return null;});
        if(tick>=r.nextWarning){var state=r.data.snapshot();int usage=Math.max(Math.max(state.evidence().size(),state.couriers().size()),Math.max(state.pending().size(),state.receipts().size()));
            if(usage>=Math.ceil(RumorLedger.LIMIT*.9)){String message="[MythAI] 소문 저장 항목이 상한의 90% 이상입니다. 관리자는 소문 보관 상한 증설을 검토해 주세요. 자동 삭제하지 않습니다.";
                MythicTrpg.LOGGER.warn("{} used={} limit={}",message,usage,RumorLedger.LIMIT);r.server.getPlayerList().broadcastSystemMessage(Component.literal(message),false);}
            r.nextWarning=tick+1200;
        }
    }
    static List<RumorLedger.HeardRumor> heard(MinecraftServer server,RumorLedger ledger,UUID subject,String god,Set<UUID> audience) {
        var r=current(server);if(r!=null)return r.engine.heard(subject,god,audience);
        return ledger.heard(subject,god,audience).stream().filter(h->ledger.evidence(h.rootId()).proof()==null).toList();
    }
    static Optional<RumorLedger.HeardRumor> heardOne(MinecraftServer server,UUID subject,String god,UUID root,Set<UUID> audience) {
        var r=current(server);return r==null?Optional.empty():r.engine.heardOne(subject,god,root,audience);
    }
    public static String status(MinecraftServer server) {
        var r=current(server);if(r==null)return "Courier rumors OFF (explicit rules/types and RUMOR_TEST required; automatic spawn/respawn unsupported)";
        var state=r.data.snapshot();return "couriers="+state.couriers().size()+", evidence="+state.evidence().size()+", pending="+state.pending().size()+", receipts="+state.receipts().size()+"; automaticSpawn=false; automaticRespawn=false";
    }
}
