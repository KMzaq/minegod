package com.sande.mythictrpg.ai.tone;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Conservative Korean/English fallback signals for turns that do not justify a first-pass LLM request. */
public final class PlayerSpeechToneHeuristics {
    private PlayerSpeechToneHeuristics() {
    }

    public static Set<PlayerSpeechTone> classify(String rawText) {
        String text = rawText == null ? "" : rawText.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return Set.of();
        }
        EnumSet<PlayerSpeechTone> tones = EnumSet.noneOf(PlayerSpeechTone.class);
        if (containsAny(text, "미안", "죄송", "사과", "sorry", "my fault")) {
            tones.add(PlayerSpeechTone.T_APOLOGETIC);
        }
        if (containsAny(text, "죽여", "죽인다", "부숴", "파괴해", "가만 안", "협박", "kill you", "i'll kill",
                "destroy you")) {
            tones.add(PlayerSpeechTone.T_THREATENING);
            tones.add(PlayerSpeechTone.T_AGGRESSIVE);
        }
        if (containsAny(text, "꺼져", "닥쳐", "병신", "멍청", "쓰레기", "개새", "좆", "fuck", "idiot",
                "shut up")) {
            tones.add(PlayerSpeechTone.T_IMPOLITE);
            tones.add(PlayerSpeechTone.T_AGGRESSIVE);
        }
        if (containsAny(text, "ㅋㅋ", "ㅎㅎ", "ㅉ", "비웃", "꼴좋", "0과 1", "고철", "lol", "lmao")) {
            tones.add(PlayerSpeechTone.T_MOCKING);
        }
        if (containsAny(text, "습니다", "습니까", "십시오", "세요", "주세요", "주실", "감사", "부탁드립니다",
                "please", "could you", "would you")) {
            tones.add(PlayerSpeechTone.T_POLITE);
        } else if (looksInformal(text)) {
            tones.add(PlayerSpeechTone.T_INFORMAL);
        }
        return Set.copyOf(tones);
    }

    /** Strong terms are safe to merge with an LLM classification; formality stays LLM-led when it is ambiguous. */
    public static Set<PlayerSpeechTone> strongSignals(String rawText) {
        EnumSet<PlayerSpeechTone> strong = EnumSet.noneOf(PlayerSpeechTone.class);
        for (PlayerSpeechTone tone : classify(rawText)) {
            if (tone == PlayerSpeechTone.T_IMPOLITE || tone == PlayerSpeechTone.T_AGGRESSIVE
                    || tone == PlayerSpeechTone.T_MOCKING || tone == PlayerSpeechTone.T_THREATENING
                    || tone == PlayerSpeechTone.T_APOLOGETIC) {
                strong.add(tone);
            }
        }
        return Set.copyOf(strong);
    }

    private static boolean looksInformal(String text) {
        return text.matches(".*(해|해봐|해라|줘|야|냐|지|군|거야|했어|인가|있어|없어|할까|할래)[?!…~.]*$")
                || text.matches("^(안녕|응|그래|아니|왜|뭐|뭐야|누구야|심심해|고마워)$");
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
