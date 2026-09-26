package com.sande.mythictrpg.quest;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;

/**
 * Compiles the actual offer/answer/current/cancel methods and QuestEnrollment unchanged.
 * World state, binding validation and FTB effects are stubs; this is a consent authority test,
 * not an inventory, persistence, reward or real Minecraft integration test.
 */
public final class QuestParticipationRoomTest {
    public static void main(String[] args) throws Exception {
        Path module = args.length == 0 ? Path.of("").toAbsolutePath() : Path.of(args[0]).toAbsolutePath();
        Path output = args.length < 2 ? module.resolve("build/quest-room-fixture") : Path.of(args[1]);
        Path classes = output.resolve("classes"), sources = output.resolve("sources");
        Files.createDirectories(classes);
        String original = Files.readString(module.resolve("src/main/java/com/sande/mythictrpg/quest/QuestParticipationService.java"));
        int begin = original.indexOf("public final class QuestParticipationService {");
        int end = original.indexOf("    /** Bounded facts for the current player's quests", begin);
        if (begin < 0 || end < 0) throw new AssertionError("Consent method extraction boundary changed; review fixture");
        var files = stubs();
        files.put("com.sande.mythictrpg.quest.QuestParticipationService", """
                package com.sande.mythictrpg.quest;
                import java.util.*; import java.time.Instant;
                import net.minecraft.server.MinecraftServer;import net.minecraft.server.level.ServerPlayer;
                import net.minecraft.resources.ResourceLocation;import net.minecraft.network.chat.Component;
                import com.sande.mythictrpg.ai.action.AiActionScope;
                import com.sande.mythictrpg.ai.server.AiConversationRuntimeService;
                import com.sande.mythictrpg.ai.server.ConversationRooms;
                """ + original.substring(begin, end) + fixture() + "\n}\n");
        var compilerArgs = new ArrayList<>(List.of("-encoding", "UTF-8", "-d", classes.toString()));
        compilerArgs.add(module.resolve("src/main/java/com/sande/mythictrpg/quest/QuestEnrollment.java").toString());
        compilerArgs.add(module.resolve("src/main/java/com/sande/mythictrpg/ai/action/AiActionScope.java").toString());
        for (var entry : files.entrySet()) {
            Path file = sources.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            compilerArgs.add(file.toString());
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null || compiler.run(null, System.out, System.err, compilerArgs.toArray(String[]::new)) != 0)
            throw new AssertionError("Consent fixture compilation failed");
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            try { loader.loadClass("com.sande.mythictrpg.quest.QuestParticipationService").getMethod("runCases").invoke(null); }
            catch (java.lang.reflect.InvocationTargetException failed) { throw new AssertionError("Consent fixture failed", failed.getCause()); }
        }
    }

    private static Map<String, String> stubs() {
        var result = new LinkedHashMap<String, String>();
        result.put("net.minecraft.resources.ResourceLocation", """
                package net.minecraft.resources;
                public record ResourceLocation(String value){public static ResourceLocation parse(String value){return new ResourceLocation(value);}public String toString(){return value;}}
                """);
        result.put("net.minecraft.server.MinecraftServer", """
                package net.minecraft.server;
                public class MinecraftServer {
                    public boolean sameThread=true;public boolean isSameThread(){return sameThread;}
                    public final Players players=new Players();public Players getPlayerList(){return players;}
                    public World overworld(){return new World();}public static class World{public long getGameTime(){return 100;}}
                    public static class Players{public final java.util.Map<java.util.UUID,net.minecraft.server.level.ServerPlayer> values=new java.util.HashMap<>();public net.minecraft.server.level.ServerPlayer getPlayer(java.util.UUID id){return values.get(id);}}
                }
                """);
        result.put("net.minecraft.server.level.ServerPlayer", """
                package net.minecraft.server.level;
                public class ServerPlayer {
                    public final net.minecraft.server.MinecraftServer server;private final java.util.UUID id;
                    public ServerPlayer(net.minecraft.server.MinecraftServer server){this.server=server;id=java.util.UUID.randomUUID();server.players.values.put(id,this);}
                    public java.util.UUID getUUID(){return id;}public void sendSystemMessage(net.minecraft.network.chat.Component c){}
                }
                """);
        result.put("net.minecraft.network.chat.Component", """
                package net.minecraft.network.chat;
                public class Component {
                    public static Component literal(String text){return new Component();}public Component append(Component c){return this;}public Component append(String s){return this;}
                    public Component withStyle(java.util.function.UnaryOperator<Style> style){style.apply(new Style());return this;}
                    public static class Style{public Style withClickEvent(ClickEvent click){return this;}}
                }
                """);
        result.put("net.minecraft.network.chat.ClickEvent", """
                package net.minecraft.network.chat;public record ClickEvent(Action action,String command){public enum Action{RUN_COMMAND}}
                """);
        result.put("com.sande.mythictrpg.ai.server.AiConversationRuntimeService", """
                package com.sande.mythictrpg.ai.server;
                import java.util.*;import net.minecraft.server.level.ServerPlayer;import com.sande.mythictrpg.ai.action.AiActionScope;
                public class AiConversationRuntimeService{
                    public static final AiConversationRuntimeService INSTANCE=new AiConversationRuntimeService();
                    public final Map<UUID,AiActionScope> scopes=new HashMap<>();public final Map<UUID,UUID> generations=new HashMap<>();public Set<UUID> audience=Set.of();
                    public Optional<AiActionScope> currentActionScope(ServerPlayer p){return Optional.ofNullable(scopes.get(p.getUUID()));}
                    public Optional<UUID> conversationGeneration(UUID player){return Optional.ofNullable(generations.get(player));}
                    public Set<UUID> conversationPlayers(ServerPlayer p){return audience;}
                }
                """);
        result.put("com.sande.mythictrpg.ai.server.ConversationRooms", """
                package com.sande.mythictrpg.ai.server;
                import java.util.*;import net.minecraft.server.level.ServerPlayer;import net.minecraft.resources.ResourceLocation;
                public class ConversationRooms{
                    public static final ConversationRooms INSTANCE=new ConversationRooms();
                    public final Map<UUID,Set<UUID>> members=new HashMap<>();public final Map<UUID,ResourceLocation> gods=new HashMap<>();
                    public boolean actionCurrent(ServerPlayer p,UUID session,ResourceLocation god){return members.getOrDefault(session,Set.of()).contains(p.getUUID())&&god.equals(gods.get(session));}
                    public Set<UUID> actionPlayers(ServerPlayer p,UUID session,ResourceLocation god){return actionCurrent(p,session,god)?members.get(session):Set.of();}
                }
                """);
        result.put("com.sande.mythictrpg.gameplay.ledger.detail.ImportantEvents", """
                package com.sande.mythictrpg.gameplay.ledger.detail;public class ImportantEvents{public static void transition(Object... values){}}
                """);
        return result;
    }

    private static String fixture() {
        return """
                private static String configurationProblem(FtbQuestBinding binding,ResourceLocation god){return "";}
                private static void npc(ServerPlayer player,ResourceLocation god,String text){}
                private static QuestOperationResult result(QuestOperationResult.Status status,ResourceLocation quest,String reason){return new QuestOperationResult(status,quest,reason);}
                private static void requireThread(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("game thread required");}
                private enum QuestParticipationType {SOLO,GROUP}
                private enum QuestNarrativeRole {SIDE,MAIN_ENTRY}
                private record Policy(QuestParticipationType type){}
                private record FtbQuestBinding(ResourceLocation questId,Policy policy){Optional<Policy> participation(){return Optional.of(policy);}QuestNarrativeRole narrativeRole(){return QuestNarrativeRole.SIDE;}}
                private record QuestOperationResult(Status status,ResourceLocation quest,String reason){enum Status{NOT_ASSIGNED,REWARD_INVALID,PARTICIPATION_CLOSED,WAITING_FOR_PARTICIPANTS}}
                private static class FtbQuestBindingManager{
                    static final FtbQuestBindingManager INSTANCE=new FtbQuestBindingManager();final Map<ResourceLocation,FtbQuestBinding> bindings=new HashMap<>();long generation=1;
                    Optional<FtbQuestBinding> find(ResourceLocation id){return Optional.ofNullable(bindings.get(id));}Snapshot snapshot(){return new Snapshot(generation);}record Snapshot(long generation){}
                }
                private static class QuestRuntimeService{
                    static final QuestRuntimeService INSTANCE=new QuestRuntimeService();final Set<UUID> ineligible=new HashSet<>();
                    Validation validateAssignment(ServerPlayer player,ResourceLocation quest,ResourceLocation god){return new Validation(!ineligible.contains(player.getUUID()));}
                    record Validation(boolean allowed){QuestOperationResult.Status rejectionStatus(){return QuestOperationResult.Status.NOT_ASSIGNED;}String reason(){return "ineligible";}}
                }
                private static class QuestParticipationRun{
                    final UUID id;final long startedAt;final Set<UUID> accepted;
                    QuestParticipationRun(UUID id,String quest,String god,Policy policy,Set<UUID> accepted,long tick){this.id=id;this.startedAt=tick;this.accepted=Set.copyOf(accepted);}
                    Snapshot snapshot(){return new Snapshot(id,startedAt);}record Snapshot(UUID runId,long startedAt){}
                }
                private static class MythicQuestState{
                    static final MythicQuestState INSTANCE=new MythicQuestState();final List<QuestParticipationRun> runs=new ArrayList<>();
                    static MythicQuestState get(MinecraftServer server){return INSTANCE;}void addParticipationRun(QuestParticipationRun run){runs.add(run);}
                }
                private static class QuestReminderState{static QuestReminderState get(MinecraftServer server){return new QuestReminderState();}void ensure(Object... values){}}
                private static class GodAttentionState{static GodAttentionState get(MinecraftServer server){return new GodAttentionState();}void recordEntryAssignment(Object... values){}}
                private static class FtbQuestAdapter{static final FtbQuestAdapter INSTANCE=new FtbQuestAdapter();void syncParticipation(Object... values){}}
                private static int checks;
                private static final MinecraftServer SERVER=new MinecraftServer();
                private static final ServerPlayer HOST=new ServerPlayer(SERVER),PEER=new ServerPlayer(SERVER),OUTSIDER=new ServerPlayer(SERVER);
                private static final ResourceLocation GOD=ResourceLocation.parse("test:god"),OTHER_GOD=ResourceLocation.parse("test:other");
                private static final UUID SESSION=UUID.randomUUID(),OTHER_SESSION=UUID.randomUUID();
                private static final FtbQuestBinding BINDING=new FtbQuestBinding(ResourceLocation.parse("test:quest"),new Policy(QuestParticipationType.GROUP));
                private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
                private static void reset(){
                    INSTANCE.clear();MythicQuestState.INSTANCE.runs.clear();QuestRuntimeService.INSTANCE.ineligible.clear();
                    FtbQuestBindingManager.INSTANCE.bindings.clear();FtbQuestBindingManager.INSTANCE.bindings.put(BINDING.questId(),BINDING);FtbQuestBindingManager.INSTANCE.generation=1;
                    var legacy=AiConversationRuntimeService.INSTANCE;legacy.scopes.clear();legacy.generations.clear();legacy.audience=Set.of(HOST.getUUID(),OUTSIDER.getUUID());
                    var rooms=ConversationRooms.INSTANCE;rooms.members.clear();rooms.gods.clear();rooms.members.put(SESSION,Set.of(HOST.getUUID(),PEER.getUUID()));rooms.gods.put(SESSION,GOD);
                    rooms.members.put(OTHER_SESSION,Set.of(HOST.getUUID(),OUTSIDER.getUUID()));rooms.gods.put(OTHER_SESSION,GOD);
                    SERVER.players.values.put(PEER.getUUID(),PEER);SERVER.sameThread=true;
                }
                private static UUID offer(){
                    check(INSTANCE.offerRoom(HOST,BINDING,GOD,new AiActionScope(SESSION,GOD)).status()==QuestOperationResult.Status.WAITING_FOR_PARTICIPANTS,"actual offerRoom waits for consent");
                    return INSTANCE.pendingOffer(HOST.getUUID()).orElseThrow();
                }
                private static void legacyScope(UUID session){
                    for(var player:List.of(HOST,PEER,OUTSIDER)){AiConversationRuntimeService.INSTANCE.scopes.put(player.getUUID(),new AiActionScope(session,GOD));AiConversationRuntimeService.INSTANCE.generations.put(player.getUUID(),UUID.randomUUID());}
                }
                public static void runCases(){
                    reset();UUID offer=offer();var pending=INSTANCE.pending.get(offer);
                    check(pending.enrollment().audience().equals(Set.of(HOST.getUUID(),PEER.getUUID())),"room enrollment never uses ambient legacy audience");
                    check(pending.roomAction()&&pending.generations().values().stream().allMatch(SESSION::equals),"pending retains explicit room generation for every participant");
                    check(!INSTANCE.handleRoomAnswer(HOST,OTHER_SESSION,GOD,"yes"),"natural answer in another room cannot accept offer");
                    check(!INSTANCE.handleRoomAnswer(HOST,SESSION,OTHER_GOD,"yes"),"different God cannot take consent");
                    check(!INSTANCE.handleAnswer(HOST,"yes"),"legacy natural chat cannot accept room offer");
                    check(!INSTANCE.answer(OUTSIDER,offer,QuestEnrollment.Answer.YES),"out-of-room explicit click rejected");
                    check(pending.enrollment().answers().isEmpty(),"wrong routing makes no consent mutation");
                    check(INSTANCE.handleRoomAnswer(HOST,SESSION,GOD,"yes"),"first room answer accepted");
                    check(MythicQuestState.INSTANCE.runs.isEmpty(),"first consent cannot assign group quest");
                    check(INSTANCE.handleRoomAnswer(PEER,SESSION,GOD,"yes"),"second room answer accepted");
                    check(MythicQuestState.INSTANCE.runs.size()==1&&MythicQuestState.INSTANCE.runs.getFirst().accepted.equals(Set.of(HOST.getUUID(),PEER.getUUID())),"exact consenting room players assigned once");
                    check(!INSTANCE.answer(PEER,offer,QuestEnrollment.Answer.YES)&&MythicQuestState.INSTANCE.runs.size()==1,"duplicate offer click cannot assign twice");

                    reset();offer=offer();INSTANCE.answer(HOST,offer,QuestEnrollment.Answer.YES);
                    ConversationRooms.INSTANCE.members.remove(SESSION);legacyScope(SESSION);
                    check(!INSTANCE.answer(PEER,offer,QuestEnrollment.Answer.YES),"revised/ended room cannot borrow matching legacy authority");
                    check(INSTANCE.pending.isEmpty()&&MythicQuestState.INSTANCE.runs.isEmpty(),"stale generation cancels without assignment");
                    reset();offer=offer();SERVER.players.values.remove(PEER.getUUID());
                    check(!INSTANCE.answer(HOST,offer,QuestEnrollment.Answer.YES),"offline participant invalidates consent round");
                    reset();offer=offer();FtbQuestBindingManager.INSTANCE.generation++;
                    check(!INSTANCE.answer(HOST,offer,QuestEnrollment.Answer.YES),"binding reload invalidates consent round");
                    reset();offer=offer();INSTANCE.answer(HOST,offer,QuestEnrollment.Answer.YES);QuestRuntimeService.INSTANCE.ineligible.add(HOST.getUUID());
                    check(!INSTANCE.answer(PEER,offer,QuestEnrollment.Answer.YES)&&MythicQuestState.INSTANCE.runs.isEmpty(),"eligibility is rechecked immediately before group assignment");

                    reset();offer=offer();INSTANCE.cancelForPlayer(SERVER,HOST.getUUID());
                    check(INSTANCE.pending.containsKey(offer),"legacy toggle cannot cancel independent room consent");
                    INSTANCE.cancelAllForPlayer(SERVER,HOST.getUUID());check(INSTANCE.pending.isEmpty(),"explicit logout cancels all affected rooms");
                    reset();offer=offer();INSTANCE.answer(HOST,offer,QuestEnrollment.Answer.NO);INSTANCE.answer(PEER,offer,QuestEnrollment.Answer.NO);
                    check(INSTANCE.pending.isEmpty()&&MythicQuestState.INSTANCE.runs.isEmpty(),"all no closes round without assignment");

                    reset();legacyScope(SESSION);AiConversationRuntimeService.INSTANCE.audience=Set.of(HOST.getUUID(),PEER.getUUID());
                    check(INSTANCE.offer(HOST,BINDING,GOD).status()==QuestOperationResult.Status.WAITING_FOR_PARTICIPANTS,"legacy offer still works");
                    offer=INSTANCE.pendingOffer(HOST.getUUID()).orElseThrow();check(!INSTANCE.pending.get(offer).roomAction(),"legacy pending does not gain room authority");
                    check(!INSTANCE.handleRoomAnswer(HOST,SESSION,GOD,"yes"),"room natural answer cannot consume legacy pending");
                    check(INSTANCE.handleAnswer(HOST,"yes")&&INSTANCE.handleAnswer(PEER,"yes")&&MythicQuestState.INSTANCE.runs.size()==1,"legacy consent routing still assigns once");
                    reset();legacyScope(SESSION);AiConversationRuntimeService.INSTANCE.audience=Set.of(HOST.getUUID(),PEER.getUUID());INSTANCE.offer(HOST,BINDING,GOD);
                    offer=INSTANCE.pendingOffer(HOST.getUUID()).orElseThrow();AiConversationRuntimeService.INSTANCE.scopes.clear();
                    check(!INSTANCE.answer(HOST,offer,QuestEnrollment.Answer.YES)&&MythicQuestState.INSTANCE.runs.isEmpty(),"legacy pending cannot borrow still-active room session");
                    System.out.println("QuestParticipationRoomTest: "+checks+" assertions passed (actual consent methods; world/FTB stubs)");
                }
                """;
    }
}
