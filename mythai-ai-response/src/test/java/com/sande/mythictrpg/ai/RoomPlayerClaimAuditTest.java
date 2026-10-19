package com.sande.mythictrpg.ai;
import com.google.gson.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.HistoryLine;
import java.util.*;
public final class RoomPlayerClaimAuditTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void rejects(Runnable call){try{call.run();throw new AssertionError("invalid response accepted");}catch(RuntimeException expected){checks++;}}
    static JsonObject response(List<AiDialogueModels.OllamaMessage> general) {
        var out=new JsonObject();
        for(var unit:RoomEvidenceAudit.units(general).entrySet()) {
            var row=new JsonObject();row.addProperty("draftText",unit.getValue());row.addProperty("sourceComparison","원문과 대사의 주체/의사 비교");
            row.addProperty("claimForm","EXISTING_INTENTION");row.addProperty("certainty","ASSERTED");
            row.addProperty("sourceSubject","QUESTIONER");row.addProperty("claimedSubject","QUESTIONER");
            row.addProperty("subjectReference","NAMED_OR_THIRD_PERSON");row.addProperty("sourceModality","COMMITTED");
            row.addProperty("claimedModality","COMMITTED");row.addProperty("attribution","SUPPORTED");out.add(unit.getKey(),row);
        }return out;
    }
    static void set(JsonObject out,String key,String value){for(var entry:out.entrySet())entry.getValue().getAsJsonObject().addProperty(key,value);}
    public static void main(String[] args) {
        var draft=PlayerClaimBoundaryEvaluation.draft("첫째. 둘째. 셋째. 넷째.");
        for(boolean secondary:List.of(false,true)) {
            var request=PlayerUnderstandingPromptTest.request("CURRENT_SECRET",List.of(
                    new HistoryLine("PLAYER",PlayerUnderstandingPromptTest.PLAYER.toString(),"같은이름","OWN_WORDS"),
                    new HistoryLine("PLAYER","4d31eb8a-cfb8-4f0c-8761-597d0c4d27b6","같은이름","OTHER_WORDS")),secondary);
            var original=PlayerUnderstandingPromptTest.messages(request,List.of("PERMITTED_MEMORY"));
            var general=RoomDialogueGrounding.reviewMessages(original,draft);var messages=RoomPlayerClaimAudit.messages(general);
            check(messages.size()==4&&messages.getLast().equals(general.getLast()),"bounded frozen draft");
            check(!messages.getFirst().content().contains("OWN_WORDS"),"source data is not system policy");
            check(messages.get(2).content().contains("responseSchema"),"schema visible to model, not only constrained decoding");
            String schema=messages.get(2).content();
            check(schema.indexOf("claimedModality")<schema.indexOf("sourceComparison")&&schema.indexOf("sourceComparison")<schema.indexOf("sourceModality"),"judge draft before source to avoid copying source strength");
            check(messages.toString().contains("CURRENT_SECRET")!=secondary,"secondary never gains undelivered input");
            check(messages.toString().contains("PERMITTED_MEMORY"),"already disclosed memory preserved");
            var view=JsonParser.parseString(messages.get(1).content()).getAsJsonObject().getAsJsonArray("permittedSources").get(0).getAsJsonObject();
            check(!view.get("currentQuestionerSpeech").toString().contains("OTHER_WORDS")&&view.get("otherParticipantsSpeech").toString().contains("OTHER_WORDS"),"ID-separated source partitions");
            check(view.getAsJsonArray("currentQuestionerSpeech").size()==(secondary?1:2),"current appended only to primary");
            var good=response(general);check(RoomPlayerClaimAudit.parse(good,general).pass(),"supported assertion");
            for(String source:List.of("NONE","DESIRE","POSSIBILITY","UNDECIDED","UNACCEPTED","WITHDRAWN","COMMITTED"))
                for(String claimed:List.of("NONE","DESIRE","POSSIBILITY","UNDECIDED","UNACCEPTED","WITHDRAWN","COMMITTED","WILL_ACT_CONFIRMED","TENTATIVE_INTENT","NEW_SELF_INTENTION"))
                    for(String certainty:List.of("ASSERTED","OPEN_CONDITION","TENTATIVE_OR_CONDITIONAL","NO_ASSERTION")) {
                        var row=response(general);set(row,"sourceModality",source);set(row,"claimedModality",claimed);set(row,"certainty",certainty);
                        boolean bad=!certainty.equals("OPEN_CONDITION")&&!source.equals("COMMITTED")
                                &&(claimed.equals("COMMITTED")||claimed.equals("WILL_ACT_CONFIRMED")&&!certainty.equals("TENTATIVE_OR_CONDITIONAL"));
                        var verdict=RoomPlayerClaimAudit.parse(row,general);
                        check(verdict.pass()!=bad,"typed commitment/condition matrix");verdict.validateAgainst(draft);
                    }
            for(String source:List.of("NONE","QUESTIONER","RESPONDING_GOD","OTHER","MIXED"))
                for(String claimed:List.of("NONE","QUESTIONER","RESPONDING_GOD","OTHER","MIXED")) {
                    var row=response(general);set(row,"sourceSubject",source);set(row,"claimedSubject",claimed);
                    boolean bad=!source.equals("MIXED")&&!claimed.equals("MIXED")&&!claimed.equals("NONE")&&!source.equals(claimed);
                    check(RoomPlayerClaimAudit.parse(row,general).pass()!=bad,"mixed owners are not compressed into a cross-clause pair");
                }
            var bound=response(general);set(bound,"sourceSubject","OTHER");set(bound,"claimedSubject","OTHER");set(bound,"subjectReference","ADDRESSEE");
            check(RoomPlayerClaimAudit.parse(bound,general).pass()==secondary,"you is game addressee, not old source author");
            set(bound,"certainty","OPEN_CONDITION");check(RoomPlayerClaimAudit.parse(bound,general).pass(),"open ownership condition is not an asserted transfer");
            set(bound,"attribution","WRONG_SUBJECT");check(!RoomPlayerClaimAudit.parse(bound,general).pass(),"explicit misattribution cannot hide in conditional label");
            set(bound,"certainty","ASSERTED");set(bound,"attribution","SUPPORTED");
            set(bound,"subjectReference","SPEAKER");check(!RoomPlayerClaimAudit.parse(bound,general).pass(),"I is responding god");
            var tentative=response(general);set(tentative,"sourceSubject","OTHER");set(tentative,"sourceModality","DESIRE");
            set(tentative,"claimedModality","TENTATIVE_INTENT");set(tentative,"certainty","TENTATIVE_OR_CONDITIONAL");set(tentative,"attribution","WRONG_SUBJECT");
            check(RoomPlayerClaimAudit.parse(tentative,general).pass(),"explicitly tentative purpose hypothesis is not a transferred fact");
            set(tentative,"claimedModality","COMMITTED");
            check(!RoomPlayerClaimAudit.parse(tentative,general).pass(),"question/polite hedge cannot hide a committed premise");
            set(tentative,"claimedModality","TENTATIVE_INTENT");set(tentative,"certainty","ASSERTED");
            check(!RoomPlayerClaimAudit.parse(tentative,general).pass(),"tentative label alone cannot erase factual attribution");
            for(String status:List.of("NONE","UNDECIDED","UNACCEPTED","WITHDRAWN","TENTATIVE_INTENT")) {
                var negative=response(general);set(negative,"sourceSubject","OTHER");set(negative,"claimedSubject","QUESTIONER");
                set(negative,"claimedModality",status);
                check(RoomPlayerClaimAudit.parse(negative,general).pass(),"negative or unknown status is not an affirmative transferred plan");
                set(negative,"attribution","WRONG_SUBJECT");
                check(!RoomPlayerClaimAudit.parse(negative,general).pass(),"explicit negative-status misattribution still rejects");
            }
            var current=response(general);set(current,"claimForm","NEW_SELF_EXPRESSION");set(current,"sourceSubject","NONE");set(current,"claimedSubject","RESPONDING_GOD");set(current,"sourceModality","NONE");
            check(RoomPlayerClaimAudit.parse(current,general).pass(),"new NPC expression needs no old commitment");
            set(current,"claimedSubject","QUESTIONER");check(!RoomPlayerClaimAudit.parse(current,general).pass(),"new self exemption cannot cover player assertion");
            var action=response(general);set(action,"claimForm","NO_INTENTION_CLAIM");set(action,"sourceSubject","NONE");set(action,"claimedSubject","NONE");set(action,"subjectReference","SPEAKER");set(action,"sourceModality","NONE");
            check(RoomPlayerClaimAudit.parse(action,general).pass(),"non-personal completed action cannot manufacture a personal commitment");
            set(action,"claimedSubject","RESPONDING_GOD");check(!RoomPlayerClaimAudit.parse(action,general).pass(),"concrete personal commitment cannot hide under generic no-claim label");
            var wrong=response(general);set(wrong,"attribution","WRONG_SUBJECT");set(wrong,"sourceComparison","가".repeat(400));
            var verdict=RoomPlayerClaimAudit.parse(wrong,general);
            check(!verdict.pass()&&verdict.issues().size()==3,"explicit wrong attribution and feedback cap");
            check(verdict.issues().stream().allMatch(i->i.correction().length()<=200),"bounded repair guidance");verdict.validateAgainst(draft);
            var missing=good.deepCopy();missing.remove("u3");rejects(()->RoomPlayerClaimAudit.parse(missing,general));
            var extra=good.deepCopy();extra.add("u99",good.get("u0"));rejects(()->RoomPlayerClaimAudit.parse(extra,general));
            var falseDraft=good.deepCopy();falseDraft.getAsJsonObject("u0").addProperty("draftText","다른 대사");rejects(()->RoomPlayerClaimAudit.parse(falseDraft,general));
            for(String field:List.of("claimForm","certainty","sourceSubject","claimedSubject","subjectReference","sourceModality","claimedModality","attribution")) {
                var invalid=good.deepCopy();invalid.getAsJsonObject("u3").addProperty(field,"UNKNOWN");rejects(()->RoomPlayerClaimAudit.parse(invalid,general));
                var absent=good.deepCopy();absent.getAsJsonObject("u3").remove(field);rejects(()->RoomPlayerClaimAudit.parse(absent,general));
                var number=good.deepCopy();number.getAsJsonObject("u3").addProperty(field,1);rejects(()->RoomPlayerClaimAudit.parse(number,general));
            }
            for(String comparison:List.of("","가".repeat(401))){var invalid=wrong.deepCopy();invalid.getAsJsonObject("u3").addProperty("sourceComparison",comparison);rejects(()->RoomPlayerClaimAudit.parse(invalid,general));}
            var directive=good.deepCopy();directive.getAsJsonObject("u3").addProperty("correction","임의 지시");rejects(()->RoomPlayerClaimAudit.parse(directive,general));
            var repaired=RoomDialogueGrounding.Attempt.initial(original).repair(PlayerClaimBoundaryEvaluation.draft("REJECTED_TEXT"),new RoomDialogueGrounding.Review(false,List.of(new RoomDialogueGrounding.Issue(RoomDialogueGrounding.Code.WRONG_SOURCE,"REJECTED_TEXT","REPAIR_NOTE"))));
            check(RoomPlayerClaimAudit.messages(repaired.reviewMessages(draft)).equals(messages),"repair notes/rejected draft never become evidence");
            check(RoomPlayerClaimAudit.outputTokens(general)<=8192,"bounded output");
        }
        check(RoomPlayerClaimAudit.messages(List.of(new AiDialogueModels.OllamaMessage("system","legacy"))).isEmpty(),"legacy unaffected");
        System.out.println("RoomPlayerClaimAuditTest: PASS ("+checks+" checks; protocol, not semantic accuracy)");
    }
}
