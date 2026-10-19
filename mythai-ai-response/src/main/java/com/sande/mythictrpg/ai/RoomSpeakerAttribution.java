package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.HistoryLine;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import java.util.*;

/** Identity labels for already-audience-projected, fitted history. No new participant or knowledge. */
final class RoomSpeakerAttribution {
    private static final Gson JSON=new Gson();
    private RoomSpeakerAttribution() { }

    static String policy() {
        return """

                [SPEAKER_ATTRIBUTION]
                speakerAttribution은 게임이 발화자의 role과 ID로 만든 구분이다. 이름이나 말투로 화자를 합치지 않는다.
                REQUESTING_PLAYER의 말과 OTHER_PLAYER의 말은 서로 다른 사람의 말이다. 다른 플레이어가 말한
                희망·계획·경험을 지금 질문한 플레이어의 것으로 옮기지 않는다. RESPONDING_GOD의 이전 말과
                OTHER_GOD의 말도 구분한다. 인용문의 '나/너'는 그 원래 대화의 화자/대상에 속한다.
                대화 기록의 role=OTHER_PLAYER/OTHER_GOD는 다른 참여자의 말이다. sourceRole은 원본 역할을 보존한 필드다.
                이 구분은 발언 출처만 알려 준다. 등장/실행/동의/비밀 공개 권한이나 발언 내용의 진실을 보증하지 않는다.
                각 history 항목의 utteranceOwner는 그 항목을 실제 말한 사람이다. CURRENT_PLAYER_MESSAGE만 지금 질문자의 새 말이다.
                현재 질문자에게 답할 때 '네가 말했다/원한다/정했다'의 '너'는 그 질문자다. 타인의 1인칭을 질문자의 말로 바꾸지 않는다.
                보조 신 반응이나 명시적 호명에서는 실제 대화 상대가 다른 신/참여자일 수 있다. 그 대상을 따르며 무조건 질문자로 바꾸지 않는다.
                발화자와 문장 속 목적의 주체는 다를 수 있다. 본인이 남의 희망을 전하거나 인용했다면 그 희망은 원래 그 사람의 것이다.
                본인이 '하고 싶다'고 말한 희망은 희망으로 받는다. '하기로 했다/정했다/결심했다/수락했다'로 강화하지 않는다.
                공감·칭찬·질문의 전제에서도 같은 강도를 유지한다. 바람에 공감하면서 이미 선택한 일처럼 되받지 않는다.
                실제 조건문이나 조심스러운 목적 추측은 가능하다. 결과가 불확실하다는 말만 붙여 결정 전제를 숨기지는 않는다.
                명시적으로 결정하거나 수락한 말은 그대로 인정한다. 무조건 미결정으로 낮추거나 모든 대답을 확인 질문으로 바꾸지 않는다.
                현재 본인이 새로 동의·선택했다면 타인의 이전 계획과 목표가 같아도 본인의 선택으로 받아들인다.
                성격에 따른 반응·거절·추측은 가능하되, 다른 사람의 과거 발언이나 선택을 새로 만들어 근거로 삼지 않는다.
                """;
    }

    static Map<String,Object> view(Request request,List<HistoryLine> fittedHistory) {
        var speakers=new LinkedHashMap<List<String>,Map<String,String>>();
        for(var line:fittedHistory) {
            String kind=switch(line.role()) {
                case "PLAYER" -> line.speakerId().equals(request.playerId().toString())?"REQUESTING_PLAYER":"OTHER_PLAYER";
                case "NPC" -> line.speakerId().equals(request.speakerGodId().toString())?"RESPONDING_GOD":"OTHER_GOD";
                default -> "UNCLASSIFIED";
            };
            speakers.putIfAbsent(List.of(line.role(),line.speakerId()),Map.of("role",line.role(),"speakerId",line.speakerId(),"relativeSpeaker",kind));
        }
        return Map.of("referencePlayerId",request.playerId().toString(),"respondingGodId",request.speakerGodId().toString(),
                "speakers",List.copyOf(speakers.values()));
    }

    /** Prompt-only role aliases; retain every original field and source role, never mutate game history. */
    static JsonArray history(Request request,List<HistoryLine> fittedHistory) {
        var result=new JsonArray();
        for(var line:fittedHistory) {
            var entry=JSON.toJsonTree(line).getAsJsonObject();
            entry.addProperty("utteranceOwner",switch(line.role()) {
                case "PLAYER" -> line.speakerId().equals(request.playerId().toString())?"현재 질문자 본인의 발언":"다른 플레이어의 발언 — 현재 질문자가 한 말이 아님";
                case "NPC" -> line.speakerId().equals(request.speakerGodId().toString())?"지금 응답하는 신 자신의 이전 발언":"다른 신의 발언 — 지금 응답하는 신이 한 말이 아님";
                default -> "게임이 제공한 원본 role과 speakerId를 따름";
            });
            if(line.role().equals("PLAYER")&&!line.speakerId().equals(request.playerId().toString())) {
                entry.addProperty("sourceRole",line.role());entry.addProperty("role","OTHER_PLAYER");
            } else if(line.role().equals("NPC")&&!line.speakerId().equals(request.speakerGodId().toString())) {
                entry.addProperty("sourceRole",line.role());entry.addProperty("role","OTHER_GOD");
            }
            result.add(entry);
        }
        return result;
    }
}
