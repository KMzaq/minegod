package com.sande.mythictrpg.godavatar.activity;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded game-issued NPC experiences. Projections never reveal lifecycle detail or invent room/player identity. */
public final class NpcActivityMemory {
    public static final int MAX_GODS = 2048, MAX_EVENTS_PER_GOD = 64, MAX_VIEW = 6;
    private static final Set<String> PHASES = Set.of("STARTED", "COMPLETED", "FAILED", "INTERRUPTED", "SPEECH");
    // Retrieval aliases for game-owned activity kinds, not canned dialogue or invented emotional rules.
    private static final Map<String, List<String>> KOREAN_KIND_TERMS = Map.ofEntries(
            Map.entry("OBSERVE", List.of("관찰", "둘러보", "살펴보")), Map.entry("REST", List.of("휴식", "쉬었", "쉬고", "쉬는", "쉬어")),
            Map.entry("READ", List.of("독서", "읽", "책")), Map.entry("STROLL", List.of("산책", "걷", "걸었")),
            Map.entry("CONVERSE", List.of("대화", "이야기", "얘기")), Map.entry("LISTEN", List.of("경청", "듣", "들었")),
            Map.entry("FOLLOW", List.of("따라", "동행")), Map.entry("GUIDE", List.of("안내", "길잡이")),
            Map.entry("WITHDRAW", List.of("물러", "퇴각", "철수")), Map.entry("INSPECT", List.of("점검", "검사", "조사")),
            Map.entry("EAT", List.of("먹", "음식", "식사")), Map.entry("DRINK", List.of("마시", "마셨", "음료")),
            Map.entry("TRAIN", List.of("훈련", "수련", "대련")), Map.entry("CRAFT", List.of("제작", "만들", "만든")),
            Map.entry("REPAIR", List.of("수리", "고치", "고쳤")), Map.entry("COOK", List.of("요리", "조리")),
            Map.entry("FARM", List.of("농사", "농작", "수확", "재배")), Map.entry("RITUAL", List.of("의식", "제의")),
            Map.entry("OFFERING", List.of("봉헌", "공물", "바쳤")), Map.entry("PLAY", List.of("놀이", "놀았", "놀고", "주사위")),
            Map.entry("PERFORM", List.of("공연", "연주", "노래")), Map.entry("SOCIAL", List.of("교류", "사교", "친교")));

    public record Event(UUID eventId, UUID runId, String godId, UUID actorId, long gameTime,
            String activityId, String kind, String mode, String phase, String detail,
            String speakerGodId, String speech, Set<String> heardGodIds, Set<UUID> heardPlayers) {
        public Event {
            Objects.requireNonNull(eventId); Objects.requireNonNull(runId); Objects.requireNonNull(actorId);
            id(godId); id(activityId); token(kind, 40); token(mode, 20); ActivityKind.valueOf(kind);
            Objects.requireNonNull(detail); Objects.requireNonNull(speakerGodId); Objects.requireNonNull(speech);
            heardGodIds = Set.copyOf(heardGodIds); heardPlayers = Set.copyOf(heardPlayers);
            if (gameTime < 0 || !PHASES.contains(phase) || !Set.of("REAL", "DECORATIVE").contains(mode)
                    || detail.length() > 1200 || speech.length() > 1200 || heardGodIds.size() > 16 || heardPlayers.size() > 256)
                throw new IllegalArgumentException("Invalid activity experience");
            heardGodIds.forEach(NpcActivityMemory::id);
            if (phase.equals("SPEECH")) {
                id(speakerGodId);
                if (speech.isBlank() || !heardGodIds.contains(godId) || !heardGodIds.contains(speakerGodId))
                    throw new IllegalArgumentException("Unproven activity speech audience");
            } else if (!speakerGodId.isEmpty() || !speech.isEmpty())
                throw new IllegalArgumentException("Lifecycle event cannot impersonate speech");
        }
    }

    /** Safe lifecycle projection: detail, exact place, participant names and book contents are never included. */
    public record Memory(UUID eventId, long gameTime, String kind, String mode, String phase,
            String speakerGodId, String speech) {
        public Memory {
            Objects.requireNonNull(eventId); Objects.requireNonNull(kind); Objects.requireNonNull(mode);
            Objects.requireNonNull(phase); Objects.requireNonNull(speakerGodId); Objects.requireNonNull(speech);
        }
    }
    /** Qualitative prior interpretation, never a present game-owned mood or relationship score. */
    public record Affect(String hint, List<UUID> sourceEventIds) {
        public Affect {
            Objects.requireNonNull(hint); sourceEventIds = List.copyOf(sourceEventIds);
            if (hint.length() > 120 || hint.codePoints().anyMatch(Character::isISOControl)
                    || sourceEventIds.size() > MAX_EVENTS_PER_GOD || new HashSet<>(sourceEventIds).size() != sourceEventIds.size()
                    || hint.isBlank() != sourceEventIds.isEmpty())
                throw new IllegalArgumentException("Invalid activity affect");
        }
        public static Affect empty() { return new Affect("", List.of()); }
    }
    public record View(String godId, long revision, List<Memory> experiences, Affect affect) {
        public View(String godId, long revision, List<Memory> experiences) {
            this(godId, revision, experiences, Affect.empty());
        }
        public View {
            id(godId); experiences = List.copyOf(experiences); Objects.requireNonNull(affect);
            if (revision < 0 || experiences.size() > MAX_VIEW
                    || experiences.stream().map(Memory::eventId).distinct().count() != experiences.size())
                throw new IllegalArgumentException("Invalid activity memory view");
        }
        public static View empty(String godId) { return new View(godId, 0, List.of(), Affect.empty()); }
    }

