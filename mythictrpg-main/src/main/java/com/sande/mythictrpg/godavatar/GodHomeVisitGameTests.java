package com.sande.mythictrpg.godavatar;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.godavatar.visit.*;
import com.sande.mythictrpg.data.world.MythicWorldState;
import com.sande.mythictrpg.quest.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@GameTestHolder("mythictrpg_home_visit")
@PrefixGameTestTemplate(false)
public final class GodHomeVisitGameTests {
    private static final ResourceLocation GOD = ResourceLocation.parse("mythictrpg:demeter");
    private static final ResourceLocation TRACK = ResourceLocation.parse("mythictrpg:visit_fixture");
    private static final String POLICY = """
            {"formatVersion":1,"dialogue":true,"autonomous":true,
             "minimumProgress":{"mythictrpg:visit_fixture":30},"evaluationPolicies":[],
             "checkIntervalTicks":6000,"cooldownTicks":12000,"requestTimeoutTicks":200,
             "travelTimeoutTicks":400,"maximumEvaluationAgeTicks":24000}
            """;
    @GameTest(templateNamespace="mythictrpg_home_visit", template="empty", timeoutTicks=500)
    public static void boundedVisitActuallyWalksAndRevalidates(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper);
        helper.runAtTickTime(499, f::close);
        try {
            f.prepare();
            require(!f.service.canRequest(f.player, GOD, false), "Progress gate was ignored");
            MythicWorldState.get(f.player.server).setQuestProgress(TRACK, 30);
            require(f.service.canRequest(f.player, GOD, false), "Valid existing avatar not eligible");
            require(f.service.request(f.player, GOD, Optional.empty()), "Autonomous request not submitted");
            require(f.request.candidates().size() == 2, "Did not offer both owner buildings");
            require(f.request.candidates().stream().noneMatch(c -> c.name().equals("Someone else's home")), "Other owner leaked");
            require(f.request.dialogue().isEmpty(), "Autonomous context borrowed a room");
            require(!f.service.request(f.player, GOD, Optional.empty()), "Concurrent request overwrote pending request");
            f.complete(UUID.randomUUID());
            require(!f.avatar.busyForVisit(), "Unoffered UUID executed");
            f.respawn();
            f.request();
            f.avatar.moveTo(f.feet.offset(0, 0, 2));
            f.complete(f.destination.id());
            require(!f.avatar.homeVisitStatus().equals("TRAVELLING"), "Late AI overwrote a newer movement order");
            f.respawn();
            f.request();
            f.policies.set(GodVisitPolicies.INSTANCE, Map.of());
            f.complete(f.destination.id());
            require(!f.avatar.busyForVisit(), "Removed visit policy still executed");
            f.policies.set(GodVisitPolicies.INSTANCE, Map.of(GOD, GodVisitPolicy.decode(GOD, JsonParser.parseString(POLICY).getAsJsonObject())));
            f.respawn();
            f.request();
            MythicWorldState.get(f.player.server).setQuestProgress(TRACK, 0);
            f.complete(f.destination.id());
            require(!f.avatar.busyForVisit(), "Progress revoked during inference still executed");
            MythicWorldState.get(f.player.server).setQuestProgress(TRACK, 30);
            f.respawn();
            f.request();
            f.future.complete(new GodVisitPlanner.Decision(Optional.empty())); f.tick();
            require(!f.avatar.busyForVisit(), "NONE still started movement");
            f.respawn();
            f.request(); f.complete(f.destination.id());
            require(f.avatar.homeVisitStatus().equals("TRAVELLING"), "Allowed selection did not start navigation");
            require(f.avatar.position().distanceToSqr(Vec3.atBottomCenterOf(f.feet)) < 0.01, "Visit teleported avatar");
            CompoundTag saved = f.avatar.saveWithoutId(new CompoundTag());
            require(saved.getUUID("HomeVisitStructure").equals(f.destination.id()) && saved.getLong("NextHomeVisitDecision") > 0,
                    "Visit order/cooldown not persisted");
            f.avatar.readAdditionalSaveData(saved);
            require(f.avatar.homeStructure().equals(f.destination.id()) && f.avatar.nextVisitDecision() > 0, "Persisted visit was not restored");
            helper.startSequence().thenWaitUntil(() -> helper.assertTrue(f.avatar.homeVisitStatus().equals("ARRIVED"),
                    "Waiting for actual path navigation and arrival: " + f.avatar.homeVisitStatus() + " pos=" + f.avatar.blockPosition()
                            + " target=" + f.avatar.homeDestination() + " entityTick=" + f.avatar.tickCount))
                    .thenExecute(() -> {
                        try {
                            require(f.destination.region().contains(f.avatar.blockPosition()), "Arrival was outside the building");
                            require(!f.service.canRequest(f.player, GOD, false), "Cooldown disappeared on arrival");
                            f.respawn(); f.request(); f.complete(f.destination.id());
                            require(f.avatar.homeVisitStatus().equals("TRAVELLING"), "Second fixture did not start");
                            PlayerConstructionState.get(f.player.server).delete(f.player.getUUID(), f.destination.name());
                            require(!f.service.travelCurrent(f.avatar), "Deleted building remained a valid visit");
                            f.respawn(); f.future = null;
                        } catch (Throwable failure) { f.close(); throw failure; }
                    }).thenWaitUntil(() -> helper.assertTrue(f.future != null, "Autonomous tick has not considered a candidate yet"))
                    .thenExecute(() -> {
                        try { require(f.request.trigger().equals("AUTONOMOUS"), "Automatic scheduler used wrong trigger");
                            f.future.complete(new GodVisitPlanner.Decision(Optional.empty())); f.tick();
                            require(!f.avatar.busyForVisit(), "Automatic decline caused movement");
                        } finally { f.close(); }
                    }).thenSucceed();
        } catch (Throwable failure) { f.close(); throw failure; }
    }
    @GameTest(templateNamespace="mythictrpg_home_visit", template="empty", timeoutTicks=500, batch="home_visit_dialogue")
    public static void actualRoomProposalSchedulesScopedVisit(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper);
        var router = com.sande.mythictrpg.ai.api.RoomConversationEngineRouter.INSTANCE;
        var engineField = field(router.getClass(), "engine"); var installedField = field(router.getClass(), "installed");
        Object previousEngine = engineField.get(router); boolean previousInstalled = installedField.getBoolean(router);
        try {
            f.prepare(); MythicWorldState.get(f.player.server).setQuestProgress(TRACK,30);
            f.policies.set(GodVisitPolicies.INSTANCE, Map.of(GOD, GodVisitPolicy.decode(GOD,
                    JsonParser.parseString(POLICY.replace("\"autonomous\":true", "\"autonomous\":false")).getAsJsonObject())));
            engineField.set(router, new com.sande.mythictrpg.ai.api.RoomConversationEngine() {
                public CompletableFuture<Result> respond(Request r) {
                    return CompletableFuture.completedFuture(new Result(r.roomId(),r.revision(),r.turnId(),
                            List.of(new Speech(GOD,"네 건축물 중 들를 만한 곳이 있는지 생각해 보마.")),
                            "[{\"type\":\"npc_visit_request\",\"parameters\":{}}]",List.of(),""));
                }
                public CompletableFuture<SplitResult> chooseSplit(SplitRequest r) { return CompletableFuture.failedFuture(new IllegalStateException("fixture")); }
            }); installedField.setBoolean(router,true);
            var rooms = com.sande.mythictrpg.ai.server.ConversationRooms.INSTANCE;
            var room = rooms.create(f.player,com.sande.mythictrpg.ai.room.RoomType.PRIVATE,List.of(GOD),
                    com.sande.mythictrpg.ai.room.RecordingScope.STANDARD);
            rooms.selectPrivate(f.player,room.roomId());
            rooms.privateText(f.player,"내 집에 한번 들러 볼래?");
            helper.startSequence().thenWaitUntil(() -> helper.assertTrue(f.future!=null,"Actual dialogue/gateway has not queued the visit"))
                    .thenExecute(() -> {
                        try {
                            require(f.request.trigger().equals("DIALOGUE") && f.request.dialogue().isPresent(),"No exact dialogue scope");
                            require(f.request.dialogue().orElseThrow().history().stream().anyMatch(l -> l.text().equals("내 집에 한번 들러 볼래?")),"Lost actual triggering speech");
                            f.complete(f.destination.id());
                            require(f.avatar.homeVisitStatus().equals("TRAVELLING"),"Actual room proposal did not start the selected visit");
                            require(rooms.visitDialogue(f.player,UUID.randomUUID(),GOD).isEmpty(),"Foreign action generation accepted");
                            // Keep the existing conversation but replace the avatar/order fixture.
                            f.respawn(); f.future=null;
                            rooms.privateText(f.player,"다시 생각해 봐.");
                        } catch(Throwable failure) { cleanupRoom(f,router,engineField,installedField,previousEngine,previousInstalled);throw failure; }
                    }).thenWaitUntil(() -> helper.assertTrue(f.future!=null,"Second dialogue request not scheduled"))
                    .thenExecute(() -> {
                        try {
                            rooms.leave(f.player,room,"FIXTURE_END"); f.complete(f.destination.id());
                            require(!f.avatar.busyForVisit(),"Late decision from ended room started movement");
                        } finally { cleanupRoom(f,router,engineField,installedField,previousEngine,previousInstalled); }
                    }).thenSucceed();
        } catch(Throwable failure) { cleanupRoom(f,router,engineField,installedField,previousEngine,previousInstalled);throw failure; }
    }
    private static void cleanupRoom(Fixture f, Object router, Field engine, Field installed, Object oldEngine,boolean oldInstalled) {
        var rooms=com.sande.mythictrpg.ai.server.ConversationRooms.INSTANCE;
        for(var room:rooms.memberships(f.player))rooms.leave(f.player,room,"FIXTURE_END");
        try { engine.set(router,oldEngine);installed.setBoolean(router,oldInstalled); }
        catch(Exception failure){throw new IllegalStateException(failure);}
        finally { f.close(); }
    }
    private static final class Fixture {
        final GameTestHelper helper;
        final GodHomeVisitService service = GodHomeVisitService.INSTANCE;
        final Field definitions, policies, provider;
        final Object priorDefinitions, priorPolicies, priorProvider;
        final int priorProgress;
        final ServerPlayer player;
        final io.netty.channel.embedded.EmbeddedChannel channel;
        final List<FreeStructureRecord> structures = new ArrayList<>();
        final Set<Long> forcedByFixture = new HashSet<>();
        GodAvatarEntity avatar;
        BlockPos feet;
        FreeStructureRecord destination;
        GodVisitPlanner.Request request;
        CompletableFuture<GodVisitPlanner.Decision> future;
        boolean closed;
        Fixture(GameTestHelper helper) throws Exception {
            this.helper = helper;
            definitions = field(GodAvatarDefinitionManager.class, "definitions"); policies = field(GodVisitPolicies.class, "policies");
            provider = field(GodVisitPlanner.class, "provider");
            priorDefinitions = definitions.get(GodAvatarDefinitionManager.INSTANCE); priorPolicies = policies.get(GodVisitPolicies.INSTANCE);
            priorProvider = provider.get(null);
            var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(), "VisitOwner"), false);
            player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
            priorProgress = MythicWorldState.get(player.server).questProgress(TRACK);
        }
        void prepare() throws Exception {
            definitions.set(GodAvatarDefinitionManager.INSTANCE, Map.of(GOD, new GodAvatarDefinition(GOD,
                    new GodAvatarDefinition.Appearance(0, 1), new GodAvatarDefinition.Stats(20,.25,2,0,16),
                    new GodAvatarDefinition.Movement(true,false,true,1,32,32),
                    new GodAvatarDefinition.Combat(false,false,false,false), new GodAvatarDefinition.Placement(false,0),4)));
            policies.set(GodVisitPolicies.INSTANCE, Map.of(GOD, GodVisitPolicy.decode(GOD, JsonParser.parseString(POLICY).getAsJsonObject())));
            GodVisitPlanner.install(value -> { request = value; future = new CompletableFuture<>(); return future; });
            feet = helper.absolutePos(new BlockPos(2,2,2));
            // Only the fixture explicitly loads terrain; production must never load a pathfinding region.
            for (int x = (feet.getX() >> 4)-3; x <= (feet.getX() >> 4)+3; x++)
                for (int z = (feet.getZ() >> 4)-3; z <= (feet.getZ() >> 4)+3; z++) {
                    helper.getLevel().getChunk(x,z);
                    long chunk = net.minecraft.world.level.ChunkPos.asLong(x,z);
                    if (!helper.getLevel().getForcedChunks().contains(chunk)) {
                        helper.getLevel().setChunkForced(x,z,true); forcedByFixture.add(chunk);
                    }
                }
            for (int x = -2; x <= 12; x++) for (int z = -3; z <= 5; z++) {
                var pos = feet.offset(x,0,z);
                helper.getLevel().setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
                for (int dy=0;dy<3;dy++) helper.getLevel().setBlockAndUpdate(pos.above(dy), Blocks.AIR.defaultBlockState());
            }
            player.setPos(Vec3.atBottomCenterOf(feet.offset(-2,0,0)));
            var data = PlayerConstructionState.get(player.server);
            destination = data.register(player.getUUID(), "Owner home", StructureRegion.between(helper.getLevel().dimension(), feet.offset(6,0,-1), feet.offset(9,0,2)), Set.of(), helper.getLevel().getGameTime());
            structures.add(destination);
            structures.add(data.register(player.getUUID(), "Owner other sanctuary", StructureRegion.between(helper.getLevel().dimension(), feet.offset(4,0,3), feet.offset(9,0,5)), Set.of(), helper.getLevel().getGameTime()));
            structures.add(data.register(UUID.randomUUID(), "Someone else's home", destination.region(), Set.of(), helper.getLevel().getGameTime()));
            MythicWorldState.get(player.server).setQuestProgress(TRACK, 0);
            respawn();
        }
        void respawn() {
            if (avatar != null) GodAvatarService.INSTANCE.despawn(avatar);
            avatar = GodAvatarService.INSTANCE.spawn(helper.getLevel(), GOD, Vec3.atBottomCenterOf(feet)).orElseThrow();
            avatar.setOnGround(true); // All synchronous fixture decisions occur before its first gravity tick.
        }
        void request() { require(service.request(player, GOD, Optional.empty()), "Fixture visit not submitted"); }
        void complete(UUID id) { future.complete(new GodVisitPlanner.Decision(Optional.of(id))); tick(); }
        void tick() { service.tick(new ServerTickEvent.Post(() -> true, player.server)); }
        void close() {
            if (closed) return; closed = true;
            if (future != null && !future.isDone()) { future.completeExceptionally(new IllegalStateException("fixture closed")); tick(); }
            if (avatar != null) GodAvatarService.INSTANCE.despawn(avatar);
            for (var s : structures) PlayerConstructionState.get(player.server).delete(s.ownerId(), s.name());
            MythicWorldState.get(player.server).setQuestProgress(TRACK, priorProgress);
            try { definitions.set(GodAvatarDefinitionManager.INSTANCE, priorDefinitions); policies.set(GodVisitPolicies.INSTANCE, priorPolicies); provider.set(null, priorProvider); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            player.server.getPlayerList().remove(player); channel.finishAndReleaseAll();
            for(long chunk:forcedByFixture) {
                var pos=new net.minecraft.world.level.ChunkPos(chunk);
                helper.getLevel().setChunkForced(pos.x,pos.z,false);
            }
        }
    }
    private static Field field(Class<?> type, String name) throws Exception { var f=type.getDeclaredField(name); f.setAccessible(true); return f; }
    private static void require(boolean okay, String message) { if (!okay) throw new GameTestAssertException(message); }
}
