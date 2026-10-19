package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Internal protocol and evidence projection only; does not assert model judgement quality. */
public final class RoomEvidenceAuditTest {
    private static final Gson JSON = new Gson();
    private static int checks;

    public static void main(String[] args) {
        System.out.println("RoomEvidenceAuditTest: " + run() + " checks passed; no model calls");
    }

    static int run() {
        checks = 0;
        primaryAndSecondaryProjectionPreservesEvidence();
        repairAndRoomScopesStayIsolated();
        allUnitsAreRequiredAndMappedToExactSpeech();
        invalidChecksNeverBecomePass();
        currentDraftUnitsCannotBeTamperedWith();
        segmentationPreservesLongRepeatedAndUnicodeSpeech();
        return checks;
    }

    private static void primaryAndSecondaryProjectionPreservesEvidence() {
        for (boolean secondary : List.of(false, true)) {
            var source = scene(secondary ? "SECONDARY" : "PRIMARY", secondary);
            String before = source.content();
            var projected = RoomEvidenceAudit.project(source);
            check(projectionPreservesEvidence(List.of(source), List.of(projected)),
                    "projection retains every original field except the five explicit generation aids");
            var data = object(projected.content());
            var profile = data.getAsJsonObject(secondary ? "persona" : "ownPersona");
            check(!data.has("retrievalHypothesisNotSocialVerdict") && !data.has("ongoingActivityHintNotFact")
                    && !profile.has("dialogueGuidelines") && !profile.has("situationGuidelines") && !profile.has("repetitionGuidelines"),
                    "all known classifier/activity/style instructions are omitted from canonical audit scenes");
            check(profile.has("identity") && profile.has("restrictions") && profile.has("speechStyles")
                    && profile.has("futureProfileField") && data.has("futurePrivateSource") && data.get("unknownLocation").isJsonNull(),
                    "static identity, restrictions, unknown additions and explicit unknowns survive");
            check(data.getAsJsonObject("questConstraint").equals(object(before).getAsJsonObject("questConstraint"))
                    && data.getAsJsonArray("gameConfirmedActionOutcomes").equals(object(before).getAsJsonArray("gameConfirmedActionOutcomes")),
                    "constraint and typed pending-result evidence are never rewritten into completion");
            profile.addProperty("identity", "MUTATED_PARSED_COPY");
            check(source.content().equals(before) && !RoomEvidenceAudit.project(source).content().contains("MUTATED_PARSED_COPY"),
                    "projecting and mutating a parsed result cannot alter the frozen original");
            check(RoomEvidenceAudit.project(projected).equals(projected), "projection is stable when applied again");
        }
        for (var message : List.of(new AiDialogueModels.OllamaMessage("user", "legacy plain text"),
                new AiDialogueModels.OllamaMessage("user", "{\"retrievalHypothesisNotSocialVerdict\":\"keep\",\"ownPersona\":{\"dialogueGuidelines\":[\"keep\"]}}"),
                new AiDialogueModels.OllamaMessage("assistant", scene("HISTORY", false).content()),
                new AiDialogueModels.OllamaMessage("user", "{malformed legacy text")))
            check(RoomEvidenceAudit.project(message).equals(message), "noncanonical and history messages remain verbatim");
    }

    private static void repairAndRoomScopesStayIsolated() {
        var originalA = List.of(new AiDialogueModels.OllamaMessage("system", "GENERATOR_ONLY"), scene("ROOM_A", false));
        var originalB = List.of(scene("ROOM_B", true));
        var initialA = RoomDialogueGrounding.Attempt.initial(originalA);
        var currentA = draft("A_CURRENT: 아직 기다리는 중이야.");
        var currentB = draft("B_CURRENT: 이제 내가 새로 제안할게.");
        var oldA = draft("A_OLD: 지급했어.");
        var feedback = new RoomDialogueGrounding.Review(false, List.of(new RoomDialogueGrounding.Issue(
                RoomDialogueGrounding.Code.UNEXECUTED_ACTION, "A_OLD", "A_REPAIR_CORRECTION")));
        var repairedA = initialA.repair(oldA, feedback);
        var repairedB = RoomDialogueGrounding.Attempt.initial(originalB).repairStructure(draft("B_OLD"), "B_STRUCTURE_CORRECTION");
        var auditA = repairedA.reviewMessages(currentA);
        var auditB = repairedB.reviewMessages(currentB);
        check(auditA.equals(initialA.reviewMessages(currentA)), "the same current draft has identical audit evidence before and after repair");
        check(repairedA.evidenceMessages().equals(originalA) && repairedB.evidenceMessages().equals(originalB),
                "both repair kinds keep unprojected source snapshots for generation");
        check(projectionPreservesEvidence(originalA, auditA.subList(1, auditA.size() - 1))
                && projectionPreservesEvidence(originalB, auditB.subList(1, auditB.size() - 1)),
                "both room audits preserve only their own canonical evidence");
        String a = JSON.toJson(auditA), b = JSON.toJson(auditB);
        check(!a.contains("ROOM_B") && !b.contains("ROOM_A"), "quest, reward, validator feedback, private memory and audience remain room-local");
        check(!a.contains("A_OLD") && !a.contains("A_REPAIR_CORRECTION") && !b.contains("B_OLD")
                && !b.contains("B_STRUCTURE_CORRECTION") && !a.contains("GENERATOR_ONLY"),
                "old drafts, repair feedback and generator system policy never become review evidence");
        check(new ArrayList<>(RoomEvidenceAudit.units(auditA).values()).equals(new ArrayList<>(RoomEvidenceAudit.units(currentA).values())),
                "current units come from current speech, never from source or repair text");
    }

