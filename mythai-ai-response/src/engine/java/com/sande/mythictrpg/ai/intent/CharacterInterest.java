package com.sande.mythictrpg.ai.intent;

import com.google.gson.JsonElement;
import com.sande.mythictrpg.understanding.PurposeEvidence;

/** Optional current-turn candidate. Game resolves the name and admits the real player source before persistence. */
public record CharacterInterest(String targetName,PurposeEvidence.Kind kind,PurposeEvidence.Signal signal,String fullQuote) {
    public static final CharacterInterest NONE=new CharacterInterest("",null,null,"");
    public boolean present() {return kind!=null&&signal!=null&&!targetName.isBlank();}
    public CharacterInterest validatedFor(String text) {return present()&&fullQuote.equals(text)&&text.contains(targetName)?this:NONE;}
    public static CharacterInterest fromJson(JsonElement raw) {
        try {
            if(raw==null||!raw.isJsonObject())return NONE;var o=raw.getAsJsonObject();
            if(!"DIRECT_SELF".equals(o.get("owner").getAsString()))return NONE;
            String name=o.get("targetName").getAsString(),quote=o.get("fullQuote").getAsString();
            var kind=PurposeEvidence.Kind.valueOf(o.get("kind").getAsString());
            var signal=PurposeEvidence.Signal.valueOf(o.get("signal").getAsString());
            if(name.isBlank()||name.length()>100||quote.isBlank()||quote.length()>256)return NONE;
            // Constructor enforces the conservative production vocabulary; DECIDE is deliberately not inferred.
            new com.sande.mythictrpg.understanding.CharacterGoalSignal(new PurposeEvidence.Goal(kind,"mythictrpg:validation"),signal,java.util.UUID.randomUUID(),quote);
            return new CharacterInterest(name,kind,signal,quote);
        }catch(RuntimeException malformed){return NONE;}
    }
    public static String instruction() {return """
            OPTIONAL_CHARACTER_INTEREST_V1
            For player support statistics only, add characterInterest to JSON. Default:
            {"owner":"NONE","targetName":"","kind":"NONE","signal":"NONE","fullQuote":""}.
            Only extract one unambiguous CURRENT_PLAYER_MESSAGE personal character-related goal.
            owner DIRECT_SELF means the actual goal belongs to the speaker, not a friend, NPC, quoted speaker,
            hypothetical person or someone the player is helping. Other-person/proxy plans, quotations, hypothetical
            or mixed ownership: return the default. Do not turn 'I ask about my friend's goal' into a personal goal.
            Copy the FULL current message exactly into fullQuote, preserving all negation. Copy an explicit character
            name into targetName; never invent IDs or resolve implicit pronouns. No explicit name: default.
            kind LEARN_ABOUT for personal information interest; MEET only for the player's wish to meet that character.
            signal ASK for a personal information question, DESIRE for a wish, CONSIDER for deliberation,
            DISOWN for 'not my goal', WITHDRAW for abandoning it, PAUSE for putting it on hold.
            Never emit DECIDE, COMPLETE or game actions. Even a strongly worded desire remains DESIRE;
            An explicit commitment alone is outside this vocabulary: return the default, never downgrade its meaning.
            Do not derive a goal from mere name-dropping, a joke, general advice or an NPC's previous suggestion.
            """;}
}
