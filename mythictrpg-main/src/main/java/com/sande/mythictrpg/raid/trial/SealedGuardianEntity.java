package com.sande.mythictrpg.raid.trial;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.level.Level;

/** Test encounter body. The raid controller owns movement orders, attack hits and rewards. */
public final class SealedGuardianEntity extends PathfinderMob {
    public static final int IDLE = 0, SWEEP = 1, SLAM = 2, BOMBARD = 3,
            SHIELD = 4, STAGGER = 5, RAGE = 6;
    private static final EntityDataAccessor<Integer> POSE = dataInt();
    private static final EntityDataAccessor<Integer> POSE_ELAPSED = dataInt();
    private static final EntityDataAccessor<Integer> POSE_DURATION = dataInt();
    private static final EntityDataAccessor<Integer> PHASE = dataInt();
    private static final EntityDataAccessor<Boolean> SHIELDED = SynchedEntityData.defineId(
            SealedGuardianEntity.class, EntityDataSerializers.BOOLEAN);

    private static EntityDataAccessor<Integer> dataInt() {
        return SynchedEntityData.defineId(SealedGuardianEntity.class, EntityDataSerializers.INT);
    }

    public SealedGuardianEntity(EntityType<? extends SealedGuardianEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 480)
                .add(Attributes.MOVEMENT_SPEED, .21).add(Attributes.FOLLOW_RANGE, 64)
                .add(Attributes.ARMOR, 8).add(Attributes.KNOCKBACK_RESISTANCE, 1)
                .add(Attributes.ATTACK_DAMAGE, 0);
    }

    @Override protected void registerGoals() {
        // No melee, retaliation, targeting or wandering goal may produce an unowned encounter hit.
        goalSelector.addGoal(0, new FloatGoal(this));
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(POSE, IDLE);
        builder.define(POSE_ELAPSED, 0);
        builder.define(POSE_DURATION, 1);
        builder.define(PHASE, 1);
        builder.define(SHIELDED, false);
    }

    public int pose() { return entityData.get(POSE); }
    public int poseElapsed() { return entityData.get(POSE_ELAPSED); }
    public int poseDuration() { return entityData.get(POSE_DURATION); }
    public int phase() { return entityData.get(PHASE); }
    public boolean shielded() { return entityData.get(SHIELDED); }

    /** Cosmetic snapshot only; this never schedules damage or grants an encounter reward. */
    public void setCombatVisual(int pose, int elapsed, int duration, int phase, boolean shielded) {
        if (!(level() instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread())
            throw new IllegalStateException("Guardian visual mutation requires the server thread");
        if (pose < IDLE || pose > RAGE) throw new IllegalArgumentException("Unknown guardian pose: " + pose);
        applyVisual(pose, elapsed, duration, phase, shielded);
    }

    private void applyVisual(int pose, int elapsed, int duration, int phase, boolean shielded) {
        int boundedDuration = Mth.clamp(duration, 1, 1200);
        entityData.set(POSE, Mth.clamp(pose, IDLE, RAGE));
        entityData.set(POSE_ELAPSED, Mth.clamp(elapsed, 0, boundedDuration));
        entityData.set(POSE_DURATION, boundedDuration);
        entityData.set(PHASE, Mth.clamp(phase, 0, 3));
        entityData.set(SHIELDED, shielded);
    }

    @Override public boolean hurt(DamageSource source, float amount) {
        return !shielded() && super.hurt(source, amount);
    }

    @Override public boolean removeWhenFarAway(double distance) { return false; }

    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("TrialVisualPose", pose());
        tag.putInt("TrialVisualElapsed", poseElapsed());
        tag.putInt("TrialVisualDuration", poseDuration());
        tag.putInt("TrialVisualPhase", phase());
        tag.putBoolean("TrialVisualShielded", shielded());
    }

    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        applyVisual(tag.getInt("TrialVisualPose"), tag.getInt("TrialVisualElapsed"),
                tag.getInt("TrialVisualDuration"), tag.contains("TrialVisualPhase")
                        ? tag.getInt("TrialVisualPhase") : 1, tag.getBoolean("TrialVisualShielded"));
    }
}
