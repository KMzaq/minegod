package com.sande.mythictrpg.dialogue.playback;

import com.sande.mythictrpg.dialogue.api.DialoguePriority;
import com.sande.mythictrpg.network.ClientDialoguePayload;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Bounded, transient client playback state kept common-safe for deterministic tests. */
public final class DialoguePlaybackState {
    public static final int MAX_PENDING = 32;
    public static final int MAX_RECENT_IDS = 128;

    private final List<ClientDialoguePayload> pending = new ArrayList<>();
    private final LinkedHashMap<UUID, Boolean> recentIds = new LinkedHashMap<>();
    private ClientDialoguePayload current;
    private DialoguePlaybackStage stage = DialoguePlaybackStage.IDLE;
    private int stageTicks;
    private long duplicateCount;
    private long overflowDropCount;
    private long overflowEvictionCount;
    private long interruptCount;

    public DialogueEnqueueResult enqueue(ClientDialoguePayload incoming) {
        if (!remember(incoming.messageId())) {
            duplicateCount++;
            return DialogueEnqueueResult.DUPLICATE;
        }
        if (current == null) {
            activate(incoming);
            return DialogueEnqueueResult.STARTED;
        }
        if (incoming.priority() == DialoguePriority.CRITICAL
                && current.priority() != DialoguePriority.CRITICAL) {
            activate(incoming);
            interruptCount++;
            return DialogueEnqueueResult.INTERRUPTED;
        }
        if (pending.size() < MAX_PENDING) {
            insertByPriority(incoming);
            return DialogueEnqueueResult.QUEUED;
        }
        return handleOverflow(incoming);
    }

    public void tick(boolean playableContext) {
        if (!playableContext || current == null) {
            return;
        }
        stageTicks++;
        switch (stage) {
            case FADE_IN -> {
                if (stageTicks >= current.fadeInTicks()) {
                    stage = DialoguePlaybackStage.HOLD;
                    stageTicks = 0;
                }
            }
            case HOLD -> {
                if (stageTicks >= current.holdTicks()) {
                    stage = DialoguePlaybackStage.FADE_OUT;
                    stageTicks = 0;
                }
            }
            case FADE_OUT -> {
                if (stageTicks >= current.fadeOutTicks()) {
                    advance();
                }
            }
            case IDLE -> advance();
        }
    }

    public DialoguePlaybackSnapshot snapshot() {
        if (current == null) {
            return DialoguePlaybackSnapshot.idle();
        }
        float alpha = switch (stage) {
            case IDLE -> 0.0F;
            case FADE_IN -> current.fadeInTicks() == 0 ? 1.0F
                    : Math.clamp((float) stageTicks / current.fadeInTicks(), 0.0F, 1.0F);
            case HOLD -> 1.0F;
            case FADE_OUT -> current.fadeOutTicks() == 0 ? 0.0F
                    : Math.clamp(1.0F - (float) stageTicks / current.fadeOutTicks(), 0.0F, 1.0F);
        };
        return new DialoguePlaybackSnapshot(Optional.of(current), stage, alpha);
    }

    public List<ClientDialoguePayload> pending() {
        return List.copyOf(pending);
    }

    public int recentIdCount() {
        return recentIds.size();
    }

    public long duplicateCount() {
        return duplicateCount;
    }

    public long overflowDropCount() {
        return overflowDropCount;
    }

    public long overflowEvictionCount() {
        return overflowEvictionCount;
    }

    public long interruptCount() {
        return interruptCount;
    }

    public void reset() {
        pending.clear();
        recentIds.clear();
        current = null;
        stage = DialoguePlaybackStage.IDLE;
        stageTicks = 0;
        duplicateCount = 0;
        overflowDropCount = 0;
        overflowEvictionCount = 0;
        interruptCount = 0;
    }

    private DialogueEnqueueResult handleOverflow(ClientDialoguePayload incoming) {
        if (incoming.priority() == DialoguePriority.NORMAL) {
            overflowDropCount++;
            return DialogueEnqueueResult.DROPPED_OVERFLOW;
        }
        if (incoming.priority() == DialoguePriority.IMPORTANT) {
            int oldestNormal = firstIndexOf(DialoguePriority.NORMAL);
            if (oldestNormal < 0) {
                overflowDropCount++;
                return DialogueEnqueueResult.DROPPED_OVERFLOW;
            }
            pending.remove(oldestNormal);
            insertByPriority(incoming);
            overflowEvictionCount++;
            return DialogueEnqueueResult.QUEUED_AFTER_EVICTION;
        }

        int evictionIndex = firstIndexOf(DialoguePriority.NORMAL);
        if (evictionIndex < 0) {
            evictionIndex = firstIndexOf(DialoguePriority.IMPORTANT);
        }
        if (evictionIndex < 0) {
            evictionIndex = firstIndexOf(DialoguePriority.CRITICAL);
        }
        pending.remove(evictionIndex);
        insertByPriority(incoming);
        overflowEvictionCount++;
        return DialogueEnqueueResult.QUEUED_AFTER_EVICTION;
    }

    private void insertByPriority(ClientDialoguePayload incoming) {
        int index = 0;
        while (index < pending.size()
                && pending.get(index).priority().networkId() >= incoming.priority().networkId()) {
            index++;
        }
        pending.add(index, incoming);
    }

    private int firstIndexOf(DialoguePriority priority) {
        for (int index = 0; index < pending.size(); index++) {
            if (pending.get(index).priority() == priority) {
                return index;
            }
        }
        return -1;
    }

    private boolean remember(UUID messageId) {
        if (recentIds.containsKey(messageId)) {
            return false;
        }
        recentIds.put(messageId, Boolean.TRUE);
        while (recentIds.size() > MAX_RECENT_IDS) {
            Iterator<Map.Entry<UUID, Boolean>> iterator = recentIds.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }
        return true;
    }

    private void activate(ClientDialoguePayload payload) {
        current = payload;
        stage = payload.fadeInTicks() == 0 ? DialoguePlaybackStage.HOLD : DialoguePlaybackStage.FADE_IN;
        stageTicks = 0;
    }

    private void advance() {
        if (pending.isEmpty()) {
            current = null;
            stage = DialoguePlaybackStage.IDLE;
            stageTicks = 0;
            return;
        }
        activate(pending.removeFirst());
    }
}
