package com.sande.mythictrpg.ai.experiencecontract;
import com.sande.mythai.response.memory.ObservedExperienceSummary;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** Test-only issuer in the game's package; never shipped as production game authority. */
public final class ObservedSummaryFixture {
    public static void run() {
        UUID event=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();var active=new AtomicBoolean(true);
        var one=new ExperienceView.Event(a,event,1,"DIRECT_WATCH","MATURE_CROP_REMOVED","minecraft:wheat","BLOCK_REMOVED_NOT_ITEM_ACQUISITION","game_tick=1");
        var duplicate=new ExperienceView.Event(b,event,1,"DIRECT_WATCH","MATURE_CROP_REMOVED","minecraft:wheat","BLOCK_REMOVED_NOT_ITEM_ACQUISITION","game_tick=1");
        var lease=new ExperienceLease(new ExperienceView(1,true,"READY",List.of(one,duplicate),ExperienceView.Relationship.UNKNOWN),ids->active.get()&&ids.equals(Set.of(a,b)));
        var summary=ObservedExperienceSummary.summarize(lease);
        if(summary.groups().size()!=1||summary.groups().getFirst().distinctEvents()!=1||!summary.current()||!summary.references().equals(Set.of(a,b)))throw new AssertionError("same event not double counted, retain all provenance");
        active.set(false);if(summary.current())throw new AssertionError("revocation applies to aggregate");
        if(!summary.coverage().contains("NOT_WHOLE_DAY_OR_ITEM_QUANTITY"))throw new AssertionError("bounded observation not complete day");
        if(!ObservedExperienceSummary.summarize(ExperienceLease.unavailable("OFF")).coverage().equals("UNAVAILABLE_NOT_ZERO"))throw new AssertionError("no permission not zero activity");
        System.out.println("ObservedSummaryFixture: PASS (4 checks)");
    }
}
