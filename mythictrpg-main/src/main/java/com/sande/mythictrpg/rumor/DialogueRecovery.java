package com.sande.mythictrpg.rumor;

import com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext;
import java.util.*;

/** A persuasion candidate is NOT a successful game action. No model/automatic reviewer is installed here. */
public final class DialogueRecovery {
    public record Proposal(UUID id,ConversationMemoryContext context,long turn,UUID rootId,long assessmentVersion,String explanation) {
        public Proposal {
            Objects.requireNonNull(id);Objects.requireNonNull(context);Objects.requireNonNull(rootId);
            if(turn<1||assessmentVersion<1||explanation==null||explanation.isBlank()||explanation.length()>600)throw new IllegalArgumentException("Recovery proposal");
        }
    }
    public record Verdict(boolean accepted,String reasonId) {
        public Verdict {CourierSettings.identifier(reasonId);}
    }
    /** Trusted game-owned port: verify actual transcript/turn, character-specific grounds and evidence.
     * It must only read a previously prepared review; never perform blocking inference on the game thread. */
    public interface Reviewer {
        long currentTurn(UUID player);
        Verdict review(Proposal proposal,ReputationLedger.Entry assessment);
    }
    public static boolean eligible(Proposal proposal,ConversationMemoryContext current,long currentTurn,ReputationLedger.Entry entry) {
        if(current==null||!proposal.context().equals(current)||proposal.turn()!=currentTurn||entry==null||entry.terminal()||entry.version()!=proposal.assessmentVersion())return false;
        var d=entry.decision();return d.worldId().equals(current.worldId())&&d.subject().equals(current.playerId())&&d.godId().equals(current.godId())&&d.rootId().equals(proposal.rootId());
    }
    private DialogueRecovery() {}
}
