package com.sande.mythictrpg.gameplay.ledger.detail;

import java.util.*;

/** Actual endpoints only. A sample interval is not a route, distance, or a teleport inference. */
public final class MovementSamples {
    public record Point(String dimension, double x,double y,double z,long tick) {
        public Point { if(dimension==null || tick<0 || !Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)) throw new IllegalArgumentException("point"); }
        public boolean samePosition(Point p) { return dimension.equals(p.dimension) && x==p.x && y==p.y && z==p.z; }
        public String coordinates() { return x+","+y+","+z; }
    }
    public record Sample(Point point, long elapsedTicks, String coverage) {}
    private final Map<UUID,Point> prior=new HashMap<>();
    public Optional<Sample> sample(UUID player,Point point,int interval) {
        if(interval<=0) { prior.remove(player); return Optional.empty(); }
        var previous=prior.get(player);
        if(previous!=null && point.tick()>=previous.tick() && point.tick()-previous.tick()<interval
                && point.dimension().equals(previous.dimension())) return Optional.empty();
        prior.put(player,point);
        String coverage=previous==null?"START_OR_RECONNECT":!point.dimension().equals(previous.dimension())?"DIMENSION_BOUNDARY":
                point.tick()<previous.tick()?"CLOCK_RESET":point.tick()-previous.tick()>interval?"SAMPLING_GAP":
                point.samePosition(previous)?"UNCHANGED_ENDPOINTS_NOT_CONTINUOUS_STILLNESS":"SAMPLED_ENDPOINTS_NOT_PATH";
        return Optional.of(new Sample(point,previous==null?0:Math.max(0,point.tick()-previous.tick()),coverage));
    }
    public void forget(UUID player) { prior.remove(player); }
    public void clear() { prior.clear(); }
}
