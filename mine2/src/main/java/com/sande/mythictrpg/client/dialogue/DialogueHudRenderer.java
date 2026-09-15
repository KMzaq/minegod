package com.sande.mythictrpg.client.dialogue;

import com.sande.mythictrpg.dialogue.playback.DialoguePlaybackSnapshot;
import com.sande.mythictrpg.network.ClientDialoguePayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

public final class DialogueHudRenderer {
    private DialogueHudRenderer() {
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null || minecraft.level == null) {
            return;
        }
        DialoguePlaybackSnapshot snapshot = DialogueHudController.INSTANCE.snapshot();
        ClientDialoguePayload payload = snapshot.current().orElse(null);
        if (payload == null || snapshot.alpha() <= 0.0F) {
            return;
        }

        Font font = minecraft.font;
        int guiWidth = graphics.guiWidth();
        int guiHeight = graphics.guiHeight();
        int maxWidth = Math.max(1, Math.min(DialogueHudLayout.MAX_WIDTH,
                guiWidth - DialogueHudLayout.HORIZONTAL_MARGIN * 2));
        Component speaker = Component.literal("[").append(payload.speakerDisplayName()).append("]");
        List<FormattedCharSequence> speakerLines = font.split(speaker, maxWidth);
        List<FormattedCharSequence> dialogueLines = font.split(payload.dialogueText(), maxWidth);
        int lineHeight = font.lineHeight + DialogueHudLayout.LINE_GAP;
        int availableHeight = Math.max(lineHeight, guiHeight - DialogueHudLayout.VERTICAL_MARGIN * 2);
        int speakerHeight = speakerLines.size() * lineHeight;
        int maxDialogueLines = Math.max(1,
                (availableHeight - speakerHeight - DialogueHudLayout.SPEAKER_BODY_GAP) / lineHeight);
        boolean clipped = dialogueLines.size() > maxDialogueLines;
        List<FormattedCharSequence> visibleDialogue = new ArrayList<>(
                dialogueLines.subList(0, Math.min(dialogueLines.size(), maxDialogueLines)));
        if (clipped) {
            visibleDialogue.set(visibleDialogue.size() - 1, Component.literal("...").getVisualOrderText());
        }

        int contentHeight = speakerHeight + DialogueHudLayout.SPEAKER_BODY_GAP
                + visibleDialogue.size() * lineHeight;
        int anchorY = Math.round(guiHeight * DialogueHudLayout.ANCHOR_Y_RATIO);
        int top = Math.clamp(anchorY - contentHeight / 2,
                DialogueHudLayout.VERTICAL_MARGIN,
                Math.max(DialogueHudLayout.VERTICAL_MARGIN,
                        guiHeight - DialogueHudLayout.VERTICAL_MARGIN - contentHeight));
        int color = (Math.clamp(Math.round(snapshot.alpha() * 255.0F), 0, 255) << 24) | 0xFFFFFF;

        int y = top;
        for (FormattedCharSequence line : speakerLines) {
            drawCentered(graphics, font, line, guiWidth, y, color);
            y += lineHeight;
        }
        y += DialogueHudLayout.SPEAKER_BODY_GAP;
        for (FormattedCharSequence line : visibleDialogue) {
            drawCentered(graphics, font, line, guiWidth, y, color);
            y += lineHeight;
        }
    }

    private static void drawCentered(GuiGraphics graphics, Font font, FormattedCharSequence line,
            int guiWidth, int y, int color) {
        int x = (guiWidth - font.width(line)) / 2;
        graphics.drawString(font, line, x, y, color, DialogueHudLayout.DROP_SHADOW);
    }
}
