package com.sande.mythictrpg.ai.experiencecontract;

import com.sande.mythictrpg.gameplay.watch.WatchContract;
import java.util.*;

/** Pure projection AFTER audience filtering; cannot recover omitted fields from raw records. */
public final class ExperienceProjection {
    private ExperienceProjection() {}
    public static ExperienceView project(WatchContract.View view, UUID actor, ExperienceView.Relationship relationship) {
        if (!view.available()) return ExperienceView.unavailable(view.reason());
        List<ExperienceView.Event> events = new ArrayList<>();
        for (var proof : view.proofs()) {
            var values = proof.visibleProjection();
            var action = values.get(WatchContract.Field.ACTION); var who = values.get(WatchContract.Field.ACTOR);
            var subject = values.get(WatchContract.Field.SUBJECT_TYPE); var outcome = values.get(WatchContract.Field.OUTCOME);
            var time = values.get(WatchContract.Field.TIME);
            if (action == null || who == null || subject == null || outcome == null || !who.text().equals(actor.toString())) continue;
            try {
                events.add(new ExperienceView.Event(proof.id(), proof.eventId(), proof.sourceRevision(), proof.acquisitionKind(),
                        action.text(), subject.text(), outcome.text(), time == null ? "NOT_DISCLOSED" : time.text()));
            } catch (IllegalArgumentException unsupported) { continue; }
            if (events.size() == 16) break;
        }
        return new ExperienceView(1, true, "READY", events, relationship);
    }
}
