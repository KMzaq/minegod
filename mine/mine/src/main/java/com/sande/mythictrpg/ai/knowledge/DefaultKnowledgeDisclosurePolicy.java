package com.sande.mythictrpg.ai.knowledge;

import com.sande.mythictrpg.ai.relationship.EmotionTypes;
import com.sande.mythictrpg.ai.relationship.RelationshipAxes;

/** Conservative default disclosure policy. Gameplay owners may replace this policy without changing the repository. */
public final class DefaultKnowledgeDisclosurePolicy implements KnowledgeDisclosurePolicy {
    private static final int UNTRUSTED_LISTENER_TRUST = 45;
    private static final int UNTRUSTED_LISTENER_CAUTION = 55;

    @Override
    public KnowledgeDisclosureDecision decide(KnowledgeEntry entry, KnowledgeAccessContext context) {
        if (!entry.knownBy(context.npcId())) {
            return decision(entry, KnowledgeDisclosureStatus.NPC_DOES_NOT_KNOW,
                    "The NPC lacks this knowledge; do not fabricate an answer.");
        }
        int minimumTrust = minimumTrust(entry.secrecy());
        int trust = axis(context.requesterRelationship(), RelationshipAxes.TRUST);
        if (trust < minimumTrust) {
            return decision(entry, KnowledgeDisclosureStatus.INSUFFICIENT_TRUST,
                    "The relationship is not trusted enough for this secrecy level.");
        }
        if (entry.secrecy() != KnowledgeSecrecy.PUBLIC && emotion(context, EmotionTypes.ANGER) >= 90) {
            return decision(entry, KnowledgeDisclosureStatus.EMOTIONALLY_WITHHELD,
                    "The NPC is too angry to disclose private knowledge now.");
        }
        if (entry.secrecy() != KnowledgeSecrecy.PUBLIC && hasUntrustedListener(context, minimumTrust)) {
            return decision(entry, KnowledgeDisclosureStatus.UNSAFE_AUDIENCE,
                    "A listener cannot be trusted with this knowledge; suggest a private conversation without revealing it.");
        }
        return decision(entry, KnowledgeDisclosureStatus.ALLOWED, "Knowledge may be supplied to this NPC context.");
    }

    private static boolean hasUntrustedListener(KnowledgeAccessContext context, int minimumTrust) {
        return context.audience().stream().filter(member -> member.state() == KnowledgeAudienceState.LISTENER)
                .filter(member -> !member.playerId().equals(context.requesterPlayerId())).anyMatch(member ->
                        axis(member.relationship(), RelationshipAxes.TRUST) < Math.max(minimumTrust,
                                UNTRUSTED_LISTENER_TRUST)
                                || axis(member.relationship(), RelationshipAxes.CAUTION) >= UNTRUSTED_LISTENER_CAUTION);
    }

    private static int minimumTrust(KnowledgeSecrecy secrecy) {
        return switch (secrecy) {
            case PUBLIC, MORTAL -> -100;
            case DIVINE -> 55;
            case SECRET -> 80;
        };
    }

    private static int axis(com.sande.mythictrpg.ai.relationship.RelationshipSnapshot relationship, String axis) {
        return relationship.axis(axis).orElseThrow();
    }

    private static int emotion(KnowledgeAccessContext context, String emotionId) {
        return context.currentEmotion().intensity(emotionId).orElse(0);
    }

    private static KnowledgeDisclosureDecision decision(KnowledgeEntry entry, KnowledgeDisclosureStatus status,
            String guidance) {
        return new KnowledgeDisclosureDecision(entry.id(), status, guidance);
    }
}
