package com.sande.mythictrpg.ai;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;

/** Executes generated adapter methods with in-memory Minecraft/content/network boundaries. */
public final class DialogueTestSessionTest {
    public static void main(String[] args) throws Exception {
        String adapter = Files.readString(Path.of(args[0]));
        StringBuilder methods = new StringBuilder();
        for (String signature : List.of(
                "    public StartResult start(ServerPlayer player, ResourceLocation godId)",
                "    public StartResult start(ServerPlayer player, List<ResourceLocation> godIds, boolean recordLogs)",
                "    private StartResult startInternal(",
                "    private Map<ResourceLocation, AiTestContentRegistryBridge.ContentSnapshot> loadContents(",
                "    private boolean recordingAllowed(",
                "    private static boolean testLeaseCurrent(",
                "    private boolean isCurrent(",
                "    private void finishFailure(",
                "    public void handlePlayerText(",
                "    public void seedNpcTurn(",
                "    public void discardPlayer(",
                "    public boolean stop(ServerPlayer player)",
                "    public boolean isActive(",
                "    public void onPlayerLoggedOut(",
                "    public void stop()",
                "    private static void trimHistory(",
                "    private static String bounded(",
                "    private static String message(",
                "    private static String joinIds(",
                "    private static GenerationPlan generationPlan(",
                "    private static List<AiDialogueModels.OllamaMessage> generationMessages(",
                "    private record GenerationPlan(",
                "    public enum StartResult",
                "    private static final class Session",
                "    private record Transcript(")) methods.append(block(adapter, signature));

        int loggingSites = 0;
        for (String line : adapter.lines().toList()) {
            if (!line.contains("logs.append(" ) && !line.contains("logs.appendBlock(")) continue;
            loggingSites++;
            if (!line.contains("if (recordingAllowed(session))") && !line.contains("if (removed.recordLogs)")) {
                throw new AssertionError("Unguarded dialogue logging site: " + line.strip());
            }
        }
        if (loggingSites < 20) throw new AssertionError("Expected transport, prompt, history and lifecycle logging coverage");
        int generation = adapter.indexOf("\"GENERATION_REQUEST_JSON\"");
        int observerStart = adapter.lastIndexOf("new LocalOllamaClient.WireObserver()", generation);
        if (generation < 0 || observerStart < 0) throw new AssertionError("Missing generated wire observer");
        String observer = blockAt(adapter, observerStart);
        String memoryContinuation = block(adapter, "    private void continueAfterMemory(");
        int continuationGuardEnd = memoryContinuation.indexOf("        if (!DialogueMemoryBridge.accept(");
        if (continuationGuardEnd < 0) throw new AssertionError("Missing memory continuation validation boundary");
        memoryContinuation = memoryContinuation.substring(0, continuationGuardEnd)
                + "        promptHistory.put(player.id,List.copyOf(session.history)); session.pending = false;\n    }\n";

        String fixture = """
            import java.util.*;
            import java.util.concurrent.*;
            public class DialogueSessionFixture {
                private final Map<UUID,Session> sessions = new ConcurrentHashMap<>();
                private final AiTestContentRegistryBridge contentRegistry = new AiTestContentRegistryBridge();
                private final Logs logs = new Logs();
                private final Client llm = new Client();
                private final Map<UUID,List<Transcript>> promptHistory = new HashMap<>();
                record ResourceLocation(String value) {
                    public String toString(){ return value; }
                    static ResourceLocation parse(String value){ return new ResourceLocation(value); }
                }
                record GameProfile(String name) { String getName(){ return name; } }
                static class Server { void execute(Runnable task){ task.run(); } }
                static class ServerPlayer {
                    final UUID id = UUID.randomUUID(); final Server server = new Server();
                    final GameProfile profile; final List<String> messages = new ArrayList<>();
                    ServerPlayer(String name){ profile = new GameProfile(name); }
                    UUID getUUID(){ return id; } GameProfile getGameProfile(){ return profile; }
                    void sendSystemMessage(Component component){ messages.add(component.text()); }
                }
                enum ChatFormatting { LIGHT_PURPLE, GRAY, RED, YELLOW }
                record Component(String text) {
                    static Component literal(String text){ return new Component(text); }
                    Component withStyle(ChatFormatting ignored){ return this; }
                }
                static class MythicTrpg { static final Logger LOGGER = new Logger(); }
                static class Logger { void warn(String message, Object... args){} }
                static class GodAiDialogueService {
                    static final GodAiDialogueService INSTANCE = new GodAiDialogueService();
                    final Set<UUID> active = new HashSet<>();
                    boolean isActive(ServerPlayer player){ return active.contains(player.id); }
                }
                static class AiTestContentRegistryBridge {
                    record Profile(String displayName){
                        String identity(){ return "PRIVATE_IDENTITY:"+displayName; }
                        String description(){ return "PRIVATE_DESCRIPTION:"+displayName; }
                        List<String> personality(){ return List.of("PERSONALITY:"+displayName); }
                        List<String> values(){ return List.of("VALUES:"+displayName); }
                        List<String> characterTags(){ return List.of("TAGS:"+displayName); }
                        List<String> restrictions(){ return List.of("RESTRICTIONS:"+displayName); }
                    }
                    record ContentSnapshot(Profile profile){
                        List<String> relationshipGuidance(){ return List.of("RELATION:"+profile.displayName()); }
                        List<String> socialRelationTags(){ return List.of("SOCIAL:"+profile.displayName()); }
                    }
                    record Lore(String id,int knowledgeLevel,String title,String accessibleLevels){}
                    record Load(ResourceLocation god, String relationship, List<ResourceLocation> audience){}
                    final List<Load> loads = new ArrayList<>(); final Set<ResourceLocation> fail = new HashSet<>();
                    ContentSnapshot load(ResourceLocation god, String tier, List<ResourceLocation> audience){
                        loads.add(new Load(god,tier,List.copyOf(audience)));
                        if (fail.contains(god)) throw new IllegalArgumentException("Unavailable profile: " + god);
                        return new ContentSnapshot(new Profile("name:" + god));
                    }
                }
                static class DialogueMemoryBridge {
                    record Context(String godId){}
                    record Turn(String value,Context context){
                        String referenceContext(){ return value; }
                        String prompt(){ return value; }
                        Object recall(){ return null; }
                        boolean recalling(){ return false; }
                        boolean hasExperiences(){ return false; }
                    }
                    static final Turn EMPTY = new Turn("",null);
                    static final Map<UUID,String> bindings = new HashMap<>();
                    static final List<UUID> unbound = new ArrayList<>();
                    static final Map<UUID,ResourceLocation> selected = new HashMap<>();
                    static final Map<UUID,CompletableFuture<Turn>> delayed = new HashMap<>();
                    static boolean failBinding;
                    static void unbind(UUID player){ unbound.add(player); bindings.remove(player); }
                    static void bind(ServerPlayer player, ResourceLocation god, UUID interaction){
                        if(failBinding) throw new IllegalStateException("Binding failed");
                        bindings.put(player.id,god.toString()); selected.put(player.id,god);
                    }
                    static void selectTestSpeaker(ServerPlayer player,ResourceLocation god,UUID interaction){
                        if(!com.sande.mythictrpg.ai.server.AiConversationRuntimeService.INSTANCE.testConversationId(player)
                                .filter(interaction::equals).isPresent()) throw new IllegalStateException("Stale lease");
                        bindings.put(player.id,god.toString()); selected.put(player.id,god);
                    }
                    static boolean current(ServerPlayer player, Turn memory){ return true; }
                    static CompletableFuture<Turn> beginAsync(ServerPlayer player, ResourceLocation god,
                            String text, long turn, Set<String> history, List<String> playerHistory){
                        var future = delayed.remove(player.id);
                        return future==null?CompletableFuture.completedFuture(EMPTY):future;
                    }
                }
                static class ConversationIntent {
                    static ConversationIntent heuristicFallback(){ return new ConversationIntent(); }
                    ConversationAct conversationAct(){ return new ConversationAct(); }
                }
                static class ConversationAct { String replyGuidance(){ return "Answer"; } }
                static class DialogueTurnDirective {
                    static boolean social;
                    static DialogueTurnDirective recall(){ return new DialogueTurnDirective(); }
                    static DialogueTurnDirective plan(String text,List<String> history,int participants,ConversationIntent intent){ return new DialogueTurnDirective(); }
                    boolean isSocialOnly(){ return social; }
                    String promptBlock(){ return "TURN_DIRECTIVE"; }
                    int maximumNpcReplies(){ return 16; }
                }
                static class ConversationDynamics {
                    boolean isPresent(){ return false; } String promptBlock(){ return ""; }
                }
                static class MemoryRecallPolicy {
                    static ConversationIntent effectiveIntent(ConversationIntent intent,boolean recalling){ return intent; }
                    static String generationSystem(String system,boolean hasMemory){ return system; }
                    static String recallSystem(String system,Object recall){ return system; }
                    static String fitContext(String context,boolean evidence){ return context; }
                }
                static class AiDialogueModels { record OllamaMessage(String role,String content){} }
                static class AiQuestContentBridge {
                    record QuestCandidate(String promptSummary){}
                    static List<QuestCandidate> candidatesFor(ResourceLocation god,ServerPlayer player){ return List.of(); }
                    static String attentionPrompt(ResourceLocation god,ServerPlayer player){ return ""; }
                }
                static class AiActionCapabilityBridge { static void appendPrompt(StringBuilder context,ResourceLocation god){} }
                static List<String> dialogueGuidelines(AiTestContentRegistryBridge.Profile profile,ConversationIntent intent,ConversationDynamics dynamics){
                    return List.of("GUIDELINE:"+profile.displayName());
                }
                static class AiDialogueConfig {
                    static final AiDialogueConfig INSTANCE = new AiDialogueConfig();
                    Settings settings(){ return new Settings(); }
                    static class Settings {
                        int maxPromptCharacters(){ return 1024; }
                        int maxResponseCharacters(){ return 2048; }
                        int transcriptMessages(){ return 32; }
                    }
                }
                record Log(UUID player, String category, String text){}
                static class Logs {
                    final List<Log> entries = new ArrayList<>(); boolean closed;
                    void append(Server server, UUID player, String category, String text){ entries.add(new Log(player,category,text)); }
                    void appendBlock(Server server, UUID player, String category, String text){ append(server,player,category,text); }
                    void close(){ closed = true; }
                }
                static class Client { boolean closed; void close(){ closed = true; } }
                static class LocalOllamaClient {
                    interface WireObserver { void onRequest(String json); void onResponse(String json); void onFailure(Throwable failure); }
                }
                static void sharePlayerLine(ServerPlayer player,String text){}
                static void pruneExperienceHistory(ServerPlayer player,Session session,ResourceLocation god){}
                static void updatePlayActivity(Session session,String text){}
            """ + memoryContinuation + methods + """
                private LocalOllamaClient.WireObserver observer(ServerPlayer player, Session session) {
                    return
            """ + observer + ";\n}\n" + """
                static int count;
                static void check(boolean condition,String why){ count++; if(!condition) throw new AssertionError(why); }
                static void emit(LocalOllamaClient.WireObserver observer){
                    observer.onRequest("request"); observer.onResponse("response"); observer.onFailure(new RuntimeException("failed"));
                }
                public static int run(){
                    var f = new DialogueSessionFixture();
                    var a = new ServerPlayer("A"); var b = new ServerPlayer("B"); var c = new ServerPlayer("C");
                    var demeter = new ResourceLocation("mythictrpg:demeter");
                    var fortuna = new ResourceLocation("mythictrpg:fortuna");
                    var athena = new ResourceLocation("mythictrpg:athena");
                    var gods = List.of(demeter,fortuna,athena);
                    check(f.start(a,gods,false)==StartResult.STARTED,"three god session starts");
                    Session sa = f.sessions.get(a.id);
                    check(sa.participants.equals(gods),"three gods and their requested order retained");
                    check(sa.profile.displayName().equals("name:"+demeter),"first profile drives initial speaker");
                    check(f.contentRegistry.loads.size()==3,"every participant profile resolved first");
                    check(f.contentRegistry.loads.stream().allMatch(load->load.audience.equals(gods)),"each profile gets full selected audience");
                    check(f.logs.entries.isEmpty(),"off does not write start log");
                    check(a.messages.getLast().contains("name:"+athena)&&a.messages.getLast().contains("기록=off"),"start feedback includes all gods and logging state");
                    check(DialogueMemoryBridge.unbound.equals(List.of(a.id)),"only successful selection detached old memory");
                    var runtime = com.sande.mythictrpg.ai.server.AiConversationRuntimeService.INSTANCE;
                    check(sa.testInteraction!=null&&runtime.testConversationId(a).orElseThrow().equals(sa.testInteraction),"CLI session obtains authoritative test lease");
                    check(runtime.participants.get(a).equals(gods)&&!runtime.recording.get(a),"game lease gets full audience and off policy");
                    check(DialogueMemoryBridge.bindings.get(a.id).equals(demeter.toString()),"test session binds first god memory");

                    DialogueMemoryBridge.bindings.put(a.id,"existing-memory");
                    f.contentRegistry.fail.add(athena);
                    int unbound = DialogueMemoryBridge.unbound.size(); int logged = f.logs.entries.size();
                    check(f.start(a,gods,true)==StartResult.CONTENT_FAILURE,"last profile failure rejects full selection");
                    check(f.sessions.get(a.id)==sa,"profile failure retains previous session atomically");
                    check("existing-memory".equals(DialogueMemoryBridge.bindings.get(a.id)),"profile failure retains memory binding");
                    check(DialogueMemoryBridge.unbound.size()==unbound&&f.logs.entries.size()==logged,"failed start has no unbind or dialogue log");
                    f.contentRegistry.fail.clear();
                    int loads = f.contentRegistry.loads.size();
                    check(f.start(a,List.of(),true)==StartResult.CONTENT_FAILURE,"empty selection rejected");
                    var tooMany = new ArrayList<ResourceLocation>();
                    for(int i=0;i<17;i++) tooMany.add(new ResourceLocation("test:god"+i));
                    check(f.start(a,tooMany,true)==StartResult.CONTENT_FAILURE,"over-limit selection rejected");
                    check(f.contentRegistry.loads.size()==loads&&f.sessions.get(a.id)==sa,"invalid size cannot resolve or publish partial session");
                    check(DialogueMemoryBridge.unbound.size()==unbound,"invalid size cannot clear existing memory");
                    GodAiDialogueService.INSTANCE.active.add(a.id);
                    check(f.start(a,List.of(fortuna),true)==StartResult.NORMAL_AI_SESSION_ACTIVE,"normal game dialogue blocks test start");
                    check(f.sessions.get(a.id)==sa&&"existing-memory".equals(DialogueMemoryBridge.bindings.get(a.id)),"active-session rejection preserves test and memory state");
                    check(f.contentRegistry.loads.size()==loads&&DialogueMemoryBridge.unbound.size()==unbound,"active-session rejection has no loading or unbinding");
                    GodAiDialogueService.INSTANCE.active.clear();
                    runtime.refused.add(a);
                    check(f.start(a,List.of(fortuna),true)==StartResult.CONTENT_FAILURE,"game lease refusal rejects the selection");
                    check(f.sessions.get(a.id)==sa&&runtime.testConversationId(a).orElseThrow().equals(sa.testInteraction),"refused lease preserves existing session and lease");
                    check("existing-memory".equals(DialogueMemoryBridge.bindings.get(a.id)),"refused lease preserves memory binding");
                    runtime.refused.clear();

                    check(f.start(b,List.of(fortuna,demeter,fortuna),true)==StartResult.STARTED,"independent on session starts");
                    Session sb = f.sessions.get(b.id);
                    check(sb.participants.equals(List.of(fortuna,demeter)),"adapter also deduplicates selected gods");
                    check(f.recordingAllowed(sb)&&!f.recordingAllowed(sa),"on/off policies remain player-local");
                    check(f.logs.entries.size()==1&&f.logs.entries.getFirst().player.equals(b.id),"only B start recorded");
                    var offObserver = f.observer(a,sa); var oldOnObserver = f.observer(b,sb);
                    logged = f.logs.entries.size(); emit(offObserver);
                    check(f.logs.entries.size()==logged,"off suppresses request response and failure payloads");
                    emit(oldOnObserver);
                    check(f.logs.entries.size()==logged+3,"on records all wire observer callbacks");
                    check(f.logs.entries.stream().allMatch(log->log.player.equals(b.id)),"A off traffic cannot enter B log");

                    logged = f.logs.entries.size();
                    f.handlePlayerText(a,"첫 질문");
                    f.seedNpcTurn(a,fortuna,"첫 답변");
                    f.handlePlayerText(a,"이전 질문을 기억하니?");
                    check(sa.history.size()==3,"off keeps in-session player and NPC history");
                    check(sa.history.get(0).text.equals("첫 질문")&&sa.history.get(1).text.equals("첫 답변"),"off preserves earlier turns verbatim");
                    check(f.promptHistory.get(a.id).size()==3,"next off request receives existing conversation history");
                    check(sb.history.isEmpty(),"other session history stays separate");
                    check(f.logs.entries.size()==logged,"off history and prompt preparation do not write logs");
                    check(f.isCurrent(a,sa,sa.turn),"off does not invalidate ordinary current requests");
                    check(sa.speaker().equals(demeter),"ordinary follow-up keeps first speaker");
                    f.handlePlayerText(a,"다음 신");
                    check(sa.speaker().equals(fortuna)&&DialogueMemoryBridge.selected.get(a.id).equals(fortuna),"second god has its own selected memory binding");
                    f.handlePlayerText(a,"다음 신");
                    check(sa.speaker().equals(athena)&&DialogueMemoryBridge.selected.get(a.id).equals(athena),"third god is reachable through real ingress");
                    f.handlePlayerText(a,"그 이야기를 계속해 줘");
                    check(sa.speaker().equals(athena),"ordinary continuation keeps third speaker");
                    f.handlePlayerText(a,fortuna+"에게 물어볼게");
                    check(sa.speaker().equals(fortuna),"explicit target switches actual ingress speaker");
                    check(f.logs.entries.size()==logged,"all off speaker changes stay unlogged");

                    var contents = f.loadContents(gods,"R_NEUTRAL");
                    var intent = ConversationIntent.heuristicFallback();
                    sa.memoryTurn = new DialogueMemoryBridge.Turn("PRIVATE_MEMORY_FORTUNA",new DialogueMemoryBridge.Context(fortuna.toString()));
                    var plan = generationPlan(sa,"모두 한마디 해줘",intent,contents);
                    check(plan.speakers().equals(List.of(fortuna)),"test generation isolates one selected speaker even for group wording");
                    for(boolean social : List.of(false,true)) {
                        DialogueTurnDirective.social = social;
                        String prompt = generationMessages(a,sa,"질문",contents,plan,List.of(),List.of(),new ConversationDynamics()).get(1).content();
                        check(prompt.contains("PRIVATE_IDENTITY:name:"+fortuna)&&prompt.contains("PRIVATE_MEMORY_FORTUNA"),"selected profile and its memory included");
                        check(!prompt.contains("PRIVATE_IDENTITY:name:"+demeter)&&!prompt.contains("PRIVATE_IDENTITY:name:"+athena),"other participant private profiles excluded");
                        check(!prompt.contains("PERSONALITY:name:"+demeter)&&!prompt.contains("RELATION:name:"+athena),"other personalities and relationship guidelines excluded");
                    }
                    DialogueTurnDirective.social = false;
                    try {
                        generationMessages(a,sa,"질문",contents,new GenerationPlan(plan.directive(),intent,List.of(athena)),List.of(),List.of(),new ConversationDynamics());
                        throw new AssertionError("cross-god private memory must reject generation");
                    } catch(IllegalArgumentException expected) { count++; }
                    sa.memoryTurn = DialogueMemoryBridge.EMPTY;

                    var failedMemory = new CompletableFuture<DialogueMemoryBridge.Turn>();
                    DialogueMemoryBridge.delayed.put(a.id,failedMemory);
                    f.handlePlayerText(a,"기억 조회 오류 시험");
                    check(sa.pending,"asynchronous memory lookup marks current turn pending");
                    failedMemory.completeExceptionally(new IllegalStateException("offline test failure"));
                    check(!sa.pending&&a.messages.getLast().contains("기억 조회에 실패"),"failed memory future clears pending through actual failure handler");
                    check(f.logs.entries.size()==logged,"off memory failure still suppresses diagnostics");

                    check(f.start(b,List.of(athena),false)==StartResult.STARTED,"on can be replaced by off");
                    Session replacedOff = f.sessions.get(b.id);
                    logged = f.logs.entries.size(); emit(oldOnObserver);
                    check(f.logs.entries.size()==logged,"old on callback cannot log after replacement with off");
                    check(!f.isCurrent(b,sb,sb.turn)&&!f.recordingAllowed(sb),"old session cannot apply or record after replacement");
                    check(f.start(b,List.of(athena),true)==StartResult.STARTED,"off can be replaced by on");
                    Session latestOn = f.sessions.get(b.id);
                    logged = f.logs.entries.size(); emit(oldOnObserver); emit(f.observer(b,replacedOff));
                    check(f.logs.entries.size()==logged,"new on policy cannot revive either old callback");
                    var lateAfterStop = f.observer(b,latestOn);
                    check(f.stop(b),"explicit stop removes live session");
                    check(runtime.testConversationId(b).isEmpty(),"stop releases current game-owned test lease");
                    check(f.logs.entries.size()==logged+1,"on stop writes one final lifecycle log");
                    logged = f.logs.entries.size(); emit(lateAfterStop);
                    check(f.logs.entries.size()==logged&&!f.isCurrent(b,latestOn,latestOn.turn),"stopped on callback neither logs nor becomes current");
                    check(!f.stop(b)&&f.logs.entries.size()==logged,"repeated stop has no duplicate log");

                    check(f.start(b,demeter)==StartResult.STARTED,"single-god compatibility overload remains available");
                    check(f.sessions.get(b.id).recordLogs,"existing internal single-god caller retains default logging");
                    check(f.sessions.get(b.id).testInteraction==null&&runtime.testConversationId(b).isEmpty(),"production overload does not create test lease");
                    var discarded = f.sessions.get(b.id); var discardedObserver = f.observer(b,discarded);
                    logged = f.logs.entries.size(); f.discardPlayer(b.id); emit(discardedObserver);
                    check(!f.isActive(b)&&!f.recordingAllowed(discarded)&&f.logs.entries.size()==logged,"discard invalidates callbacks without invented stop logs");
                    check(f.start(c,List.of(athena),true)==StartResult.STARTED,"third on session starts");
                    var logoutObserver = f.observer(c,f.sessions.get(c.id));
                    logged = f.logs.entries.size(); f.onPlayerLoggedOut(c); emit(logoutObserver);
                    check(f.logs.entries.size()==logged+1&&!f.isActive(c),"on logout emits one lifecycle record and blocks late callbacks");
                    check(runtime.testConversationId(c).isEmpty(),"logout releases authoritative lease");
                    logged = f.logs.entries.size(); f.onPlayerLoggedOut(a); emit(offObserver);
                    check(f.logs.entries.size()==logged&&!f.isActive(a),"off logout records nothing");
                    check(f.start(a,gods,false)==StartResult.STARTED,"off can restart after logout");
                    Session oldA = f.sessions.get(a.id);
                    var delayedMemory = new CompletableFuture<DialogueMemoryBridge.Turn>();
                    DialogueMemoryBridge.delayed.put(a.id,delayedMemory); f.handlePlayerText(a,"늦은 기억 조회");
                    f.promptHistory.remove(a.id);
                    check(f.start(a,List.of(athena),false)==StartResult.STARTED,"session can replace pending old memory turn");
                    Session newA = f.sessions.get(a.id);
                    delayedMemory.complete(DialogueMemoryBridge.EMPTY);
                    check(!f.promptHistory.containsKey(a.id)&&newA.history.isEmpty(),"late memory completion cannot enter replacement session");
                    check(!f.isCurrent(a,oldA,oldA.turn),"old game lease also rejects old response");
                    UUID externalReplacement = UUID.randomUUID(); runtime.leases.put(a,externalReplacement);
                    check(!f.isCurrent(a,newA,newA.turn),"revoked game lease rejects otherwise current adapter session");
                    int oldHistorySize = newA.history.size(); f.handlePlayerText(a,"이미 만료된 세션");
                    check(newA.history.size()==oldHistorySize,"expired lease rejects new chat ingress");
                    logged = f.logs.entries.size(); check(f.stop(a),"off stop succeeds");
                    check(f.logs.entries.size()==logged,"off stop does not write lifecycle logs");
                    check(runtime.testConversationId(a).orElseThrow().equals(externalReplacement),"old adapter stop cannot end replacement game lease");
                    runtime.leases.remove(a);
                    DialogueMemoryBridge.failBinding = true;
                    check(f.start(a,gods,true)==StartResult.CONTENT_FAILURE,"post-lease binding failure rejected");
                    check(!f.isActive(a)&&runtime.testConversationId(a).isEmpty(),"binding failure cleans published session and acquired lease");
                    DialogueMemoryBridge.failBinding = false;
                    check(f.start(b,fortuna)==StartResult.STARTED,"on session before shutdown");
                    var shutdownObserver = f.observer(b,f.sessions.get(b.id)); logged = f.logs.entries.size();
                    f.stop(); emit(shutdownObserver);
                    check(f.sessions.isEmpty()&&f.logs.closed&&f.llm.closed,"shutdown clears sessions and closes boundaries");
                    check(f.logs.entries.size()==logged,"shutdown blocks any remaining transport callback");
                    return count;
                }
            }
            """;
        Path root = Files.createDirectories(Path.of(args[1]));
        Path dir = Files.createTempDirectory(root, "dialogue-session-fixture-");
        Path source = dir.resolve("DialogueSessionFixture.java");
        Files.writeString(source, fixture, StandardCharsets.UTF_8);
        Path social = dir.resolve("SocialRuntime.java");
        Files.writeString(social, """
            package com.sande.mythictrpg.rumor;
            public final class SocialRuntime { public static void cancelPlayer(java.util.UUID player) {} }
            """, StandardCharsets.UTF_8);
        Path runtime = dir.resolve("AiConversationRuntimeService.java");
        Files.writeString(runtime, """
            package com.sande.mythictrpg.ai.server;
            import java.util.*;
            public final class AiConversationRuntimeService {
                public static final AiConversationRuntimeService INSTANCE = new AiConversationRuntimeService();
                public final Map<Object,UUID> leases = new HashMap<>();
                public final Map<Object,List<?>> participants = new HashMap<>();
                public final Map<Object,Boolean> recording = new HashMap<>();
                public final Set<Object> refused = new HashSet<>();
                public Optional<UUID> beginTestConversation(Object player,List<?> gods,boolean record){
                    if(refused.contains(player)) return Optional.empty();
                    UUID interaction = UUID.randomUUID(); leases.put(player,interaction);
                    participants.put(player,List.copyOf(gods)); recording.put(player,record);
                    return Optional.of(interaction);
                }
                public Optional<UUID> testConversationId(Object player){ return Optional.ofNullable(leases.get(player)); }
                public void endTestConversation(Object player,UUID interaction){
                    if(leases.remove(player,interaction)){ participants.remove(player); recording.remove(player); }
                }
            }
            """, StandardCharsets.UTF_8);
        Path quests = dir.resolve("QuestParticipationService.java");
        Files.writeString(quests, """
            package com.sande.mythictrpg.quest;
            public final class QuestParticipationService {
                public static final QuestParticipationService INSTANCE = new QuestParticipationService();
                public String contextFor(Object player,Object god){ return ""; }
            }
            """, StandardCharsets.UTF_8);
        Path module = Path.of(args[0]).toAbsolutePath().getParent();
        Path helper = Path.of("src/main/java/com/sande/mythai/response/DialogueTestArguments.java");
        while (module != null && !Files.isRegularFile(module.resolve(helper))) module = module.getParent();
        if (module == null) throw new AssertionError("Could not locate actual command argument/speaker helper sources");
        Path speaker = module.resolve("src/main/java/com/sande/mythai/response/DialogueTestSpeakerPolicy.java");
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "--release", "21", "-encoding", "UTF-8",
                "-d", dir.toString(), source.toString(), social.toString(), runtime.toString(), quests.toString(),
                module.resolve(helper).toString(), speaker.toString()) != 0) {
            throw new AssertionError("Generated dialogue session fixture did not compile");
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()}, null)) {
            int checks = (int) loader.loadClass("DialogueSessionFixture").getMethod("run").invoke(null);
            System.out.println("DialogueTestSessionTest: PASS (" + checks + " behavioral checks, " + loggingSites
                    + " guarded log sites); generated methods, no live server/LLM");
        }
    }

    private static String block(String text, String signature) {
        int start = text.indexOf(signature);
        if (start < 0) throw new AssertionError("Missing generated block: " + signature);
        return blockAt(text, start) + "\n";
    }

    private static String blockAt(String text, int start) {
        int opening = text.indexOf('{', start), depth = 1, end = opening + 1;
        if (opening < 0) throw new AssertionError("Block has no opening brace");
        while (depth > 0 && end < text.length()) {
            char next = text.charAt(end++);
            if (next == '{') depth++; else if (next == '}') depth--;
        }
        if (depth != 0) throw new AssertionError("Unterminated generated block");
        return text.substring(start, end);
    }
}
