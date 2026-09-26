package com.sande.mythai.response.memory;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Explicitly approved, opt-in LIVE benchmark. Only synthetic text, never opens a world or journal. */
public final class MemoryModelBenchmark {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final URI ENDPOINT=URI.create("http://127.0.0.1:11434");
    private static final HttpClient HTTP=HttpClient.newHttpClient();
    private static final List<Map<String,Object>> results=new ArrayList<>();
    private static JsonObject metadata(String path)throws Exception {
        var r=HTTP.send(HttpRequest.newBuilder(ENDPOINT.resolve(path)).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(r.statusCode()!=200)throw new IllegalStateException("metadata HTTP "+r.statusCode());return JsonParser.parseString(r.body()).getAsJsonObject();
    }
    private static String digest(JsonObject tags,String model){for(var value:tags.getAsJsonArray("models")){
        var m=value.getAsJsonObject();if(model.equals(m.get("name").getAsString()))return m.get("digest").getAsString();}
        throw new IllegalArgumentException("not installed: "+model);
    }
    public static void main(String[] args)throws Exception {
        String mode=args[0];if(!Set.of("embedding-cpu","embedding-gpu","extract-gemma","extract-qwen","extract-gemma-validation","extract-qwen-validation").contains(mode))throw new IllegalArgumentException("benchmark mode");
        var tags=metadata("/api/tags");var before=metadata("/api/ps");
        boolean cpu=mode.equals("embedding-cpu"),qwen=mode.startsWith("extract-qwen");String extractor=qwen?"qwen2.5:7b":"gemma4:12b";
        var execution=new MemoryIndexSettings.Execution(cpu,4,30,qwen,4,8192,512,30,0.65,true);
        var s=new MemoryIndexSettings(true,MemoryIndexSettings.Mode.ON,true,268435456,12000,ENDPOINT,
                "bge-m3:latest",digest(tags,"bge-m3:latest"),1024,extractor,digest(tags,extractor),1500,30000,execution);
        var backend=new OllamaMemoryBackend(s);Path output=Files.createDirectories(Path.of(args[1])).resolve(mode+"-"+System.currentTimeMillis()+".json");
        try {if(mode.startsWith("embedding"))embeddings(backend);else annotations(backend,mode.endsWith("validation"));}
        finally {var report=new LinkedHashMap<String,Object>();report.put("mode",mode);report.put("at",Instant.now().toString());report.put("settings",s.toString());
            report.put("before",before);report.put("after",metadata("/api/ps"));report.put("results",results);Files.writeString(output,JSON.toJson(report));System.out.println("REPORT="+output);}
    }
    private static void embeddings(OllamaMemoryBackend backend)throws Exception {
        String[] documents={"나는 수영을 못해서 깊은 물에 들어가기 무서워.","내일 바닷가 신전에 다시 찾아가겠다고 약속할게.",
                "나는 복숭아를 먹으면 두드러기가 나.","채굴하다가 다이아몬드 세 개를 발견했어.","친구 민수가 드래곤을 쓰러뜨렸다고 말했어.","나는 검보다 활을 쓰는 게 편해."};
        String[] queries={"물놀이를 꺼리는 이유를 말했었지?","전에 해변 성소에 가겠다고 했던 계획 기억해?","내가 피해야 하는 과일을 기억해?",
                "광산에서 무슨 보석을 얻었는지 기억해?","민수의 용 사냥 이야기 했었지?","내가 선호하는 무기가 뭐였지?","내가 좋아하는 음악이 뭐였지?"};
        var vectors=new ArrayList<SemanticIndex.Vector>();
        for(String document:documents)vectors.add(embedding(backend,document,true));
        for(int i=0;i<queries.length;i++){
            var q=embedding(backend,queries[i],false);var scores=new ArrayList<Double>();for(var v:vectors)scores.add(q.cosine(v));
            int top=0;for(int j=1;j<scores.size();j++)if(scores.get(j)>scores.get(top))top=j;
            var row=new LinkedHashMap<String,Object>();row.put("kind","ranking");row.put("query",queries[i]);row.put("expected",i<6?i:-1);row.put("top",top);row.put("scores",scores);results.add(row);
            System.out.println("RANK expected="+(i<6?i:-1)+" top="+top+" score="+scores.get(top));
        }
    }
    private static SemanticIndex.Vector embedding(OllamaMemoryBackend backend,String text,boolean background)throws Exception {
        long start=System.nanoTime();var vector=backend.embed(text,background);double ms=(System.nanoTime()-start)/1e6;
        results.add(Map.of("kind","embedding","text",text,"background",background,"ms",ms,"dimensions",vector.values().length));
        System.out.printf(Locale.ROOT,"EMBED background=%s ms=%.1f%n",background,ms);return vector;
    }
    private record Case(List<String> texts,String kind,String relation){}
    private static void annotations(OllamaMemoryBackend backend,boolean validation)throws Exception {
        var cases=validation?List.of(
                new Case(List.of("내일 제단에 철괴를 가져다 놓을게.","철괴를 가져오려던 계획은 취소할게. 대신 돌을 모으겠어."),"PLAN_OR_PROMISE","CANCELS"),
                new Case(List.of("나는 복숭아를 싫어해.","싫다는 말은 정정할게. 먹고 싶지만 알레르기가 있어서 못 먹는 거야."),"SELF_CLAIM","CORRECTS"),
                new Case(List.of("내일 신전에 갈게.","친구가 '나는 벌써 신전에 다녀왔어'라고 하더라."),"REPORTED","NONE"),
                new Case(List.of("제단 수리를 끝내겠다고 약속할게.","오늘 하늘은 맑았어.","마을에서 빵을 먹었어.","숲에서 나무를 모았어.","도끼는 집에 두고 왔어.","제단을 고치겠다는 약속을 지켰어. 수리를 끝냈어."),"SELF_CLAIM","REPORTS_FULFILLMENT"))
                :List.of(new Case(List.of("내일 바다에 가겠다고 약속할게."),"PLAN_OR_PROMISE","NONE"),
                new Case(List.of("나는 수영을 못해.","이제 수영을 배웠어."),"SELF_CLAIM","CORRECTS"),
                new Case(List.of("내일 바다에 갈게.","바다에 가려던 약속은 취소할게."),"PLAN_OR_PROMISE","CANCELS"),
                new Case(List.of("내일 바다에 갈게.","친구가 바다에 갔다고 했어."),"REPORTED","NONE"),
                new Case(List.of("내일 바다에 갈게.","비가 그치면 바다에 갈 수도 있어."),"CONDITIONAL","NONE"),
                new Case(List.of("내일 바다에 갈게.","모레 산에도 갈 거야."),"PLAN_OR_PROMISE","ALSO_PLANNED"),
                new Case(List.of("내일 바다에 가겠다고 약속할게.","약속대로 바다에 다녀왔어."),"SELF_CLAIM","REPORTS_FULFILLMENT"),
                new Case(List.of("내일 바다에 갈게.","바다에 가기로 했어."),"PLAN_OR_PROMISE","NONE"));
        UUID world=UUID.randomUUID(),player=UUID.randomUUID(),session=UUID.randomUUID();var key=new MemoryJournal.Key(world,"mythictrpg:fortuna",player);
        for(int n=0;n<cases.size();n++) {var c=cases.get(n);var entries=new ArrayList<MemoryJournal.Entry>();long now=System.currentTimeMillis();
            for(int i=0;i<c.texts().size();i++)entries.add(new MemoryJournal.Entry(UUID.randomUUID(),key,session,1,MemoryJournal.Source.PLAYER_STATEMENT,Set.of(player),now-1000+i,c.texts().get(i),false));
            long start=System.nanoTime();var row=new LinkedHashMap<String,Object>();row.put("case",n);row.put("texts",c.texts());row.put("expectedKind",c.kind());row.put("expectedRelation",c.relation());
            try {var a=backend.annotate(entries);String relation=a.links().isEmpty()?"NONE":a.links().getFirst().relation();row.put("kind",a.kind());row.put("relation",relation);row.put("match",a.kind().equals(c.kind())&&relation.equals(c.relation()));}
            catch(Exception e){row.put("error",e.getClass().getSimpleName()+": "+e.getMessage());}
            row.put("ms",(System.nanoTime()-start)/1e6);results.add(row);System.out.println("ANNOTATION "+new Gson().toJson(row));
        }
    }
}
