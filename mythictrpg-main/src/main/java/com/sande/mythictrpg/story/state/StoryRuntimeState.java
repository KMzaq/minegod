package com.sande.mythictrpg.story.state;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.story.api.StoryStateView;
import com.sande.mythictrpg.story.api.StoryStateView.HolderType;
import com.sande.mythictrpg.story.api.StoryStateView.StoryActorView;
import com.sande.mythictrpg.story.api.StoryStateView.StoryEventView;
import com.sande.mythictrpg.story.api.StoryStateView.StoryKnowledgeHolder;
import com.sande.mythictrpg.story.api.StoryStateView.StoryScopeKey;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.definition.StoryDefinitions.ActorDefinition;
import com.sande.mythictrpg.story.definition.StoryDefinitions.AvailabilityState;
import com.sande.mythictrpg.story.definition.StoryDefinitions.ExistenceState;
import com.sande.mythictrpg.story.definition.StoryDefinitions.ScopeType;
import com.sande.mythictrpg.story.state.StoryRuntimeModels.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/** Sparse, versioned and server-wide authority for all runtime Story truth. */
public final class StoryRuntimeState extends SavedData implements StoryStateView {
    public static final int CURRENT_DATA_VERSION = 1;
    public static final int MAX_AUDIT = 2_048;
    private static final String FILE_NAME = "mythictrpg_story";
    private static final Factory<StoryRuntimeState> FACTORY = new Factory<>(
            StoryRuntimeState::new, StoryRuntimeState::load);

    private long globalRevision;
    private final Map<ScopedFactKey, Boolean> facts = new LinkedHashMap<>();
    private final Map<ResourceLocation, ActorState> actorStates = new LinkedHashMap<>();
    private final Map<String, EventInstance> eventInstances = new LinkedHashMap<>();
    private final Map<String, LatchedTrigger> latchedTriggers = new LinkedHashMap<>();
    private final Map<UUID, ScheduledSignal> schedules = new LinkedHashMap<>();
    private final Map<KnowledgeKey, KnowledgeRecord> knowledge = new LinkedHashMap<>();
    private final Map<ResourceLocation, Integer> publicPlayerKnowledge = new LinkedHashMap<>();
    private final Map<UUID, PresentationOpportunity> presentations = new LinkedHashMap<>();
    private final Map<String, HookAcceptanceRecord> hookAcceptances = new LinkedHashMap<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private CompoundTag rejectedRawData;
    private String rejectionReason;

    public static StoryRuntimeState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    @Override public boolean isReady() { return rejectedRawData == null; }
    public long globalRevision() { return globalRevision; }
    public Optional<String> rejectionReason() { return Optional.ofNullable(rejectionReason); }

    @Override
    public boolean fact(StoryScopeKey scope, ResourceLocation factId) {
        Boolean stored = facts.get(new ScopedFactKey(scope, factId));
        if (stored != null) return stored;
        return StoryDefinitionManager.INSTANCE.fact(factId).map(value -> value.defaultValue()).orElse(false);
    }

    @Override
    public Optional<StoryActorView> actor(ResourceLocation actorId) {
        ActorState stored = actorStates.get(actorId);
        if (stored != null) {
            return Optional.of(new StoryActorView(actorId, stored.existence(), stored.availability(),
                    stored.locationId(), stored.revision()));
        }
        return StoryDefinitionManager.INSTANCE.actor(actorId).map(definition -> new StoryActorView(actorId,
                definition.initialExistence(), definition.initialAvailability(),
                definition.initialLocationId(), 0));
    }

    @Override
    public Optional<StoryEventView> latestEvent(ResourceLocation eventId, StoryScopeKey scope) {
        return eventInstances.values().stream().filter(value -> value.eventId().equals(eventId))
                .filter(value -> value.scope().equals(scope)).max(Comparator.comparingInt(EventInstance::sequence))
                .map(value -> new StoryEventView(value.eventId(), value.instanceId(), value.scope(),
                        value.status().name(), value.selectedOutcomeId(), value.revision()));
    }

    @Override
    public int knowledgeLevel(StoryKnowledgeHolder holder, ResourceLocation factId) {
        int direct = Optional.ofNullable(knowledge.get(new KnowledgeKey(holder, factId)))
                .map(KnowledgeRecord::knownLevel).orElse(0);
        return holder.type() == HolderType.PLAYER
                ? Math.max(direct, publicPlayerKnowledge.getOrDefault(factId, 0)) : direct;
    }