    private static void allUnitsAreRequiredAndMappedToExactSpeech() {
        var current = draft("첫째. 둘째. 셋째. 넷째.");
        var messages = RoomDialogueGrounding.reviewMessages(List.of(), current);
        var units = RoomEvidenceAudit.units(messages);
        check(new ArrayList<>(units.keySet()).equals(List.of("u0", "u1", "u2", "u3")), "each sentence gets an ordered stable unit ID");
        var schema = JSON.toJsonTree(RoomDialogueGrounding.schema(messages)).getAsJsonObject();
        check(schema.getAsJsonObject("properties").keySet().equals(units.keySet())
                && schema.get("required").equals(JSON.toJsonTree(new ArrayList<>(units.keySet())))
                && !schema.get("additionalProperties").getAsBoolean(), "the schema requires every unit and forbids an overall guessed verdict");
        var unitSchema = schema.getAsJsonObject("properties").getAsJsonObject("u0");
        check(unitSchema.getAsJsonObject("properties").keySet().equals(Set.of("kind", "finding", "correction"))
                && !unitSchema.get("additionalProperties").getAsBoolean(), "unit response fields are closed and contain no free quotation");
        var accepted = RoomDialogueGrounding.parse(response(units, "NONE", ""), messages);
        check(accepted.pass() && accepted.issues().isEmpty(), "only complete all-NONE coverage becomes the existing PASS Review");
        var response = response(units, "WRONG_SOURCE", "발언자를 확인할 것");
        var revised = RoomDialogueGrounding.parse(response, messages);
        check(!revised.pass() && revised.issues().size() == 3, "four valid findings retain the bounded three-issue repair contract");
        check(revised.issues().stream().map(RoomDialogueGrounding.Issue::excerpt).toList()
                .equals(List.of("첫째.", "둘째.", "셋째.")), "returned excerpts are exact ordered source units, not model text");
        revised.validateAgainst(current); checks++;
        check(JSON.toJsonTree(revised).getAsJsonObject().keySet().equals(Set.of("pass", "issues")),
                "internal claim classifications are not added to public Review or game facts");
        var reverse = new JsonObject();
        for (String id : List.of("u3", "u2", "u1", "u0")) reverse.add(id, response.get(id));
        check(RoomDialogueGrounding.parse(reverse, messages).equals(revised), "response object ordering cannot reorder repair feedback");
        check(RoomDialogueGrounding.outputTokens(messages) > 512 && RoomDialogueGrounding.outputTokens(messages) <= 8192,
                "multi-unit response receives bounded output capacity");
        var legacy = List.of(new AiDialogueModels.OllamaMessage("system", "[DIALOGUE_EVIDENCE_AUDIT_V6]\nlegacy"));
        check(RoomDialogueGrounding.parse(object("{\"verdict\":\"PASS\",\"issues\":[]}"), legacy).pass()
                && RoomDialogueGrounding.outputTokens(legacy) == 512, "legacy review parsing and output budget remain unchanged");
    }

