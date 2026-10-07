package com.sande.mythictrpg.ai.social;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.ai.room.RoomType;
import com.sande.mythictrpg.ai.social.RoomSocialContextProvider.Fact;
import com.sande.mythictrpg.ai.social.RoomSocialContextProvider.Kind;
import com.sande.mythictrpg.ai.social.RoomSocialContextProvider.ProviderSnapshot;
import com.sande.mythictrpg.ai.social.RoomSocialContextProvider.Publication;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Offline tests of actual policy/projection/revalidation; no server or model request. */
public final class RoomSocialContextTest {
    private static final ResourceLocation SPEAKER = id("speaker"), LISTENER = id("listener"), PATRON = id("patron");
    private static final ResourceLocation SOURCE = id("verified_game_service");
    private static final UUID PLAYER = UUID.randomUUID(), OTHER = UUID.randomUUID();
    private static final AffinityTierPolicy POLICY = AffinityTierPolicy.testDefaults();
    private static final Function<UUID, RoomSocialContext.Affinity> ZERO = ignored -> RoomSocialContext.Affinity.defaultZero();
    private static int checks;

    public static void main(String[] args) throws Exception {
        tierBoundariesAndInvalidPolicies();
        Path root = Files.createDirectories(Path.of(args[0]));
        policyFilesFailClearlyAndChangesInvalidate(root);
        unknownFactsAndUnassessedEmotion();
        disclosureIntersectsKnowledgeAndAllListeners();
        providerOwnershipAndSessionIsolation();
        splitHasNoFavoredCurrentPlayer();
        changedInputsInvalidateAndFailuresClose();
        immutableAndBounded();
        System.out.println("RoomSocialContextTest: " + checks + " assertions passed");
    }

    private static void tierBoundariesAndInvalidPolicies() {
        int[][] intervals = {{-1000,-900},{-899,-600},{-599,-300},{-299,-100},{-99,99},
                {100,299},{300,599},{600,899},{900,1000}};
        for (int i = 0; i < intervals.length; i++) for (int affinity = intervals[i][0]; affinity <= intervals[i][1]; affinity++)
            check(POLICY.tier(affinity).equals(AffinityTierPolicy.TAGS.get(i)), "default boundary " + affinity);
        check(POLICY.source().equals("TEST_DEFAULTS"), "defaults explicitly provisional");
        var custom = new AffinityTierPolicy(2, "CONFIGURED", List.of(-800,-500,-200,-50,50,200,500,800));
        check(custom.tier(-50).equals("R_WARY") && custom.tier(50).equals("R_FAVORABLE"), "custom inclusive endpoints");
        rejects(() -> POLICY.tier(-1001), "low affinity rejected");
        rejects(() -> POLICY.tier(1001), "high affinity rejected");
        rejects(() -> new AffinityTierPolicy(0, "CONFIGURED", POLICY.thresholds()), "revision required");
        rejects(() -> new AffinityTierPolicy(1, "CONFIGURED", List.of(-1001,-600,-300,-100,100,300,600,900)), "minimum bound");
        rejects(() -> new AffinityTierPolicy(1, "CONFIGURED", List.of(-900,-600,-300,-100,100,300,600,1001)), "maximum bound");
        rejects(() -> new AffinityTierPolicy(1, "CONFIGURED", List.of(-900,-600,-300,-100,100,300,300,900)), "duplicate bound");
        rejects(() -> new AffinityTierPolicy(1, "CONFIGURED", List.of(-900,-600,-300,0,100,300,600,900)), "negative endpoint not zero");
        rejects(() -> new AffinityTierPolicy(1, "CONFIGURED", List.of(-900,-600,-300,-100,0,300,600,900)), "positive endpoint not zero");
        rejects(() -> AffinityTierPolicy.parse("{}"), "missing policy fields");
        rejects(() -> AffinityTierPolicy.parse(config(1).replace("-900", "-900.5")), "fractional boundary rejected");
        rejects(() -> AffinityTierPolicy.parse(config(1).replace("-900", "\"-900\"")), "string boundary rejected");
        rejects(() -> AffinityTierPolicy.parse(config(1).replace("\"revision\":1", "\"revision\":1,\"other\":true")), "unknown field rejected");
        rejects(() -> AffinityTierPolicy.parse(config(1).replace("schemaVersion\":1", "schemaVersion\":2")), "unknown schema rejected");
    }

