package com.sande.mythictrpg.ai.intent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;

/** A bounded, turn-local reading of words, never an NPC emotion, participant identity or execution authority. */
public record TurnInterpretation(Referent subject, Referent target, Mode mode, String meaning, String evidence) {
    public static final int MAX_TEXT_CHARACTERS = 160;

    /** Roles inside the described action/state; CURRENT_NPC is the authorized responding NPC only. */
    public enum Referent { UNSPECIFIED, CURRENT_PLAYER, CURRENT_NPC, OTHER }
    public enum Mode { UNSPECIFIED, STATEMENT, QUESTION, REQUEST, WISH, HYPOTHETICAL, QUOTED, CORRECTION, REFUSAL, BANTER }

    public TurnInterpretation {
        subject = subject == null ? Referent.UNSPECIFIED : subject;
        target = target == null ? Referent.UNSPECIFIED : target;
        mode = mode == null ? Mode.UNSPECIFIED : mode;
        meaning = boundedText(meaning);
        evidence = boundedText(evidence);
        // Reject incomplete/oversized readings together rather than keeping apparently precise role labels.
        if (meaning.isEmpty() || evidence.isEmpty()) {
            subject = Referent.UNSPECIFIED;
            target = Referent.UNSPECIFIED;
            mode = Mode.UNSPECIFIED;
            meaning = "";
            evidence = "";
        }
    }

    public static TurnInterpretation empty() {
        return new TurnInterpretation(Referent.UNSPECIFIED, Referent.UNSPECIFIED, Mode.UNSPECIFIED, "", "");
    }

    public boolean isEmpty() { return meaning.isEmpty(); }

    /** Evidence must quote this exact input, not a memory, another player's line or an earlier turn. */
    public TurnInterpretation validatedFor(String currentPlayerMessage) {
        return !isEmpty() && currentPlayerMessage != null && currentPlayerMessage.contains(evidence) ? this : empty();
    }

    /** Optional extension: malformed data must not discard the otherwise usable legacy classification. */
    public static TurnInterpretation fromJson(JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) return empty();
        JsonObject value = raw.getAsJsonObject();
        try {
            return new TurnInterpretation(enumValue(Referent.class, value, "subject"),
                    enumValue(Referent.class, value, "target"), enumValue(Mode.class, value, "mode"),
                    string(value, "meaning"), string(value, "evidence"));
        } catch (IllegalArgumentException malformed) {
            return empty();
        }
    }

    /** Shared by both classifiers; output is a concise result, not free-form reasoning. */
    public static String classificationInstruction() {
        return """
                TURN_INTERPRETATION_V1
                Add turnInterpretation to the JSON: {"subject":"UNSPECIFIED","target":"UNSPECIFIED",
                "mode":"UNSPECIFIED","meaning":"","evidence":""}. It is an optional, fallible reading of
                CURRENT_PLAYER_MESSAGE in the recent exchange. subject and target describe who performs/experiences
                the action/state and whom it concerns, not merely who is speaking. Their only values are
                UNSPECIFIED, CURRENT_PLAYER, CURRENT_NPC, OTHER. CURRENT_NPC is the authorized responding NPC;
                OTHER is an unresolved reference, never an invented participant ID or permission to add anyone.
                Read Korean subject/object particles, omitted subjects and corrections before assigning roles.
                Tie both roles to the central action/state explicitly named in meaning, not to 'the player says'.
                Asking what the NPC intends to do concerns an NPC action: subject=CURRENT_NPC, with target
                UNSPECIFIED unless the exchange establishes a recipient. Offering to help the NPC instead has
                subject=CURRENT_PLAYER and target=CURRENT_NPC. A question does not itself name its beneficiary.
                Do not reverse who is asking, helping, fearing, mocking or being discussed. Use UNSPECIFIED when
                the exchange does not resolve a role. mode must be UNSPECIFIED, STATEMENT, QUESTION, REQUEST,
                WISH, HYPOTHETICAL, QUOTED, CORRECTION, REFUSAL or BANTER. Distinguish an actual request from a
                wish, imagined case, quoted speech, question, refusal or joke using context, not a keyword alone.
                For a correction, meaning states the corrected reading, not the NPC's earlier misunderstanding.
                meaning is one short paraphrase. evidence is an exact, contiguous quote from CURRENT_PLAYER_MESSAGE
                supporting that reading. Keep each preferably within 60 characters; 160 characters is the hard
                maximum for either field. Leave both empty
                when unsupported. Do not add reasoning, analysis, explanations or an NPC response. Neither this
                reading nor situation/tone tags establish the NPC's emotions, a world fact, consent or successful
                execution. Even REQUEST only describes words; the game still validates every proposed action.
                """;
    }

    private static String boundedText(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.length() > MAX_TEXT_CHARACTERS || value.chars().anyMatch(Character::isISOControl)) return "";
        return value;
    }

    private static String string(JsonObject value, String name) {
        JsonElement raw = value.get(name);
        if (raw == null || raw.isJsonNull()) return "";
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Interpretation fields must be strings");
        return raw.getAsString();
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, JsonObject value, String name) {
        String raw = string(value, name).strip().toUpperCase(Locale.ROOT);
        return Enum.valueOf(type, raw.isEmpty() ? "UNSPECIFIED" : raw);
    }
}
