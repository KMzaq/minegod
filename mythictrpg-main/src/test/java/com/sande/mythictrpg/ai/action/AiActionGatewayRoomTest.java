package com.sande.mythictrpg.ai.action;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.tools.ToolProvider;

/** Executes the actual Gateway source against bounded game-service stubs without booting Minecraft. */
public final class AiActionGatewayRoomTest {
    public static void main(String[] args) throws Exception {
        Path module = args.length == 0 ? Path.of("").toAbsolutePath() : Path.of(args[0]).toAbsolutePath();
        Path output = args.length < 2 ? module.resolve("build/action-room-fixture") : Path.of(args[1]);
        Path sources = output.resolve("sources"), classes = output.resolve("classes");
        Files.createDirectories(classes);
        var compilerArgs = new ArrayList<String>();
        compilerArgs.addAll(java.util.List.of("-encoding", "UTF-8", "-d", classes.toString()));
        for (String name : java.util.List.of("AiActionGateway", "AiActionScope", "AiActionContext", "AiActionProposal",
                "AiActionResult", "AiActionValidation", "AiActionExecution", "AiActionDefinition", "AiActionValidator", "AiActionExecutor", "QuestOfferAiAction")) {
            compilerArgs.add(module.resolve("src/main/java/com/sande/mythictrpg/ai/action/" + name + ".java").toString());
        }
        for (var entry : stubs().entrySet()) {
            Path source = sources.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, entry.getValue(), StandardCharsets.UTF_8);
            compilerArgs.add(source.toString());
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null || compiler.run(null, System.out, System.err, compilerArgs.toArray(String[]::new)) != 0)
            throw new AssertionError("Actual Gateway fixture compilation failed");
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            try { loader.loadClass("fixture.GatewayCases").getMethod("run").invoke(null); }
            catch (java.lang.reflect.InvocationTargetException failed) { throw new AssertionError("Gateway fixture failed", failed.getCause()); }
        }
    }

    private static Map<String, String> stubs() {
        var files = new LinkedHashMap<String, String>();
        files.put("net.minecraft.resources.ResourceLocation", """
                package net.minecraft.resources;
                public record ResourceLocation(String value) {
                    public static ResourceLocation fromNamespaceAndPath(String namespace,String path){return new ResourceLocation(namespace+":"+path);}
                    public static ResourceLocation parse(String value){if(value==null||value.isBlank()||value.contains(" "))throw new IllegalArgumentException("invalid ID");return new ResourceLocation(value.contains(":")?value:"mythictrpg:"+value);}
                    public static ResourceLocation tryParse(String value){try{return parse(value);}catch(IllegalArgumentException e){return null;}}
                    public String toString(){return value;}
                }
                """);
        files.put("net.minecraft.server.MinecraftServer", """
                package net.minecraft.server;
                public class MinecraftServer {
                    public boolean sameThread=true; public final World world=new World();
                    public boolean isSameThread(){return sameThread;} public World overworld(){return world;}
                    public final Players players=new Players(); public Players getPlayerList(){return players;}
                    public static class Players {public final java.util.Map<java.util.UUID,net.minecraft.server.level.ServerPlayer> values=new java.util.HashMap<>();public net.minecraft.server.level.ServerPlayer getPlayer(java.util.UUID id){return values.get(id);}}
                    public static class World {public long tick; public long getGameTime(){return tick;}}
                }
                """);
        files.put("net.minecraft.server.level.ServerPlayer", """
                package net.minecraft.server.level;
                public class ServerPlayer {
                    public final net.minecraft.server.MinecraftServer server; private final java.util.UUID id;
                    public ServerPlayer(net.minecraft.server.MinecraftServer server,java.util.UUID id){this.server=server;this.id=id;server.players.values.put(id,this);}
                    public java.util.UUID getUUID(){return id;}
                }
                """);
        files.put("com.sande.mythictrpg.MythicTrpg", """
                package com.sande.mythictrpg;
                public class MythicTrpg {public static final Log LOGGER=new Log(); public static class Log {public void error(String s,Object...v){} public void info(String s,Object...v){}}}
                """);
        files.put("com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings", """
                package com.sande.mythictrpg.ai.memorycontract;
                public class MemoryFoundationSettings {public enum Mode {OFF,PERSONAL,RUMOR_TEST} public static Mode value=Mode.PERSONAL; public static Mode mode(){return value;}}
                """);
        files.put("com.sande.mythictrpg.ai.server.AiConversationRuntimeService", """
                package com.sande.mythictrpg.ai.server;
                import java.util.*; import com.sande.mythictrpg.ai.action.*; import net.minecraft.server.level.ServerPlayer;
                public class AiConversationRuntimeService {
                    public static final AiConversationRuntimeService INSTANCE=new AiConversationRuntimeService();
                    public final Map<UUID,AiActionScope> scopes=new HashMap<>();
                    public Set<UUID> audience=Set.of();public Set<UUID> conversationPlayers(ServerPlayer player){return audience;}
                    public Optional<AiActionScope> currentActionScope(ServerPlayer player){return Optional.ofNullable(scopes.get(player.getUUID()));}
                }
                """);
        files.put("com.sande.mythictrpg.ai.server.ConversationRooms", """
                package com.sande.mythictrpg.ai.server;
                import java.util.*; import com.sande.mythictrpg.ai.action.*; import net.minecraft.server.level.ServerPlayer; import net.minecraft.resources.ResourceLocation;
                public class ConversationRooms {
                    public static final ConversationRooms INSTANCE=new ConversationRooms();
                    public record Scope(UUID room,long revision,UUID session,UUID player,ResourceLocation god){}
                    public final Map<UUID,Scope> scopes=new HashMap<>();
                    public final Map<UUID,Set<UUID>> audience=new HashMap<>();
                    public Set<UUID> actionPlayers(ServerPlayer p,UUID session,ResourceLocation god){return scopes.values().stream().filter(s->s.session().equals(session)&&s.god().equals(god)&&members(s).contains(p.getUUID())).findFirst().map(this::members).orElse(Set.of());}
                    private Set<UUID> members(Scope s){return audience.getOrDefault(s.room(),Set.of(s.player()));}
                    public Optional<AiActionScope> actionScope(ServerPlayer p,UUID room,long revision,ResourceLocation god){
                        var s=scopes.get(room);return s!=null&&s.revision()==revision&&members(s).contains(p.getUUID())&&s.god().equals(god)?Optional.of(new AiActionScope(s.session(),god)):Optional.empty();
                    }
                    public boolean actionCurrent(ServerPlayer p,UUID session,ResourceLocation god){return scopes.values().stream().anyMatch(s->s.session().equals(session)&&members(s).contains(p.getUUID())&&s.god().equals(god));}
                }
                """);
        files.put("com.sande.mythictrpg.ai.action.AiActionTypes", """
                package com.sande.mythictrpg.ai.action;
                import net.minecraft.resources.ResourceLocation;
                public class AiActionTypes {public static final ResourceLocation ITEM_REQUEST=fromProtocolName("item_request"),PLAYER_DAMAGE=fromProtocolName("player_damage"),QUEST_OFFER=fromProtocolName("quest_offer");public static ResourceLocation fromProtocolName(String name){return ResourceLocation.parse(name);}}
                """);
        files.put("com.sande.mythictrpg.ai.action.AiActionRegistry", """
                package com.sande.mythictrpg.ai.action;
                import java.util.*; import net.minecraft.resources.ResourceLocation;
                public class AiActionRegistry {public static final AiActionRegistry INSTANCE=new AiActionRegistry(); public final Map<ResourceLocation,AiActionDefinition> definitions=new HashMap<>(); public Optional<AiActionDefinition> find(ResourceLocation id){return Optional.ofNullable(definitions.get(id));} public Set<ResourceLocation> actionTypes(){return Set.copyOf(definitions.keySet());}public void registerQuest(){definitions.put(AiActionTypes.QUEST_OFFER,QuestOfferAiAction.definition());}}
                """);
        files.put("com.sande.mythictrpg.quest.QuestAssignmentValidation", """
                package com.sande.mythictrpg.quest;
                public record QuestAssignmentValidation(boolean allowed,String reason){}
                """);
        files.put("com.sande.mythictrpg.quest.QuestOperationResult", """
                package com.sande.mythictrpg.quest;
                public record QuestOperationResult(Status status){public enum Status{ASSIGNED,WAITING_FOR_PARTICIPANTS}public boolean succeeded(){return status==Status.ASSIGNED;}public java.util.Optional<String> reasonOptional(){return java.util.Optional.empty();}}
                """);
        files.put("com.sande.mythictrpg.quest.QuestRuntimeService", """
                package com.sande.mythictrpg.quest;
                import net.minecraft.server.level.ServerPlayer;import net.minecraft.resources.ResourceLocation;import com.sande.mythictrpg.ai.action.AiActionScope;
                public class QuestRuntimeService{
                    public static final QuestRuntimeService INSTANCE=new QuestRuntimeService();public ServerPlayer recipient;public AiActionScope roomScope;
                    public QuestAssignmentValidation validateAssignment(ServerPlayer p,ResourceLocation quest,ResourceLocation god){return new QuestAssignmentValidation(true,"");}
                    public QuestOperationResult assign(ServerPlayer p,ResourceLocation quest,ResourceLocation god){recipient=p;roomScope=null;return new QuestOperationResult(QuestOperationResult.Status.ASSIGNED);}
                    public QuestOperationResult assignRoom(ServerPlayer p,ResourceLocation quest,ResourceLocation god,AiActionScope scope){recipient=p;roomScope=scope;return new QuestOperationResult(QuestOperationResult.Status.WAITING_FOR_PARTICIPANTS);}
                }
                """);
        files.put("com.sande.mythictrpg.ai.action.AiActionParameters", """
                package com.sande.mythictrpg.ai.action;
                public class AiActionParameters {public static <T>T template(AiActionProposal proposal,Class<T> type){return null;}}
                """);
        files.put("com.sande.mythictrpg.ai.action.PlayerDamageTemplate", """
                package com.sande.mythictrpg.ai.action;
                public record PlayerDamageTemplate(int maxUsesPerSession,int cooldownTicks){}
                """);
        files.put("com.sande.mythictrpg.network.AiActionConfirmationPayload", """
                package com.sande.mythictrpg.network;
                public record AiActionConfirmationPayload(java.util.UUID proposalId,net.minecraft.resources.ResourceLocation actionType,String title,String summary,int seconds){}
                """);
        files.put("net.neoforged.neoforge.network.PacketDistributor", """
                package net.neoforged.neoforge.network;
                public class PacketDistributor {public static void sendToPlayer(net.minecraft.server.level.ServerPlayer p,Object packet){}}
                """);
        files.put("fixture.GatewayCases", cases());
        return files;
    }

    private static String cases() {
        return """
                package fixture;
                import java.util.*;
                import com.sande.mythictrpg.ai.action.*;
                import com.sande.mythictrpg.ai.server.*;
                import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
                import net.minecraft.server.*;
                import net.minecraft.server.level.*;
                import net.minecraft.resources.*;
                public class GatewayCases {
                    static int checks,executions,validations;
                    static AiActionContext lastContext;static AiActionProposal lastProposal;
                    static boolean rejectValidation,invalidateInValidation;
                    static final ResourceLocation GOD=ResourceLocation.parse("test:god"),OTHER=ResourceLocation.parse("test:other");
                    static final MinecraftServer SERVER=new MinecraftServer();
                    static final ServerPlayer PLAYER=new ServerPlayer(SERVER,UUID.randomUUID()),OUTSIDER=new ServerPlayer(SERVER,UUID.randomUUID());
                    static final ServerPlayer PEER=new ServerPlayer(SERVER,UUID.randomUUID());
                    static final UUID ROOM=UUID.randomUUID(),ROOM_B=UUID.randomUUID(),SESSION=UUID.randomUUID(),SESSION_B=UUID.randomUUID();
                    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
                    static void expect(AiActionResult result,AiActionResult.Status status,String message){check(result.status()==status,message+": "+result);}
                    static void setup(){
                        AiActionGateway.clear();AiActionRegistry.INSTANCE.definitions.clear();
                        AiConversationRuntimeService.INSTANCE.scopes.clear();ConversationRooms.INSTANCE.scopes.clear();
                        AiConversationRuntimeService.INSTANCE.audience=Set.of(PLAYER.getUUID(),OUTSIDER.getUUID());ConversationRooms.INSTANCE.audience.clear();
                        com.sande.mythictrpg.quest.QuestRuntimeService.INSTANCE.recipient=null;com.sande.mythictrpg.quest.QuestRuntimeService.INSTANCE.roomScope=null;
                        MemoryFoundationSettings.value=MemoryFoundationSettings.Mode.PERSONAL;SERVER.world.tick=0;SERVER.sameThread=true;
                        executions=validations=0;lastContext=null;lastProposal=null;rejectValidation=false;invalidateInValidation=false;
                        ConversationRooms.INSTANCE.scopes.put(ROOM,new ConversationRooms.Scope(ROOM,1,SESSION,PLAYER.getUUID(),GOD));
                        ConversationRooms.INSTANCE.scopes.put(ROOM_B,new ConversationRooms.Scope(ROOM_B,1,SESSION_B,PLAYER.getUUID(),GOD));
                        register("immediate",false);register("confirm",true);register("item_request",true);
                    }
                    static void register(String name,boolean confirmation){
                        var type=AiActionTypes.fromProtocolName(name);
                        AiActionRegistry.INSTANCE.definitions.put(type,new AiActionDefinition(type,confirmation?AiActionDefinition.ConfirmationPolicy.PLAYER_CONFIRMATION_REQUIRED:AiActionDefinition.ConfirmationPolicy.IMMEDIATE,
                            (context,proposal)->{validations++;lastContext=context;lastProposal=proposal;
                                if(invalidateInValidation)ConversationRooms.INSTANCE.scopes.remove(ROOM);
                                return rejectValidation?AiActionValidation.reject("inventory changed"):AiActionValidation.accept();},
                            (context,proposal)->{executions++;lastContext=context;lastProposal=proposal;return AiActionExecution.executed(Map.of());}));
                    }
                    static AiActionResult room(UUID room,long revision,ResourceLocation god,String type){return AiActionGateway.submitRoom(PLAYER,room,revision,god,type,"title","summary",Map.of(),false);}
                    static AiActionResult legacy(String type){return AiActionGateway.submit(PLAYER,GOD,type,"title","summary",Map.of(),false);}
                    public static void run(){
                        setup();AiConversationRuntimeService.INSTANCE.scopes.put(PLAYER.getUUID(),new AiActionScope(UUID.randomUUID(),OTHER));
                        expect(room(ROOM,1,GOD,"immediate"),AiActionResult.Status.EXECUTED,"explicit room ignores unrelated ambient legacy scope");
                        check(lastContext.roomScope().orElseThrow().sessionId().equals(SESSION)&&lastProposal.sessionId().equals(SESSION),"room scope propagated to validators and executors");
                        check(AiConversationRuntimeService.INSTANCE.currentActionScope(PLAYER).orElseThrow().actingGodId().equals(OTHER),"ambient session never switched");
                        expect(room(ROOM,1,GOD,"immediate"),AiActionResult.Status.REJECTED,"duplicate same-room execution rejected");
                        expect(room(ROOM_B,1,GOD,"immediate"),AiActionResult.Status.EXECUTED,"same action may execute independently in different room");
                        check(executions==2,"only intended room executions committed");

                        setup();AiConversationRuntimeService.INSTANCE.scopes.put(PLAYER.getUUID(),new AiActionScope(SESSION,GOD));
                        expect(room(ROOM,2,GOD,"immediate"),AiActionResult.Status.REJECTED,"stale room revision cannot borrow matching legacy scope");
                        expect(room(ROOM,1,OTHER,"immediate"),AiActionResult.Status.REJECTED,"unlisted room God rejected");
                        expect(AiActionGateway.submitRoom(OUTSIDER,ROOM,1,GOD,"immediate","","",Map.of(),false),AiActionResult.Status.REJECTED,"other player cannot borrow room");
                        check(executions==0&&validations==0,"unauthorized room requests never reach game validator");

                        setup();var pending=room(ROOM,1,GOD,"confirm");
                        expect(pending,AiActionResult.Status.PENDING_CONFIRMATION,"room action awaits explicit confirmation");
                        check(executions==0,"pending action does not execute");
                        expect(AiActionGateway.confirm(OUTSIDER,pending.proposalId()),AiActionResult.Status.REJECTED,"other player cannot consume pending confirmation");
                        expect(AiActionGateway.confirm(PLAYER,pending.proposalId()),AiActionResult.Status.EXECUTED,"original room confirmation succeeds");
                        check(validations==2&&executions==1&&lastContext.roomScope().isPresent(),"confirmation revalidates game state under explicit room context");
                        expect(AiActionGateway.confirm(PLAYER,pending.proposalId()),AiActionResult.Status.REJECTED,"duplicate confirmation rejected");

                        setup();pending=room(ROOM,1,GOD,"confirm");
                        ConversationRooms.INSTANCE.scopes.put(ROOM,new ConversationRooms.Scope(ROOM,2,UUID.randomUUID(),PLAYER.getUUID(),GOD));
                        AiConversationRuntimeService.INSTANCE.scopes.put(PLAYER.getUUID(),new AiActionScope(SESSION,GOD));
                        expect(AiActionGateway.confirm(PLAYER,pending.proposalId()),AiActionResult.Status.REJECTED,"room revision change rejects confirmation even with matching legacy scope");
                        check(executions==0,"expired room confirmation cannot commit");

                        setup();AiConversationRuntimeService.INSTANCE.scopes.put(PLAYER.getUUID(),new AiActionScope(SESSION,GOD));
                        pending=legacy("confirm");expect(pending,AiActionResult.Status.PENDING_CONFIRMATION,"legacy confirmation preserved");
                        check(lastContext.roomScope().isEmpty(),"legacy validator gets no room authority");
                        AiConversationRuntimeService.INSTANCE.scopes.remove(PLAYER.getUUID());
                        expect(AiActionGateway.confirm(PLAYER,pending.proposalId()),AiActionResult.Status.REJECTED,"legacy confirmation cannot borrow same UUID room scope");

                        setup();AiConversationRuntimeService.INSTANCE.scopes.put(PLAYER.getUUID(),new AiActionScope(UUID.randomUUID(),GOD));
                        pending=legacy("confirm");expect(AiActionGateway.confirm(PLAYER,pending.proposalId()),AiActionResult.Status.EXECUTED,"valid legacy confirmation still works");
                        check(lastContext.roomScope().isEmpty(),"legacy executor remains on legacy context");

                        setup();pending=room(ROOM,1,GOD,"confirm");rejectValidation=true;
                        expect(AiActionGateway.confirm(PLAYER,pending.proposalId()),AiActionResult.Status.REJECTED,"changed inventory/conditions rejected after confirmation");
                        check(executions==0,"revalidation prevents commit");
                        setup();invalidateInValidation=true;
                        expect(room(ROOM,1,GOD,"immediate"),AiActionResult.Status.REJECTED,"validator cannot execute after revoking room");
                        check(executions==0,"execution phase rechecks authority");

                        setup();pending=room(ROOM,1,GOD,"confirm");SERVER.world.tick=1200;
                        expect(AiActionGateway.confirm(PLAYER,pending.proposalId()),AiActionResult.Status.REJECTED,"expired confirmation rejected");
                        setup();pending=room(ROOM,1,GOD,"confirm");MemoryFoundationSettings.value=MemoryFoundationSettings.Mode.RUMOR_TEST;
                        expect(AiActionGateway.confirm(PLAYER,pending.proposalId()),AiActionResult.Status.REJECTED,"dialogue-only mode rechecked at confirmation");
                        expect(room(ROOM,1,GOD,"immediate"),AiActionResult.Status.REJECTED,"dialogue-only mode denies new room gameplay");

                        setup();pending=AiActionGateway.submitRoom(PLAYER,ROOM,1,GOD,"item_request","","",Map.of("player_ready","true","session_id",SESSION_B.toString()),false);
                        expect(pending,AiActionResult.Status.PENDING_CONFIRMATION,"item request follows confirmation route");
                        check(lastProposal.parameters().get("player_ready").equals("false")&&lastProposal.sessionId().equals(SESSION),"model params cannot forge readiness or session identity");
                        check(AiActionGateway.cancel(PLAYER,pending.proposalId()),"owner may cancel pending action");
                        expect(AiActionGateway.confirm(PLAYER,pending.proposalId()),AiActionResult.Status.REJECTED,"cancelled proposal cannot execute");
                        setup();expect(room(ROOM,1,GOD,"unregistered"),AiActionResult.Status.REJECTED,"unregistered action remains denied");

                        setup();AiActionRegistry.INSTANCE.registerQuest();ConversationRooms.INSTANCE.audience.put(ROOM,Set.of(PLAYER.getUUID(),PEER.getUUID()));
                        var quest=com.sande.mythictrpg.quest.QuestRuntimeService.INSTANCE;
                        expect(AiActionGateway.submitRoom(PLAYER,ROOM,1,GOD,"quest_offer","","",Map.of("quest_id","test:quest","recipient_id",OUTSIDER.getUUID().toString()),false),AiActionResult.Status.REJECTED,"quest recipient cannot be borrowed from ambient legacy audience");
                        check(quest.recipient==null,"out-of-room recipient gets no assignment");
                        var offered=AiActionGateway.submitRoom(PLAYER,ROOM,1,GOD,"quest_offer","","",Map.of("quest_id","test:quest","recipient_id",PEER.getUUID().toString()),false);
                        expect(offered,AiActionResult.Status.EXECUTED,"room participant can receive scoped quest recruitment");
                        check(quest.recipient==PEER&&quest.roomScope.sessionId().equals(SESSION),"quest assignment receives original room authority and recipient");
                        check(offered.details().get("quest_status").equals("WAITING_FOR_PARTICIPANTS"),"recruitment reports waiting not accepted assignment");
                        expect(AiActionGateway.submitRoom(PLAYER,ROOM,1,GOD,"quest_offer","","",Map.of("quest_id","test:other_quest"),false),AiActionResult.Status.EXECUTED,"implicit room quest recipient is current speaker");
                        check(quest.recipient==PLAYER&&quest.roomScope.sessionId().equals(SESSION),"implicit recipient retains explicit scope");
                        AiConversationRuntimeService.INSTANCE.scopes.put(PLAYER.getUUID(),new AiActionScope(UUID.randomUUID(),GOD));
                        expect(AiActionGateway.submit(PLAYER,GOD,"quest_offer","","",Map.of("quest_id","test:legacy_quest","recipient_id",OUTSIDER.getUUID().toString()),false),AiActionResult.Status.EXECUTED,"legacy quest recipient path preserved");
                        check(quest.recipient==OUTSIDER&&quest.roomScope==null,"legacy quest assignment never acquires room authority");

                        SERVER.sameThread=false;try{room(ROOM,1,GOD,"immediate");throw new AssertionError("off-thread accepted");}catch(IllegalStateException expected){checks++;}
                        System.out.println("AiActionGatewayRoomTest: "+checks+" assertions passed (actual Gateway source)");
                    }
                }
                """;
    }
}