    private static void policyFilesFailClearlyAndChangesInvalidate(Path root) throws Exception {
        Path temp = Files.createTempDirectory(root, "social-policy-");
        Path config = temp.resolve("affinity.json");
        check(AffinityTierPolicy.load(config).equals(POLICY), "missing configuration uses labelled test default");
        Files.writeString(config, config(1));
        var configured = AffinityTierPolicy.load(config);
        check(configured.source().equals("CONFIGURED") && configured.thresholds().equals(POLICY.thresholds()), "configured policy parsed");
        var scope = scope(PLAYER, Set.of(PLAYER), Set.of(SPEAKER), false);
        var first = capture(scope, configured, ZERO, 1, Map.of());
        Files.writeString(config, config(2));
        check(!RoomSocialContext.isCurrent(first, () -> capture(scope, AffinityTierPolicy.load(config), ZERO, 1, Map.of())),
                "policy revision alone expires old reply");
        Files.writeString(config, config(1).replace("-900", "-850"));
        check(!RoomSocialContext.isCurrent(first, () -> capture(scope, AffinityTierPolicy.load(config), ZERO, 1, Map.of())),
                "changed boundary expires even at same revision/tier");
        Files.writeString(config, "{malformed");
        rejects(() -> AffinityTierPolicy.load(config), "malformed file does not invent a fallback");
        check(!RoomSocialContext.isCurrent(first, () -> capture(scope, AffinityTierPolicy.load(config), ZERO, 1, Map.of())),
                "malformed replacement cannot validate prior reply");
        Files.writeString(config, " ".repeat(4097));
        rejects(() -> AffinityTierPolicy.load(config), "oversized file rejected");
    }

    private static void unknownFactsAndUnassessedEmotion() {
        var scope = scope(PLAYER, Set.of(PLAYER), Set.of(SPEAKER), false);
        var empty = capture(scope, POLICY, ZERO, 1, Map.of());
        check(empty.currentPlayerTier().equals("R_NEUTRAL"), "existing game zero default yields a real neutral tier");
        check(empty.promptText().contains("GAME_DEFAULT_ZERO"), "default zero origin explicit");
        var data = json(empty);
        check(data.getAsJsonObject("emotion").get("assessment").getAsString().equals("UNASSESSED"), "emotion unassessed");
        check(!empty.promptText().contains("E_NEUTRAL"), "unknown emotion never neutral emotion");
        check(data.getAsJsonArray("confirmedFacts").isEmpty(), "no provider means no facts");
        for (Kind kind : Kind.values()) check(data.getAsJsonObject("factAssessments").get(kind.name()).getAsString().equals("UNKNOWN"),
                "missing " + kind + " is unknown");
        var negative = fact("none_verified", PLAYER, Kind.PATRONAGE, "The game verified that no patron contract is active.",
                Set.of(SPEAKER), privateFor(scope), Optional.empty(), 1);
        var confirmed = withFacts(scope, List.of(negative));
        check(confirmed.facts().size() == 1 && !confirmed.equals(empty), "verified negative differs from absence");
        check(json(confirmed).getAsJsonObject("factAssessments").get("PATRONAGE").getAsString().equals("PARTIAL_CONFIRMED_EVIDENCE"),
                "a confirmed fact does not claim complete omniscience");
        rejects(() -> capture(scope, POLICY, id -> null, 1, Map.of()), "unavailable affinity fails closed without neutral guideline");
    }

