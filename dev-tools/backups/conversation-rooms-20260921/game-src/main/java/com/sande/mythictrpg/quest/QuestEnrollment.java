package com.sande.mythictrpg.quest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** One explicit consent round. The game supplies the audience and eligibility, never the model. */
public final class QuestEnrollment {
    public enum Answer { YES, NO }
    private final UUID offerId;
    private final UUID conversationId;
    private final Set<UUID> audience;
    private final Set<UUID> eligible;
    private final Map<UUID, Answer> answers = new LinkedHashMap<>();

    public QuestEnrollment(UUID offerId, UUID conversationId, Set<UUID> audience, Set<UUID> eligible) {
        this.offerId = Objects.requireNonNull(offerId);
        this.conversationId = Objects.requireNonNull(conversationId);
        this.audience = Set.copyOf(audience);
        this.eligible = Set.copyOf(eligible);
        if (audience.isEmpty() || !audience.containsAll(eligible)) {
            throw new IllegalArgumentException("Eligibility must be a subset of a nonempty audience");
        }
    }

    public UUID offerId() { return offerId; }
    public UUID conversationId() { return conversationId; }
    public Set<UUID> audience() { return audience; }
    public Set<UUID> eligible() { return eligible; }
    public Map<UUID, Answer> answers() { return Map.copyOf(answers); }

    /** Exact offer/session binding prevents a delayed yes from accepting another quest. */
    public boolean answer(UUID offer, UUID conversation, UUID player, Answer answer) {
        Objects.requireNonNull(answer);
        if (!offerId.equals(offer) || !conversationId.equals(conversation)
                || !audience.contains(player) || answers.containsKey(player)) return false;
        if (answer == Answer.YES && !eligible.contains(player)) return false;
        answers.put(player, answer);
        return true;
    }

    public Set<UUID> waitingFor() {
        return audience.stream().filter(id -> !answers.containsKey(id)).collect(Collectors.toUnmodifiableSet());
    }

    public boolean ready() { return answers.size() == audience.size(); }

    public Set<UUID> accepted() {
        if (!ready()) throw new IllegalStateException("Every audience member must explicitly answer first");
        return answers.entrySet().stream().filter(e -> e.getValue() == Answer.YES)
                .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }
}
