package com.sande.mythictrpg.ai.relationship;

import java.util.ArrayList;
import java.util.List;

/** Small, replaceable English interpretation used in the LLM context. */
public final class DefaultRelationshipContextInterpreter implements RelationshipContextInterpreter {
    @Override
    public List<String> describe(RelationshipMetrics relationship) {
        List<String> meaning = new ArrayList<>(4);
        meaning.add(relationship.affinity() <= -50 ? "Personally dislikes this player."
                : relationship.affinity() >= 50 ? "Personally feels strong affection for this player."
                : "Has no strong personal liking or dislike for this player.");
        meaning.add(relationship.trust() <= -50 ? "Expects this player may betray or deceive them."
                : relationship.trust() >= 50 ? "Believes this player is likely to keep promises."
                : "Does not yet have strong confidence in this player's reliability.");
        meaning.add(relationship.respect() <= -50 ? "Has little regard for this player's ability or judgement."
                : relationship.respect() >= 50 ? "Recognizes this player's ability and judgement."
                : "Has no strong evaluation of this player's ability yet.");
        meaning.add(relationship.caution() >= 70 ? "Remains highly guarded around this player."
                : relationship.caution() >= 35 ? "Does not fully let down their guard around this player."
                : "Feels little immediate need to guard against this player.");
        return List.copyOf(meaning);
    }
}