    private final BooleanSupplier enabled;
    private final Runnable onMutation;
    private final Map<String, GodMemory> gods = new LinkedHashMap<>();
    private static final class GodMemory {
        long revision;
        final LinkedHashMap<UUID, Event> events = new LinkedHashMap<>();
        Affect affect = Affect.empty();
    }
    public NpcActivityMemory(BooleanSupplier enabled, Runnable onMutation) {
        this.enabled = Objects.requireNonNull(enabled); this.onMutation = Objects.requireNonNull(onMutation);
    }
    public boolean record(Event event) {
        Objects.requireNonNull(event);
        if (!enabled.getAsBoolean()) return false;
        GodMemory state = gods.get(event.godId());
        if (state != null && state.events.containsKey(event.eventId())) {
            if (!state.events.get(event.eventId()).equals(event))
                throw new IllegalArgumentException("Conflicting activity event identity");
            return false;
        }
        if (state == null) {
            // Optional long-term memory must not interrupt a physical activity when its bounded store is full.
            if (gods.size() >= MAX_GODS) return false;
            state = new GodMemory(); gods.put(event.godId(), state);
        }
        if (state.revision == Long.MAX_VALUE) return false;
        long nextRevision = Math.incrementExact(state.revision);
        state.events.put(event.eventId(), event);
        while (state.events.size() > MAX_EVENTS_PER_GOD) state.events.remove(state.events.keySet().iterator().next());
        if (!state.events.keySet().containsAll(state.affect.sourceEventIds())) state.affect = Affect.empty();
        state.revision = nextRevision; onMutation.run(); return true;
    }
    public View view(String god, Set<String> godAudience, Set<UUID> playerAudience, String query) {
        id(god);
        if (!enabled.getAsBoolean()) return View.empty(god);
        audience(godAudience, playerAudience);
        GodMemory state = gods.get(god);
        if (state == null) return View.empty(god);
        var eligible = new ArrayList<Memory>();
        for (Event event : state.events.values()) project(event, godAudience, playerAudience).ifPresent(eligible::add);
        Collections.reverse(eligible);
        var selected = new ArrayList<>(eligible.subList(0, Math.min(3, eligible.size())));
        var related = new ArrayList<>(eligible.subList(selected.size(), eligible.size()));
        String boundedQuery = query == null ? "" : query.substring(0, Math.min(512, query.length())).toLowerCase(Locale.ROOT);
        related.removeIf(memory -> relevance(memory, boundedQuery) == 0);
        related.sort(Comparator.comparingInt((Memory memory) -> relevance(memory, boundedQuery)).reversed());
        selected.addAll(related.subList(0, Math.min(3, related.size())));
        return new View(god, state.revision, selected, visibleAffect(state, godAudience, playerAudience));
    }
    /** Original owner need not be in the new audience: callers separately prove later actual hearing. */
    public Optional<Memory> project(String god, UUID eventId, Set<String> godAudience, Set<UUID> playerAudience) {
        id(god); Objects.requireNonNull(eventId);
        if (!enabled.getAsBoolean()) return Optional.empty();
        audience(godAudience, playerAudience);
        GodMemory state = gods.get(god);
        return state == null ? Optional.empty() : project(state.events.get(eventId), godAudience, playerAudience);
    }
    public boolean current(View view, Set<String> godAudience, Set<UUID> playerAudience) {
        Objects.requireNonNull(view);
        if (!enabled.getAsBoolean()) return empty(view);
        GodMemory state = gods.get(view.godId());
        return view.revision() == (state == null ? 0 : state.revision)
                && disclosureCurrent(view, godAudience, playerAudience)
                && view.affect().equals(state == null ? Affect.empty() : visibleAffect(state, godAudience, playerAudience));
    }
    /** Revalidates a trusted game-issued snapshot, not a model-submitted view. Ignores newer interpretation/revision. */
    public boolean disclosureCurrent(View view, Set<String> godAudience, Set<UUID> playerAudience) {
        Objects.requireNonNull(view);
        if (!enabled.getAsBoolean()) return empty(view);
        audience(godAudience, playerAudience);
        for (Memory memory : view.experiences())
            if (!project(view.godId(), memory.eventId(), godAudience, playerAudience).filter(memory::equals).isPresent()) return false;
        for (UUID source : view.affect().sourceEventIds())
            if (project(view.godId(), source, godAudience, playerAudience).isEmpty()) return false;
        return true;
    }
    public boolean applyAffect(View view, Affect affect, Set<String> godAudience, Set<UUID> playerAudience) {
        Objects.requireNonNull(affect);
        if (!enabled.getAsBoolean() || !current(view, godAudience, playerAudience)) return false;
        GodMemory state = gods.get(view.godId());
        if (state == null) return false;
        // An omitted affect is not a request to erase a previous interpretation.
        if (affect.sourceEventIds().isEmpty()) return true;
        Set<UUID> inputIds = new LinkedHashSet<>();
        view.experiences().forEach(memory -> inputIds.add(memory.eventId()));
        if (affect.sourceEventIds().size() > 4 || !inputIds.containsAll(affect.sourceEventIds())) return false;
        // A model may have used every input, including the previous hint. Selected refs cannot narrow disclosure.
        inputIds.addAll(view.affect().sourceEventIds());
        Affect stored = new Affect(affect.hint(), List.copyOf(inputIds));
        if (state.affect.equals(stored)) return true;
        if (state.revision == Long.MAX_VALUE) return false;
        state.affect = stored; state.revision++; onMutation.run(); return true;
    }
    /** Persistence-only snapshots deliberately bypass OFF; disabled memory must survive save/load unchanged. */
    record StoredGod(String godId, long revision, List<Event> events, Affect affect) {
        StoredGod { id(godId); events = List.copyOf(events); Objects.requireNonNull(affect); }
    }
    List<StoredGod> snapshot() {
        return gods.entrySet().stream().map(entry -> new StoredGod(entry.getKey(), entry.getValue().revision,
                List.copyOf(entry.getValue().events.values()), entry.getValue().affect)).toList();
    }
    void restore(List<StoredGod> saved) {
        if (!gods.isEmpty() || saved.size() > MAX_GODS) throw new IllegalArgumentException("Invalid activity memory restore");
        var loaded = new LinkedHashMap<String, GodMemory>();
        for (StoredGod god : saved) {
            if (loaded.containsKey(god.godId()) || god.events().isEmpty() || god.events().size() > MAX_EVENTS_PER_GOD
                    || god.revision() < god.events().size()) throw new IllegalArgumentException("Invalid activity God record");
            var state = new GodMemory(); state.revision = god.revision(); state.affect = god.affect();
            for (Event event : god.events())
                if (!event.godId().equals(god.godId()) || state.events.putIfAbsent(event.eventId(), event) != null)
                    throw new IllegalArgumentException("Conflicting saved activity identity");
            if (!state.events.keySet().containsAll(state.affect.sourceEventIds()))
                throw new IllegalArgumentException("Orphaned activity affect provenance");
            loaded.put(god.godId(), state);
        }
        gods.putAll(loaded);
    }
    private static boolean empty(View view) {
        return view.revision() == 0 && view.experiences().isEmpty() && view.affect().equals(Affect.empty());
    }
    private static Affect visibleAffect(GodMemory state, Set<String> gods, Set<UUID> players) {
        for (UUID source : state.affect.sourceEventIds())
            if (project(state.events.get(source), gods, players).isEmpty()) return Affect.empty();
        return state.affect;
    }
    private static Optional<Memory> project(Event event, Set<String> gods, Set<UUID> players) {
        if (event == null || event.phase().equals("SPEECH")
                && (!event.heardGodIds().containsAll(gods) || !event.heardPlayers().containsAll(players))) return Optional.empty();
        return Optional.of(new Memory(event.eventId(), event.gameTime(), event.kind(), event.mode(), event.phase(),
                event.speakerGodId(), event.speech()));
    }
    private static void audience(Set<String> gods, Set<UUID> players) {
        Objects.requireNonNull(gods); Objects.requireNonNull(players);
        if (gods.size() > 16 || players.size() > 256) throw new IllegalArgumentException("Activity audience budget");
        gods.forEach(NpcActivityMemory::id); players.forEach(Objects::requireNonNull);
    }
    private static int relevance(Memory memory, String query) {
        if (query.isBlank()) return 0;
        String safeText = (memory.kind() + " " + memory.mode() + " " + memory.phase() + " " + memory.speech()).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : query.split("[^\\p{L}\\p{N}_]+")) if (!token.isEmpty() && safeText.contains(token)) score++;
        if (KOREAN_KIND_TERMS.getOrDefault(memory.kind(), List.of()).stream().anyMatch(query::contains)) score++;
        return score;
    }
    private static void id(String value) {
        if (value == null || value.length() > 256 || !value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
            throw new IllegalArgumentException("Invalid namespaced activity ID");
    }
    private static void token(String value, int length) {
        if (value == null || value.isBlank() || value.length() > length || !value.matches("[A-Z_]+"))
            throw new IllegalArgumentException("Invalid activity token");
    }
}
