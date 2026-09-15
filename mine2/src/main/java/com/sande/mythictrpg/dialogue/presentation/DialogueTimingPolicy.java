package com.sande.mythictrpg.dialogue.presentation;

import com.sande.mythictrpg.dialogue.api.DialogueDisplayOptions;
import net.minecraft.network.chat.Component;

public final class DialogueTimingPolicy {
    public static final int FADE_IN_TICKS = 8;
    public static final int FADE_OUT_TICKS = 12;
    public static final int MIN_HOLD_TICKS = 40;
    public static final int MAX_HOLD_TICKS = 200;

    private DialogueTimingPolicy() {
    }

    public static DialogueTiming calculate(Component dialogueText, DialogueDisplayOptions options) {
        int codePoints = DialogueComponentSanitizer.codePointCount(dialogueText.getString());
        int automatic = 30 + codePoints * 20 / 15;
        int requested = options.requestedHoldTicks().orElse(automatic);
        return new DialogueTiming(FADE_IN_TICKS,
                Math.clamp(requested, MIN_HOLD_TICKS, MAX_HOLD_TICKS), FADE_OUT_TICKS);
    }
}
