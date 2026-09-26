package com.sande.mythictrpg.rumor;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;

/** Deterministic game-side orchestration, reusable without Minecraft in offline tests. Not an LLM client. */
public final class CourierEngine {
    public interface Probe {
        boolean available(UUID courier,UUID subject);
        boolean witnessed(UUID courier,Event event,CourierSettings.Rule rule);
        boolean receiverAvailable(String god);
    }
    public record Event(UUID sourceId,long sourceRevision,UUID subject,Set<UUID> mentionedSubjects,Set<UUID> audience,
            String eventType,CourierSettings.Source source,String dimension,int x,int y,int z,String excerpt,long recordedAt,long gameTick) {
        public Event { Objects.requireNonNull(sourceId);Objects.requireNonNull(subject);Objects.requireNonNull(source);
            mentionedSubjects=Set.copyOf(mentionedSubjects);audience=Set.copyOf(audience);
            CourierSettings.identifier(eventType);CourierSettings.identifier(dimension);
            if(sourceRevision<1||recordedAt<0||gameTick<0||excerpt==null||excerpt.isBlank()||excerpt.length()>600||audience.isEmpty()||audience.size()>16||!audience.contains(subject))throw new IllegalArgumentException("event budget/disclosure"); }
    }
    /** Safe asynchronous input; does not contain hidden transcript, recipient list or game mutation handles. */
    public record CandidateInput(UUID worldId,UUID rootId,UUID epoch,String ruleFingerprint,String excerpt) {}
    public record Candidate(CandidateInput input,String quotedEvidence,String allegation,String epithet) {
        public Candidate { Objects.requireNonNull(input);Objects.requireNonNull(quotedEvidence);
            if(allegation==null||allegation.isBlank()||allegation.length()>300||epithet==null||epithet.length()>60)throw new IllegalArgumentException("candidate budget"); }
    }
    private final RumorLedger ledger;private final CourierSettings settings;private final Probe probe;private final LongSupplier tick;
    private final Map<String,CourierSettings.Rule> rules=new HashMap<>();private int cursor,candidateCursor;
    public CourierEngine(RumorLedger ledger,CourierSettings settings,Probe probe,LongSupplier tick) {
        this.ledger=ledger;this.settings=settings;this.probe=probe;this.tick=tick;settings.rules().forEach(r->rules.put(r.id(),r));
    }
    public boolean bind(UUID subject,UUID courier){return settings.enabled()&&probe.available(courier,subject)&&ledger.bindCourier(subject,courier);}
    /** Caller must supply a CONFIRMED death, not unload, logout or a cancelable death attempt. */
    public boolean confirmedDeath(UUID courier){return settings.enabled()&&ledger.courierDied(courier);}
    public List<CandidateInput> observe(Event event) {
        return observe(event,null);
    }
    Set<String> witnessedRules(Event event) {
        if(!settings.enabled()||event.gameTick()!=tick.getAsLong()||!event.mentionedSubjects().equals(Set.of(event.subject())))return Set.of();
        var courier=ledger.courier(event.subject());if(courier==null||courier.blocked()||!probe.available(courier.entity(),event.subject()))return Set.of();
        return settings.rules().stream().filter(rule->rule.source()==event.source()&&rule.eventType().equals(event.eventType())
                &&rule.dimension().equals(event.dimension())&&probe.witnessed(courier.entity(),event,rule)).map(CourierSettings.Rule::fingerprint).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    List<CandidateInput> observe(Event event,Set<String> previouslyWitnessedRules) {
        if(!settings.enabled()||event.gameTick()!=tick.getAsLong()||!event.mentionedSubjects().equals(Set.of(event.subject())))return List.of();
        var courier=ledger.courier(event.subject());if(courier==null||courier.blocked()||!probe.available(courier.entity(),event.subject()))return List.of();
        var inputs=new ArrayList<CandidateInput>();
        for(var rule:settings.rules()) {
            if(previouslyWitnessedRules!=null&&!previouslyWitnessedRules.contains(rule.fingerprint()))continue;
            if(!rule.source().equals(event.source())||!rule.eventType().equals(event.eventType())||!rule.dimension().equals(event.dimension())||!probe.witnessed(courier.entity(),event,rule))continue;
            UUID root=UUID.nameUUIDFromBytes((ledger.worldId()+"/"+event.subject()+"/"+event.sourceId()+"/"+rule.id()).getBytes(StandardCharsets.UTF_8));
            if(ledger.evidence(root)!=null)continue;
            if(ledger.evidence().stream().anyMatch(e->e.subject().equals(event.subject())&&e.proof()!=null&&e.proof().ruleId().equals(rule.id())
                    &&(event.gameTick()<e.proof().gameTick()||event.gameTick()-e.proof().gameTick()<rule.cooldownTicks())))continue;
            var proof=new CourierProof(event.sourceId(),event.sourceRevision(),rule.id(),rule.fingerprint(),event.eventType(),event.source(),event.recordedAt(),event.gameTick(),event.dimension(),event.x(),event.y(),event.z(),CourierSettings.hash(event.excerpt()));
            if(!ledger.observe(root,event.subject(),courier.entity(),event.mentionedSubjects(),event.excerpt(),rule.receivers().keySet(),event.audience(),proof))continue;
            var input=new CandidateInput(ledger.worldId(),root,courier.epoch(),rule.fingerprint(),event.excerpt());inputs.add(input);
            if(rule.publication()==CourierSettings.Publication.AUTHORED)ledger.publish(root,rule.authoredText(),rule.epithet());
        }
        return List.copyOf(inputs);
    }
    public boolean publish(Candidate candidate) {
        if(!settings.enabled())return false;var input=candidate.input();var evidence=ledger.evidence(input.rootId());
        var rule=rule(evidence);
        if(rule==null||rule.publication()!=CourierSettings.Publication.CANDIDATE||!fresh(evidence,rule)||!ledger.worldId().equals(input.worldId())
                ||!evidence.epoch().equals(input.epoch())||!rule.fingerprint().equals(input.ruleFingerprint())||!evidence.excerpt().equals(input.excerpt())
                ||!evidence.excerpt().equals(candidate.quotedEvidence()))return false;
        var courier=ledger.courier(evidence.subject());
        return courier!=null&&probe.available(courier.entity(),evidence.subject())&&ledger.publish(input.rootId(),candidate.allegation(),candidate.epithet());
    }
    /** Bounded restart recovery for a future worker. No worker, model call or automatic retry is started here. */
    public List<CandidateInput> pendingCandidates(int limit) {
        if(limit<1||limit>32)throw new IllegalArgumentException("candidate limit 1..32");
        if(!settings.enabled())return List.of();var result=new ArrayList<CandidateInput>();
        var observations=ledger.evidence();int scanned=0;
        for(;scanned<observations.size();scanned++) {
            var e=observations.get(Math.floorMod(candidateCursor+scanned,observations.size()));
            var rule=rule(e);var courier=ledger.courier(e.subject());
            if(rule==null||rule.publication()!=CourierSettings.Publication.CANDIDATE||ledger.claimed(e.id())||!fresh(e,rule)
                    ||courier==null||courier.blocked()||!courier.epoch().equals(e.epoch())||!probe.available(courier.entity(),e.subject()))continue;
            result.add(new CandidateInput(ledger.worldId(),e.id(),e.epoch(),rule.fingerprint(),e.excerpt()));
            if(result.size()==limit){scanned++;break;}
        }
        if(!observations.isEmpty())candidateCursor=Math.floorMod(candidateCursor+scanned,observations.size());
        return List.copyOf(result);
    }
    /** Bounded fair rotation. Missing chunks/receivers defer; expired/changed policy work is discarded. */
    public int deliver() {
        if(!settings.enabled())return 0;var pending=ledger.pending();if(pending.isEmpty())return 0;int delivered=0,examined=Math.min(settings.deliveriesPerTick(),pending.size());
        for(int i=0;i<examined;i++){
            var d=pending.get(Math.floorMod(cursor+i,pending.size()));var e=ledger.evidence(d.rootId());
            if(e==null||e.proof()==null)continue; // Legacy pending work is not upgraded into a witnessed event.
            var rule=rule(e);if(rule==null||!fresh(e,rule)){ledger.discard(d);continue;}
            var courier=ledger.courier(e.subject());
            if(courier!=null&&probe.available(courier.entity(),e.subject())&&probe.receiverAvailable(d.godId())&&ledger.deliver(d))delivered++;
        }
        cursor=Math.floorMod(cursor+examined,pending.size());return delivered;
    }
    public List<RumorLedger.HeardRumor> heard(UUID subject,String god,Set<UUID> audience) {
        var result=new ArrayList<RumorLedger.HeardRumor>();
        for(var heard:ledger.heard(subject,god,audience)){
            var evidence=ledger.evidence(heard.rootId());
            if(evidence.proof()==null){result.add(heard);continue;} // preserve the isolated legacy RUMOR_TEST contract
            if(!settings.enabled())continue;var rule=rule(evidence);if(rule==null||!probe.receiverAvailable(god))continue;
            var reception=rule.receivers().get(god);if(reception==null||reception==CourierSettings.Reception.IGNORE)continue;
            result.add(new RumorLedger.HeardRumor(heard.rootId(),heard.revision(),heard.text(),heard.epithet(),reception.name()));
        }
        return List.copyOf(result);
    }
    Optional<RumorLedger.HeardRumor> heardOne(UUID subject,String god,UUID root,Set<UUID> audience) {
        return ledger.heardOne(subject,god,root,audience).flatMap(heard->{
            var evidence=ledger.evidence(root);if(evidence.proof()==null)return Optional.of(heard);
            if(!settings.enabled()||!probe.receiverAvailable(god))return Optional.empty();
            var rule=rule(evidence);if(rule==null)return Optional.empty();var reception=rule.receivers().get(god);
            return reception==null||reception==CourierSettings.Reception.IGNORE?Optional.empty()
                    :Optional.of(new RumorLedger.HeardRumor(root,heard.revision(),heard.text(),heard.epithet(),reception.name()));
        });
    }
    private CourierSettings.Rule rule(RumorLedger.Evidence e){if(e==null||e.proof()==null)return null;var r=rules.get(e.proof().ruleId());return r!=null&&r.fingerprint().equals(e.proof().ruleFingerprint())?r:null;}
    private boolean fresh(RumorLedger.Evidence e,CourierSettings.Rule r){long now=tick.getAsLong();return now>=e.proof().gameTick()&&now-e.proof().gameTick()<=r.maximumAgeTicks();}
}
