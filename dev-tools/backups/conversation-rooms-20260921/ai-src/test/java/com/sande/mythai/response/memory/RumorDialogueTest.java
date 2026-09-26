package com.sande.mythai.response.memory;

import com.sande.mythictrpg.rumor.RumorLedger;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline prompt/provenance checks; no server and no inference. */
public final class RumorDialogueTest {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    public static void main(String[] args){
        var r=new RumorLedger.HeardRumor(UUID.randomUUID(),1,"비늘을 부탁했다는 이야기","오해받은 여행자","CAUTIOUS");
        var legacy=new RumorLedger.HeardRumor(UUID.randomUUID(),1,"옛 소문","");
        check(!DialogueMemoryBridge.rumorRow(legacy).containsKey("reception"),"legacy representation preserved");
        check(DialogueMemoryBridge.rumorRow(r).get("reception").equals("CAUTIOUS"),"god reception enters data");
        check(DialogueMemoryBridge.selectRumors(List.of(r),"안녕",true).equals(List.of(r)),"first impression");
        check(DialogueMemoryBridge.selectRumors(List.of(r),"사과 가격이 얼마야",false).isEmpty(),"unrelated turn no forced epithet");
        check(DialogueMemoryBridge.selectRumors(List.of(r),"내 소문을 들었어?",false).equals(List.of(r)),"explicit rumor recall");
        var prompt=DialogueMemoryBridge.prompt(List.of(),List.of(r));
        check(prompt.contains("RUMOR_RECEIVED")&&prompt.contains("CAUTIOUS")&&!prompt.contains("PLAYER_STATEMENT"),"hearsay not player admission");
        var policy=MemoryRecallPolicy.generationSystem("base",true);
        check(policy.contains("not automatically believed")&&policy.contains("do not repeat an epithet"),"character-sensitive nonrepetitive guidance");
        check(MemoryRecallPolicy.generationSystem("base",false).equals("base"),"memory-free prompt untouched");
        var list=new ArrayList<RumorLedger.HeardRumor>();for(int i=0;i<8;i++)list.add(new RumorLedger.HeardRumor(UUID.randomUUID(),1,"x".repeat(180),"y".repeat(60),"INTERESTED"));
        var bounded=DialogueMemoryBridge.prompt(List.of(),list);var json=bounded.substring(bounded.indexOf("[{"),bounded.lastIndexOf(']')+1);
        check(json.length()<=640&&com.google.gson.JsonParser.parseString(json).getAsJsonArray().size()<=3,"shared rumor budget");
        var turn=new DialogueMemoryBridge.Turn(null,null,List.of(),List.of(r),prompt,1);
        check(turn.hasGuardedEvidence(),"rumor NPC replies cannot enter independent journal");
        var valid=new AtomicBoolean(true);var ref=ExperienceHistory.Reference.guarded(valid::get);
        var h=new ExperienceHistory();h.record("그 소문을 들었지",List.of(ref));
        check(h.excluded(List.of("그 소문을 들었지")).isEmpty(),"valid receipt keeps session paraphrase");
        var inherited=h.references(Set.of("그 소문을 들었지"));h.record("아까 그 얘기 말이야",inherited);
        valid.set(false);
        check(h.excluded(List.of("그 소문을 들었지","아까 그 얘기 말이야")).size()==2,"revocation invalidates original and inherited paraphrase");
        check(new ExperienceHistory().excluded(List.of("그 소문을 들었지")).isEmpty(),"sessions do not share provenance store");
        check(!ExperienceHistory.Reference.guarded(()->{throw new IllegalStateException("unavailable");}).current(),"unavailable game state fails closed");
        var full=new ExperienceHistory();var transcript=new ArrayList<String>();
        for(int i=0;i<64;i++){String line="line"+i;transcript.add(line);var state=new AtomicBoolean(true);full.record(line,List.of(ExperienceHistory.Reference.guarded(state::get)));}
        var excluded=full.excluded(transcript);check(excluded.size()==2,"reserve two independent provenance slots");
        var retained=new HashSet<>(transcript);retained.removeAll(excluded);var refs=new ArrayList<>(full.references(retained));
        refs.add(ref);refs.add(ExperienceHistory.Reference.guarded(new AtomicBoolean(true)::get));
        full.record("observation plus rumor",refs);check(refs.size()==64,"fresh observation and rumor cannot overflow provenance limit");
        System.out.println("RumorDialogueTest: "+checks+" assertions passed");
    }
}
