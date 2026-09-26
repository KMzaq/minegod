package com.sande.mythictrpg.ai.experiencecontract;

import com.sande.mythictrpg.gameplay.watch.*;
import java.util.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode;
import net.minecraft.resources.ResourceLocation;
import static com.sande.mythictrpg.gameplay.watch.WatchContract.*;

/** Source proof fingerprints are portable; hidden text is not copied into a replay reference. */
public final class ExperiencePortableEvidenceTest {
    private static int checks;
    public static void main(String[] args) {
        UUID world=UUID.randomUUID(), subject=UUID.randomUUID(), observation=UUID.randomUUID();
        Ref permission=new Ref("test:disclosure",1), eligibility=new Ref("test:eligibility",1);
        var values = new EnumMap<Field,Value>(Field.class);
        values.put(Field.ACTOR,new Value(subject.toString(),Map.of(subject,permission)));
        values.put(Field.ACTION,new Value("MATURE_CROP_REMOVED",Map.of(subject,permission)));
        values.put(Field.SUBJECT_TYPE,new Value("minecraft:wheat",Map.of(subject,permission)));
        values.put(Field.OUTCOME,new Value("BLOCK_REMOVED_NOT_ITEM_ACQUISITION",Map.of(subject,permission)));
        values.put(Field.TIME,new Value("utc=123;tick=12;dayTime=6000",Map.of(subject,permission)));
        var proof=new Proof(observation,world,UUID.randomUUID(),1,"test:commit",subject,"test:god",UUID.randomUUID(),
                new Ref("test:policy",1),new Ref("test:scene",1),1,values,1);
        var audience=new Audience(world,new Key("test:god",subject),Set.of(subject),new Ref("test:old_session",1));
        var snapshot=new AsyncGodWatch.ReadSnapshot(audience,new View(true,"READY",List.of(proof)),Map.of(observation,eligibility));
        var refs=ExperienceRoomEvidence.capture(snapshot);
        check(refs.size()==1 && refs.get(observation).kind().equals(ExperienceRoomEvidence.KIND),"one explicit source reference");
        check(!refs.get(observation).payload().contains("minecraft:wheat")&&!refs.get(observation).payload().contains("MATURE_CROP_REMOVED"),"no raw/projection text in payload");
        var newSession=new Audience(world,audience.key(),audience.players(),new Ref("test:new_session",1));
        check(ExperienceRoomEvidence.capture(new AsyncGodWatch.ReadSnapshot(newSession,snapshot.view(),snapshot.eligibilityRefs())).equals(refs),"room/session generation is not persistent proof identity");
        check(!ExperienceRoomEvidence.fingerprint(proof,eligibility).equals(ExperienceRoomEvidence.fingerprint(proof,new Ref(eligibility.id(),2))),"eligibility revision changes fingerprint");
        var narrower=new EnumMap<Field,Value>(values);narrower.remove(Field.TIME);
        var filtered=new Proof(proof.id(),proof.worldId(),proof.eventId(),proof.sourceRevision(),proof.sourceRef(),proof.observerTarget(),
                proof.observerGodId(),proof.watchId(),proof.policy(),proof.context(),proof.observedAtSequence(),narrower,proof.revision());
        check(!ExperienceRoomEvidence.fingerprint(filtered,eligibility).equals(ExperienceRoomEvidence.fingerprint(proof,eligibility)),"narrower disclosure cannot validate formerly richer text");
        var view=ExperienceProjection.project(snapshot.view(),subject,ExperienceView.Relationship.UNKNOWN);
        var lease=new ExperienceLease(view,ids->true,refs);
        check(lease.current(Set.of(observation)),"game issued known observation accepted");
        check(!lease.current(Set.of(UUID.randomUUID())),"AI cannot add observation to lease");
        check(lease.portableEvidence().equals(refs),"portable source belongs to original game lease");
        readOnlyMemoryBoundary();
        System.out.println("ExperiencePortableEvidenceTest: " + checks + " checks PASS");
    }
    private static void readOnlyMemoryBoundary() {
        UUID player=UUID.randomUUID(),room=UUID.randomUUID(),world=UUID.randomUUID(),generation=UUID.randomUUID();
        var god=ResourceLocation.parse("test:god");
        var memory=new ConversationMemoryContext(world,room,generation,player,god.toString(),Set.of(player),false);
        for (boolean recording : List.of(false,true)) {
            var request=new Request(room,1,UUID.randomUUID(),player,"player",List.of(god),god,"query",List.of(),true,recording,false,
                    List.of(new GodState(god,"R_NEUTRAL","E_NEUTRAL","",memory)),false,Set.of(player));
            check(ExperienceRoomEvidence.memoryAudienceSupported(request,Mode.PERSONAL),"action-read-only room still reads personal observation; recording="+recording);
            check(!ExperienceRoomEvidence.memoryAudienceSupported(request,Mode.RUMOR_TEST),"global rumor test cannot consume private observation");
            check(!ExperienceRoomEvidence.memoryAudienceSupported(request,Mode.OFF),"global memory OFF denies observations");
            var blockedMemory=new ConversationMemoryContext(world,room,generation,player,god.toString(),Set.of(player),true);
            var blocked=new Request(room,1,UUID.randomUUID(),player,"player",List.of(god),god,"query",List.of(),false,recording,false,
                    List.of(new GodState(god,"R_NEUTRAL","E_NEUTRAL","",blockedMemory)),false,Set.of(player));
            check(!ExperienceRoomEvidence.memoryAudienceSupported(blocked,Mode.PERSONAL),"memory-read-only contract remains closed despite mutable action scope");
        }
    }
    private static void check(boolean condition,String label) { checks++;if(!condition)throw new AssertionError(label); }
}
