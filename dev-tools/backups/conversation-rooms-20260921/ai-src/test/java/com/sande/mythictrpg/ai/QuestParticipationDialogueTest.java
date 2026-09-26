package com.sande.mythictrpg.ai;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;

/** Executes the generated peer-ingress/late-response guard with stubbed Minecraft/LLM boundaries. */
public final class QuestParticipationDialogueTest {
    public static void main(String[] args) throws Exception {
        String adapter = Files.readString(Path.of(args[0]));
        String peer = method(adapter, "    public void observeConversationPeer(");
        String guard = method(adapter, "    private boolean isCurrent(");
        String testLease = method(adapter, "    private static boolean testLeaseCurrent(");
        if (!adapter.contains("sharePlayerLine(player, text);\n        session.pending = true;"))
            throw new AssertionError("peer ingress missing from player request path");
        if (!adapter.contains("QuestParticipationService.INSTANCE.contextFor(player, godId)")
                || !adapter.contains("QuestParticipationService.INSTANCE.contextFor(player, plan.speakers().getFirst())"))
            throw new AssertionError("authoritative quest context missing");
        String fixture = """
            import java.util.*;
            public class PeerFixture {
                private final Map<UUID, Session> sessions = new HashMap<>();
                record Profile(String name) { String getName() { return name; } }
                record ServerPlayer(UUID uuid, Profile profile) { UUID getUUID(){ return uuid; } Profile getGameProfile(){ return profile; } }
                record Transcript(String role, String id, String name, String text) {}
                static class Session {
                    long turn = 5; boolean pending = true; UUID testInteraction;
                    final List<Transcript> history = new ArrayList<>();
                    final Map<String,String> memoryTurn;
                    Session(String name) { memoryTurn = Map.of("quest", "q-"+name, "reward", "r-"+name, "feedback", "f-"+name); }
                }
                static class DialogueMemoryBridge {
                    static boolean current(ServerPlayer p, Map<String,String> context) { return !context.containsKey("revoked"); }
                }
                static String bounded(String s, int n) { return s.substring(0, Math.min(n, s.length())); }
                static void trimHistory(Session s) { while(s.history.size()>32) s.history.removeFirst(); }
            """ + peer + guard + testLease + """
                static int count;
                static void check(boolean ok, String why) { count++; if(!ok) throw new AssertionError(why); }
                public static int run() {
                    var f = new PeerFixture();
                    var a = new ServerPlayer(UUID.randomUUID(), new Profile("A"));
                    var b = new ServerPlayer(UUID.randomUUID(), new Profile("B"));
                    var c = new ServerPlayer(UUID.randomUUID(), new Profile("C"));
                    var sa = new Session("A"); var sb = new Session("B"); var sc = new Session("C");
                    f.sessions.put(a.uuid, sa); f.sessions.put(b.uuid, sb); f.sessions.put(c.uuid, sc);
                    check(f.isCurrent(b,sb,5), "initial request not current");
                    f.observeConversationPeer(b,a,"공동 발언");
                    check(sb.turn==6 && !sb.pending, "old worker not cancelled");
                    check(com.sande.mythictrpg.rumor.SocialRuntime.cancelled.equals(Set.of(b.uuid)), "only listener social review cancelled");
                    check(!f.isCurrent(b,sb,5), "late answer accepted");
                    check(f.isCurrent(a,sa,5) && f.isCurrent(c,sc,5), "other session invalidated");
                    check(sb.history.getFirst().id.equals(a.uuid.toString()), "speaker attribution lost");
                    check(sb.history.getFirst().name.equals("A"), "speaker name lost");
                    check(sa.history.isEmpty() && sc.history.isEmpty(), "private audience leaked");
                    for (var key : List.of("quest","reward","feedback")) {
                        check(sa.memoryTurn.get(key).endsWith("-A"), "A constraint overwritten");
                        check(sb.memoryTurn.get(key).endsWith("-B"), "B constraint overwritten");
                        check(sc.memoryTurn.get(key).endsWith("-C"), "C constraint overwritten");
                    }
                    f.observeConversationPeer(b,a,"x".repeat(5000));
                    check(sb.history.getLast().text.length()==1024, "peer text unbounded");
                    f.sessions.put(b.uuid, new Session("replacement"));
                    check(!f.isCurrent(b,sb,sb.turn), "replaced session revived");
                    f.sessions.remove(b.uuid); f.observeConversationPeer(b,a,"late");
                    check(!f.sessions.containsKey(b.uuid), "left listener silently re-added");
                    var runtime = com.sande.mythictrpg.ai.server.AiConversationRuntimeService.INSTANCE;
                    sa.testInteraction = UUID.randomUUID(); runtime.leases.put(a,sa.testInteraction);
                    check(f.isCurrent(a,sa,sa.turn), "current authoritative test lease rejected");
                    runtime.leases.put(a,UUID.randomUUID());
                    check(!f.isCurrent(a,sa,sa.turn), "replaced authoritative test lease accepted");
                    check(f.isCurrent(c,sc,sc.turn), "production peer without a test lease invalidated");
                    return count;
                }
            }
            """;
        Path root = Files.createDirectories(Path.of(args[1]));
        Path dir = Files.createTempDirectory(root, "peer-fixture-");
        Path source = dir.resolve("PeerFixture.java"); Files.writeString(source, fixture, StandardCharsets.UTF_8);
        Path social = dir.resolve("SocialRuntime.java");
        Files.writeString(social, """
            package com.sande.mythictrpg.rumor;
            public final class SocialRuntime {
                public static final java.util.Set<java.util.UUID> cancelled = new java.util.HashSet<>();
                public static void cancelPlayer(java.util.UUID player) { cancelled.add(player); }
            }
            """, StandardCharsets.UTF_8);
        Path runtime = dir.resolve("AiConversationRuntimeService.java");
        Files.writeString(runtime, """
            package com.sande.mythictrpg.ai.server;
            public final class AiConversationRuntimeService {
                public static final AiConversationRuntimeService INSTANCE = new AiConversationRuntimeService();
                public final java.util.Map<Object,java.util.UUID> leases = new java.util.HashMap<>();
                public java.util.Optional<java.util.UUID> testConversationId(Object player) {
                    return java.util.Optional.ofNullable(leases.get(player));
                }
            }
            """, StandardCharsets.UTF_8);
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "--release", "21", "-encoding", "UTF-8", "-d", dir.toString(), source.toString(), social.toString(), runtime.toString()) != 0)
            throw new AssertionError("Generated peer fixture did not compile");
        try (var loader = new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()}, null)) {
            int checks = (int) loader.loadClass("PeerFixture").getMethod("run").invoke(null);
            System.out.println("QuestParticipationDialogueTest: PASS (" + (checks + 2) + " checks); generated methods, not live LLM/HUD");
        }
    }
    private static String method(String text, String start) {
        int from = text.indexOf(start); if (from < 0) throw new AssertionError("Missing method: " + start);
        int opening = text.indexOf('{', from), depth = 1, end = opening + 1;
        while (depth > 0) { char c = text.charAt(end++); if (c == '{') depth++; else if (c == '}') depth--; }
        return text.substring(from, end) + "\n";
    }
}
