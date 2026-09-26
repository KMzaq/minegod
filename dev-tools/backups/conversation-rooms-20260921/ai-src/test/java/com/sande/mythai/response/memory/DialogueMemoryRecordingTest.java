package com.sande.mythai.response.memory;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;

/** Executes the actual bridge write gates and lookup entry points against deterministic boundary stubs. */
public final class DialogueMemoryRecordingTest {
    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(args[0])).replace("\r\n", "\n");
        StringBuilder methods = new StringBuilder();
        for (String signature : List.of(
                "    private static Set<String> godAudience(",
                "    private static void recordPlayer(",
                "    private static CompletableFuture<Turn> beginPersonalAsync(",
                "    public static Turn begin(ServerPlayer player, ResourceLocation god, String text, long number, Set<String> recent,",
                "    public static void delivered(",
                "    private static String contextDirectory(",
                "    private static MemoryJournal.Key key(",
                "    private static UUID id(",
                "    private static String bounded(")) methods.append(block(source, signature));
        String fixture = """
            import java.util.*;
            import java.util.concurrent.*;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Path;
            public final class MemoryRecordingFixture {
                static int checks, searches, writes, readySearches;
                static final Map<UUID,ConversationMemoryContext> BOUND=new HashMap<>();
                static final Map<UUID,Turn> TURNS=new HashMap<>();
                static final Map<MemoryJournal.Key,RecallQuery.Focus> FOCUS=new HashMap<>();
                static final Map<Server,RecallSettings> RECALL_SETTINGS=new HashMap<>();
                static final Map<UUID,ExperienceHistory> DERIVED_HISTORY=new HashMap<>();
                static final Turn EMPTY=new Turn(null,null,List.of(),List.of(),"",0);
                record ResourceLocation(String value) {
                    public String toString(){return value;}
                    static ResourceLocation parse(String value){return new ResourceLocation(value);}
                }
                static final class Server {
                    final MemoryJournal journal=new MemoryJournal();
                    Path getServerDirectory(){return Path.of(".");}
                    Path getWorldPath(LevelResource ignored){return Path.of(".");}
                    boolean isSameThread(){return true;}
                }
                enum LevelResource { ROOT }
                static final class ServerPlayer {
                    final UUID id=UUID.randomUUID(); final Server server;
                    ServerPlayer(Server server){this.server=server;}
                    UUID getUUID(){return id;}
                }
                record ConversationMemoryContext(UUID worldId,UUID interactionId,UUID generation,UUID playerId,
                        String godId,Set<UUID> audience,boolean readOnly){}
                static final class AiConversationRuntimeService {
                    static final AiConversationRuntimeService INSTANCE=new AiConversationRuntimeService();
                    record Lease(List<ResourceLocation> gods,Map<ResourceLocation,ConversationMemoryContext> contexts,boolean recording){}
                    final Map<UUID,Lease> leases=new HashMap<>();
                    boolean isTestConversation(ServerPlayer p){return leases.containsKey(p.id);}
                    List<ResourceLocation> conversationGods(ServerPlayer p){var l=leases.get(p.id);return l==null?List.of():l.gods;}
                    Optional<ConversationMemoryContext> memoryContext(ServerPlayer p,ResourceLocation god){
                        var l=leases.get(p.id);return l==null?Optional.empty():Optional.ofNullable(l.contexts.get(god));
                    }
                    boolean memoryContextCurrent(ServerPlayer p,ConversationMemoryContext c){
                        return c!=null&&c.playerId().equals(p.id)&&memoryContext(p,ResourceLocation.parse(c.godId())).filter(c::equals).isPresent();
                    }
                    boolean recordingAllowed(ServerPlayer p,ConversationMemoryContext c){
                        var l=leases.get(p.id);return l!=null&&l.recording&&memoryContextCurrent(p,c);
                    }
                    ConversationMemoryContext install(ServerPlayer p,List<ResourceLocation> gods,boolean on,UUID world,UUID interaction,UUID generation){
                        var contexts=new LinkedHashMap<ResourceLocation,ConversationMemoryContext>();
                        for(var god:gods)contexts.put(god,new ConversationMemoryContext(world,interaction,generation,p.id,god.toString(),Set.of(p.id),false));
                        leases.put(p.id,new Lease(gods,contexts,on));return contexts.get(gods.getFirst());
                    }
                }
                static final class MemoryJournal {
                    enum Source { PLAYER_STATEMENT,NPC_UTTERANCE,HEARSAY_NPC }
                    record Key(UUID world,String god,UUID player){}
                    record Entry(UUID id,Key key,UUID session,long turn,Source source,Set<UUID> audience,
                            long occurredAt,String text,boolean important,Set<String> godAudience){}
                    record ReadView(List<Entry> entries){}
                    final List<Entry> entries=new ArrayList<>();
                    ReadView readView(Key key,Set<UUID> audience,Set<String> gods){
                        readySearches++;return new ReadView(entries.stream().filter(e->e.key.equals(key)&&e.audience.containsAll(audience)&&e.godAudience.containsAll(gods)).toList());
                    }
                    List<Entry> searchConversation(Key key,Set<UUID> audience,String query,Set<String> recent,
                            List<String> recentPlayers,UUID generation,long now,int maximum,long budget,Set<String> gods){
                        searches++;return readView(key,audience,gods).entries;
                    }
                }
                static MemoryJournal journal(Server server){return server.journal;}
                static void store(MemoryJournal journal,MemoryJournal.Entry entry){writes++;journal.entries.add(entry);}
                record RecallSettings(boolean enabled){static final RecallSettings OFF=new RecallSettings(false);}
                static final class RecallQuery {
                    record Scope(MemoryJournal.Key key,UUID generation,Set<UUID> audience){}
                    record Focus(){}
                    static RecallQuery plan(Scope scope,String text,long number,long now,Focus previous){return new RecallQuery();}
                    Focus focus(){return null;}
                }
                static final class RecallSearch {
                    record Result(List<MemoryJournal.Entry> selected){}
                    static Result unavailable(RecallQuery query,String reason){return new Result(List.of());}
                }
                static final class DerivedService {
                    static RecallSearch.Result search(MemoryJournal journal,Path config,Path directory,MemoryJournal.ReadView view,
                            RecallQuery query,RecallSettings settings,Set<String> recent,List<String> recentPlayers,long now){
                        return new RecallSearch.Result(view.entries());
                    }
                }
                static Executor reader(){return Runnable::run;}
                static Turn recallTurn(ConversationMemoryContext c,MemoryJournal journal,MemoryJournal.ReadView view,
                        List<RumorLedger.HeardRumor> rumors,long number,RecallSearch.Result result,RecallSettings settings){
                    return new Turn(c,journal,result.selected(),rumors,"remembered",number);
                }
                static final class RumorLedger {record HeardRumor(String text){}}
                static List<RumorLedger.HeardRumor> heard(Server server,ConversationMemoryContext c){return List.of();}
                static List<RumorLedger.HeardRumor> selectRumors(List<RumorLedger.HeardRumor> rows,String text,boolean opening){return rows;}
                static String prompt(List<MemoryJournal.Entry> rows,List<RumorLedger.HeardRumor> rumors){return rows.isEmpty()?"":"remembered";}
                static final class MemoryFoundationSettings {enum Mode{RUMOR_TEST} static Mode mode(){return Mode.RUMOR_TEST;}}
                static final class ExperienceHistory {
                    record Reference(java.util.function.BooleanSupplier guard){static Reference guarded(java.util.function.BooleanSupplier guard){return new Reference(guard);}}
                    int records; void record(String text,List<Reference> refs){records++;}
                }
                static final class Turn {
                    final ConversationMemoryContext context;final MemoryJournal journal;final List<MemoryJournal.Entry> selected;
                    final List<RumorLedger.HeardRumor> rumors;final String prompt;final long turn;boolean guarded;
                    Turn(ConversationMemoryContext c,MemoryJournal j,List<MemoryJournal.Entry> s,List<RumorLedger.HeardRumor> r,String p,long n){
                        context=c;journal=j;selected=s;rumors=r;prompt=p;turn=n;
                    }
                    ConversationMemoryContext context(){return context;} MemoryJournal journal(){return journal;}
                    long turn(){return turn;} boolean hasGuardedEvidence(){return guarded;}
                    boolean hasRumors(){return !rumors.isEmpty();} List<RumorLedger.HeardRumor> rumors(){return rumors;}
                    List<ExperienceHistory.Reference> evidence(){return List.of(new ExperienceHistory.Reference(()->true));}
                }
                static boolean current(ServerPlayer p,Turn turn){return AiConversationRuntimeService.INSTANCE.memoryContextCurrent(p,turn.context);}
            """ + methods + """
                static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
                static MemoryJournal.Entry prior(ConversationMemoryContext c,Set<String> gods){
                    return new MemoryJournal.Entry(UUID.randomUUID(),key(c),UUID.randomUUID(),1,MemoryJournal.Source.PLAYER_STATEMENT,
                            c.audience(),System.currentTimeMillis(),"기존에 저장된 약속",false,gods);
                }
                public static int run(){
                    var runtime=AiConversationRuntimeService.INSTANCE;var server=new Server();
                    var a=new ServerPlayer(server);var b=new ServerPlayer(server);
                    var gods=List.of(ResourceLocation.parse("mythictrpg:athena"),ResourceLocation.parse("mythictrpg:hermes"),ResourceLocation.parse("mythictrpg:hades"));
                    var all=Set.of("mythictrpg:athena","mythictrpg:hermes","mythictrpg:hades");
                    var world=UUID.randomUUID();var interaction=UUID.randomUUID();var generation=UUID.randomUUID();
                    var ca=runtime.install(a,gods,true,world,interaction,generation);
                    var cb=runtime.install(b,gods,false,world,interaction,generation);
                    BOUND.put(a.id,ca);BOUND.put(b.id,cb);
                    server.journal.entries.add(prior(ca,all));server.journal.entries.add(prior(cb,all));
                    int before=writes;var bTurn=begin(b,gods.getFirst(),"기억하니",2,Set.of(),List.of());
                    check(bTurn!=EMPTY&&!bTurn.selected.isEmpty(),"off synchronous begin still retrieves existing memory");
                    check(writes==before,"off synchronous begin does not enqueue player writes");
                    delivered(b,gods.getFirst(),"기억하고 있어");
                    check(writes==before,"off delivered NPC does not enqueue a write");
                    RECALL_SETTINGS.put(server,new RecallSettings(true));
                    var async=beginPersonalAsync(b,gods.getFirst(),"다시 알려줘",3,Set.of(),List.of()).join();
                    check(async!=EMPTY&&!async.selected.isEmpty(),"off async/derived lookup entry still retrieves");
                    check(writes==before&&readySearches>=2,"off async path keeps read activity but no raw append");
                    TURNS.put(b.id,async);async.guarded=true;delivered(b,gods.getFirst(),"근거에 기반한 답변");
                    check(writes==before&&DERIVED_HISTORY.get(b.id).records==1,"off keeps ephemeral evidence guard, not persistent NPC memory");
                    var aTurn=begin(a,gods.getFirst(),"세 신에게 하는 공동 약속",2,Set.of(),List.of());
                    check(writes==before+3,"on accepted player line records once for each of three actual Gods");
                    var captured=server.journal.entries.stream().filter(e->e.session().equals(generation)&&e.source()==MemoryJournal.Source.PLAYER_STATEMENT).toList();
                    check(captured.size()==3&&captured.stream().allMatch(e->e.key().player().equals(a.id)),"other player's off session never receives new records");
                    check(captured.stream().map(e->e.id()).distinct().count()==3,"same generation and turn yield distinct God IDs");
                    check(captured.stream().map(e->e.key().god()).collect(java.util.stream.Collectors.toSet()).equals(all),"all own God buckets recorded");
                    check(captured.stream().allMatch(e->e.godAudience().equals(all)),"all captured entries preserve full actual NPC audience");
                    check(!id("player",ca,2,"").equals(id("player",cb,2,"")),"same generation/interaction/turn across players does not collide");
                    delivered(a,gods.getFirst(),"공동 약속을 들었다");
                    check(writes==before+4,"on NPC delivery writes exactly once under real speaker");
                    var npc=server.journal.entries.getLast();
                    check(npc.key().equals(key(ca))&&npc.godAudience().equals(all)&&npc.source()==MemoryJournal.Source.NPC_UTTERANCE,"NPC memory has speaker identity and audience");
                    before=writes;delivered(a,gods.get(1),"잘못된 화자");
                    check(writes==before,"different speaker cannot use preceding speaker's turn");
                    runtime.leases.remove(a.id);delivered(a,gods.getFirst(),"늦은 응답");
                    check(writes==before,"stale delivered context cannot enqueue memory");
                    check(begin(a,gods.getFirst(),"늦은 입력",4,Set.of(),List.of())==EMPTY,"stale synchronous begin rejected before reading/writing");
                    check(beginPersonalAsync(a,gods.getFirst(),"늦은 입력",4,Set.of(),List.of()).join()==EMPTY,"stale asynchronous begin rejected");
                    check(writes==before,"stale paths left write count unchanged");
                    recordPlayer(b,cb,5,"off 직접 기록 경로");
                    check(writes==before,"direct multi-God recording entry point observes off");
                    return checks;
                }
            }
            """;
        Path root = Files.createDirectories(Path.of(args[1]));
        Path directory = Files.createTempDirectory(root, "memory-recording-fixture-");
        Path java = directory.resolve("MemoryRecordingFixture.java");
        Files.writeString(java, fixture, StandardCharsets.UTF_8);
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "--release", "21", "-encoding", "UTF-8",
                "-d", directory.toString(), java.toString()) != 0) throw new AssertionError("Actual memory bridge fixture did not compile");
        try (var loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, null)) {
            int checks = (int) loader.loadClass("MemoryRecordingFixture").getMethod("run").invoke(null);
            System.out.println("DialogueMemoryRecordingTest: PASS (" + checks + " behavioral checks); actual methods, no server/model");
        }
    }

    private static String block(String text, String signature) {
        int start = text.indexOf(signature);
        if (start < 0) throw new AssertionError("Missing bridge method: " + signature);
        int opening = text.indexOf('{', start), depth = 1, end = opening + 1;
        while (depth > 0 && end < text.length()) {
            char next = text.charAt(end++);
            if (next == '{') depth++; else if (next == '}') depth--;
        }
        if (opening < 0 || depth != 0) throw new AssertionError("Unterminated bridge method: " + signature);
        return text.substring(start, end) + "\n";
    }
}