    @Override
    public Set<ResourceLocation> actorsAt(ResourceLocation locationId) {
        Set<ResourceLocation> result = new LinkedHashSet<>();
        StoryDefinitionManager.INSTANCE.snapshot().actors().keySet().stream().sorted().forEach(actorId ->
                actor(actorId).flatMap(StoryActorView::locationId).filter(locationId::equals)
                        .ifPresent(ignored -> result.add(actorId)));
        return Set.copyOf(result);
    }

    public Map<ScopedFactKey, Boolean> factOverrides() { return Map.copyOf(facts); }
    public Map<ResourceLocation, ActorState> actorOverrides() { return Map.copyOf(actorStates); }
    public Map<String, EventInstance> eventInstances() { return Map.copyOf(eventInstances); }
    public Map<String, LatchedTrigger> latchedTriggers() { return Map.copyOf(latchedTriggers); }
    public Map<UUID, ScheduledSignal> schedules() { return Map.copyOf(schedules); }
    public Map<KnowledgeKey, KnowledgeRecord> knowledge() { return Map.copyOf(knowledge); }
    public Map<ResourceLocation, Integer> publicPlayerKnowledge() { return Map.copyOf(publicPlayerKnowledge); }
    public Map<UUID, PresentationOpportunity> presentations() { return Map.copyOf(presentations); }
    public Map<String, HookAcceptanceRecord> hookAcceptances() { return Map.copyOf(hookAcceptances); }
    public List<AuditEntry> audit() { return List.copyOf(audit); }

    public Optional<KnowledgeRecord> knowledgeRecord(StoryKnowledgeHolder holder, ResourceLocation factId) {
        return Optional.ofNullable(knowledge.get(new KnowledgeKey(holder, factId)));
    }

    public boolean setFact(StoryScopeKey scope, ResourceLocation factId, boolean value) {
        ensureWritable();
        boolean previous = fact(scope, factId);
        if (previous == value) return false;
        boolean defaultValue = StoryDefinitionManager.INSTANCE.fact(factId)
                .map(definition -> definition.defaultValue()).orElse(false);
        ScopedFactKey key = new ScopedFactKey(scope, factId);
        if (value == defaultValue) facts.remove(key); else facts.put(key, value);
        changed();
        return true;
    }

    public ActorState putActorState(ResourceLocation actorId, ExistenceState existence,
            AvailabilityState availability, Optional<ResourceLocation> locationId,
            long gameTime, ResourceLocation causeId) {
        ensureWritable();
        StoryActorView previous = actor(actorId).orElseThrow(() ->
                new IllegalArgumentException("Unknown Story actor " + actorId));
        ActorState next = new ActorState(actorId, existence, availability, locationId,
                previous.revision() + 1, gameTime, causeId);
        actorStates.put(actorId, next);
        changed();
        return next;
    }

    public void putEvent(EventInstance instance) {
        ensureWritable();
        eventInstances.put(instance.instanceId(), instance);
        changed();
    }

    public Optional<EventInstance> eventInstance(String instanceId) {
        return Optional.ofNullable(eventInstances.get(instanceId));
    }

    public int nextSequence(ResourceLocation eventId, StoryScopeKey scope) {
        return eventInstances.values().stream().filter(value -> value.eventId().equals(eventId))
                .filter(value -> value.scope().equals(scope)).mapToInt(EventInstance::sequence).max().orElse(0) + 1;
    }

    public int resolvedCount(ResourceLocation eventId, StoryScopeKey scope) {
        return (int) eventInstances.values().stream().filter(value -> value.eventId().equals(eventId))
                .filter(value -> value.scope().equals(scope)).filter(value -> value.status() == EventStatus.RESOLVED)
                .count();
    }

    public OptionalLong lastResolvedGameTime(ResourceLocation eventId, StoryScopeKey scope) {
        return eventInstances.values().stream().filter(value -> value.eventId().equals(eventId))
                .filter(value -> value.scope().equals(scope)).filter(value -> value.status() == EventStatus.RESOLVED)
                .mapToLong(value -> value.decidedAtGameTime().orElse(value.triggeredAtGameTime())).max();
    }