    private static void disclosureIntersectsKnowledgeAndAllListeners() {
        var scope = scope(PLAYER, Set.of(PLAYER, OTHER), Set.of(SPEAKER, LISTENER), false);
        var privatePlayer = fact("secret_player", PLAYER, Kind.POWER, "PRIVATE_PLAYER_SECRET", Set.of(SPEAKER),
                new Publication(false, Set.of(PLAYER), Set.of(SPEAKER, LISTENER)), Optional.empty(), 1);
        var privateGod = fact("secret_god", PLAYER, Kind.OBLIGATION, "PRIVATE_GOD_SECRET", Set.of(SPEAKER),
                new Publication(false, Set.of(PLAYER, OTHER), Set.of(SPEAKER)), Optional.empty(), 1);
        var unknownGod = fact("other_god", PLAYER, Kind.REPUTATION, "OTHER_GOD_SECRET", Set.of(LISTENER),
                privateFor(scope), Optional.empty(), 1);
        var wrongSubject = fact("other_player", UUID.randomUUID(), Kind.POWER, "OUTSIDE_PLAYER_SECRET", Set.of(SPEAKER),
                privateFor(scope), Optional.empty(), 1);
        var permitted = fact("patron", PLAYER, Kind.PATRONAGE, "Verified patron covenant.", Set.of(SPEAKER),
                privateFor(scope), Optional.of(PATRON), 1);
        var result = withFacts(scope, List.of(privatePlayer, privateGod, unknownGod, wrongSubject, permitted));
        check(result.facts().equals(List.of(permitted)), "intersect knowledge, subject and every listener's publication permission");
        check(!result.promptText().contains("SECRET"), "withheld facts never enter prompt");
        check(result.promptText().contains(PATRON.toString()) && !result.scope().participantGodIds().contains(PATRON),
                "known permitted patron reference does not create participant");
        var publicScope = scope(PLAYER, Set.of(PLAYER, OTHER), Set.of(SPEAKER, LISTENER), true);
        check(withFacts(publicScope, List.of(permitted)).facts().isEmpty(), "private authorization cannot authorize public output");
        var publicFact = fact("public", PLAYER, Kind.POWER, "Verified public feat.", Set.of(SPEAKER),
                new Publication(true, Set.of(), Set.of()), Optional.empty(), 1);
        check(withFacts(publicScope, List.of(publicFact)).facts().size() == 1, "explicit public fact allowed");
        var changedAudience = new RoomSocialContext.Scope(scope.roomId(), scope.roomRevision(), scope.roomType(), SPEAKER, PLAYER,
                Set.of(PLAYER), Set.of(SPEAKER), Set.of(PLAYER));
        check(withFacts(changedAudience, List.of(privatePlayer)).facts().size() == 1, "private fact can reach precisely allowed audience");
        var ownAssessment = fact("own_assessment", PLAYER, Kind.REPUTATION, "This God doubts the received claim.",
                Set.of(SPEAKER), privateFor(scope), Optional.empty(), 1);
        check(withFacts(scope, List.of(ownAssessment)).facts().equals(List.of(ownAssessment)),
                "God may express own assessment to an authorized private audience");
        var listenerTurn = new RoomSocialContext.Scope(scope.roomId(), scope.roomRevision(), scope.roomType(), LISTENER, PLAYER,
                scope.participantPlayerIds(), scope.participantGodIds(), scope.audiencePlayerIds());
        check(withFacts(listenerTurn, List.of(ownAssessment)).facts().isEmpty(),
                "publication permission does not make another God's assessment the listener's own knowledge");
    }

    private static void providerOwnershipAndSessionIsolation() {
        var a = scope(PLAYER, Set.of(PLAYER), Set.of(SPEAKER), false);
        var b = scope(OTHER, Set.of(OTHER), Set.of(LISTENER), false);
        var facts = List.of(fact("a", PLAYER, Kind.OBLIGATION, "A_ONLY", Set.of(SPEAKER), privateFor(a), Optional.empty(), 1));
        var capturedA = new ProviderSnapshot(SOURCE, 1, a, facts);
        rejects(() -> capture(b, POLICY, ZERO, 1, Map.of(SOURCE, ignored -> capturedA)), "Session A result cannot be attached to B");
        rejects(() -> capture(a, POLICY, ZERO, 1, Map.of(id("impostor"), ignored -> capturedA)), "provider identity cannot be substituted");
        rejects(() -> new ProviderSnapshot(id("impostor"), 1, a, facts), "provider cannot own another source's facts");
        check(withFacts(b, facts).facts().isEmpty(), "even relabelled scope cannot publish foreign player/God data");
        rejects(() -> new RoomSocialContext.Scope(a.roomId(), 1, RoomType.PRIVATE, SPEAKER, OTHER, Set.of(PLAYER),
                Set.of(SPEAKER), Set.of(PLAYER)), "current player must be participant");
        rejects(() -> new RoomSocialContext.Scope(a.roomId(), 1, RoomType.PRIVATE, PATRON, PLAYER, Set.of(PLAYER),
                Set.of(SPEAKER), Set.of(PLAYER)), "patron reference cannot become speaker");
        rejects(() -> new RoomSocialContext.Scope(a.roomId(), 1, RoomType.PRIVATE, SPEAKER, PLAYER, Set.of(PLAYER),
                Set.of(SPEAKER), Set.of(PLAYER, OTHER)), "private outsider audience rejected");
    }

