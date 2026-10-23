package com.sande.mythictrpg.skills;

import com.sande.mythictrpg.prototype.PrototypeCombatAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;

/** Continuous horizontal AABB sweep, followed by support checks. Never requests a chunk load. */
final class DashCollision {
    private static final double EPSILON=1.0E-6;
    private DashCollision() { }

    static Vec3 safeDelta(ServerPlayer player,PrototypeCombatAccess.Scope scope,Vec3 requested) {
        if(!finite(requested)||Math.abs(requested.y)>EPSILON||requested.lengthSqr()<EPSILON*EPSILON)return Vec3.ZERO;
        AABB box=player.getBoundingBox();
        AABB sweep=box.expandTowards(requested).inflate(EPSILON);
        if(!loaded(player,sweep))return Vec3.ZERO;
        Vec3 center=box.getCenter();
        double fraction=1;
        for(var shape:player.serverLevel().getBlockCollisions(player,sweep))
            for(AABB obstacle:shape.toAabbs())fraction=Math.min(fraction,firstCollision(center,requested,box,obstacle));
        // Living participants are solid for this movement, including allies. No pushing or teleporting through them.
        for(var entity:player.serverLevel().getEntities(player,sweep,e->e.isAlive()&&!e.isSpectator()
                &&(e instanceof LivingEntity||e.canBeCollidedWith())))
            fraction=Math.min(fraction,firstCollision(center,requested,box,entity.getBoundingBox()));
        double length=requested.length();
        if(fraction<1)fraction=Math.max(0,fraction-EPSILON/length);
        Vec3 candidate=requested.scale(fraction);
        // Support is continuous, too. Even a narrow diagonal unsupported gap must stop this movement.
        double support=supportedFraction(player,box,candidate);
        if(support<1&&candidate.lengthSqr()>EPSILON*EPSILON)
            support=Math.max(0,support-EPSILON/candidate.length());
        candidate=candidate.scale(support);
        // This final bounded check applies authored borders and liquid constraints without stepping up.
        int samples=Math.max(1,(int)Math.ceil(candidate.length()*32));
        double safe=0;
        for(int sample=1;sample<=samples;sample++) {
            double ratio=sample/(double)samples;
            Vec3 delta=candidate.scale(ratio);
            AABB next=box.move(delta);
            if(!loaded(player,next)||!PrototypeCombatAccess.safeMove(player,scope,player.position().add(delta))
                    ||liquid(player,next)||!supported(player,next))break;
            safe=ratio;
        }
        return candidate.scale(safe);
    }

    static boolean supported(ServerPlayer player,AABB box) {
        AABB feet=new AABB(box.minX+EPSILON,box.minY-0.02,box.minZ+EPSILON,
                box.maxX-EPSILON,box.minY,box.maxZ-EPSILON);
        if(!loaded(player,feet))return false;
        for(var ignored:player.serverLevel().getBlockCollisions(player,feet))return true;
        return false;
    }

    private static double supportedFraction(ServerPlayer player,AABB box,Vec3 delta) {
        AABB feet=new AABB(box.minX+EPSILON,box.minY-0.02,box.minZ+EPSILON,
                box.maxX-EPSILON,box.minY,box.maxZ-EPSILON);
        AABB sweep=feet.expandTowards(delta);
        if(!loaded(player,sweep))return 0;
        Vec3 center=box.getCenter();
        double halfX=box.getXsize()/2-EPSILON,halfZ=box.getZsize()/2-EPSILON;
        var intervals=new ArrayList<double[]>();
        for(var shape:player.serverLevel().getBlockCollisions(player,sweep))for(AABB surface:shape.toAabbs()) {
            if(surface.maxY<feet.minY||surface.minY>feet.maxY)continue;
            double[] interval={0,1};
            if(axisInterval(center.x,delta.x,surface.minX-halfX,surface.maxX+halfX,interval)
                    &&axisInterval(center.z,delta.z,surface.minZ-halfZ,surface.maxZ+halfZ,interval))intervals.add(interval);
        }
        intervals.sort(Comparator.comparingDouble(interval->interval[0]));
        double covered=0;
        for(double[] interval:intervals) {
            if(interval[0]>covered+EPSILON)break;
            covered=Math.max(covered,interval[1]);
            if(covered>=1)return 1;
        }
        return Math.max(0,covered);
    }

    private static boolean axisInterval(double start,double delta,double minimum,double maximum,double[] interval) {
        if(Math.abs(delta)<1.0E-12)return start>minimum&&start<maximum;
        double first=(minimum-start)/delta,last=(maximum-start)/delta;
        interval[0]=Math.max(interval[0],Math.min(first,last));
        interval[1]=Math.min(interval[1],Math.max(first,last));
        return interval[0]<=interval[1];
    }

    static boolean liquid(ServerPlayer player,AABB box) {
        if(!loaded(player,box))return true;
        for(BlockPos pos:BlockPos.betweenClosed(BlockPos.containing(box.minX+EPSILON,box.minY+EPSILON,box.minZ+EPSILON),
                BlockPos.containing(box.maxX-EPSILON,box.maxY-EPSILON,box.maxZ-EPSILON)))
            if(!player.serverLevel().getFluidState(pos).isEmpty())return true;
        return false;
    }

    private static double firstCollision(Vec3 center,Vec3 delta,AABB moving,AABB obstacle) {
        double x=moving.getXsize()/2,y=moving.getYsize()/2,z=moving.getZsize()/2;
        AABB expanded=new AABB(obstacle.minX-x+EPSILON,obstacle.minY-y+EPSILON,obstacle.minZ-z+EPSILON,
                obstacle.maxX+x-EPSILON,obstacle.maxY+y-EPSILON,obstacle.maxZ+z-EPSILON);
        if(expanded.contains(center))return 0;
        return expanded.clip(center,center.add(delta)).map(hit->Math.min(1,center.distanceTo(hit)/delta.length())).orElse(1.0);
    }

    private static boolean loaded(ServerPlayer player,AABB box) {
        if(box.minY<player.level().getMinBuildHeight()||box.maxY>player.level().getMaxBuildHeight())return false;
        for(int x=(int)Math.floor(box.minX)>>4;x<=((int)Math.floor(box.maxX)>>4);x++)
            for(int z=(int)Math.floor(box.minZ)>>4;z<=((int)Math.floor(box.maxZ)>>4);z++)
                if(!player.serverLevel().getChunkSource().hasChunk(x,z))return false;
        return true;
    }
    private static boolean finite(Vec3 value){return Double.isFinite(value.x)&&Double.isFinite(value.y)&&Double.isFinite(value.z);}
}