    public boolean hasOpenInstance(ResourceLocation eventId, StoryScopeKey scope) {
        return eventInstances.values().stream().filter(value -> value.eventId().equals(eventId))
                .filter(value -> value.scope().equals(scope)).anyMatch(value -> value.status() != EventStatus.RESOLVED
                        && value.status() != EventStatus.CANCELLED && value.status() != EventStatus.RECOVERY_REQUIRED);
    }

    public void putLatched(LatchedTrigger trigger) {
        ensureWritable();
        latchedTriggers.putIfAbsent(trigger.key(), trigger);
        changed();
    }

    public void removeLatched(String key) {
        ensureWritable();
        if (latchedTriggers.remove(key) != null) changed();
    }

    public void putSchedule(ScheduledSignal schedule) {
        ensureWritable();
        if (schedules.putIfAbsent(schedule.scheduleId(), schedule) != null)
            throw new IllegalArgumentException("Duplicate Story schedule " + schedule.scheduleId());
        changed();
    }

    public Optional<ScheduledSignal> removeSchedule(UUID scheduleId) {
        ensureWritable();
        ScheduledSignal removed = schedules.remove(scheduleId);
        if (removed != null) changed();
        return Optional.ofNullable(removed);
    }

    public KnowledgeRecord grantKnowledge(StoryKnowledgeHolder holder, ResourceLocation factId, int level,
            ResourceLocation disclosurePolicyId, String sourceInstanceId, long gameTime) {
        ensureWritable();
        KnowledgeKey key = new KnowledgeKey(holder, factId);
        KnowledgeRecord previous = knowledge.get(key);
        int resolved = Math.max(level, previous == null ? 0 : previous.knownLevel());
        long revision = previous == null ? 1 : previous.revision() + 1;
        KnowledgeRecord next = new KnowledgeRecord(key, resolved, disclosurePolicyId, sourceInstanceId,
                gameTime, revision);
        if (previous == null || !previous.equals(next)) {
            knowledge.put(key, next);
            changed();
        }
        return next;
    }

    public boolean grantPublicPlayerKnowledge(ResourceLocation factId, int level) {
        ensureWritable();
        int previous = publicPlayerKnowledge.getOrDefault(factId, 0);
        if (previous >= level) return false;
        publicPlayerKnowledge.put(factId, level);
        changed();
        return true;
    }

    public void putPresentation(PresentationOpportunity opportunity) {
        ensureWritable();
        if (presentations.putIfAbsent(opportunity.opportunityId(), opportunity) != null)
            throw new IllegalArgumentException("Duplicate Story presentation " + opportunity.opportunityId());
        changed();
    }

    public boolean updatePresentationStatus(UUID opportunityId, PresentationStatus status) {
        ensureWritable();
        PresentationOpportunity previous = presentations.get(opportunityId);
        if (previous == null || previous.status() == status) return false;
        presentations.put(opportunityId, new PresentationOpportunity(previous.opportunityId(),
                previous.eventInstanceId(), previous.eventRevision(), previous.presentationId(),
                previous.audiencePlayerId(), status, previous.createdAtGameTime()));
        changed();
        return true;
    }

    public Optional<HookAcceptanceRecord> hookAcceptance(String key) {
        return Optional.ofNullable(hookAcceptances.get(key));
    }

    public HookAcceptanceRecord recordHookAcceptance(String key, ResourceLocation hookId,
            String scopeKey, long gameTime) {
        ensureWritable();
        HookAcceptanceRecord previous = hookAcceptances.get(key);
        HookAcceptanceRecord next = new HookAcceptanceRecord(key, hookId, scopeKey,
                previous == null ? 1 : previous.count() + 1, gameTime);
        hookAcceptances.put(key, next);
        changed();
        return next;
    }

    public void addAudit(long gameTime, String code, String detail) {
        ensureWritable();
        audit.add(new AuditEntry(gameTime, code, detail));
        if (audit.size() > MAX_AUDIT) audit.removeFirst();
        changed();
    }

