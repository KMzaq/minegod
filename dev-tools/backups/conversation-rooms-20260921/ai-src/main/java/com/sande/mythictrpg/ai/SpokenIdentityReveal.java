package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.data.player.KnowledgeMutationResult;
import com.sande.mythictrpg.data.player.PlayerGodKnowledgeService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Converts an explicit self-introduction in approved NPC speech into a game-owned identification record. */
final class SpokenIdentityReveal {
    private SpokenIdentityReveal() {
    }

    static void commitIfNameWasSpoken(ServerPlayer player, ResourceLocation speakerGodId,
            String registeredDisplayName, String approvedSpeech) {
        if (com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode()
                == com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.RUMOR_TEST) return;
        String name = normalize(registeredDisplayName);
        String speech = normalize(approvedSpeech);
        if (name.isBlank() || speech.isBlank() || !explicitlyIntroducesSelf(speech, name)) {
            return;
        }
        PlayerGodKnowledgeService knowledge = PlayerGodKnowledgeService.get(player.server);
        if (knowledge.snapshot(player.getUUID(), speakerGodId).identified()) {
            return;
        }
        KnowledgeMutationResult result = knowledge.identifyGod(player, speakerGodId);
        if (result == KnowledgeMutationResult.NEW_RECORD) {
            MythicTrpg.LOGGER.info("Player {} identified {} after its approved dialogue disclosed the registered name",
                    player.getUUID(), speakerGodId);
        }
    }

    private static boolean explicitlyIntroducesSelf(String speech, String name) {
        String quotedName = Pattern.quote(name);
        String ending = "(?:이다|다|야|란다|입니다|예요|라고 한다|라고 하네|라고 부른다|라고 부르거라)?";
        String boundary = "(?:\\s|[,.!?。]|$)";
        return Pattern.compile("(?:^|[.!?。]\\s*)(?:나는|난|내가|이 몸은)[^.!?。]{0,80}?"
                        + quotedName + ending + boundary).matcher(speech).find()
                || Pattern.compile("(?:^|[.!?。]\\s*)내 이름은\\s*(?:바로\\s*)?"
                        + quotedName + ending + boundary).matcher(speech).find()
                || Pattern.compile("(?:^|[.!?。]\\s*)나를\\s*" + quotedName
                        + "(?:라고|라)?\\s*(?:부르면 된다|부르거라|불러라|불러도 된다)" + boundary)
                        .matcher(speech).find()
                || Pattern.compile("(?:^|[.!?。]\\s*)" + quotedName
                        + "(?:이다|다|야|란다|입니다|예요)" + boundary).matcher(speech).find()
                || Pattern.compile("(?:^|[.!?。]\\s*)(?:i am|i'm|my name is|call me)\\s+"
                        + quotedName + boundary).matcher(speech).find();
    }

    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
