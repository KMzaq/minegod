package com.sande.mythictrpg.story.presentation;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.tools.ToolProvider;

/** Actual token service with bounded Minecraft/Story-service stubs; no server, state files or LLM. */
public final class StoryHookTokenRoomTest {
    public static void main(String[] args) throws Exception {
        Path module = args.length == 0 ? Path.of("").toAbsolutePath() : Path.of(args[0]).toAbsolutePath();
        Path output = args.length < 2 ? module.resolve("build/story-hook-fixture") : Path.of(args[1]);
        Path sources = output.resolve("sources"), classes = output.resolve("classes");
        Files.createDirectories(classes);
        var compilerArgs = new ArrayList<>(List.of("-encoding", "UTF-8", "-d", classes.toString()));
        for (String name : List.of("story/presentation/StoryAiHookTokenService", "ai/action/AiActionProposal", "ai/action/AiActionScope"))
            compilerArgs.add(module.resolve("src/main/java/com/sande/mythictrpg/" + name + ".java").toString());
        for (var entry : stubs().entrySet()) {
            Path source = sources.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(source.getParent()); Files.writeString(source, entry.getValue(), StandardCharsets.UTF_8);
            compilerArgs.add(source.toString());
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null || compiler.run(null, System.out, System.err, compilerArgs.toArray(String[]::new)) != 0)
            throw new AssertionError("Story token fixture compilation failed");
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            try { loader.loadClass("fixture.StoryTokenCases").getMethod("run").invoke(null); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw new AssertionError("Story token fixture failed", failure.getCause()); }
        }
    }

    private static Map<String, String> stubs() {
        var files = new LinkedHashMap<String, String>();
        files.put("net.minecraft.resources.ResourceLocation", """
                package net.minecraft.resources;
                public record ResourceLocation(String value) { public static ResourceLocation parse(String text){return new ResourceLocation(text);} }
                """);
        files.put("net.minecraft.server.MinecraftServer", """
                package net.minecraft.server;
                public class MinecraftServer {
                    public boolean sameThread=true; public final World world=new World();
                    public boolean isSameThread(){return sameThread;} public World overworld(){return world;}
                    public static class World {public long tick;public long getGameTime(){return tick;}}
                }
                """);
        files.put("net.minecraft.server.level.ServerPlayer", """
                package net.minecraft.server.level;
                public class ServerPlayer {public final net.minecraft.server.MinecraftServer server;private final java.util.UUID id;
                    public ServerPlayer(net.minecraft.server.MinecraftServer server){this.server=server;id=java.util.UUID.randomUUID();}
                    public java.util.UUID getUUID(){return id;}}
                """);
        files.put("com.sande.mythictrpg.ai.server.AiConversationRuntimeService", """
                package com.sande.mythictrpg.ai.server;
                import java.util.*;import com.sande.mythictrpg.ai.action.*;import net.minecraft.server.level.ServerPlayer;
                public class AiConversationRuntimeService {public static final AiConversationRuntimeService INSTANCE=new AiConversationRuntimeService();
                    public final Map<UUID,AiActionScope> scopes=new HashMap<>();
                    public Optional<AiActionScope> currentActionScope(ServerPlayer p){return Optional.ofNullable(scopes.get(p.getUUID()));}}
                """);
        files.put("com.sande.mythictrpg.ai.server.ConversationRooms", """
                package com.sande.mythictrpg.ai.server;
                import java.util.*;import com.sande.mythictrpg.ai.action.*;import net.minecraft.server.level.ServerPlayer;import net.minecraft.resources.ResourceLocation;
                public class ConversationRooms {public static final ConversationRooms INSTANCE=new ConversationRooms();
                    public record Binding(UUID room,long revision,UUID session,UUID player,ResourceLocation god){}
                    public final Map<UUID,Binding> rooms=new HashMap<>();
                    public Optional<AiActionScope> actionScope(ServerPlayer p,UUID room,long revision,ResourceLocation god){
                        var b=rooms.get(room);return b!=null&&b.revision()==revision&&b.player().equals(p.getUUID())&&b.god().equals(god)
                            ?Optional.of(new AiActionScope(b.session(),god)):Optional.empty();}}
                """);
        files.put("com.sande.mythictrpg.story.api.StoryStateView", """
                package com.sande.mythictrpg.story.api;
                public interface StoryStateView {record StoryScopeKey(String type,String key){
                    public static StoryScopeKey server(){return new StoryScopeKey("SERVER","server");}
                    public static StoryScopeKey player(java.util.UUID id){return new StoryScopeKey("PLAYER",id.toString());}
                    public static StoryScopeKey team(java.util.UUID id){return new StoryScopeKey("TEAM",id.toString());}}}
                """);
        files.put("com.sande.mythictrpg.story.definition.StoryDefinitionManager", """
                package com.sande.mythictrpg.story.definition;
                import java.util.*;import net.minecraft.resources.ResourceLocation;
                public class StoryDefinitionManager {public static final StoryDefinitionManager INSTANCE=new StoryDefinitionManager();
                    public enum Scope {SERVER,PLAYER,TEAM} public record Hook(ResourceLocation targetEventId,Scope targetScope){}
                    public record Snapshot(long generation){} public long generation=1;
                    public final Map<ResourceLocation,Hook> hooks=new HashMap<>();
                    public Optional<Hook> hook(ResourceLocation id){return Optional.ofNullable(hooks.get(id));}
                    public Snapshot snapshot(){return new Snapshot(generation);}}
                """);
        files.put("com.sande.mythictrpg.story.runtime.StoryTeamResolver", """
                package com.sande.mythictrpg.story.runtime;
                public class StoryTeamResolver {public record Team(java.util.UUID stableTeamId){} public static java.util.UUID team=java.util.UUID.randomUUID();
                    public static Team resolve(net.minecraft.server.level.ServerPlayer p){return new Team(team);}}
                """);
        files.put("com.sande.mythictrpg.story.presentation.StoryRoomConversationService", """
                package com.sande.mythictrpg.story.presentation;
                import net.minecraft.resources.ResourceLocation;import net.minecraft.server.level.ServerPlayer;
                public class StoryRoomConversationService {public static final StoryRoomConversationService INSTANCE=new StoryRoomConversationService();
                    public boolean available=true,audienceAllowed=true;public ResourceLocation checkedGod;public java.util.Set<java.util.UUID> audience;
                    public java.util.Set<java.util.UUID> roomAudience(ServerPlayer player,java.util.UUID room,long revision,ResourceLocation god){return audience==null?java.util.Set.of(player.getUUID()):audience;}
                    public boolean hookAudienceCurrent(ServerPlayer player,java.util.UUID room,long revision,ResourceLocation god,ResourceLocation hook,java.util.Set<java.util.UUID> expected){return audienceAllowed&&roomAudience(player,room,revision,god).equals(expected);}
                    public boolean speakerAvailable(ServerPlayer player,ResourceLocation god){checkedGod=god;return available;}}
                """);
        files.put("com.sande.mythictrpg.story.runtime.StoryHookService", """
                package com.sande.mythictrpg.story.runtime;
                import java.util.*;import net.minecraft.resources.ResourceLocation;import net.minecraft.server.level.ServerPlayer;
                public class StoryHookService {public static final StoryHookService INSTANCE=new StoryHookService();public boolean allowed=true;public int accepted;
                    public record HookAcceptResult(boolean accepted,String reason,List<String> instanceIds){}
                    public HookAcceptResult preview(ServerPlayer p,ResourceLocation hook,ResourceLocation actor){return new HookAcceptResult(allowed,"preview",List.of());}
                    public HookAcceptResult accept(ServerPlayer p,ResourceLocation hook,ResourceLocation actor,boolean confirmed){accepted++;return new HookAcceptResult(true,"",List.of("instance"));}}
                """);
        files.put("com.sande.mythictrpg.story.state.StoryRuntimeState", """
                package com.sande.mythictrpg.story.state;
                import java.util.*;import net.minecraft.resources.ResourceLocation;import com.sande.mythictrpg.story.api.StoryStateView.StoryScopeKey;
                public class StoryRuntimeState {public static final StoryRuntimeState INSTANCE=new StoryRuntimeState();
                    public record Event(String instanceId,long revision){} public Optional<Event> event=Optional.empty();
                    public static StoryRuntimeState get(net.minecraft.server.MinecraftServer server){return INSTANCE;}
                    public Optional<Event> latestEvent(ResourceLocation id,StoryScopeKey scope){return event;}}
                """);
        files.put("fixture.StoryTokenCases", """
                package fixture;
                import java.util.*;import net.minecraft.resources.ResourceLocation;import net.minecraft.server.MinecraftServer;import net.minecraft.server.level.ServerPlayer;
                import com.sande.mythictrpg.ai.action.*;import com.sande.mythictrpg.ai.server.*;import com.sande.mythictrpg.story.presentation.*;
                import com.sande.mythictrpg.story.definition.*;import com.sande.mythictrpg.story.runtime.*;import com.sande.mythictrpg.story.state.*;
                public class StoryTokenCases {
                    static int checks;static StoryAiHookTokenService tokens=StoryAiHookTokenService.INSTANCE;
                    static ResourceLocation god=ResourceLocation.parse("test:god"), actor=ResourceLocation.parse("test:actor"), hook=ResourceLocation.parse("test:hook");
                    static MinecraftServer server=new MinecraftServer();static ServerPlayer player=new ServerPlayer(server), outsider=new ServerPlayer(server);
                    static UUID room=UUID.randomUUID(), session=UUID.randomUUID();static long revision=1;
                    static void bind(){ConversationRooms.INSTANCE.rooms.put(room,new ConversationRooms.Binding(room,revision,session,player.getUUID(),god));}
                    static UUID offer(){UUID snapshot=UUID.randomUUID();String alias=tokens.issueRoom(player,snapshot,hook,actor,god,room,revision,1);
                        return tokens.resolveRoomAlias(player,snapshot,alias,god,room,revision).orElseThrow();}
                    static AiActionProposal proposal(UUID token){return new AiActionProposal(UUID.randomUUID(),session,ResourceLocation.parse("test:story_event_hook"),god,player.getUUID(),"","",Map.of("token",token.toString()));}
                    public static void run(){
                        var definitions=StoryDefinitionManager.INSTANCE;definitions.hooks.put(hook,new StoryDefinitionManager.Hook(ResourceLocation.parse("test:event"),StoryDefinitionManager.Scope.PLAYER));bind();
                        UUID snapshot=UUID.randomUUID();String alias=tokens.issueRoom(player,snapshot,hook,actor,god,room,revision,1);
                        UUID token=tokens.resolveRoomAlias(player,snapshot,alias,god,room,revision).orElseThrow();
                        check(tokens.resolveAlias(player,snapshot,alias,god).isEmpty(),"room token cannot resolve through ambient legacy path");
                        check(tokens.resolveRoomAlias(player,snapshot,alias,god,UUID.randomUUID(),revision).isEmpty(),"other room rejected");
                        check(tokens.resolveRoomAlias(player,snapshot,alias,god,room,revision+1).isEmpty(),"other revision rejected");
                        check(tokens.resolveRoomAlias(outsider,snapshot,alias,god,room,revision).isEmpty(),"other player rejected");
                        check(tokens.resolveRoomAlias(player,snapshot,alias,ResourceLocation.parse("test:other"),room,revision).isEmpty(),"other god rejected");
                        var action=proposal(token);check(tokens.validate(player,action).accepted(),"current bound token accepted");
                        check(!tokens.validate(outsider,action).accepted(),"action owner mismatch rejected");
                        StoryRoomConversationService.INSTANCE.available=false;
                        check(!tokens.validate(player,action).accepted(),"speaker becoming unavailable blocks pending room token without revision change");
                        check(!tokens.consume(player,action).accepted()&&StoryHookService.INSTANCE.accepted==0,"unavailable speaker cannot execute through direct consume");
                        check(god.equals(StoryRoomConversationService.INSTANCE.checkedGod),"availability is checked for the token's authoritative speaker");
                        StoryRoomConversationService.INSTANCE.available=true;
                        check(tokens.consume(player,action).accepted(),"confirmed token consumed");
                        check(!tokens.consume(player,action).accepted()&&StoryHookService.INSTANCE.accepted==1,"single use prevents duplicate effect");
                        token=offer();action=proposal(token);StoryRoomConversationService.INSTANCE.audience=Set.of(player.getUUID(),outsider.getUUID());
                        check(!tokens.validate(player,action).accepted(),"public observer change expires token even without room revision change");
                        StoryRoomConversationService.INSTANCE.audience=null;
                        token=offer();action=proposal(token);StoryRoomConversationService.INSTANCE.audienceAllowed=false;
                        check(!tokens.validate(player,action).accepted(),"disclosure authorization is rechecked at confirmation");
                        try{offer();throw new AssertionError("unauthorized audience accepted on issue");}catch(IllegalArgumentException expected){checks++;}
                        StoryRoomConversationService.INSTANCE.audienceAllowed=true;
                        StoryRoomConversationService.INSTANCE.audience=Set.of(player.getUUID(),outsider.getUUID());
                        token=offer();action=proposal(token);check(tokens.validate(player,action).accepted(),"explicitly allowed multi-player audience may offer to current owner");
                        check(!tokens.validate(outsider,action).accepted(),"public offer does not transfer acceptance ownership");
                        StoryRoomConversationService.INSTANCE.audience=null;
                        token=offer();action=proposal(token);revision++;bind();check(!tokens.validate(player,action).accepted(),"membership revision expires token");
                        token=offer();action=proposal(token);definitions.generation++;check(!tokens.validate(player,action).accepted(),"definition reload expires token");
                        token=offer();action=proposal(token);server.world.tick+=1200;check(!tokens.validate(player,action).accepted(),"TTL exact boundary rejected");
                        token=offer();action=proposal(token);StoryRuntimeState.INSTANCE.event=Optional.of(new StoryRuntimeState.Event("changed",1));check(!tokens.validate(player,action).accepted(),"event instance and revision change rejected");
                        token=offer();action=proposal(token);StoryRuntimeState.INSTANCE.event=Optional.of(new StoryRuntimeState.Event("changed",2));check(!tokens.validate(player,action).accepted(),"same instance changed revision rejected");
                        token=offer();action=proposal(token);StoryHookService.INSTANCE.allowed=false;check(!tokens.validate(player,action).accepted(),"current hook conditions rechecked");StoryHookService.INSTANCE.allowed=true;
                        definitions.hooks.put(hook,new StoryDefinitionManager.Hook(ResourceLocation.parse("test:event"),StoryDefinitionManager.Scope.TEAM));
                        token=offer();action=proposal(token);StoryTeamResolver.team=UUID.randomUUID();check(!tokens.validate(player,action).accepted(),"team change rejected even if no event exists");
                        token=offer();action=proposal(token);tokens.discardPlayer(player.getUUID());check(!tokens.validate(player,action).accepted(),"logout discard rejected");
                        token=offer();action=proposal(token);tokens.clear();check(!tokens.validate(player,action).accepted(),"server stop clears token");
                        token=offer();action=proposal(token);ConversationRooms.INSTANCE.rooms.clear();AiConversationRuntimeService.INSTANCE.scopes.put(player.getUUID(),new AiActionScope(session,god));
                        check(!tokens.validate(player,action).accepted(),"expired room never borrows matching ambient session");
                        bind();UUID legacySnapshot=UUID.randomUUID();String legacyAlias=tokens.issue(player,legacySnapshot,hook,actor,god,session,1);
                        check(tokens.resolveAlias(player,legacySnapshot,legacyAlias,god).isPresent(),"valid legacy path preserved");
                        check(tokens.resolveRoomAlias(player,legacySnapshot,legacyAlias,god,room,revision).isEmpty(),"legacy token cannot resolve through room");
                        var legacyAction=proposal(tokens.resolveAlias(player,legacySnapshot,legacyAlias,god).orElseThrow());
                        StoryRoomConversationService.INSTANCE.available=false;
                        check(!tokens.validate(player,legacyAction).accepted(),"legacy pending token also rechecks speaker availability");
                        StoryRoomConversationService.INSTANCE.available=true;
                        server.sameThread=false;try{offer();throw new AssertionError("wrong-thread issue accepted");}catch(IllegalStateException expected){checks++;}server.sameThread=true;
                        System.out.println("StoryHookTokenRoomTest: PASS ("+checks+" checks; game-service stubs, no server or LLM)");
                    }
                    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
                }
                """);
        return files;
    }
}
