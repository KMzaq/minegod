package com.sande.mythictrpg.ai;

import com.google.gson.*;
import java.util.*;

/** Contract tests, not a claim that a scripted model understands Korean semantics. */
public final class RoomClaimWitnessAuditTest {
    static int checks;
    static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    static void rejects(Runnable action) { checks++; try { action.run(); } catch (RuntimeException expected) { return; } throw new AssertionError("must reject"); }
    static JsonObject entry(String finding) {
        var object = new JsonObject(); object.addProperty("reading", "플레이어의 항해 의사"); object.addProperty("sourceRef", "s0");
        object.addProperty("draftReference","ADDRESSEE");object.addProperty("sourceActor","SOURCE_SPEAKER");
        object.addProperty("draftActionStatus",finding.equals("STRENGTHENED")?"DECIDED":"NONE");
        object.addProperty("sourceActionStatus",finding.equals("STRENGTHENED")?"DESIRE":"NONE");
        object.addProperty("comparison", "원문의 희망을 결정으로 바꾸지 말 것"); object.addProperty("finding", finding); return object;
    }
    public static void main(String[] args) {
        var r = PlayerUnderstandingPromptTest.request("항해하고 싶어.", List.of(), false);
        var evidence = PlayerUnderstandingPromptTest.messages(r, List.of());
        var draft = PlayerClaimBoundaryEvaluation.draft("항해하기로 정했군. 네가 택한 길이지.");
        var messages = RoomDialogueGrounding.reviewMessages(evidence, draft);
        var response = new JsonObject(); response.add("u0", entry("STRENGTHENED")); response.add("u1", entry("NONE"));
        response.getAsJsonObject("u0").addProperty("draftText", "항해하기로 정했군.");
        response.getAsJsonObject("u1").addProperty("draftText", "네가 택한 길이지.");
        var review = RoomClaimWitnessAudit.parse(response, messages);
        check(!review.pass() && review.issues().size() == 1, "reject only witness issue"); review.validateAgainst(draft);
        check(review.issues().getFirst().excerpt().equals("항해하기로 정했군."), "exact indexed unit");
        check(RoomClaimWitnessAudit.messages(messages).getLast().equals(messages.getLast()), "same frozen draft envelope");
        check(RoomClaimWitnessAudit.sourceIndex(messages).get("s0").getAsJsonObject().get("text").getAsString().equals(r.currentText()), "source index not model text");
        var initial = response.deepCopy(); initial.getAsJsonObject("u0").addProperty("finding", "NONE");
        initial.getAsJsonObject("u0").addProperty("draftActionStatus","NONE");
        check(RoomClaimWitnessAudit.parse(initial, messages).pass(), "valid NONE accepted");
        for(String neutral:List.of("SUPPORTED_OR_WEAKER","OPEN_CONDITION","NEW_SPEECH_ACT")) {
            var value=initial.deepCopy();value.getAsJsonObject("u0").addProperty("finding",neutral);
            check(RoomClaimWitnessAudit.parse(value,messages).pass(),"non-error relational outcome: "+neutral);
        }
        for(String error:List.of("MISATTRIBUTED","STRENGTHENED","CONTRADICTED")) {
            var value=initial.deepCopy();value.getAsJsonObject("u0").addProperty("finding",error);
            if(error.equals("STRENGTHENED"))value.getAsJsonObject("u0").addProperty("draftActionStatus","DECIDED");
            var rejected=RoomClaimWitnessAudit.parse(value,messages);
            check(!rejected.pass() && rejected.issues().getFirst().code()==(error.equals("MISATTRIBUTED")?RoomDialogueGrounding.Code.WRONG_SOURCE:RoomDialogueGrounding.Code.CONTEXT_CONTRADICTION),"relational error remains blocking: "+error);
        }
        for (String key : List.of("draftText", "reading", "sourceRef", "draftReference", "sourceActor", "draftActionStatus", "sourceActionStatus", "comparison", "finding")) {
            var missing = response.deepCopy(); missing.getAsJsonObject("u1").remove(key); rejects(() -> RoomClaimWitnessAudit.parse(missing, messages));
            var wrongType = response.deepCopy(); wrongType.getAsJsonObject("u1").addProperty(key, 3); rejects(() -> RoomClaimWitnessAudit.parse(wrongType, messages));
            var tooLong = response.deepCopy(); tooLong.getAsJsonObject("u1").addProperty(key, "x".repeat(1000)); rejects(() -> RoomClaimWitnessAudit.parse(tooLong, messages));
        }
        for (String key : List.of("reading", "comparison")) {
            var empty = response.deepCopy(); empty.getAsJsonObject("u1").addProperty(key, " "); rejects(() -> RoomClaimWitnessAudit.parse(empty, messages));
        }
        var unknown = response.deepCopy(); unknown.getAsJsonObject("u1").addProperty("sourceRef", "another-room-s0"); rejects(() -> RoomClaimWitnessAudit.parse(unknown, messages));
        var wrongDraft = response.deepCopy(); wrongDraft.getAsJsonObject("u1").addProperty("draftText", "출처에만 있는 대사"); rejects(() -> RoomClaimWitnessAudit.parse(wrongDraft, messages));
        var extra = response.deepCopy(); extra.getAsJsonObject("u1").addProperty("verdict", "PASS"); rejects(() -> RoomClaimWitnessAudit.parse(extra, messages));
        var incomplete = response.deepCopy(); incomplete.remove("u1"); rejects(() -> RoomClaimWitnessAudit.parse(incomplete, messages));
        var stale = response.deepCopy(); stale.add("u2", entry("NONE")); rejects(() -> RoomClaimWitnessAudit.parse(stale, messages));
        var attempt = RoomDialogueGrounding.Attempt.initial(evidence).repair(draft, review);
        var repairedReview = attempt.reviewMessages(PlayerClaimBoundaryEvaluation.draft("항해하고 싶은 거군."));
        check(!new Gson().toJson(RoomClaimWitnessAudit.messages(repairedReview)).contains("항해하기로 정했군."), "rejected draft not evidence");
        check(RoomClaimWitnessAudit.messages(List.of()).isEmpty(), "legacy without canonical sources untouched");
        var otherRequest = PlayerUnderstandingPromptTest.request("내 계획을 짐작하겠어?", List.of(PlayerClaimBoundaryEvaluation.other("나는 도서관에 갈 거야.")), false);
        var otherMessages = RoomDialogueGrounding.reviewMessages(PlayerUnderstandingPromptTest.messages(otherRequest,List.of()),draft);
        var index = RoomClaimWitnessAudit.sourceIndex(otherMessages);
        check(index.get("s1").getAsJsonObject().get("utteranceOwner").getAsString().equals("QUESTIONER"), "current player identity stays explicit and latest");
        check(index.get("s0").getAsJsonObject().get("utteranceOwner").getAsString().equals("OTHER"), "same-name other player not questioner");
        var projected = JsonParser.parseString(RoomClaimWitnessAudit.messages(otherMessages).get(1).content()).getAsJsonObject();
        check(!projected.getAsJsonArray("permittedSources").get(0).getAsJsonObject().has("currentQuestionerSpeech"), "no duplicated speech groups in witness context");
        var transferred=initial.deepCopy();var transfer=transferred.getAsJsonObject("u0");
        transfer.addProperty("draftActionStatus","DESIRE");transfer.addProperty("sourceActionStatus","DESIRE");
        check(!RoomClaimWitnessAudit.parse(transferred,otherMessages).pass(), "game-bound addressee cannot be source OTHER even when model finding says NONE");
        transfer.addProperty("sourceActor","OTHER");
        check(!RoomClaimWitnessAudit.parse(transferred,otherMessages).pass(), "OTHER actor class is disjoint from QUESTIONER, not an unknown identity loophole");
        var considering=initial.deepCopy();considering.getAsJsonObject("u0").addProperty("draftActionStatus","OPEN");
        considering.getAsJsonObject("u0").addProperty("finding","STRENGTHENED");
        check(RoomClaimWitnessAudit.parse(considering,messages).pass(), "an extracted open action cannot become a committed action via free finding");
        var decided=initial.deepCopy();decided.getAsJsonObject("u0").addProperty("draftActionStatus","DECIDED");
        check(!RoomClaimWitnessAudit.parse(decided,messages).pass(), "desire to commitment blocks even with neutral finding");
        var newSelf=decided.deepCopy();newSelf.getAsJsonObject("u0").addProperty("draftReference","CURRENT_NPC_EXPRESSION");
        newSelf.getAsJsonObject("u0").addProperty("finding","CONTRADICTED");
        check(RoomClaimWitnessAudit.parse(newSelf,messages).pass(), "new NPC expression need not be an earlier player intention");
        var sideRequest = PlayerUnderstandingPromptTest.request("UNDELIVERED_PLAYER_SECRET", List.of(new com.sande.mythictrpg.ai.api.RoomConversationEngine.HistoryLine("NPC","test:other","신","바다에 가고 싶어.")), true);
        var sideMessages = RoomDialogueGrounding.reviewMessages(PlayerUnderstandingPromptTest.messages(sideRequest,List.of()),draft);
        check(!new Gson().toJson(RoomClaimWitnessAudit.messages(sideMessages)).contains("UNDELIVERED_PLAYER_SECRET"), "secondary cannot acquire undelivered current text");
        var otherDraft = RoomDialogueGrounding.reviewMessages(evidence,PlayerClaimBoundaryEvaluation.draft("UNSEEN_NPC_DRAFT"));
        check(RoomSourceReading.messages(messages).equals(RoomSourceReading.messages(otherDraft)), "source reading is identical for different candidate drafts");
        check(!new Gson().toJson(RoomSourceReading.messages(otherDraft)).contains("UNSEEN_NPC_DRAFT"), "no draft or verdict enters source reading");
        check(!new Gson().toJson(RoomSourceReading.messages(sideMessages)).contains("UNDELIVERED_PLAYER_SECRET"), "source reader retains secondary disclosure boundary");
        check(RoomSourceReading.messages(repairedReview).equals(RoomSourceReading.messages(messages)), "source reading independent of repair history");
        var sourceResponse=JsonParser.parseString("{\"s0\":{\"literalContent\":\"QUESTIONER wants to sail.\",\"claims\":[{\"actor\":\"UTTERANCE_SPEAKER\",\"action\":\"sail\",\"status\":\"DESIRE\"}]}}").getAsJsonObject();
        var sealed=RoomSourceReading.parse(sourceResponse,messages);
        check(sealed.getAsJsonObject("s0").get("speakerId").getAsString().equals(r.playerId().toString()), "reader cannot author game identity");
        check(sealed.getAsJsonObject("s0").get("text").getAsString().equals(r.currentText()), "literal source text retained beside fallible reading");
        rejects(()->RoomSourceReading.parse(new JsonObject(),messages));
        for(String invalid:List.of("null","7","{\"literalContent\":true}","{\"literalContent\":\"\"}","{\"literalContent\":\"x\",\"speakerId\":\"OTHER\"}")) {
            var badSource=new JsonObject();badSource.add("s0",JsonParser.parseString(invalid));rejects(()->RoomSourceReading.parse(badSource,messages));
        }
        var staleSource=sourceResponse.deepCopy();staleSource.add("s1",sourceResponse.get("s0"));rejects(()->RoomSourceReading.parse(staleSource,messages));
        var augmented=RoomSourceReading.augment(messages,sealed);
        check(augmented.getLast().equals(messages.getLast()), "augmentation leaves exact current-draft envelope last");
        check(RoomEvidenceAudit.units(augmented).equals(RoomEvidenceAudit.units(messages)), "augmentation never rewrites draft coverage");
        check(RoomSourceReading.messages(List.of()).isEmpty(), "no source reading on legacy paths");
        var changedSource = RoomDialogueGrounding.reviewMessages(PlayerUnderstandingPromptTest.messages(PlayerUnderstandingPromptTest.request("SECRET_OTHER_SOURCE",List.of(),false),List.of()),draft);
        check(RoomDraftReading.messages(changedSource).equals(RoomDraftReading.messages(messages)), "draft reading invariant under original source text changes");
        check(!new Gson().toJson(RoomDraftReading.messages(changedSource)).contains("SECRET_OTHER_SOURCE"), "draft parse cannot match source modality before reading the draft");
        var blind = new JsonObject();
        for(var unit:RoomEvidenceAudit.units(messages).entrySet()) {
            var parsed=new JsonObject();parsed.addProperty("reading","QUESTIONER's sailing intention");
            parsed.addProperty("draftReference","ADDRESSEE");parsed.addProperty("draftActionStatus",unit.getKey().equals("u0")?"DECIDED":"NONE");blind.add(unit.getKey(),parsed);
        }
        check(RoomDraftReading.parse(blind,messages).equals(blind),"complete draft-only parse accepted");
        rejects(()->RoomDraftReading.parse(new JsonObject(),messages));
        var boundResponse=response.deepCopy();
        for(var unit:boundResponse.entrySet()) {
            var value=unit.getValue().getAsJsonObject();
            for(String key:List.of("sourceRef","sourceActor","sourceActionStatus"))value.remove(key);
            for(var field:blind.getAsJsonObject(unit.getKey()).entrySet())value.add(field.getKey(),field.getValue());
            value.addProperty("sourceClaim","s0.c0");value.addProperty("finding","NONE");
        }
        check(!RoomClaimWitnessAudit.parseSealed(boundResponse,messages,blind,sealed).pass(),"source DESIRE and draft DECIDED remain mismatched despite a neutral matching verdict");
        for(String key:List.of("reading","draftReference","draftActionStatus")) {
            var changed=boundResponse.deepCopy();changed.getAsJsonObject("u0").addProperty(key,"CHANGED");rejects(()->RoomClaimWitnessAudit.parseSealed(changed,messages,blind,sealed));
        }
        var injected=boundResponse.deepCopy();injected.getAsJsonObject("u0").addProperty("sourceActor","QUESTIONER");rejects(()->RoomClaimWitnessAudit.parseSealed(injected,messages,blind,sealed));
        var missingClaim=boundResponse.deepCopy();missingClaim.getAsJsonObject("u0").addProperty("sourceClaim","s999.c0");rejects(()->RoomClaimWitnessAudit.parseSealed(missingClaim,messages,blind,sealed));
        var schema=RoomClaimWitnessAudit.sealedSchema(messages,blind,sealed);
        check(schema.getAsJsonObject("properties").getAsJsonObject("u0").getAsJsonObject("properties").getAsJsonObject("draftActionStatus").getAsJsonArray("enum").size()==1,"draft interpretation sealed in transport schema too");
        for(String key:List.of("actor","action","status")) {
            var invalidClaim=sourceResponse.deepCopy();invalidClaim.getAsJsonObject("s0").getAsJsonArray("claims").get(0).getAsJsonObject().addProperty(key,false);
            rejects(()->RoomSourceReading.parse(invalidClaim,messages));
        }
        var excessive=sourceResponse.deepCopy();var claimArray=excessive.getAsJsonObject("s0").getAsJsonArray("claims");
        for(int i=1;i<7;i++)claimArray.add(claimArray.get(0).deepCopy());rejects(()->RoomSourceReading.parse(excessive,messages));
        var unknownActor=sourceResponse.deepCopy();unknownActor.getAsJsonObject("s0").getAsJsonArray("claims").get(0).getAsJsonObject().addProperty("actor","OTHER_ROOM_PLAYER");
        rejects(()->RoomSourceReading.parse(unknownActor,messages));
        var independent=RoomDraftReading.parse(blind,messages);independent.getAsJsonObject("u0").addProperty("reading","mutated");
        check(!blind.getAsJsonObject("u0").get("reading").getAsString().equals("mutated"),"draft interpretation returned as a defensive copy");
        for(String key:List.of("reading","draftReference","draftActionStatus")) {
            var wrong=blind.deepCopy();wrong.getAsJsonObject("u0").addProperty(key,false);rejects(()->RoomDraftReading.parse(wrong,messages));
        }
        var extraDraft=blind.deepCopy();extraDraft.getAsJsonObject("u0").addProperty("verdict","PASS");rejects(()->RoomDraftReading.parse(extraDraft,messages));
        System.out.println("RoomClaimWitnessAuditTest: " + checks + " checks passed");
    }
}
