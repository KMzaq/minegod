package com.sande.mythictrpg.rumor;

import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;

/** Authored judgment-only modifiers. No operational values or automatic acceptance are supplied. */
public record ReputationSettings(int schemaVersion, boolean enabled, int negativeCap, int positiveCap, List<Rule> rules) {
    public record Rule(String id, String courierRuleId, String godId, int modifier, int minimumBaseAffinity, int maximumBaseAffinity) {
        public Rule {
            CourierSettings.identifier(id); CourierSettings.identifier(courierRuleId); CourierSettings.identifier(godId);
            if(modifier< -1000||modifier>1000||minimumBaseAffinity< -1000||maximumBaseAffinity>1000||minimumBaseAffinity>maximumBaseAffinity)
                throw new IllegalArgumentException("Reputation rule range");
        }
        public String fingerprint(){return CourierSettings.hash(new Gson().toJson(this));}
    }
    public static final ReputationSettings OFF=new ReputationSettings(1,false,0,0,List.of());
    public ReputationSettings {
        rules=List.copyOf(rules);
        if(schemaVersion!=1||negativeCap<0||negativeCap>1000||positiveCap<0||positiveCap>1000||rules.size()>128
                ||rules.stream().map(Rule::id).distinct().count()!=rules.size()
                ||enabled&&rules.isEmpty())throw new IllegalArgumentException("Reputation settings");
    }
    public static ReputationSettings load(Path path){try{
        if(!Files.isRegularFile(path)||Files.size(path)>65536)return OFF;
        var settings=new Gson().fromJson(Files.readString(path),ReputationSettings.class);return settings==null?OFF:settings;
    }catch(Exception invalid){return OFF;}}
}
