package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.experiencecontract.*;
import java.util.*;

/** Search-only aggregate of a game-issued bounded view, never raw global events or inferred daily behavior. */
public final class ObservedExperienceSummary {
    public record Group(String action,String subject,String outcome,int distinctEvents,Set<UUID> observations,Set<UUID> events) {
        public Group { observations=Set.copyOf(observations);events=Set.copyOf(events); }
    }
    public record Summary(ExperienceLease lease,List<Group> groups,Set<UUID> references,String coverage) {
        public Summary { groups=List.copyOf(groups);references=Set.copyOf(references); }
        /** Must be rechecked on the game thread at consumption; all contributing proofs remain guarded. */
        public boolean current(){return lease.current(references);}
    }
    private ObservedExperienceSummary() {}
    public static Summary summarize(ExperienceLease lease) {
        var view=lease.view();
        if(!view.available())return new Summary(lease,List.of(),Set.of(),"UNAVAILABLE_NOT_ZERO");
        Map<String,List<ExperienceView.Event>> buckets=new LinkedHashMap<>();
        Map<UUID,ExperienceView.Event> seen=new HashMap<>();
        for(var e:view.events()) {
            var previous=seen.putIfAbsent(e.eventId(),e);
            if(previous!=null && (!previous.actionType().equals(e.actionType())||!previous.subjectType().equals(e.subjectType())
                    ||!previous.outcome().equals(e.outcome())||previous.sourceRevision()!=e.sourceRevision()))
                return new Summary(lease,List.of(),Set.of(),"CONFLICTING_EVIDENCE");
            buckets.computeIfAbsent(e.actionType()+"/"+e.subjectType()+"/"+e.outcome(),k->new ArrayList<>()).add(e);
        }
        var groups=new ArrayList<Group>();var refs=new HashSet<UUID>();
        for(var bucket:buckets.values()) {
            Set<UUID> observations=new HashSet<>(),events=new HashSet<>();
            bucket.forEach(e->{observations.add(e.observationId());events.add(e.eventId());});refs.addAll(observations);
            var e=bucket.getFirst();groups.add(new Group(e.actionType(),e.subjectType(),e.outcome(),events.size(),observations,events));
        }
        return new Summary(lease,groups,refs,"AUTHORIZED_WINDOW_MAX_16_NOT_WHOLE_DAY_OR_ITEM_QUANTITY");
    }
}
