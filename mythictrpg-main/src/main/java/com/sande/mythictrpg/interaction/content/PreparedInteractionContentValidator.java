package com.sande.mythictrpg.interaction.content;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.dialogue.presentation.DialogueComponentSanitizer;
import com.sande.mythictrpg.dialogue.presentation.DialogueValidationException;
import com.sande.mythictrpg.interaction.director.InteractionPlan;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PreparedInteractionContentValidator {
    public static final PreparedInteractionContentValidator INSTANCE =
            new PreparedInteractionContentValidator();
    public static final ResourceLocation EMPTY_CONTENT = id("empty_content");
    public static final ResourceLocation TOO_MANY_TURNS = id("too_many_dialogue_turns");
    public static final ResourceLocation OUTSIDER_SPEAKER = id("speaker_not_participant");
    public static final ResourceLocation PRIMARY_SILENT = id("primary_did_not_speak");
    public static final ResourceLocation INVALID_COMPONENT = id("invalid_dialogue_component");

    private PreparedInteractionContentValidator() {
    }

    public ContentValidationResult validate(InteractionPlan plan, PreparedInteractionContent content) {
        if (content.turns().isEmpty()) {
            return ContentValidationResult.rejected(EMPTY_CONTENT);
        }
        if (content.turns().size() > PreparedInteractionContent.MAX_TURNS) {
            return ContentValidationResult.rejected(TOO_MANY_TURNS);
        }
        Set<ResourceLocation> participants = new LinkedHashSet<>();
        participants.add(plan.participants().primaryGodId());
        participants.addAll(plan.participants().secondaryGodIds());

        List<PreparedDialogueTurn> sanitized = new ArrayList<>();
        Set<ResourceLocation> manifested = new LinkedHashSet<>();
        try {
            for (PreparedDialogueTurn turn : content.turns()) {
                if (!participants.contains(turn.speakerGodId())) {
                    return ContentValidationResult.rejected(OUTSIDER_SPEAKER);
                }
                sanitized.add(new PreparedDialogueTurn(turn.speakerGodId(),
                        DialogueComponentSanitizer.sanitize(turn.text(),
                                com.sande.mythictrpg.network.ClientDialoguePayload.MAX_DIALOGUE_CODE_POINTS,
                                "dialogueText"),
                        turn.priority(), turn.displayOptions()));
                manifested.add(turn.speakerGodId());
            }
        } catch (DialogueValidationException exception) {
            return ContentValidationResult.rejected(INVALID_COMPONENT);
        }
        if (!manifested.contains(plan.participants().primaryGodId())) {
            return ContentValidationResult.rejected(PRIMARY_SILENT);
        }
        return ContentValidationResult.accepted(
                new ValidatedInteractionContent(sanitized, manifested));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