    /** Isolates authored sample scenarios across persistent GameTest server runs. */
    public void resetForTesting(Set<ResourceLocation> eventIds, Set<ResourceLocation> actorIds,
            Set<ResourceLocation> factIds, Set<ResourceLocation> hookIds) {
        ensureWritable();
        Set<String> removedInstances = eventInstances.values().stream()
                .filter(value -> eventIds.contains(value.eventId()))
                .map(EventInstance::instanceId).collect(java.util.stream.Collectors.toSet());
        facts.keySet().removeIf(value -> factIds.contains(value.factId()));
        actorStates.keySet().removeIf(actorIds::contains);
        eventInstances.values().removeIf(value -> eventIds.contains(value.eventId()));
        latchedTriggers.values().removeIf(value -> eventIds.contains(value.eventId()));
        schedules.values().removeIf(value -> eventIds.contains(value.targetEventId())
                || removedInstances.contains(value.causeInstanceId()));
        knowledge.keySet().removeIf(value -> factIds.contains(value.factId()));
        publicPlayerKnowledge.keySet().removeIf(factIds::contains);
        presentations.values().removeIf(value -> removedInstances.contains(value.eventInstanceId()));
        hookAcceptances.values().removeIf(value -> hookIds.contains(value.hookId()));
        changed();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (rejectedRawData != null) return rejectedRawData.copy();
        tag.putInt("dataVersion", CURRENT_DATA_VERSION);
        tag.putLong("globalRevision", globalRevision);
        tag.put("facts", writeFacts());
        tag.put("actors", writeActors());
        tag.put("events", writeEvents());
        tag.put("latchedTriggers", writeLatched());
        tag.put("schedules", writeSchedules());
        tag.put("knowledge", writeKnowledge());
        tag.put("publicPlayerKnowledge", writePublicKnowledge());
        tag.put("presentations", writePresentations());
        tag.put("hookAcceptances", writeHookAcceptances());
        tag.put("audit", writeAudit());
        return tag;
    }

    public static StoryRuntimeState load(CompoundTag tag, HolderLookup.Provider registries) {
        StoryRuntimeState state = new StoryRuntimeState();
        try {
            if (!tag.contains("dataVersion", Tag.TAG_ANY_NUMERIC)
                    || tag.getInt("dataVersion") != CURRENT_DATA_VERSION)
                throw new IllegalArgumentException("Unsupported or missing Story dataVersion");
            state.globalRevision = nonNegative(tag.getLong("globalRevision"), "globalRevision");
            readFacts(state, list(tag, "facts", Tag.TAG_COMPOUND));
            readActors(state, list(tag, "actors", Tag.TAG_COMPOUND));
            readEvents(state, list(tag, "events", Tag.TAG_COMPOUND));
            readLatched(state, list(tag, "latchedTriggers", Tag.TAG_COMPOUND));
            readSchedules(state, list(tag, "schedules", Tag.TAG_COMPOUND));
            readKnowledge(state, list(tag, "knowledge", Tag.TAG_COMPOUND));
            readPublicKnowledge(state, list(tag, "publicPlayerKnowledge", Tag.TAG_COMPOUND));
            readPresentations(state, list(tag, "presentations", Tag.TAG_COMPOUND));
            readHookAcceptances(state, list(tag, "hookAcceptances", Tag.TAG_COMPOUND));
            readAudit(state, list(tag, "audit", Tag.TAG_COMPOUND));
        } catch (RuntimeException exception) {
            state.clearLoaded();
            state.rejectedRawData = tag.copy();
            state.rejectionReason = exception.getMessage();
            MythicTrpg.LOGGER.error("Rejected Story data without replacing it: {}",
                    exception.getMessage(), exception);
        }
        return state;
    }

