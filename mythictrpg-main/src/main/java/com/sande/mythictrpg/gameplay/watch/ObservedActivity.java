package com.sande.mythictrpg.gameplay.watch;

import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import static com.sande.mythictrpg.gameplay.watch.WatchContract.*;
import java.util.*;

/** Bounded, volatile visible samples, not deltas of omniscient vanilla stats. No hidden backfill. */
public final class ObservedActivity {
    private record Key(UUID watch,ActionRecord.Type action,String subject,String dimension) { }
    public record Summary(Watch watch,ActionRecord.Draft last,long firstTick,int count) { }
    private final Map<Key,Summary> pending=new LinkedHashMap<>();
    private record Cursor(UUID session,long order) { }
    private final Map<UUID,Cursor> seen=new HashMap<>();
    public boolean accept(Watch watch,ActionRecord.Draft event,Scene scene) {
        var policy=watch.approval().policy();
        boolean permitted=watch.state()==State.ACTIVE && watch.approval().key().playerId().equals(event.actorId())
                && !scene.occluded()&&!scene.privateScene()&&!scene.blockedGods().contains(policy.godId())
                && scene.powers().contains(policy.power())&&scene.domains().contains(policy.domain())
                && policy.places().stream().anyMatch(p->p.contains(event));
        if(!permitted){discard(watch.id());return false;}
        var cursor=seen.get(watch.id());
        if(cursor!=null) {
            if(!cursor.session().equals(event.captureSession())) {discard(watch.id());return false;}
            // The game thread assigns strictly increasing orders; retries/reordering never add samples.
            if(event.captureOrder()<=cursor.order())return true;
        } else if(seen.size()>=256)return false;
        var key=new Key(watch.id(),event.type(),event.subject().typeId(),event.dimensionId());
        var before=pending.get(key);
        if(before==null && pending.size()>=256)return false;
        seen.put(watch.id(),new Cursor(event.captureSession(),event.captureOrder()));
        pending.put(key,new Summary(watch,event,before==null?event.gameTick():before.firstTick(),before==null?1:Math.min(1_000_000,before.count()+1)));
        return true;
    }
    public void discard(UUID watch) { pending.keySet().removeIf(k->k.watch().equals(watch)); seen.remove(watch); }
    public void clear() { pending.clear(); seen.clear(); }
    public List<Summary> due(long now,int window) {
        if(window<=0)return List.of();
        var result=new ArrayList<Summary>();var it=pending.values().iterator();
        while(it.hasNext()){var s=it.next();if(now<s.firstTick()){it.remove();continue;}
            if(now-s.firstTick()>=window){result.add(s);it.remove();}}
        return List.copyOf(result);
    }
}
