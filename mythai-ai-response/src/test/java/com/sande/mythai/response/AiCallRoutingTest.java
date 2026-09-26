package com.sande.mythai.response;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;

/**
 * Executes complete production command sources with real Brigadier in an isolated loader.
 * Minecraft, registry and room authority are boundary stubs: this verifies routing and
 * validation before delegation, not actual room creation, persistence, permissions inside
 * ConversationRooms, spatial membership, server lifecycle or LLM behavior.
 */
public final class AiCallRoutingTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected markmar root and artifact directory");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path directory = Files.createTempDirectory(Files.createDirectories(Path.of(args[1])), "ai-call-routing-");
        Path brigadier = Path.of(Class.forName("com.mojang.brigadier.CommandDispatcher")
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        var sources = new ArrayList<String>();
        for (String file : List.of(
                "mythai-ai-response/src/main/java/com/sande/mythai/response/AiCallCommands.java",
                "mythai-ai-response/src/main/java/com/sande/mythai/response/AiTestCommands.java",
                "mythai-ai-response/src/main/java/com/sande/mythai/response/AiCallArguments.java",
                "mythai-ai-response/src/main/java/com/sande/mythai/response/DialogueTestArguments.java",
                "mythictrpg-main/src/main/java/com/sande/mythictrpg/command/ConversationRoomCommands.java",
                "mythictrpg-main/src/main/java/com/sande/mythictrpg/ai/room/RoomType.java",
                "mythictrpg-main/src/main/java/com/sande/mythictrpg/ai/room/RecordingScope.java")) {
            sources.add(root.resolve(file).toString());
        }
        stub(directory, sources, "net.minecraft.commands.Commands", """
                import com.mojang.brigadier.arguments.ArgumentType;
                import com.mojang.brigadier.builder.*;
                public final class Commands {
                    public static LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
                        return LiteralArgumentBuilder.literal(name);
                    }
                    public static <T> RequiredArgumentBuilder<CommandSourceStack,T> argument(String name,ArgumentType<T> type) {
                        return RequiredArgumentBuilder.argument(name,type);
                    }
                }
                """);
        stub(directory, sources, "net.minecraft.commands.CommandSourceStack", """
                import java.util.*;
                import java.util.function.Supplier;
                import com.mojang.brigadier.LiteralMessage;
                import com.mojang.brigadier.exceptions.*;
                import net.minecraft.network.chat.Component;
                import net.minecraft.server.level.ServerPlayer;
                public final class CommandSourceStack {
                    public final List<String> successes=new ArrayList<>(), failures=new ArrayList<>();
                    public final ServerPlayer player;
                    private final int permission;
                    public CommandSourceStack(int permission,ServerPlayer player){this.permission=permission;this.player=player;}
                    public boolean hasPermission(int minimum){return permission>=minimum;}
                    public ServerPlayer getPlayerOrException() throws CommandSyntaxException {
                        if(player==null)throw new SimpleCommandExceptionType(new LiteralMessage("Player required")).create();
                        return player;
                    }
                    public void sendSuccess(Supplier<Component> message,boolean broadcast){successes.add(message.get().text);}
                    public void sendFailure(Component message){failures.add(message.text);}
                }
                """);
        stub(directory, sources, "net.minecraft.network.chat.Component", """
                import java.util.function.UnaryOperator;
                public final class Component {
                    public final String text;
                    private Component(String text){this.text=text;}
                    public static Component literal(String text){return new Component(text);}
                    public Component append(Component other){return new Component(text+other.text);}
                    public Component withStyle(UnaryOperator<Style> action){action.apply(new Style());return this;}
                }
                """);
        stub(directory, sources, "net.minecraft.network.chat.Style", """
                public final class Style {public Style withClickEvent(ClickEvent event){return this;}}
                """);
        stub(directory, sources, "net.minecraft.network.chat.ClickEvent", """
                public record ClickEvent(Action action,String value){public enum Action {RUN_COMMAND}}
                """);
        stub(directory, sources, "net.minecraft.resources.ResourceLocation", """
                public record ResourceLocation(String value) {
                    public static ResourceLocation parse(String value){return new ResourceLocation(value);}
                    @Override public String toString(){return value;}
                }
                """);
        stub(directory, sources, "net.minecraft.server.level.ServerPlayer", """
                import net.minecraft.network.chat.Component;
                public final class ServerPlayer {
                    public void sendSystemMessage(Component message){throw new AssertionError("Unexpected player message");}
                }
                """);
        stub(directory, sources, "net.neoforged.neoforge.event.RegisterCommandsEvent", """
                import com.mojang.brigadier.CommandDispatcher;
                import net.minecraft.commands.CommandSourceStack;
                public record RegisterCommandsEvent(CommandDispatcher<CommandSourceStack> dispatcher) {
                    public CommandDispatcher<CommandSourceStack> getDispatcher(){return dispatcher;}
                }
                """);
        stub(directory, sources, "net.minecraft.commands.arguments.EntityArgument", """
                import com.mojang.brigadier.arguments.StringArgumentType;
                import com.mojang.brigadier.context.CommandContext;
                import net.minecraft.commands.CommandSourceStack;
                import net.minecraft.server.level.ServerPlayer;
                public final class EntityArgument {
                    public static StringArgumentType player(){return StringArgumentType.word();}
                    public static ServerPlayer getPlayer(CommandContext<CommandSourceStack> c,String name){
                        throw new AssertionError("Unexpected entity lookup");
                    }
                }
                """);
        stub(directory, sources, "net.minecraft.commands.arguments.UuidArgument", """
                import java.util.UUID;
                import com.mojang.brigadier.arguments.StringArgumentType;
                import com.mojang.brigadier.context.CommandContext;
                import net.minecraft.commands.CommandSourceStack;
                public final class UuidArgument {
                    public static StringArgumentType uuid(){return StringArgumentType.word();}
                    public static UUID getUuid(CommandContext<CommandSourceStack> c,String name){
                        return UUID.fromString(StringArgumentType.getString(c,name));
                    }
                }
                """);
        stub(directory, sources, "com.sande.mythictrpg.data.god.GodDefinitionManager", """
                import java.util.*;
                import net.minecraft.resources.ResourceLocation;
                public final class GodDefinitionManager {
                    public static final GodDefinitionManager INSTANCE=new GodDefinitionManager();
                    public final Set<String> definitions=new HashSet<>();
                    public Optional<String> find(ResourceLocation id){
                        return definitions.contains(id.toString())?Optional.of(id.toString()):Optional.empty();
                    }
                }
                """);
        stub(directory, sources, "com.sande.mythaiaicontent.content.AiContentRegistry", """
                import java.util.*;
                import net.minecraft.resources.ResourceLocation;
                public final class AiContentRegistry {
                    public static final AiContentRegistry INSTANCE=new AiContentRegistry();
                    public final Map<ResourceLocation,Object> profiles=new LinkedHashMap<>();
                    public boolean unavailable;
                    public Snapshot snapshot(){
                        if(unavailable)throw new IllegalStateException("Registry unavailable");
                        return new Snapshot(profiles);
                    }
                    public record Snapshot(Map<ResourceLocation,Object> godsByGodId){}
                }
                """);
        stub(directory, sources, "com.sande.mythictrpg.ai.room.ConversationRoomSnapshot", """
                import java.util.*;
                public record ConversationRoomSnapshot(UUID roomId,long revision,String code,RoomType type,
                        RecordingScope recordingScope,Set<String> godIds){}
                """);
        stub(directory, sources, "com.sande.mythictrpg.ai.server.ConversationRooms", """
                import java.util.*;
                import com.sande.mythictrpg.ai.room.*;
                import net.minecraft.resources.ResourceLocation;
                import net.minecraft.server.level.ServerPlayer;
                public final class ConversationRooms {
                    public static final ConversationRooms INSTANCE=new ConversationRooms();
                    public record Call(ServerPlayer player,RoomType type,List<ResourceLocation> gods,RecordingScope scope){}
                    public final List<Call> calls=new ArrayList<>();
                    public int attempts;
                    public String rejection;
                    public void reset(){calls.clear();attempts=0;rejection=null;}
                    public ConversationRoomSnapshot create(ServerPlayer player,RoomType type,List<ResourceLocation> gods,RecordingScope scope){
                        attempts++;
                        if(rejection!=null)throw new IllegalStateException(rejection);
                        calls.add(new Call(player,type,List.copyOf(gods),scope));
                        return new ConversationRoomSnapshot(UUID.randomUUID(),1,"CAPTURED",type,scope,Set.of());
                    }
                    public void privateText(ServerPlayer p,String text){throw unexpected();}
                    public void selectPrivate(ServerPlayer p,UUID room){throw unexpected();}
                    public void leave(ServerPlayer p,ConversationRoomSnapshot room,String reason){throw unexpected();}
                    public Optional<Object> actionScope(ServerPlayer p,UUID room,long revision,ResourceLocation god){throw unexpected();}
                    public void accept(ServerPlayer p,UUID invitation){throw unexpected();}
                    public void invite(ConversationRoomSnapshot room,ResourceLocation god,ServerPlayer player){throw unexpected();}
                    public Optional<ConversationRoomSnapshot> resolveMember(ServerPlayer p,String room){throw unexpected();}
                    public List<ConversationRoomSnapshot> memberships(ServerPlayer p){throw unexpected();}
                    private static AssertionError unexpected(){return new AssertionError("Unexpected game mutation/lookup");}
                }
                """);
        stub(directory, sources, "com.sande.mythictrpg.quest.QuestParticipationService", """
                import net.minecraft.resources.ResourceLocation;
                import net.minecraft.server.level.ServerPlayer;
                public final class QuestParticipationService {
                    public static final QuestParticipationService INSTANCE=new QuestParticipationService();
                    public Object confirmRoom(ServerPlayer p,ResourceLocation quest,Object scope){
                        throw new AssertionError("Unexpected quest submission");
                    }
                }
                """);
        stub(directory, sources, "com.sande.mythai.response.AiCallRoutingFixture", fixture());
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new AssertionError("JDK 21 compiler required for routing fixture");
        var compilerArgs = new ArrayList<>(List.of("-proc:none", "--release", "21", "-encoding", "UTF-8",
                "-classpath", brigadier.toString(), "-d", directory.toString()));
        compilerArgs.addAll(sources);
        if (compiler.run(null, null, null, compilerArgs.toArray(String[]::new)) != 0) {
            throw new AssertionError("Actual command sources did not compile against routing boundary fixture");
        }
        // Null parent prevents real game classes from replacing boundary stubs, even on a full mod classpath.
        try (var loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL(), brigadier.toUri().toURL()}, null)) {
            int checks = (int) loader.loadClass("com.sande.mythai.response.AiCallRoutingFixture")
                    .getMethod("run").invoke(null);
            System.out.println("AiCallRoutingTest: PASS (" + checks + " checks); actual command sources and Brigadier,"
                    + " stubbed game/registry boundaries, no server/model");
        }
    }

    private static void stub(Path directory, List<String> sources, String name, String body) throws Exception {
        Path path = directory.resolve(name.replace('.', '/') + ".java");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + body, StandardCharsets.UTF_8);
        sources.add(path.toString());
    }

    private static String fixture() {
        return """
                import java.util.*;
                import com.mojang.brigadier.CommandDispatcher;
                import com.mojang.brigadier.exceptions.CommandSyntaxException;
                import com.sande.mythaiaicontent.content.AiContentRegistry;
                import com.sande.mythictrpg.ai.room.*;
                import com.sande.mythictrpg.ai.server.ConversationRooms;
                import com.sande.mythictrpg.command.ConversationRoomCommands;
                import com.sande.mythictrpg.data.god.GodDefinitionManager;
                import net.minecraft.commands.CommandSourceStack;
                import net.minecraft.resources.ResourceLocation;
                import net.minecraft.server.level.ServerPlayer;
                import net.neoforged.neoforge.event.RegisterCommandsEvent;
                public final class AiCallRoutingFixture {
                    private static final String FIRST="mythictrpg:fortuna", SECOND="mythictrpg:demeter";
                    private static final String PROFILE_ONLY="mythictrpg:profile_only", GAME_ONLY="mythictrpg:game_only";
                    private static final ConversationRooms ROOMS=ConversationRooms.INSTANCE;
                    private static final AiContentRegistry REGISTRY=AiContentRegistry.INSTANCE;
                    private static final GodDefinitionManager GODS=GodDefinitionManager.INSTANCE;
                    private static final CommandDispatcher<CommandSourceStack> COMMANDS=new CommandDispatcher<>();
                    private static int checks;

                    public static int run() throws Exception {
                        var event=new RegisterCommandsEvent(COMMANDS);
                        AiCallCommands.onRegisterCommands(event);
                        AiTestCommands.onRegisterCommands(event);
                        ConversationRoomCommands.register(event);
                        for(var id:List.of(FIRST,SECOND,PROFILE_ONLY))REGISTRY.profiles.put(ResourceLocation.parse(id),new Object());
                        GODS.definitions.addAll(List.of(FIRST,SECOND,GAME_ONLY));
                        allModesAndRecordingScopes();
                        permissionAndPlayerBoundary();
                        explicitSyntax();
                        invalidGodsAreAtomic();
                        legacyCommandsOnlyExplainMigration();
                        suggestionsUseCompleteUsableIds();
                        delegatedRejectionAndUnavailableRegistry();
                        return checks;
                    }

                    private static void allModesAndRecordingScopes() throws Exception {
                        var modes=Map.of("public",RoomType.PUBLIC_FIXED,"mobile",RoomType.PUBLIC_MOBILE,"private",RoomType.PRIVATE);
                        for(var mode:modes.entrySet())for(String recording:List.of("on","off")) {
                            ROOMS.reset();var source=source(2);
                            check(COMMANDS.execute("ai_call "+mode.getKey()+" "+recording+" "+FIRST+" "+SECOND,source)==1,"successful route");
                            check(ROOMS.attempts==1&&ROOMS.calls.size()==1,"exactly one game create call");
                            var call=ROOMS.calls.getFirst();
                            check(call.player()==source.player&&call.type()==mode.getValue(),"same player and requested room type");
                            check(call.scope()==(recording.equals("on")?RecordingScope.TEST_RECORDING:RecordingScope.TEST_EPHEMERAL),"explicit recording scope reaches game");
                            check(call.gods().stream().map(Object::toString).toList().equals(List.of(FIRST,SECOND)),"all selected full IDs reach game in order");
                            check(source.failures.isEmpty()&&source.successes.size()==1,"one success only after game accepts");
                            String message=source.successes.getFirst();
                            check(message.contains("CAPTURED")&&message.contains("기록="+recording),"response includes created room and recording policy");
                            check(message.contains(mode.getValue()==RoomType.PRIVATE?"/s <할말>":"일반 채팅"),"input guidance follows audience type");
                            check(message.contains("/mythroom leave CAPTURED"),"response supplies actual room exit command");
                        }
                        ROOMS.reset();var source=source(2);
                        check(COMMANDS.execute("ai_call private off "+SECOND+","+FIRST+" "+SECOND,source)==1,"comma and duplicate IDs accepted");
                        check(ROOMS.calls.getFirst().gods().stream().map(Object::toString).toList().equals(List.of(SECOND,FIRST)),"duplicate removal preserves first selection order");
                    }

                    private static void permissionAndPlayerBoundary() throws Exception {
                        for(int level:List.of(0,1))for(String command:List.of("ai_call public on "+FIRST,"ai_test on "+FIRST,"mythroom open private on "+FIRST)) {
                            ROOMS.reset();var source=source(level);
                            syntaxRejected(command,source);
                            check(ROOMS.attempts==0,"non-OP cannot reach create through any entry point");
                            check(source.successes.isEmpty()&&source.failures.isEmpty(),"permission rejection does not execute command handler");
                        }
                        ROOMS.reset();var admin=source(3);
                        check(COMMANDS.execute("ai_call mobile off "+FIRST,admin)==1&&ROOMS.attempts==1,"permission above OP2 remains allowed");
                        ROOMS.reset();var console=new CommandSourceStack(4,null);
                        syntaxRejected("ai_call private on "+FIRST,console);
                        check(ROOMS.attempts==0,"console cannot create a player room");
                    }

                    private static void explicitSyntax() throws Exception {
                        for(String command:List.of("ai_call","ai_call public","ai_call mobile","ai_call private",
                                "ai_call public on","ai_call public off","ai_call mobile on","ai_call mobile off",
                                "ai_call private on","ai_call private off")) {
                            ROOMS.reset();var source=source(2);
                            check(COMMANDS.execute(command,source)==1,"incomplete command provides usage");
                            check(ROOMS.attempts==0,"incomplete command never creates a room");
                            check(source.successes.size()==1&&source.successes.getFirst().contains("/ai_call <public|mobile|private> <on|off>"),"usage makes both choices explicit");
                        }
                        for(String command:List.of("ai_call public "+FIRST,"ai_call private true "+FIRST,
                                "ai_call mobile false "+FIRST,"ai_call public ON "+FIRST,"ai_call private OFF "+FIRST,
                                "ai_call fixed off "+FIRST,"ai_call on "+FIRST,"ai_call 공개 on "+FIRST)) {
                            ROOMS.reset();syntaxRejected(command,source(2));
                            check(ROOMS.attempts==0,"unsupported mode/recording syntax cannot default into create");
                        }
                    }

                    private static void invalidGodsAreAtomic() throws Exception {
                        for(String selection:List.of("포르투나","fortuna","demeter","mythictrpg:unknown",GAME_ONLY,
                                PROFILE_ONLY,FIRST+" 포르투나",FIRST+" fortuna",FIRST+" "+GAME_ONLY,FIRST+" "+PROFILE_ONLY)) {
                            ROOMS.reset();var source=source(2);
                            check(COMMANDS.execute("ai_call public off "+selection,source)==0,"invalid selection fails: "+selection);
                            check(ROOMS.attempts==0&&ROOMS.calls.isEmpty(),"entire selection validated before create: "+selection);
                            check(source.successes.isEmpty()&&source.failures.size()==1,"invalid selection reports one failure without success");
                            String error=source.failures.getFirst();
                            if(selection.contains(PROFILE_ONLY))check(error.contains("God Definition"),"missing game definition diagnosed");
                            if(selection.contains(GAME_ONLY))check(error.contains("AI 프로필"),"missing loaded AI profile diagnosed");
                        }
                    }

                    private static void legacyCommandsOnlyExplainMigration() throws Exception {
                        for(String command:List.of("ai_test","ai_test on 포르투나","ai_test off "+FIRST,
                                "ai_test stop","ai_test arbitrary legacy text","mythroom open",
                                "mythroom open private on "+FIRST,"mythroom open fixed off "+FIRST,
                                "mythroom open mobile off 포르투나","mythroom open arbitrary legacy text")) {
                            ROOMS.reset();var source=source(2);
                            check(COMMANDS.execute(command,source)==0,"legacy route is not a successful start");
                            check(ROOMS.attempts==0&&ROOMS.calls.isEmpty(),"legacy route never creates rooms");
                            check(source.successes.isEmpty()&&source.failures.size()==1,"legacy route emits migration guidance only");
                            check(source.failures.getFirst().contains("/ai_call <public|mobile|private> <on|off>"),"legacy guidance names replacement syntax");
                        }
                    }

                    private static void suggestionsUseCompleteUsableIds() throws Exception {
                        ROOMS.reset();var source=source(2);
                        check(suggestions("ai_call public on mythictrpg:",source).equals(Set.of("ai_call public on "+FIRST,"ai_call public on "+SECOND)),"completion intersects live profiles with game definitions");
                        check(suggestions("ai_call private off "+FIRST+" mythictrpg:",source).equals(Set.of("ai_call private off "+FIRST+" "+SECOND)),"multi-ID completion preserves prefix and excludes selected ID");
                        check(suggestions("ai_call mobile off 포",source).isEmpty(),"Korean aliases are not suggested");
                        check(suggestions("ai_call mobile off fort",source).isEmpty(),"short path aliases are not suggested");
                        check(ROOMS.attempts==0,"tab completion cannot create rooms");
                    }

                    private static void delegatedRejectionAndUnavailableRegistry() throws Exception {
                        ROOMS.reset();ROOMS.rejection="GAME_RECORDING_DISABLED";var rejected=source(2);
                        check(COMMANDS.execute("ai_call public on "+FIRST,rejected)==0,"game rejection returns failure");
                        check(ROOMS.attempts==1&&ROOMS.calls.isEmpty(),"game receives request once and can reject it");
                        check(rejected.successes.isEmpty()&&rejected.failures.equals(List.of("[AI Call] GAME_RECORDING_DISABLED")),"game denial is propagated without success claim");
                        ROOMS.reset();REGISTRY.unavailable=true;
                        try {
                            var source=source(2);
                            check(COMMANDS.execute("ai_call private off "+FIRST,source)==0,"registry failure is contained");
                            check(source.successes.isEmpty()&&source.failures.size()==1,"registry failure reports failure only");
                            check(suggestions("ai_call private off mythictrpg:",source).isEmpty(),"registry failure does not crash completion");
                            check(ROOMS.attempts==0,"registry failure never mutates game rooms");
                        } finally {REGISTRY.unavailable=false;}
                        ROOMS.reset();var source=source(2);
                        check(COMMANDS.execute("ai_call private off "+FIRST,source)==1&&ROOMS.calls.size()==1,"later valid command works after prior failures");
                    }

                    private static Set<String> suggestions(String input,CommandSourceStack source) throws Exception {
                        var result=new HashSet<String>();
                        for(var suggestion:COMMANDS.getCompletionSuggestions(COMMANDS.parse(input,source)).get().getList()) {
                            result.add(suggestion.apply(input));
                        }
                        return result;
                    }
                    private static CommandSourceStack source(int permission){return new CommandSourceStack(permission,new ServerPlayer());}
                    private static void syntaxRejected(String input,CommandSourceStack source) throws Exception {
                        try {COMMANDS.execute(input,source);throw new AssertionError("Expected syntax/permission/player rejection: "+input);}
                        catch(CommandSyntaxException expected){checks++;}
                    }
                    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
                }
                """;
    }
}