    private ListTag writeFacts() {
        ListTag list = new ListTag();
        facts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag value = new CompoundTag(); writeScope(value, entry.getKey().scope());
            value.putString("fact", entry.getKey().factId().toString()); value.putBoolean("value", entry.getValue());
            list.add(value);
        }); return list;
    }

    private ListTag writeActors() {
        ListTag list = new ListTag(); actorStates.values().stream().sorted(Comparator.comparing(ActorState::actorId))
                .forEach(actor -> { CompoundTag value = new CompoundTag();
                    value.putString("actor", actor.actorId().toString());
                    value.putString("existence", actor.existence().name());
                    value.putString("availability", actor.availability().name());
                    actor.locationId().ifPresent(id -> value.putString("location", id.toString()));
                    value.putLong("revision", actor.revision()); value.putLong("gameTime", actor.lastChangedGameTime());
                    value.putString("cause", actor.lastCauseId().toString()); list.add(value); }); return list;
    }

    private ListTag writeEvents() {
        ListTag list = new ListTag(); eventInstances.values().stream().sorted(Comparator.comparing(EventInstance::instanceId))
                .forEach(event -> { CompoundTag value = new CompoundTag(); value.putString("instance", event.instanceId());
                    value.putString("event", event.eventId().toString()); writeScope(value, event.scope());
                    value.put("audience", uuids(event.frozenAudiencePlayerIds()));
                    event.initiatingPlayerId().ifPresent(id -> value.putString("player", id.toString()));
                    value.putString("signal", event.triggerSignalId().toString()); value.putString("status", event.status().name());
                    value.putLong("revision", event.revision()); value.putString("fingerprint", event.definitionFingerprint());
                    event.selectedOutcomeId().ifPresent(id -> value.putString("outcome", id.toString()));
                    if (event.deterministicRoll().isPresent()) value.putLong("roll", event.deterministicRoll().getAsLong());
                    value.putLong("triggeredAt", event.triggeredAtGameTime());
                    if (event.decidedAtGameTime().isPresent()) value.putLong("decidedAt", event.decidedAtGameTime().getAsLong());
                    value.put("effects", ids(event.appliedEffectIds())); value.putInt("sequence", event.sequence()); list.add(value); });
        return list;
    }

    private ListTag writeLatched() {
        ListTag list = new ListTag(); latchedTriggers.values().stream().sorted(Comparator.comparing(LatchedTrigger::key))
                .forEach(trigger -> { CompoundTag value = new CompoundTag(); value.putString("key", trigger.key());
                    value.putString("event", trigger.eventId().toString()); writeScope(value, trigger.scope());
                    value.put("audience", uuids(trigger.frozenAudiencePlayerIds()));
                    trigger.initiatingPlayerId().ifPresent(id -> value.putString("player", id.toString()));
                    value.putString("signal", trigger.signalId().toString());
                    trigger.subjectId().ifPresent(id -> value.putString("subject", id.toString()));
                    value.putLong("createdAt", trigger.createdGameTime());
                    if (trigger.expiresAtGameTime().isPresent()) value.putLong("expiresAt", trigger.expiresAtGameTime().getAsLong());
                    list.add(value); }); return list;
    }

    private ListTag writeSchedules() {
        ListTag list = new ListTag(); schedules.values().stream().sorted(Comparator.comparing(ScheduledSignal::dueGameTime)
                        .thenComparing(value -> value.scheduleId().toString())).forEach(schedule -> {
                    CompoundTag value = new CompoundTag(); value.putString("id", schedule.scheduleId().toString());
                    value.putString("event", schedule.targetEventId().toString()); writeScope(value, schedule.scope());
                    value.put("audience", uuids(schedule.frozenAudiencePlayerIds()));
                    schedule.initiatingPlayerId().ifPresent(id -> value.putString("player", id.toString()));
                    value.putLong("due", schedule.dueGameTime()); value.putString("causeInstance", schedule.causeInstanceId());
                    value.putString("causeEffect", schedule.causeEffectId().toString()); list.add(value); }); return list;
    }

    private ListTag writeKnowledge() {
        ListTag list = new ListTag(); knowledge.values().stream().sorted(Comparator.comparing(KnowledgeRecord::key))
                .forEach(record -> { CompoundTag value = new CompoundTag();
                    value.putString("holderType", record.key().holder().type().name());
                    value.putString("holder", record.key().holder().id()); value.putString("fact", record.key().factId().toString());
                    value.putInt("level", record.knownLevel()); value.putString("policy", record.disclosurePolicyId().toString());
                    value.putString("source", record.sourceInstanceId()); value.putLong("learnedAt", record.learnedAtGameTime());
                    value.putLong("revision", record.revision()); list.add(value); }); return list;
    }

    private ListTag writePublicKnowledge() {
        ListTag list = new ListTag(); publicPlayerKnowledge.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> { CompoundTag value = new CompoundTag(); value.putString("fact", entry.getKey().toString());
                    value.putInt("level", entry.getValue()); list.add(value); }); return list;
    }

    private ListTag writePresentations() {
        ListTag list = new ListTag(); presentations.values().stream()
                .sorted(Comparator.comparing(value -> value.opportunityId().toString())).forEach(presentation -> {
                    CompoundTag value = new CompoundTag(); value.putString("id", presentation.opportunityId().toString());
                    value.putString("instance", presentation.eventInstanceId()); value.putLong("eventRevision", presentation.eventRevision());
                    value.putString("presentation", presentation.presentationId().toString());
                    value.putString("player", presentation.audiencePlayerId().toString());
                    value.putString("status", presentation.status().name()); value.putLong("createdAt", presentation.createdAtGameTime());
                    list.add(value); }); return list;
    }

    private ListTag writeAudit() {
        ListTag list = new ListTag(); audit.forEach(entry -> { CompoundTag value = new CompoundTag();
            value.putLong("gameTime", entry.gameTime()); value.putString("code", entry.code());
            value.putString("detail", entry.detail()); list.add(value); }); return list;
    }

    private ListTag writeHookAcceptances() {
        ListTag list = new ListTag();
        hookAcceptances.values().stream().sorted(Comparator.comparing(HookAcceptanceRecord::key))
                .forEach(record -> { CompoundTag value = new CompoundTag();
                    value.putString("key", record.key()); value.putString("hook", record.hookId().toString());
                    value.putString("scopeKey", record.scopeKey()); value.putInt("count", record.count());
                    value.putLong("lastAccepted", record.lastAcceptedGameTime()); list.add(value); });
        return list;
    }

    private static void readFacts(StoryRuntimeState state, ListTag list) {
        for (int index = 0; index < list.size(); index++) { CompoundTag value = list.getCompound(index);
            ScopedFactKey key = new ScopedFactKey(readScope(value), id(value.getString("fact"), "facts.fact"));
            if (state.facts.putIfAbsent(key, value.getBoolean("value")) != null) throw duplicate("fact", key); }
    }

    private static void readActors(StoryRuntimeState state, ListTag list) {
        for (int index = 0; index < list.size(); index++) { CompoundTag value = list.getCompound(index);
            ResourceLocation actorId = id(value.getString("actor"), "actors.actor");
            ActorState actor = new ActorState(actorId, enumValue(value.getString("existence"), ExistenceState.class),
                    enumValue(value.getString("availability"), AvailabilityState.class),
                    optionalId(value, "location"), nonNegative(value.getLong("revision"), "actors.revision"),
                    nonNegative(value.getLong("gameTime"), "actors.gameTime"), id(value.getString("cause"), "actors.cause"));
            if (state.actorStates.putIfAbsent(actorId, actor) != null) throw duplicate("actor", actorId); }
    }

    private static void readEvents(StoryRuntimeState state, ListTag list) {
        for (int index = 0; index < list.size(); index++) { CompoundTag value = list.getCompound(index);
            String instanceId = required(value.getString("instance"), "events.instance");
            EventInstance event = new EventInstance(instanceId, id(value.getString("event"), "events.event"),
                    readScope(value), readUuids(value, "audience"), optionalUuid(value, "player"),
                    uuid(value.getString("signal"), "events.signal"), enumValue(value.getString("status"), EventStatus.class),
                    nonNegative(value.getLong("revision"), "events.revision"), required(value.getString("fingerprint"), "events.fingerprint"),
                    optionalId(value, "outcome"), value.contains("roll", Tag.TAG_ANY_NUMERIC)
                            ? OptionalLong.of(value.getLong("roll")) : OptionalLong.empty(),
                    nonNegative(value.getLong("triggeredAt"), "events.triggeredAt"), value.contains("decidedAt", Tag.TAG_ANY_NUMERIC)
                            ? OptionalLong.of(nonNegative(value.getLong("decidedAt"), "events.decidedAt")) : OptionalLong.empty(),
                    readIds(value, "effects"), positive(value.getInt("sequence"), "events.sequence"));
            if (state.eventInstances.putIfAbsent(instanceId, event) != null) throw duplicate("event", instanceId); }
    }

    private static void readLatched(StoryRuntimeState state, ListTag list) {
        for (int index = 0; index < list.size(); index++) { CompoundTag value = list.getCompound(index);
            String key = required(value.getString("key"), "latched.key");
            LatchedTrigger trigger = new LatchedTrigger(key, id(value.getString("event"), "latched.event"),
                    readScope(value), readUuids(value, "audience"), optionalUuid(value, "player"),
                    uuid(value.getString("signal"), "latched.signal"), optionalId(value, "subject"),
                    nonNegative(value.getLong("createdAt"), "latched.createdAt"),
                    value.contains("expiresAt", Tag.TAG_ANY_NUMERIC)
                            ? OptionalLong.of(nonNegative(value.getLong("expiresAt"), "latched.expiresAt"))
                            : OptionalLong.empty());
            if (state.latchedTriggers.putIfAbsent(key, trigger) != null) throw duplicate("latched trigger", key); }
    }

    private static void readSchedules(StoryRuntimeState state, ListTag list) {
        for (int index = 0; index < list.size(); index++) { CompoundTag value = list.getCompound(index);
            UUID scheduleId = uuid(value.getString("id"), "schedules.id");
            ScheduledSignal schedule = new ScheduledSignal(scheduleId, id(value.getString("event"), "schedules.event"),
                    readScope(value), readUuids(value, "audience"), optionalUuid(value, "player"),
                    nonNegative(value.getLong("due"), "schedules.due"), required(value.getString("causeInstance"), "schedules.causeInstance"),
                    id(value.getString("causeEffect"), "schedules.causeEffect"));
            if (state.schedules.putIfAbsent(scheduleId, schedule) != null) throw duplicate("schedule", scheduleId); }
    }

    private static void readKnowledge(StoryRuntimeState state, ListTag list) {
        for (int index = 0; index < list.size(); index++) { CompoundTag value = list.getCompound(index);
            StoryKnowledgeHolder holder = new StoryKnowledgeHolder(enumValue(value.getString("holderType"), HolderType.class),
                    required(value.getString("holder"), "knowledge.holder"));
            KnowledgeKey key = new KnowledgeKey(holder, id(value.getString("fact"), "knowledge.fact"));
            KnowledgeRecord record = new KnowledgeRecord(key, positive(value.getInt("level"), "knowledge.level"),
                    id(value.getString("policy"), "knowledge.policy"), required(value.getString("source"), "knowledge.source"),
                    nonNegative(value.getLong("learnedAt"), "knowledge.learnedAt"), positive(value.getLong("revision"), "knowledge.revision"));
            if (state.knowledge.putIfAbsent(key, record) != null) throw duplicate("knowledge", key); }
    }

    private static void readPublicKnowledge(StoryRuntimeState state, ListTag list) {
        for (int index = 0; index < list.size(); index++) { CompoundTag value = list.getCompound(index);
            ResourceLocation factId = id(value.getString("fact"), "publicKnowledge.fact");
            if (state.publicPlayerKnowledge.putIfAbsent(factId, positive(value.getInt("level"), "publicKnowledge.level")) != null)
                throw duplicate("public knowledge", factId); }
    }

    private static void readPresentations(StoryRuntimeState state, ListTag list) {
        for (int index = 0; index < list.size(); index++) { CompoundTag value = list.getCompound(index);
            UUID opportunityId = uuid(value.getString("id"), "presentations.id");
            PresentationOpportunity presentation = new PresentationOpportunity(opportunityId,
                    required(value.getString("instance"), "presentations.instance"),
                    nonNegative(value.getLong("eventRevision"), "presentations.eventRevision"),
                    id(value.getString("presentation"), "presentations.presentation"),
                    uuid(value.getString("player"), "presentations.player"),
                    enumValue(value.getString("status"), PresentationStatus.class),
                    nonNegative(value.getLong("createdAt"), "presentations.createdAt"));
            if (state.presentations.putIfAbsent(opportunityId, presentation) != null)
                throw duplicate("presentation", opportunityId); }
    }

    private static void readAudit(StoryRuntimeState state, ListTag list) {
        if (list.size() > MAX_AUDIT) throw new IllegalArgumentException("Story audit exceeds " + MAX_AUDIT);
        for (int index = 0; index < list.size(); index++) { CompoundTag value = list.getCompound(index);
            state.audit.add(new AuditEntry(nonNegative(value.getLong("gameTime"), "audit.gameTime"),
                    required(value.getString("code"), "audit.code"), required(value.getString("detail"), "audit.detail"))); }
    }

    private static void readHookAcceptances(StoryRuntimeState state, ListTag list) {
        for (int index = 0; index < list.size(); index++) { CompoundTag value = list.getCompound(index);
            String key = required(value.getString("key"), "hookAcceptances.key");
            HookAcceptanceRecord record = new HookAcceptanceRecord(key,
                    id(value.getString("hook"), "hookAcceptances.hook"),
                    required(value.getString("scopeKey"), "hookAcceptances.scopeKey"),
                    positive(value.getInt("count"), "hookAcceptances.count"),
                    nonNegative(value.getLong("lastAccepted"), "hookAcceptances.lastAccepted"));
            if (state.hookAcceptances.putIfAbsent(key, record) != null)
                throw duplicate("hook acceptance", key); }
    }

    private void clearLoaded() {
        globalRevision = 0; facts.clear(); actorStates.clear(); eventInstances.clear(); latchedTriggers.clear();
        schedules.clear(); knowledge.clear(); publicPlayerKnowledge.clear(); presentations.clear();
        hookAcceptances.clear(); audit.clear();
    }

    private void changed() { globalRevision++; setDirty(); }
    private void ensureWritable() {
        if (!isReady()) throw new IllegalStateException("Story state is read-only: " + rejectionReason);
    }

    private static void writeScope(CompoundTag tag, StoryScopeKey scope) {
        tag.putString("scopeType", scope.type().name()); tag.putString("scopeKey", scope.key());
    }
    private static StoryScopeKey readScope(CompoundTag tag) {
        return new StoryScopeKey(enumValue(tag.getString("scopeType"), ScopeType.class),
                required(tag.getString("scopeKey"), "scopeKey"));
    }
    private static ListTag uuids(Set<UUID> values) {
        ListTag list = new ListTag(); values.stream().map(UUID::toString).sorted().map(StringTag::valueOf).forEach(list::add); return list;
    }
    private static ListTag ids(Set<ResourceLocation> values) {
        ListTag list = new ListTag(); values.stream().sorted().map(ResourceLocation::toString).map(StringTag::valueOf).forEach(list::add); return list;
    }
    private static Set<UUID> readUuids(CompoundTag tag, String field) {
        ListTag list = list(tag, field, Tag.TAG_STRING); Set<UUID> result = new LinkedHashSet<>();
        for (int index = 0; index < list.size(); index++) {
            UUID value = uuid(list.getString(index), field); if (!result.add(value)) throw duplicate(field, value); }
        return Set.copyOf(result);
    }
    private static Set<ResourceLocation> readIds(CompoundTag tag, String field) {
        ListTag list = list(tag, field, Tag.TAG_STRING); Set<ResourceLocation> result = new LinkedHashSet<>();
        for (int index = 0; index < list.size(); index++) {
            ResourceLocation value = id(list.getString(index), field); if (!result.add(value)) throw duplicate(field, value); }
        return Set.copyOf(result);
    }
    private static Optional<ResourceLocation> optionalId(CompoundTag tag, String field) {
        return tag.contains(field, Tag.TAG_STRING) ? Optional.of(id(tag.getString(field), field)) : Optional.empty();
    }
    private static Optional<UUID> optionalUuid(CompoundTag tag, String field) {
        return tag.contains(field, Tag.TAG_STRING) ? Optional.of(uuid(tag.getString(field), field)) : Optional.empty();
    }
    private static ListTag list(CompoundTag tag, String field, int elementType) {
        if (!tag.contains(field, Tag.TAG_LIST)) throw new IllegalArgumentException("Missing list '" + field + "'");
        ListTag list = tag.getList(field, elementType);
        if (!list.isEmpty() && list.getElementType() != elementType)
            throw new IllegalArgumentException("Invalid list element type for '" + field + "'");
        return list;
    }
    private static ResourceLocation id(String value, String field) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !value.contains(":")) throw new IllegalArgumentException("Invalid ID in " + field + ": " + value);
        return id;
    }
    private static UUID uuid(String value, String field) {
        try { return UUID.fromString(value); } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid UUID in " + field, exception); }
    }
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + field); return value.trim();
    }
    private static long nonNegative(long value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " cannot be negative"); return value;
    }
    private static int positive(int value, String field) {
        if (value < 1) throw new IllegalArgumentException(field + " must be positive"); return value;
    }
    private static long positive(long value, String field) {
        if (value < 1) throw new IllegalArgumentException(field + " must be positive"); return value;
    }
    private static <E extends Enum<E>> E enumValue(String value, Class<E> type) {
        try { return Enum.valueOf(type, value); } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid " + type.getSimpleName() + ": " + value, exception); }
    }
    private static IllegalArgumentException duplicate(String type, Object key) {
        return new IllegalArgumentException("Duplicate Story " + type + " " + key);
    }
}