    private static void invalidChecksNeverBecomePass() {
        var messages = RoomDialogueGrounding.reviewMessages(List.of(), draft("첫째. 둘째. 셋째. 넷째."));
        var units = RoomEvidenceAudit.units(messages);
        var good = response(units, "NONE", "");
        var missing = good.deepCopy(); missing.remove("u3");
        rejects(() -> RoomDialogueGrounding.parse(missing, messages), "missing last unit is not PASS");
        var extra = good.deepCopy(); extra.add("u99", good.get("u0"));
        rejects(() -> RoomDialogueGrounding.parse(extra, messages), "unknown unit cannot enter coverage");
        rejects(() -> RoomDialogueGrounding.parse(object("{\"verdict\":\"PASS\",\"issues\":[]}"), messages),
                "legacy verdict cannot bypass the current per-unit protocol");
        for (String invalid : List.of("null", "7", "{}",
                "{\"kind\":\"UNKNOWN\",\"finding\":\"NONE\",\"correction\":\"\"}",
                "{\"kind\":\"MIXED\",\"finding\":\"OTHER\",\"correction\":\"확인\"}",
                "{\"kind\":\"MIXED\",\"finding\":\"NONE\",\"correction\":\" \"}",
                "{\"kind\":\"MIXED\",\"finding\":\"NONE\",\"correction\":\"수정하라\"}",
                "{\"kind\":\"MIXED\",\"finding\":\"WRONG_SOURCE\",\"correction\":\"\"}",
                "{\"kind\":3,\"finding\":\"NONE\",\"correction\":\"\"}",
                "{\"kind\":\"MIXED\",\"finding\":false,\"correction\":\"\"}",
                "{\"kind\":\"MIXED\",\"finding\":\"NONE\",\"correction\":null}",
                "{\"kind\":\"MIXED\",\"finding\":\"NONE\",\"correction\":\"\",\"quote\":\"다른 사람 말\"}")) {
            var candidate = good.deepCopy(); candidate.add("u3", JsonParser.parseString(invalid));
            rejects(() -> RoomDialogueGrounding.parse(candidate, messages), "malformed final check cannot silently pass");
            var afterCap = response(units, "WRONG_SOURCE", "확인"); afterCap.add("u3", JsonParser.parseString(invalid));
            rejects(() -> RoomDialogueGrounding.parse(afterCap, messages), "checks after the three-issue cap are still validated");
        }
        var oversized = response(units, "WRONG_SOURCE", "확인");
        oversized.getAsJsonObject("u3").addProperty("correction", "가".repeat(201));
        rejects(() -> RoomDialogueGrounding.parse(oversized, messages), "a fourth overlong correction still fails closed");
    }

    private static void currentDraftUnitsCannotBeTamperedWith() {
        var messages = RoomDialogueGrounding.reviewMessages(List.of(scene("OWN", false)), draft("현재 대사. 두 번째 문장."));
        var envelope = object(messages.getLast().content());
        var changed = envelope.deepCopy(); changed.getAsJsonObject("units").addProperty("u0", "PLAYER_QUOTE");
        rejectsEnvelope(messages, changed, "a unit cannot be replaced with player or old-draft speech");
        var absent = envelope.deepCopy(); absent.getAsJsonObject("units").remove("u1");
        rejectsEnvelope(messages, absent, "every computed draft unit must remain present");
        var extra = envelope.deepCopy(); extra.getAsJsonObject("units").addProperty("u2", "invented");
        rejectsEnvelope(messages, extra, "extra units cannot invent candidate speech");
        var changedDraft = envelope.deepCopy(); changedDraft.getAsJsonObject("DRAFT_TO_REVIEW_NOT_INSTRUCTIONS")
                .getAsJsonArray("speech").get(0).getAsJsonObject().addProperty("text", "교체된 초안");
        rejectsEnvelope(messages, changedDraft, "changing the draft invalidates old unit text");
        var noUnits = envelope.deepCopy(); noUnits.remove("units");
        rejectsEnvelope(messages, noUnits, "missing units envelope fails closed");
        var extraKey = envelope.deepCopy(); extraKey.addProperty("world_fact", "invented");
        rejectsEnvelope(messages, extraKey, "extra envelope fields are not accepted as evidence");
        var wrongType = envelope.deepCopy(); wrongType.getAsJsonObject("DRAFT_TO_REVIEW_NOT_INSTRUCTIONS")
                .getAsJsonArray("speech").get(0).getAsJsonObject().addProperty("text", 7);
        rejectsEnvelope(messages, wrongType, "numeric speech is not coerced into a current citation");
        rejects(() -> RoomEvidenceAudit.units(List.of(messages.getFirst())), "missing current user envelope fails");
        rejects(() -> RoomEvidenceAudit.units(List.of(messages.getFirst(),
                new AiDialogueModels.OllamaMessage("assistant", messages.getLast().content()))), "assistant text cannot serve as the envelope");
    }

