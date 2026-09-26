package com.sande.mythictrpg.ai.room;

import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import java.util.*;
import java.util.function.*;

/** Single game-side fan-out. Recording failures never undo delivery or abort other recipients. */
public final class RoomDialoguePublisher {
    private RoomDialoguePublisher() { }
    public static RoomDialogueEvent publish(RoomDialogueEvent draft, Collection<UUID> recipients,
            Function<UUID, RoomDialogueEvent.Delivery> dispatch, Consumer<RoomDialogueEvent> observer,
            Consumer<RuntimeException> reportFailure) {
        // Validate scope before sending even one private message.
        var targets = new LinkedHashSet<>(recipients);
        if (!draft.deliveries().isEmpty() || draft.roomType() == RoomType.PRIVATE
                && !draft.participantNames().keySet().containsAll(targets))
            throw new IllegalArgumentException("Invalid room fan-out");
        var actual = new LinkedHashMap<UUID, RoomDialogueEvent.Delivery>();
        for (UUID target : targets) {
            try {
                var delivery = dispatch.apply(target);
                if (delivery != null) actual.put(target, delivery);
            } catch (RuntimeException failed) { reportFailure.accept(failed); }
        }
        var event = draft.withDeliveries(actual);
        // Off means no new recording callback, including initial/consent/non-LLM dialogue.
        if (event.recordingScope().recordingAllowed()) {
            try { observer.accept(event); }
            catch (RuntimeException failed) { reportFailure.accept(failed); }
        }
        return event;
    }
}
