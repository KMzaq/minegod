package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.experiencecontract.ExperienceLease;
import java.util.*;

/** Bounded, session-only provenance for NPC paraphrases. Never persisted as independent facts. */
public final class ExperienceHistory {
    public static final int INHERITED_LIMIT = 62;
    public record Reference(ExperienceLease lease, Set<UUID> ids, java.util.function.BooleanSupplier guard) {
        public Reference(ExperienceLease lease,Set<UUID> ids) { this(Objects.requireNonNull(lease),ids,null); }
        public Reference { if(lease==null&&guard==null)throw new IllegalArgumentException("Missing provenance"); ids = Set.copyOf(ids); }
        public static Reference guarded(java.util.function.BooleanSupplier guard) { return new Reference(null,Set.of(),Objects.requireNonNull(guard)); }
        public boolean current() {
            try { return (lease==null||lease.current(ids))&&(guard==null||guard.getAsBoolean()); }
            catch(RuntimeException unavailable) { return false; }
        }
    }
    private final Map<String, List<Reference>> lines = new LinkedHashMap<>();
    public void record(String text, List<Reference> references) {
        var unique = references.stream().distinct().toList();
        if (unique.isEmpty() || unique.size() > 64) throw new IllegalArgumentException("Invalid evidence budget");
        lines.remove(text); lines.put(text, unique);
        if (lines.size() > 64) lines.remove(lines.keySet().iterator().next());
    }
    /** Prefer recent lines; reserve one slot each for fresh observation and rumor receipts. */
    public Set<String> excluded(List<String> transcript) {
        var retained = new HashSet<Reference>(); var excluded = new HashSet<String>();
        for (String text : transcript.reversed()) {
            var refs = lines.get(text); if (refs == null) continue;
            var proposed = new HashSet<>(retained); proposed.addAll(refs);
            if (proposed.size() > INHERITED_LIMIT || refs.stream().anyMatch(r -> !r.current())) excluded.add(text);
            else retained = proposed;
        }
        return Set.copyOf(excluded);
    }
    public List<Reference> references(Set<String> transcript) {
        return transcript.stream().filter(lines::containsKey).flatMap(t -> lines.get(t).stream()).distinct().toList();
    }
}
