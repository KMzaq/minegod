package com.sande.mythictrpg.ai;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Loopback scripted HTTP only, no real model. Verifies composed review and fail-closed behavior. */
public final class RoomPlayerClaimTransportTest {
    private static int checks;
    private static final Gson JSON=new Gson();
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        frozenBaselineContract();
        for(String scenario:List.of("PASS","GENERAL_REJECT","CLAIM_REJECT","INVALID_CLAIM","HTTP_FAILURE")) baseline(scenario);
        for(String scenario:List.of("GENERAL_REJECT","CLAIM_REJECT","PASS","INVALID_SOURCE","SOURCE_HTTP_FAILURE","INVALID_DRAFT","DRAFT_HTTP_FAILURE","INVALID_CLAIM","HTTP_FAILURE"))run(scenario);
        System.out.println("RoomPlayerClaimTransportTest: PASS ("+checks+" checks; fake loopback transport, no model)");
    }
    private static void frozenBaselineContract()throws Exception {
        var root=java.nio.file.Path.of("../검토결과/player-understanding-20261018/claim-v27-regression");
        int rows=0;
        try(var paths=java.nio.file.Files.list(root)) {
            for(var path:paths.filter(p->p.getFileName().toString().matches(".*-(good|bad)\\.json")).sorted().toList()) {
                var row=JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
                var evidence=List.of(JSON.fromJson(row.get("evidence"),AiDialogueModels.OllamaMessage[].class));
                var draft=JSON.fromJson(row.get("draft"),AiDialogueModels.StructuredAiResult.class);
                var general=RoomDialogueGrounding.reviewMessages(evidence,draft);
                int calls=0;
                for(var raw:row.getAsJsonArray("reviewWire")) {
                    var event=raw.getAsJsonObject();
                    if(event.has("request")) {
                        var request=JsonParser.parseString(event.get("request").getAsString()).getAsJsonObject();
                        var expected=calls++==0?general:BaselineRoomClaimWitnessAudit.messages(general);
                        var actual=List.of(JSON.fromJson(request.get("messages"),AiDialogueModels.OllamaMessage[].class));
                        check(expected.size()==actual.size(),"baseline message count "+path);
                        for(int i=0;i<expected.size();i++) {
                            check(expected.get(i).role().equals(actual.get(i).role()),"baseline role");
                            String a=expected.get(i).content(),b=actual.get(i).content();boolean same=a.equals(b);
                            if(!same)try{same=JsonParser.parseString(a).equals(JsonParser.parseString(b));}catch(RuntimeException ignored){}
                            check(same,"frozen baseline message "+path+" call="+calls+" message="+i);
                        }
                        check(JSON.toJsonTree(calls==1?RoomDialogueGrounding.schema(general):BaselineRoomClaimWitnessAudit.schema(general)).equals(request.get("format")),"frozen schema");
                        check(request.getAsJsonObject("options").get("num_predict").getAsInt()==(calls==1?RoomDialogueGrounding.outputTokens(general):BaselineRoomClaimWitnessAudit.outputTokens(general)),"frozen output budget");
                    } else if(event.has("response")&&calls==2) {
                        var response=JsonParser.parseString(JsonParser.parseString(event.get("response").getAsString()).getAsJsonObject().getAsJsonObject("message").get("content").getAsString()).getAsJsonObject();
                        check(JSON.toJsonTree(BaselineRoomClaimWitnessAudit.parse(response,general)).equals(row.get("review")),"frozen parser result "+path);
                    }
                }
                rows++;
            }
        }
        check(rows==77,"all frozen baseline rows checked");
    }
    private static void baseline(String scenario)throws Exception {
        var count=new AtomicInteger();var failure=new AtomicReference<Throwable>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/chat",exchange->{
            try {
                int n=count.incrementAndGet();
                var request=JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                String policy=request.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
                if(n>2||!policy.startsWith(n==1?RoomEvidenceAudit.MARKER:BaselineRoomClaimWitnessAudit.MARKER))throw new AssertionError("default must not activate experimental readers");
                var unit=new JsonObject();
                if(n==1) {unit.addProperty("kind","MIXED");unit.addProperty("finding",scenario.equals("GENERAL_REJECT")?"WRONG_SOURCE":"NONE");unit.addProperty("correction",scenario.equals("GENERAL_REJECT")?"주체 확인":"");}
                else {unit.addProperty("draftText","항해하기로 결심했군.");unit.addProperty("reading","The questioner decided to sail.");unit.addProperty("sourceRef","s0");unit.addProperty("comparison","The source is only a wish.");unit.addProperty("finding",scenario.equals("CLAIM_REJECT")?"STRENGTHENED":"NONE");}
                var result=new JsonObject();result.add("u0",unit);
                if(n==2&&scenario.equals("INVALID_CLAIM"))result=new JsonObject();
                byte[] bytes=JSON.toJson(Map.of("message",Map.of("content",result.toString()))).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(n==2&&scenario.equals("HTTP_FAILURE")?503:200,bytes.length);exchange.getResponseBody().write(bytes);
            } catch(Throwable e){failure.set(e);exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}
        });server.start();
        // Even an evaluation JVM property cannot turn on extra calls in the default production constructor.
        String previous=System.getProperty("mythai.eval.independentFrames");System.setProperty("mythai.eval.independentFrames","true");
        try(var client=new LocalOllamaClient()) {
            var r=PlayerUnderstandingPromptTest.request("항해하고 싶어.",List.of(),false);
            var draft=PlayerClaimBoundaryEvaluation.draft("항해하기로 결심했군.");
            var messages=RoomDialogueGrounding.reviewMessages(PlayerUnderstandingPromptTest.messages(r,List.of()),draft);
            var settings=new AiDialogueConfig.Settings(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/chat"),"gemma4:12b",20,600,420,20,260,180,true,96,3,1,16,2,3,3,120,3,false);
            try {
                var review=client.submitReview(UUID.randomUUID(),messages,settings).completion().get(25,TimeUnit.SECONDS).value();
                check(!Set.of("INVALID_CLAIM","HTTP_FAILURE").contains(scenario),"baseline fail closed");
                check(review.pass()==scenario.equals("PASS"),"baseline outcome");review.validateAgainst(draft);checks++;
            }catch(ExecutionException expected){check(Set.of("INVALID_CLAIM","HTTP_FAILURE").contains(scenario),"only scripted baseline failure");}
            check(failure.get()==null,"baseline wire valid");check(count.get()==(scenario.equals("GENERAL_REJECT")?1:2),"baseline bounded calls");
        } finally {server.stop(0);if(previous==null)System.clearProperty("mythai.eval.independentFrames");else System.setProperty("mythai.eval.independentFrames",previous);}
    }
    private static void run(String scenario)throws Exception {
        var count=new AtomicInteger();var failure=new AtomicReference<Throwable>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/chat",exchange->{
            try {
                int n=count.incrementAndGet();
                var request=JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                String policy=request.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
                if(n==1&&!policy.startsWith(RoomSourceReading.MARKER)||n==2&&!policy.startsWith(RoomDraftReading.MARKER)||n==3&&!policy.startsWith(RoomEvidenceAudit.MARKER)||n==4&&!policy.startsWith(RoomClaimWitnessAudit.MARKER))throw new AssertionError("unexpected audit order");
                if(n>4||!request.get("model").getAsString().equals("gemma4:12b"))throw new AssertionError("request budget/model changed");
                if(request.get("think").getAsBoolean()||request.getAsJsonObject("options").get("temperature").getAsDouble()!=0)throw new AssertionError("audit controls changed");
                var check=new JsonObject();
                if(n==1) {
                    if(request.toString().contains("항해하기로 결심했군")) throw new AssertionError("source reading saw rejected/current draft");
                    check.addProperty("literalContent","QUESTIONER wants to sail.");
                    check.add("claims",JSON.toJsonTree(List.of(Map.of("actor","UTTERANCE_SPEAKER","action","sail","status","DESIRE"))));
                }
                else if(n==2) {
                    if(request.toString().contains("항해하고 싶어"))throw new AssertionError("draft reading saw original source");
                    check.addProperty("reading","질문자의 항해 결정 주장");check.addProperty("draftReference","ADDRESSEE");
                    check.addProperty("draftActionStatus",scenario.equals("CLAIM_REJECT")?"DECIDED":"NONE");
                }
                else if(n==3){check.addProperty("kind","PAST_SPEECH_ATTRIBUTION");check.addProperty("finding",scenario.equals("GENERAL_REJECT")?"WRONG_SOURCE":"NONE");check.addProperty("correction",scenario.equals("GENERAL_REJECT")?"일반 검토 거절":"");}
                else {
                    var messages = request.getAsJsonArray("messages");
                    var units = JsonParser.parseString(messages.get(messages.size()-1).getAsJsonObject().get("content").getAsString()).getAsJsonObject().getAsJsonObject("units");
                    check.add("draftText", units.get("u0").deepCopy());
                    check.addProperty("reading","질문자의 항해 결정 주장");
                    check.addProperty("sourceClaim","s0.c0");
                    check.addProperty("draftReference","ADDRESSEE");
                    check.addProperty("draftActionStatus",scenario.equals("CLAIM_REJECT")?"DECIDED":"NONE");
                    check.addProperty("comparison","질문자는 희망을 밝혔고 대사는 결심을 주장한다.");
                    check.addProperty("finding",scenario.equals("INVALID_CLAIM")?"INVALID":scenario.equals("CLAIM_REJECT")?"STRENGTHENED":"NONE");
                }
                var result=new JsonObject();result.add(n==1?"s0":"u0",check);
                if(n==1&&scenario.equals("INVALID_SOURCE"))result=new JsonObject();
                if(n==2&&scenario.equals("INVALID_DRAFT"))result=new JsonObject();
                byte[] body=JSON.toJson(Map.of("message",Map.of("role","assistant","content",result.toString()),"done",true)).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(n==4&&scenario.equals("HTTP_FAILURE")||n==1&&scenario.equals("SOURCE_HTTP_FAILURE")||n==2&&scenario.equals("DRAFT_HTTP_FAILURE")?503:200,body.length);
                exchange.getResponseBody().write(body);
            }catch(Throwable error){failure.set(error);exchange.sendResponseHeaders(500,-1);}
            finally{exchange.close();}
        });server.start();
        try(var client=new LocalOllamaClient(true)) {
            var r=PlayerUnderstandingPromptTest.request("항해하고 싶어.",List.of(),false);
            var draft=PlayerClaimBoundaryEvaluation.draft("항해하기로 결심했군.");
            var messages=RoomDialogueGrounding.reviewMessages(PlayerUnderstandingPromptTest.messages(r,List.of()),draft);
            var settings=new AiDialogueConfig.Settings(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/chat"),"gemma4:12b",20,600,420,20,260,180,true,96,3,1,16,2,3,3,120,3,false);
            try {
                var review=client.submitReview(UUID.randomUUID(),messages,settings).completion().get(25,TimeUnit.SECONDS).value();
                check(!Set.of("INVALID_SOURCE","SOURCE_HTTP_FAILURE","INVALID_DRAFT","DRAFT_HTTP_FAILURE","INVALID_CLAIM","HTTP_FAILURE").contains(scenario),"audit failure must not fall back to general PASS");
                check(review.pass()==scenario.equals("PASS"),"combined result");review.validateAgainst(draft);checks++;
                if(scenario.equals("CLAIM_REJECT"))check(review.issues().getFirst().code()==RoomDialogueGrounding.Code.CONTEXT_CONTRADICTION,"claim finding preserved");
            }catch(ExecutionException expected){check(Set.of("INVALID_SOURCE","SOURCE_HTTP_FAILURE","INVALID_DRAFT","DRAFT_HTTP_FAILURE","INVALID_CLAIM","HTTP_FAILURE").contains(scenario),"only scripted failure expected");}
            check(failure.get()==null,"valid ordered HTTP requests");
            check(count.get()==(Set.of("INVALID_SOURCE","SOURCE_HTTP_FAILURE").contains(scenario)?1:Set.of("INVALID_DRAFT","DRAFT_HTTP_FAILURE").contains(scenario)?2:scenario.equals("GENERAL_REJECT")?3:4),"bounded calls; reading failure stops chain, general rejection skips claim audit");
        }finally{server.stop(0);}
    }
}