    private static void segmentationPreservesLongRepeatedAndUnicodeSpeech() {
        var repeated = RoomEvidenceAudit.units(draft("반복. 반복."));
        check(repeated.size() == 2 && new ArrayList<>(repeated.values()).equals(List.of("반복.", "반복.")),
                "identical clauses remain separate checks instead of being deduplicated");
        String unicode = "가".repeat(239) + "\uD83D\uDE03" + "나".repeat(250) + "꼬리";
        var normal = RoomEvidenceAudit.units(draft(unicode));
        check(String.join("", normal.values()).equals(unicode), "ordinary windows retain all speech including a crossing emoji and tail");
        String crowded = "가. ".repeat(66) + "A".repeat(41) + "\uD83D\uDE03" + "끝".repeat(250);
        var many = RoomEvidenceAudit.units(draft(crowded));
        check(many.size() <= 64 && many.size() > 1, "more than 64 sentence spans are rewindowed within the unit cap");
        check(String.join("", many.values()).replaceAll("\\s+", "").equals(crowded.replaceAll("\\s+", "")),
                "rewindowing drops no non-whitespace punctuation, words, emoji or tail");
        check(java.util.stream.Stream.concat(normal.values().stream(), many.values().stream()).allMatch(text ->
                !text.isBlank() && text.length() <= 240 && !Character.isLowSurrogate(text.charAt(0))
                        && !Character.isHighSurrogate(text.charAt(text.length() - 1))), "all spans remain valid bounded exact unicode substrings");
        var maximum = draft("가".repeat(4000), "나".repeat(4000));
        var maximumUnits = RoomEvidenceAudit.units(maximum);
        check(String.join("", maximumUnits.values()).equals("가".repeat(4000) + "나".repeat(4000)),
                "8000 characters across two speech entries are retained without merging duplicate windows");
        check(maximumUnits.values().stream().allMatch(text -> maximum.speech().stream().anyMatch(s -> s.text().contains(text))),
                "no unit crosses a speech-entry boundary");
        var secondaryMaximum = draft("가".repeat(4000), "나".repeat(4000), "다".repeat(4000), "라".repeat(4000));
        var secondaryUnits = RoomEvidenceAudit.units(secondaryMaximum);
        check(String.join("", secondaryUnits.values()).equals("가".repeat(4000) + "나".repeat(4000)
                + "다".repeat(4000) + "라".repeat(4000)), "all 16000 characters allowed by Secondary survive review projection");
        check(secondaryUnits.values().stream().allMatch(text -> secondaryMaximum.speech().stream().anyMatch(s -> s.text().contains(text))),
                "secondary maximum never crosses speech boundaries");
        rejects(() -> RoomEvidenceAudit.units(draft("가".repeat(4000), "나".repeat(4000), "다".repeat(4000), "라".repeat(4001))),
                "16001 total characters fail without truncation");
        rejects(() -> RoomEvidenceAudit.units(draft("   ")), "blank speech has no valid audit unit");
        rejects(() -> maximumUnits.put("u999", "새 사실"), "computed unit map is immutable");
    }

    /** Independent comparison: original fields are recursively retained except the documented removals. */
    static boolean projectionPreservesEvidence(List<AiDialogueModels.OllamaMessage> original,
            List<AiDialogueModels.OllamaMessage> projected) {
        var source = original.stream().filter(m -> !m.role().equals("system")).toList();
        if (source.size() != projected.size()) return false;
        for (int index = 0; index < source.size(); index++) {
            var before = source.get(index); var after = projected.get(index);
            if (!before.role().equals(after.role())) return false;
            JsonObject data;
            try { data = object(before.content()); }
            catch (RuntimeException text) { if (!before.equals(after)) return false; else continue; }
            boolean primary = data.has("authorizedSpeakerId") && data.has("gameContextWithSourceBoundaries");
            boolean secondary = data.has("speaker") && data.has("ownAuthoritativeContext");
            if (!before.role().equals("user") || !primary && !secondary) {
                if (!before.equals(after)) return false;
                continue;
            }
            data.remove("retrievalHypothesisNotSocialVerdict"); data.remove("ongoingActivityHintNotFact");
            var profile = data.get(primary ? "ownPersona" : "persona");
            if (profile != null && profile.isJsonObject())
                for (String key : List.of("dialogueGuidelines", "situationGuidelines", "repetitionGuidelines")) profile.getAsJsonObject().remove(key);
            if (!data.equals(object(after.content()))) return false;
        }
        return true;
    }

