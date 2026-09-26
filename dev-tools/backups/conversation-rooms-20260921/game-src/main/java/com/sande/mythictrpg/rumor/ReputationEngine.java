package com.sande.mythictrpg.rumor;

import java.util.*;

/** Computes a revocable view from CURRENT received evidence. Calling preview does not mutate anything. */
public final class ReputationEngine {
    public record Evidence(UUID worldId,UUID rootId,long revision,UUID subject,String godId,UUID sourceId,String courierRuleId) {}
    public interface Probe { Optional<Evidence> received(UUID subject,String godId,UUID rootId); }
    public record Contribution(UUID sourceId,UUID rootId,long version,int modifier,String policyId) {}
    public enum Status { OFF, READY, UNAVAILABLE }
    public record View(Status status, int baseAffinity, int modifier, int judgementAffinity, long revision, List<Contribution> contributions) {
        public View {contributions=List.copyOf(contributions);}
        public static View inactive(Status status,int base){return new View(status,base,0,base,0,List.of());}
    }
    private final ReputationLedger ledger;private final ReputationSettings settings;private final Probe probe;
    private final Map<String,ReputationSettings.Rule> rules=new HashMap<>();
    private final Map<String,String> fingerprints=new HashMap<>();
    public ReputationEngine(ReputationLedger ledger,ReputationSettings settings,Probe probe) {
        this.ledger=Objects.requireNonNull(ledger);this.settings=settings;this.probe=probe;
        settings.rules().forEach(r->{rules.put(r.id(),r);fingerprints.put(r.id(),r.fingerprint());});
    }
    public ReputationLedger.Result applyApproved(ReputationLedger.Decision decision) {
        if(!settings.enabled()||!ledger.worldId().equals(decision.worldId()))return ReputationLedger.Result.REJECTED;
        var rule=rules.get(decision.policyId());
        if(rule==null||!fingerprints.get(rule.id()).equals(decision.policyFingerprint())||!rule.godId().equals(decision.godId()))return ReputationLedger.Result.REJECTED;
        var old=ledger.find(decision.subject(),decision.godId(),decision.sourceId());
        // Administrative removal/recovery can still remove an existing effect after its rumor is unavailable.
        boolean removes=decision.outcome()==ReputationLedger.Outcome.RETRACTED||decision.outcome()==ReputationLedger.Outcome.RECOVERED;
        if(!removes||old==null) {
            if(!current(decision,rule))return ReputationLedger.Result.REJECTED;
        }
        return ledger.apply(decision);
    }
    private boolean current(ReputationLedger.Decision decision,ReputationSettings.Rule rule) {
        var e=probe.received(decision.subject(),decision.godId(),decision.rootId()).orElse(null);
        return e!=null&&e.worldId().equals(ledger.worldId())&&e.rootId().equals(decision.rootId())&&e.revision()==decision.rumorRevision()
                &&e.subject().equals(decision.subject())&&e.godId().equals(decision.godId())&&e.sourceId().equals(decision.sourceId())&&e.courierRuleId().equals(rule.courierRuleId());
    }
    public View preview(UUID subject,String god,int baseAffinity) {
        if(baseAffinity< -1000||baseAffinity>1000)throw new IllegalArgumentException("base affinity");
        if(!settings.enabled())return View.inactive(Status.OFF,baseAffinity);
        var used=new ArrayList<Contribution>();int positive=0,negative=0;
        for(var entry:ledger.entries()) {
            var d=entry.decision();if(!d.subject().equals(subject)||!d.godId().equals(god)||d.outcome()!=ReputationLedger.Outcome.ACCEPTED||d.directImpact()!=ReputationLedger.DirectImpact.NOT_APPLIED)continue;
            var rule=rules.get(d.policyId());if(rule==null||!fingerprints.get(rule.id()).equals(d.policyFingerprint())||!rule.godId().equals(god)
                    ||baseAffinity<rule.minimumBaseAffinity()||baseAffinity>rule.maximumBaseAffinity()||!current(d,rule))continue;
            if(rule.modifier()>0)positive+=rule.modifier();else negative+=rule.modifier();
            used.add(new Contribution(d.sourceId(),d.rootId(),entry.version(),rule.modifier(),rule.id()));
        }
        int modifier=Math.min(positive,settings.positiveCap())-Math.min(-negative,settings.negativeCap());
        int judged=Math.max(-1000,Math.min(1000,baseAffinity+modifier));
        return new View(Status.READY,baseAffinity,modifier,judged,ledger.revision(),used);
    }
}
