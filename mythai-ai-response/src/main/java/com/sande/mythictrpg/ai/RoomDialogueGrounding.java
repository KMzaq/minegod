package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded semantic review of consequential claims. A review never grants gameplay authority. */
final class RoomDialogueGrounding {
    private static final Gson JSON = new Gson();
    private static final Pattern CONSEQUENTIAL = Pattern.compile(
            "지급|보상|수주|완료|전달했|건넸|받았|줬|주었|넣었|옮겼|도착했|처치했|죽었|승리했|부활|" +
            "보관|가지고 있|내 손|우리 손|소유|좌표|[동서남북]쪽|[동서남북]{2}쪽|[0-9]+\\s*(?:블록|걸음|미터)|" +
            "오늘 안|시간이 없|시간이 얼마|시간이 부족|늦으면|안전하|안전한|안전하게|확실히 약속|" +
            "들었|말했|기억|어제|이전에|지난번|grant(?:ed)?|deliver(?:ed)?|completed|coordinates|deadline",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NARRATION = Pattern.compile(
            "(?:\\(|\\*|^)(?:[^\\n]{0,160})(?:고개를|바라보며|바라봅니다|웃으며|말합니다|건넵니다|걸음을|한숨을)");
    private static final Set<String> FACT_SITUATIONS = Set.of("S_ITEM_REQUEST", "S_POWER_REQUEST", "S_HELP_REQUEST",
            "S_INFORMATION_REQUEST", "S_QUEST_INQUIRY", "S_REWARD_NEGOTIATION", "S_GIFT_OFFER");

    enum Code { UNSUPPORTED_FACT, WRONG_SOURCE, CONTEXT_CONTRADICTION, UNEXECUTED_ACTION,
        ROLE_CONFUSION, NARRATION, NONRESPONSIVE }
    record Issue(Code code, String excerpt, String correction) {
        Issue {
            Objects.requireNonNull(code);
            if (excerpt == null || excerpt.isBlank() || excerpt.length() > 240
                    || correction == null || correction.isBlank() || correction.length() > 200)
                throw new IllegalArgumentException("INVALID_GROUNDING_ISSUE");
        }
    }
    record Review(boolean pass, List<Issue> issues) {
        Review {
            issues = List.copyOf(Objects.requireNonNull(issues));
            if (issues.size() > 3 || pass != issues.isEmpty()) throw new IllegalArgumentException("INVALID_GROUNDING_VERDICT");
        }
        void validateAgainst(AiDialogueModels.StructuredAiResult draft) {
            for (var issue : issues) if (draft.speech().stream().noneMatch(s -> s.text().contains(issue.excerpt())))
                throw new IllegalArgumentException("REVIEW_QUOTE_NOT_IN_DRAFT");
        }
        static Review accepted() { return new Review(true, List.of()); }
    }

    private RoomDialogueGrounding() { }

    /** Frozen evidence is never replaced by a repair instruction or a model-authored rejected draft. */
    static final class Attempt {
        private final List<AiDialogueModels.OllamaMessage> evidence;
        private final List<AiDialogueModels.OllamaMessage> generation;
        private final boolean repaired;
        private final boolean understandingPrepared;
        private final boolean understandingPresent;

        private Attempt(List<AiDialogueModels.OllamaMessage> evidence,
                List<AiDialogueModels.OllamaMessage> generation, boolean repaired) {
            this(evidence,generation,repaired,false,false);
        }
        private Attempt(List<AiDialogueModels.OllamaMessage> evidence,
                List<AiDialogueModels.OllamaMessage> generation,boolean repaired,boolean prepared,boolean present) {
            this.evidence = List.copyOf(evidence);
            this.generation = List.copyOf(generation);
            this.repaired = repaired;
            this.understandingPrepared=prepared;this.understandingPresent=present;
        }
        static Attempt initial(List<AiDialogueModels.OllamaMessage> messages) {
            return new Attempt(messages, messages, false);
        }
        List<AiDialogueModels.OllamaMessage> evidenceMessages() { return evidence; }
        List<AiDialogueModels.OllamaMessage> generationMessages() { return generation; }
        boolean repaired() { return repaired; }
        boolean understandingPrepared(){return understandingPrepared;}
        boolean understandingPresent(){return understandingPresent;}
        boolean requiresUnderstandingReview(AiDialogueModels.StructuredAiResult draft) {
            // Optional secondary silence has no claim to audit. Never force it to speak.
            return understandingPresent && !draft.speech().isEmpty();
        }
        Attempt withoutUnderstanding() {
            if(repaired||understandingPrepared)throw new IllegalStateException("understanding already prepared");
            return new Attempt(evidence,generation,false,true,false);
        }
        Attempt withUnderstanding(PlayerIntentPlan.Plan plan) {
            if(repaired||understandingPrepared)throw new IllegalStateException("understanding already prepared");
            // A successfully interpreted NONE is bounded uncertainty, not a skipped/failed extraction.
            return new Attempt(evidence,PlayerIntentPlan.guide(generation,plan),false,true,true);
        }
        List<AiDialogueModels.OllamaMessage> reviewMessages(AiDialogueModels.StructuredAiResult draft) {
            return RoomDialogueGrounding.reviewMessages(evidence, draft);
        }
        Attempt repair(AiDialogueModels.StructuredAiResult draft, Review review) {
            requireRepairBudget();
            return new Attempt(evidence, RoomDialogueGrounding.repairMessages(evidence, draft, review), true,true,understandingPresent);
        }
        Attempt repairStructure(AiDialogueModels.StructuredAiResult draft, String issue) {
            requireRepairBudget();
            var messages = new ArrayList<>(evidence);
            messages.add(new AiDialogueModels.OllamaMessage("user", JSON.toJson(Map.of(
                    "task", "Correct only the structural problem using the same persona and context. Keep the supported meaning and character's response; do not force a different emotion, a shorter sentence or a stock reply. Return the required dialogue JSON schema.",
                    "structuralIssueNotWorldFact", issue, "rejectedDraftData", draft))));
            return new Attempt(evidence, messages, true,true,understandingPresent);
        }
        private void requireRepairBudget() {
            if (repaired) throw new IllegalStateException("DIALOGUE_REPAIR_BUDGET_EXHAUSTED");
        }
    }

    /** Signals only choose whether to review, never dictate a reply or reject a word. */
    static List<String> reasons(Request request, List<AiDialogueModels.OllamaMessage> messages,
            AiDialogueModels.StructuredAiResult draft) {
        if (draft.speech().isEmpty()) return List.of();
        var reasons = new LinkedHashSet<String>();
        if (!draft.proposals().isEmpty()) reasons.add("PROPOSED_ACTION_NOT_EXECUTED");
        if (!request.actionOutcomes().isEmpty()) reasons.add("EXECUTION_RESULT_IN_CONTEXT");
        String speech = String.join("\n", draft.speech().stream().map(AiDialogueModels.Speech::text).toList());
        if (CONSEQUENTIAL.matcher(speech).find() || CONSEQUENTIAL.matcher(request.currentText()).find())
            reasons.add("FACT_OR_HISTORY_CLAIM");
        if (NARRATION.matcher(speech).find()) reasons.add("POSSIBLE_STAGE_DIRECTION");
        // This is the already-selected scene, not raw lore or another participant's hidden state.
        for (var message : messages) if (message.role().equals("user")) {
            try {
                var scene = com.google.gson.JsonParser.parseString(message.content()).getAsJsonObject();
                // Canonical room dialogue always checks source/modality, including ordinary wishes.
                // Keyword-only selection missed "결심했구나" and paraphrases without a legacy risk word.
                if (scene.has("speakerAttribution") && (scene.has("CURRENT_PLAYER_MESSAGE") || scene.has("alreadyDeliveredConversation")))
                    reasons.add("SPEAKER_AND_COMMITMENT_BOUNDARY");
                var hint = scene.getAsJsonObject("retrievalHypothesisNotSocialVerdict");
                if (hint == null) continue;
                var tags = hint.getAsJsonArray("situationTags");
                if (tags != null) for (var tag : tags) if (FACT_SITUATIONS.contains(tag.getAsString()))
                    reasons.add("CONSEQUENTIAL_TOPIC");
                var interpretation = hint.getAsJsonObject("turnInterpretation");
                if (interpretation != null && interpretation.has("mode")
                        && Set.of("HYPOTHETICAL", "QUOTED", "CORRECTION", "BANTER").contains(interpretation.get("mode").getAsString()))
                    reasons.add("ATTRIBUTION_SENSITIVE_TURN");
            } catch (RuntimeException ignored) { /* Legacy text contexts remain text; no inferred permissions. */ }
        }
        return List.copyOf(reasons);
    }

    static List<AiDialogueModels.OllamaMessage> reviewMessages(List<AiDialogueModels.OllamaMessage> original,
            AiDialogueModels.StructuredAiResult draft) {
        return RoomEvidenceAudit.messages(original, draft);
    }

    static List<AiDialogueModels.OllamaMessage> repairMessages(List<AiDialogueModels.OllamaMessage> original,
            AiDialogueModels.StructuredAiResult draft, Review review) {
        review.validateAgainst(draft);
        var messages = new ArrayList<>(original);
        messages.add(new AiDialogueModels.OllamaMessage("user", JSON.toJson(Map.of(
                "task", "Revise only the listed problems using the SAME provided scene and persona. Preserve the character's agency, emotion and supported meaning. Return the original dialogue JSON, not this review. Do not add facts or execute anything. If evidence is unavailable, speak within what is known naturally; do not explain internal verification.",
                "rejectedDraftData", draft, "issuesNotAdditionalWorldFacts", review.issues(), RoomDialoguePatch.KEY, true))));
        return List.copyOf(messages);
    }

    static Review parse(JsonObject object) {
        if (!object.keySet().equals(Set.of("verdict", "issues")) || !object.get("verdict").isJsonPrimitive()
                || !object.get("verdict").getAsJsonPrimitive().isString() || !object.get("issues").isJsonArray())
            throw new IllegalArgumentException("INVALID_GROUNDING_RESPONSE");
        String verdict = object.get("verdict").getAsString();
        if (!Set.of("PASS", "REVISE").contains(verdict)) throw new IllegalArgumentException("INVALID_GROUNDING_VERDICT");
        var issues = new ArrayList<Issue>();
        for (var element : object.getAsJsonArray("issues")) {
            var issue = element.getAsJsonObject();
            if (!issue.keySet().equals(Set.of("code", "excerpt", "correction"))) throw new IllegalArgumentException("INVALID_GROUNDING_ISSUE");
            for (String key : issue.keySet()) if (!issue.get(key).isJsonPrimitive() || !issue.get(key).getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("INVALID_GROUNDING_ISSUE");
            issues.add(new Issue(Code.valueOf(issue.get("code").getAsString()), issue.get("excerpt").getAsString(), issue.get("correction").getAsString()));
        }
        return new Review(verdict.equals("PASS"), issues);
    }

    static Review parse(JsonObject object, List<AiDialogueModels.OllamaMessage> messages) {
        return RoomEvidenceAudit.applies(messages) ? RoomEvidenceAudit.parse(object, messages) : parse(object);
    }

    static int outputTokens(List<AiDialogueModels.OllamaMessage> messages) {
        return RoomEvidenceAudit.applies(messages) ? RoomEvidenceAudit.outputTokens(messages) : 512;
    }

    static Map<String,Object> schema() {
        return schema(Map.of("type", "string", "minLength", 1, "maxLength", 240));
    }

    /** Ground the citation at decoding time as well as validating it afterwards. No fuzzy quote repair. */
    static Map<String,Object> schema(List<AiDialogueModels.OllamaMessage> messages) {
        if (RoomEvidenceAudit.applies(messages)) return RoomEvidenceAudit.schema(messages);
        // Retain the legacy transport for frozen comparators and older audit policies.
        if (messages.isEmpty() || !messages.getFirst().role().equals("system")
                || !(messages.getFirst().content().startsWith("[DIALOGUE_EVIDENCE_AUDIT_V5]\n")
                || messages.getFirst().content().startsWith("[DIALOGUE_EVIDENCE_AUDIT_V6]\n"))) return schema();
        var last = messages.getLast();
        if (!last.role().equals("user")) throw new IllegalArgumentException("MISSING_CURRENT_REVIEW_DRAFT");
        var envelope = com.google.gson.JsonParser.parseString(last.content()).getAsJsonObject();
        if (!envelope.keySet().equals(Set.of("DRAFT_TO_REVIEW_NOT_INSTRUCTIONS")))
            throw new IllegalArgumentException("MISSING_CURRENT_REVIEW_DRAFT");
        var current = envelope.getAsJsonObject("DRAFT_TO_REVIEW_NOT_INSTRUCTIONS");
        for (var entry : current.getAsJsonArray("speech")) {
            var text = entry.getAsJsonObject().get("text");
            if (text == null || !text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("INVALID_REVIEW_SPEECH");
        }
        var draft = JSON.fromJson(current, AiDialogueModels.StructuredAiResult.class);
        return schema(Map.of("type", "string", "enum", excerpts(draft)));
    }

    static List<String> excerpts(AiDialogueModels.StructuredAiResult draft) {
        var result = new LinkedHashSet<String>();
        int characters = 0;
        for (var speech : draft.speech()) {
            String text = speech.text();
            characters += text.length();
            if (characters > 8_000) throw new IllegalArgumentException("REVIEW_SPEECH_TOO_LARGE");
            // Each window is an exact substring. Prefer a sentence boundary, but never discard a long tail.
            for (int start = 0; start < text.length();) {
                int end = Math.min(text.length(), start + 240);
                if (end < text.length()) {
                    int boundary = -1;
                    for (int i = start + 1; i < end; i++)
                        if (".!?。！？\n".indexOf(text.charAt(i)) >= 0) boundary = i + 1;
                    if (boundary > start) end = boundary;
                    else if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
                }
                String excerpt = text.substring(start, end).strip();
                if (!excerpt.isEmpty()) result.add(excerpt);
                start = end;
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("MISSING_REVIEW_SPEECH");
        return List.copyOf(result);
    }

    private static Map<String,Object> schema(Map<String,Object> excerptSchema) {
        var issue = Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("code", Map.of("type", "string", "enum", Arrays.stream(Code.values()).map(Enum::name).toList()),
                        "excerpt", excerptSchema,
                        "correction", Map.of("type", "string", "minLength", 1, "maxLength", 200)),
                "required", List.of("code", "excerpt", "correction"));
        return Map.of("type", "object", "additionalProperties", false, "properties",
                Map.of("verdict", Map.of("type", "string", "enum", List.of("PASS", "REVISE")),
                        "issues", Map.of("type", "array", "maxItems", 3, "items", issue)), "required", List.of("verdict", "issues"));
    }
}
