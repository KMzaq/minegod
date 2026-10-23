package com.sande.mythictrpg.ai;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real HTTP/parser/scheduler, stub loopback endpoint. No real model calls. */
public final class LocalOllamaCompletionTest {
    private static int checks;
    private static void check(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
    public static void main(String[] args) throws Exception {
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Path.of(args[0]).toAbsolutePath());
        var json = new Gson();
        String valid = json.toJson(Map.of("speech",List.of(Map.of("speakerId","test:god","text","반갑구나.","audienceParticipantIds",List.of())),"currentTopic","","proposals",List.of()));
        for (String mode : List.of("length", "syntax", "both-length", "complete")) {
            var requests = new ArrayList<JsonObject>();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/chat",exchange -> {
                requests.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject());
                boolean truncated = mode.equals("both-length") || mode.equals("length") && requests.size()==1;
                String content = mode.equals("syntax") && requests.size()==1 ? "{broken" : valid;
                byte[] bytes = json.toJson(Map.of("done",true,"done_reason",truncated?"length":"stop","message",Map.of("content",content))).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start();
            try (var client = new LocalOllamaClient()) {
                var settings = new AiDialogueConfig.Settings(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/chat"),"gemma4:12b",
                        30,600,420,20,260,180,true,96,3,1,16,2,3,3,120,3,false);
                try {
                    var result=client.submit(UUID.randomUUID(),List.of(new AiDialogueModels.OllamaMessage("user","test")),settings).completion().get(15,TimeUnit.SECONDS).value();
                    check(!mode.equals("both-length") && result.speech().getFirst().text().equals("반갑구나."),"complete result only");
                } catch (java.util.concurrent.ExecutionException failure) {
                    check(mode.equals("both-length") && failure.getCause() instanceof LocalOllamaClient.TruncatedGeneration,"typed exhausted truncation");
                    check(failure.getCause().getSuppressed().length==1,"first failure retained");
                }
                check(requests.size()==(mode.equals("complete")?1:2),"one bounded retry");
                check(requests.getFirst().getAsJsonObject("options").get("num_predict").getAsInt()==260,"base unchanged");
                if(requests.size()==2)check(requests.get(1).getAsJsonObject("options").get("num_predict").getAsInt()==(mode.equals("syntax")?260:520),"adaptive only for length");
                for(var request:requests)check(request.get("model").getAsString().equals("gemma4:12b"),"model unchanged");
            } finally { server.stop(0); }
        }
        check(LocalOllamaClient.retryTokens(1024,new LocalOllamaClient.TruncatedGeneration())==1024,"cap");
        check(LocalOllamaClient.retryTokens(32,new LocalOllamaClient.TruncatedGeneration())==512,"minimum complete envelope retry");
        System.out.println("LocalOllamaCompletionTest: "+checks+" checks passed");
    }
}
