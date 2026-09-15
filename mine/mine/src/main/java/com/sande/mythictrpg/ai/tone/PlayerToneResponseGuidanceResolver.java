package com.sande.mythictrpg.ai.tone;

import com.sande.mythictrpg.ai.AiDialogueModels;
import com.sande.mythictrpg.ai.agent.NpcAgent;
import com.sande.mythictrpg.ai.example.DialogueExampleTag;
import com.sande.mythictrpg.ai.tag.ExampleStyleTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Converts tone + authoritative social/relationship context into bounded response guidance for one NPC. */
public final class PlayerToneResponseGuidanceResolver {
    public PlayerToneResponseGuidance resolve(Set<PlayerSpeechTone> tones,
            AiDialogueModels.RelationshipContext relationship, NpcAgent agent,
            NpcSocialAuthorityContext authority) {
        Set<PlayerSpeechTone> safeTones = tones == null ? Set.of() : Set.copyOf(tones);
        NpcSocialAuthorityContext safeAuthority = authority == null ? NpcSocialAuthorityContext.unknown() : authority;
        boolean close = relationship != null && relationship.derivedTags().contains(DialogueExampleTag.R_CLOSE.name());
        boolean friendly = relationship != null && relationship.derivedTags().contains(DialogueExampleTag.R_FRIENDLY.name());
        boolean formalSensitive = agent != null && agent.speechStyles().stream().anyMatch(style -> style == ExampleStyleTag.P_FORMAL
                || style == ExampleStyleTag.P_STRICT || style == ExampleStyleTag.P_IMPERIOUS);
        boolean playful = agent != null && agent.speechStyles().contains(ExampleStyleTag.P_PLAYFUL);
        List<String> rules = new ArrayList<>();

        if (safeTones.contains(PlayerSpeechTone.T_THREATENING) || safeTones.contains(PlayerSpeechTone.T_AGGRESSIVE)) {
            rules.add("Treat hostile or threatening delivery as the immediate issue; set a clear boundary and de-escalate.");
            rules.add("Do not claim punishment, combat, a relationship change, or any game action already occurred.");
            return new PlayerToneResponseGuidance(safeTones, safeAuthority, ToneResponseDisposition.DEESCALATE, rules);
        }
        if (safeTones.contains(PlayerSpeechTone.T_IMPOLITE)) {
            if (safeAuthority.relativeAuthority() == RelativeAuthority.PLAYER_SUPERIOR) {
                rules.add("The player holds higher current authority, but insulting delivery is still not automatically acceptable.");
                rules.add("Respond firmly and professionally; address the request instead of demanding etiquette alone.");
                return new PlayerToneResponseGuidance(safeTones, safeAuthority, ToneResponseDisposition.FIRM_BOUNDARY, rules);
            }
            rules.add("Treat the delivery as rude and state a proportionate boundary in this NPC's own voice.");
            rules.add("A single rude turn changes immediate tone only; do not assert that long-term relationship values changed.");
            return new PlayerToneResponseGuidance(safeTones, safeAuthority, ToneResponseDisposition.FIRM_BOUNDARY, rules);
        }
        if (safeTones.contains(PlayerSpeechTone.T_MOCKING) && !(close && playful)) {
            rules.add("Notice the mockery and answer with a measured warning or a character-appropriate retort.");
            rules.add("Do not escalate to a game action or persistent penalty from one remark.");
            return new PlayerToneResponseGuidance(safeTones, safeAuthority, ToneResponseDisposition.CAUTION, rules);
        }
        if (safeTones.contains(PlayerSpeechTone.T_INFORMAL)) {
            if (close || (friendly && !formalSensitive)
                    || safeAuthority.relativeAuthority() == RelativeAuthority.PLAYER_SUPERIOR) {
                rules.add("Treat casual speech as acceptable in this relationship or authority context; do not scold the player solely for speaking informally.");
                return new PlayerToneResponseGuidance(safeTones, safeAuthority, ToneResponseDisposition.ACCEPT, rules);
            }
            if (formalSensitive && safeAuthority.relativeAuthority() != RelativeAuthority.PLAYER_SUPERIOR) {
                rules.add("The player is not yet close enough for casual speech with this formal NPC; give a mild, in-character etiquette warning if it fits the reply.");
                rules.add("Keep the response proportional: casual speech alone is not a completed relationship penalty.");
                return new PlayerToneResponseGuidance(safeTones, safeAuthority, ToneResponseDisposition.CAUTION, rules);
            }
            rules.add("Notice the casual delivery without assuming it is hostile; answer the actual request normally.");
            return new PlayerToneResponseGuidance(safeTones, safeAuthority, ToneResponseDisposition.NOTICE, rules);
        }
        if (safeTones.contains(PlayerSpeechTone.T_APOLOGETIC)) {
            rules.add("Acknowledge the apology in character, but do not automatically forgive or alter relationship values.");
            return new PlayerToneResponseGuidance(safeTones, safeAuthority, ToneResponseDisposition.NOTICE, rules);
        }
        if (safeTones.contains(PlayerSpeechTone.T_POLITE)) {
            rules.add("Recognize the respectful delivery naturally, without granting a reward, favor, or relationship change.");
            return new PlayerToneResponseGuidance(safeTones, safeAuthority, ToneResponseDisposition.ACCEPT, rules);
        }
        rules.add("No strong speech-tone reaction is required; prioritize the request, relationship, emotion, and persona.");
        return new PlayerToneResponseGuidance(safeTones, safeAuthority, ToneResponseDisposition.NOTICE, rules);
    }
}
