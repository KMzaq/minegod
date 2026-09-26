package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Replayed quest prose retains its exact static source and current whole-audience permission. */
public final class RoomQuestKnowledgeTest {
    private static int checks;
    public static void main(String[] args) {
        var source=ResourceLocation.parse("test:source"); var listener=ResourceLocation.parse("test:listener");
        var quest=ResourceLocation.parse("test:quest"); var players=Set.of(new UUID(0,1));
        String hash="1".repeat(64); var proof=RoomQuestKnowledge.evidence(source,quest,hash);
        var actual=new AtomicBoolean();
        check(RoomQuestKnowledge.validEvidence(proof,false,List.of(listener),players,(s,q,p,g,a)-> {
            actual.set(s.equals(source)&&q.equals(quest)&&!p&&g.equals(List.of(listener))&&a.equals(players));return Optional.of(hash);
        }),"original source may be absent while current listener recalls authorized quest prose");
        check(actual.get(),"original owner and entire current audience used");
        check(!proof.payload().contains("title")&&!proof.payload().contains("content"),"proof payload contains no quest prose");
        check(!RoomQuestKnowledge.validEvidence(proof,true,List.of(listener),players,(s,q,p,g,a)->Optional.empty()),"current audience denial or ownership removal rejects replay");
        check(!RoomQuestKnowledge.validEvidence(proof,false,List.of(listener),players,(s,q,p,g,a)->Optional.of("2".repeat(64))),"changed author definition/policy rejects replay");
        check(!RoomQuestKnowledge.validEvidence(proof,false,List.of(listener),players,(s,q,p,g,a)-> {throw new IllegalStateException("registry unavailable");}),"registry failure closes replay");
        check(RoomQuestKnowledge.validEvidence(proof,false,List.of(listener),players,(s,q,p,g,a)->Optional.of(hash)),"same item survives reload and unrelated quest/progress changes");
        for(String payload:List.of("null","{}","[]",proof.payload().replace("sourceGodId","unknown"),
                proof.payload().replace("test:quest","BAD QUEST"),proof.payload().replace(hash,"bad")))
            check(!RoomQuestKnowledge.validEvidence(new RoomEvidenceReference(RoomQuestKnowledge.EVIDENCE_KIND,payload),false,
                    List.of(listener),players,(s,q,p,g,a)->Optional.of(hash)),"malformed source proof denied");
        check(!RoomQuestKnowledge.validEvidence(new RoomEvidenceReference("UNKNOWN",proof.payload()),false,List.of(listener),players,
                (s,q,p,g,a)->Optional.of(hash)),"unknown kind rejected");
        System.out.println("RoomQuestKnowledgeTest: "+checks+" checks PASS");
    }
    private static void check(boolean valid,String message) {checks++;if(!valid)throw new AssertionError(message);}
}