    private static void splitHasNoFavoredCurrentPlayer() {
        var split = new RoomSocialContext.Scope(UUID.randomUUID(), 1, RoomType.PUBLIC_MOBILE, SPEAKER, null,
                Set.of(PLAYER, OTHER), Set.of(SPEAKER), Set.of(PLAYER, OTHER), RoomSocialContext.Purpose.SYSTEM_SPLIT_CONTEXT);
        var result = capture(split, POLICY, player -> new RoomSocialContext.Affinity(player.equals(PLAYER) ? 750 : -750, false), 1, Map.of());
        check(!json(result).has("currentPlayerId"), "split does not fabricate a current player");
        check(json(result).get("purpose").getAsString().equals("SYSTEM_SPLIT_CONTEXT"), "split purpose explicit");
        check(result.relationships().size() == 2 && result.promptText().contains("R_TRUSTED") && result.promptText().contains("R_HOSTILE"),
                "split retains each participant's independent relationship");
        rejects(result::currentPlayerTier, "split cannot use one arbitrary player's content tier");
        rejects(() -> new RoomSocialContext.Scope(split.roomId(), 1, split.roomType(), SPEAKER, PLAYER,
                split.participantPlayerIds(), split.participantGodIds(), split.audiencePlayerIds(), RoomSocialContext.Purpose.SYSTEM_SPLIT_CONTEXT),
                "split cannot silently prioritize a current player");
    }

    private static void changedInputsInvalidateAndFailuresClose() {
        var scope = scope(PLAYER, Set.of(PLAYER, OTHER), Set.of(SPEAKER), false);
        var affinities = new LinkedHashMap<UUID, RoomSocialContext.Affinity>();
        affinities.put(PLAYER, new RoomSocialContext.Affinity(700, false));
        affinities.put(OTHER, new RoomSocialContext.Affinity(-400, false));
        var initialFact = fact("power", PLAYER, Kind.POWER, "Confirmed battle evidence.", Set.of(SPEAKER), privateFor(scope), Optional.empty(), 1);
        var provider = new MutableProvider(scope, List.of(initialFact));
        Map<ResourceLocation, RoomSocialContextProvider> providers = Map.of(SOURCE, provider);
        var initial = capture(scope, POLICY, affinities::get, 1, providers);
        check(initial.currentPlayerTier().equals("R_TRUSTED"), "current player own tier chosen");
        check(RoomSocialContext.isCurrent(initial, () -> capture(scope, POLICY, affinities::get, 1, providers)), "unchanged snapshot current");
        affinities.put(OTHER, new RoomSocialContext.Affinity(-399, false));
        check(!RoomSocialContext.isCurrent(initial, () -> capture(scope, POLICY, affinities::get, 1, providers)), "other participant affinity change invalidates even same tier");
        affinities.put(OTHER, new RoomSocialContext.Affinity(-400, false));
        provider.revision++;
        check(!RoomSocialContext.isCurrent(initial, () -> capture(scope, POLICY, affinities::get, 1, providers)), "provider revision invalidates even same fact");
        provider.revision--;
        provider.facts = List.of(fact("power", PLAYER, Kind.POWER, "Changed battle evidence.", Set.of(SPEAKER), privateFor(scope), Optional.empty(), 1));
        check(!RoomSocialContext.isCurrent(initial, () -> capture(scope, POLICY, affinities::get, 1, providers)), "changed fact invalidates even unchanged provider revision");
        provider.facts = List.of(initialFact);
        check(!RoomSocialContext.isCurrent(initial, () -> capture(scope, POLICY, affinities::get, 2, providers)), "provider replacement registry revision invalidates");
        var differentPlayer = new RoomSocialContext.Scope(scope.roomId(), scope.roomRevision(), scope.roomType(), SPEAKER, OTHER,
                scope.participantPlayerIds(), scope.participantGodIds(), scope.audiencePlayerIds());
        check(!RoomSocialContext.isCurrent(initial, () -> capture(differentPlayer, POLICY, affinities::get, 1, Map.of())), "different current player invalidates");
        var foreignRoom = scope(PLAYER, Set.of(PLAYER, OTHER), Set.of(SPEAKER), false);
        check(!RoomSocialContext.isCurrent(initial, () -> capture(foreignRoom, POLICY, affinities::get, 1, Map.of())), "other room rejected");
        provider.fail = true;
        rejects(() -> capture(scope, POLICY, affinities::get, 1, providers), "provider exception closes capture");
        check(!RoomSocialContext.isCurrent(initial, () -> capture(scope, POLICY, affinities::get, 1, providers)), "provider exception rejects late reply");
        check(!RoomSocialContext.isCurrent(initial, () -> capture(scope, POLICY, affinities::get, 1, Map.of(SOURCE, ignored -> null))), "null provider result fails closed");
    }