    private static AiDialogueModels.OllamaMessage scene(String scope, boolean secondary) {
        var data = new JsonObject();
        data.addProperty(secondary ? "speaker" : "authorizedSpeakerId", scope + "_GOD");
        data.addProperty(secondary ? "ownAuthoritativeContext" : "gameContextWithSourceBoundaries",
                scope + "_CURRENT_TASK_UNFINISHED\n[QUEST_CANDIDATES] not accepted\n[NPC_SESSION_EMOTION] not game truth");
        data.add(secondary ? "persona" : "ownPersona", JSON.toJsonTree(Map.of("identity", scope + "_IDENTITY",
                "restrictions", List.of(scope + "_RESTRICTION"), "speechStyles", List.of("P_GRUFF"),
                "dialogueGuidelines", List.of("GENERATION_GUIDE"), "situationGuidelines", Map.of("S_CHAT", List.of("GENERATION_GUIDE")),
                "repetitionGuidelines", Map.of("REPEAT", List.of("GENERATION_GUIDE")), "futureProfileField", Map.of("unknown", scope))));
        data.add("room", JSON.toJsonTree(Map.of("publicRoom", false, "readOnly", true, "turnId", scope + "_TURN")));
        data.add("audiencePlayerIds", JSON.toJsonTree(List.of(scope + "_AUDIENCE")));
        data.add("questConstraint", JSON.toJsonTree(Map.of("questId", scope + "_QUEST", "status", "UNFINISHED")));
        data.add("rewardConstraint", JSON.toJsonTree(Map.of("reward", scope + "_REWARD", "permitted", false)));
        data.add("proposalValidationFeedback", JSON.toJsonTree(Map.of("reason", scope + "_FEEDBACK", "accepted", false)));
        data.add("gameConfirmedActionOutcomes", JSON.toJsonTree(List.of(Map.of("proposalId", scope + "_RESULT", "status", "PENDING_CONFIRMATION"))));
        data.addProperty("actuallyHeardMemory", scope + "_MEMORY source=NPC_UTTERANCE speaker_id=OTHER_GOD recorded_at=old excerpt=true");
        data.addProperty("ownPermittedMemory", scope + "_LEGACY_MEMORY NOT_VERIFIED");
        data.add(secondary ? "alreadyDeliveredConversation" : "RECENT_CONVERSATION", JSON.toJsonTree(List.of(Map.of(
                "role", "NPC", "speakerId", scope + "_OTHER_GOD", "sourceRoomId", scope + "_SOURCE_ROOM", "text", "나중에 들려줄게."))));
        if (!secondary) data.addProperty("CURRENT_PLAYER_MESSAGE", scope + "_CURRENT_PLAYER_TEXT");
        data.addProperty("retrievalHypothesisNotSocialVerdict", "CLASSIFIER_WRONG_OWNER");
        data.addProperty("ongoingActivityHintNotFact", "INFERRED_ACTIVITY");
        data.add("optionalContextAvailability", JSON.toJsonTree(Map.of("heardMemory", "OMITTED_CONTEXT_BUDGET")));
        data.add("futurePrivateSource", JSON.toJsonTree(Map.of("audience", List.of(scope), "nested", Map.of("unrecognized", scope))));
        data.add("unknownLocation", com.google.gson.JsonNull.INSTANCE);
        return new AiDialogueModels.OllamaMessage("user", data.toString());
    }

    private static JsonObject response(Map<String, String> units, String finding, String correction) {
        var result = new JsonObject();
        for (String id : units.keySet()) result.add(id, JSON.toJsonTree(Map.of("kind", "MIXED", "finding", finding, "correction", correction)));
        return result;
    }
    private static void rejectsEnvelope(List<AiDialogueModels.OllamaMessage> messages, JsonObject envelope, String reason) {
        var changed = new ArrayList<>(messages);
        changed.set(changed.size() - 1, new AiDialogueModels.OllamaMessage("user", JSON.toJson(envelope)));
        rejects(() -> RoomDialogueGrounding.schema(changed), reason);
    }
    private static AiDialogueModels.StructuredAiResult draft(String... lines) {
        return new AiDialogueModels.StructuredAiResult(java.util.Arrays.stream(lines)
                .map(text -> new AiDialogueModels.Speech("test:current", text, List.of())).toList(), "", List.of());
    }
    private static JsonObject object(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static void check(boolean valid, String reason) { if (!valid) throw new AssertionError(reason); checks++; }
    private static void rejects(Runnable action, String reason) {
        try { action.run(); throw new AssertionError(reason); }
        catch (RuntimeException expected) { checks++; }
    }
}