    private static void immutableAndBounded() {
        var scope = scope(PLAYER, Set.of(PLAYER), Set.of(SPEAKER), false);
        var list = new ArrayList<Fact>();
        list.add(fact("one", PLAYER, Kind.POWER, "Confirmed.", Set.of(SPEAKER), privateFor(scope), Optional.empty(), 1));
        var provider = new ProviderSnapshot(SOURCE, 1, scope, list);
        list.clear();
        check(provider.facts().size() == 1, "provider result copied");
        var result = withFacts(scope, provider.facts());
        rejects(() -> result.facts().clear(), "snapshot facts immutable");
        rejects(() -> result.relationships().clear(), "snapshot relationships immutable");
        rejects(() -> result.scope().participantGodIds().add(PATRON), "snapshot scope immutable");
        rejects(() -> new ProviderSnapshot(SOURCE, 1, scope, List.of(provider.facts().getFirst(), provider.facts().getFirst())), "duplicate fact ID rejected");
        var excessive = new ArrayList<Fact>();
        for (int i = 0; i <= RoomSocialContext.MAX_VISIBLE_FACTS; i++) excessive.add(fact("fact_" + i, PLAYER, Kind.POWER,
                "Confirmed.", Set.of(SPEAKER), privateFor(scope), Optional.empty(), 1));
        rejects(() -> withFacts(scope, excessive), "visible fact budget enforced");
        rejects(() -> fact("long", PLAYER, Kind.POWER, "x".repeat(257), Set.of(SPEAKER), privateFor(scope), Optional.empty(), 1), "unbounded statement rejected");
        var maximumPlayers = new java.util.LinkedHashSet<UUID>(); maximumPlayers.add(PLAYER);
        while (maximumPlayers.size() < 64) maximumPlayers.add(UUID.randomUUID());
        var largest = capture(scope(PLAYER, maximumPlayers, Set.of(SPEAKER), false), POLICY,
                id -> new RoomSocialContext.Affinity(-1000, false), 1, Map.of());
        check(largest.relationships().size() == 64 && largest.promptText().length() <= RoomSocialContext.MAX_PROMPT_CHARACTERS,
                "maximum supported room retains all basic relationships within the social budget");
    }

    private static RoomSocialContext.Snapshot withFacts(RoomSocialContext.Scope scope, List<Fact> facts) {
        return capture(scope, POLICY, ZERO, 1, Map.of(SOURCE, queried -> new ProviderSnapshot(SOURCE, 1, queried, facts)));
    }
    private static RoomSocialContext.Snapshot capture(RoomSocialContext.Scope scope, AffinityTierPolicy policy,
            Function<UUID, RoomSocialContext.Affinity> values, long revision, Map<ResourceLocation, RoomSocialContextProvider> providers) {
        return RoomSocialContext.capture(scope, policy, values, revision, providers);
    }
    private static RoomSocialContext.Scope scope(UUID player, Set<UUID> players, Set<ResourceLocation> gods, boolean publicRoom) {
        ResourceLocation speaker = gods.contains(SPEAKER) ? SPEAKER : LISTENER;
        return new RoomSocialContext.Scope(UUID.randomUUID(), 1, publicRoom ? RoomType.PUBLIC_MOBILE : RoomType.PRIVATE,
                speaker, player, players, gods, players);
    }
    private static Publication privateFor(RoomSocialContext.Scope scope) {
        return new Publication(false, scope.audiencePlayerIds(), scope.participantGodIds());
    }
    private static Fact fact(String id, UUID subject, Kind kind, String statement, Set<ResourceLocation> knows,
            Publication publication, Optional<ResourceLocation> reference, long revision) {
        return new Fact(id, subject, kind, statement, SOURCE, "game-evidence/" + id, revision, knows, publication, reference);
    }
    private static com.google.gson.JsonObject json(RoomSocialContext.Snapshot snapshot) {
        return JsonParser.parseString(snapshot.promptText().substring(snapshot.promptText().indexOf('\n') + 1)).getAsJsonObject();
    }
    private static String config(int revision) {
        return "{\"schemaVersion\":1,\"revision\":" + revision + ",\"thresholds\":[-900,-600,-300,-100,100,300,600,900]}";
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("test", path); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void rejects(Runnable operation, String message) {
        checks++;
        try { operation.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError(message);
    }
    private static final class MutableProvider implements RoomSocialContextProvider {
        final RoomSocialContext.Scope scope;
        List<Fact> facts;
        long revision = 1;
        boolean fail;
        MutableProvider(RoomSocialContext.Scope scope, List<Fact> facts) { this.scope = scope; this.facts = facts; }
        @Override public ProviderSnapshot capture(RoomSocialContext.Scope ignored) {
            if (fail) throw new IllegalStateException("unavailable");
            return new ProviderSnapshot(SOURCE, revision, scope, facts);
        }
    }
}
