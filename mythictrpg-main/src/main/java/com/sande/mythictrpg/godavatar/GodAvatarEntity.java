package com.sande.mythictrpg.godavatar;

import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.godavatar.activity.NpcActivityRuntime;
import com.sande.mythictrpg.godavatar.activity.NpcSparring;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.UUID;

/** Server-only God ID and orders; client metadata contains only an opaque skin number and body type. */
public final class GodAvatarEntity extends PathfinderMob {
    private static final EntityDataAccessor<Integer> TEXTURE_VARIANT = SynchedEntityData.defineId(
            GodAvatarEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> SLIM_MODEL = SynchedEntityData.defineId(
            GodAvatarEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> ACTIVITY_POSE = SynchedEntityData.defineId(
            GodAvatarEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<ItemStack> ACTIVITY_PROP = SynchedEntityData.defineId(
            GodAvatarEntity.class, EntityDataSerializers.ITEM_STACK);
    private final SimpleContainer activityInventory = new SimpleContainer(27);
    private boolean activityInventoryDropped;
    private ResourceLocation godId;
    private BlockPos moveTarget;
    private UUID visitTarget;
    private UUID raidTargetOwner;
    private UUID homeStructure, homeOwner;
    private UUID lastHomeOwner;
    private long homeStatusTime;
    private long homeDeadline, nextVisitDecision, orderRevision;
    private String homeVisitStatus = "NONE";
    private long appliedAvatarGeneration = -1;
    private long appliedGodGeneration = -1;

    public GodAvatarEntity(EntityType<? extends GodAvatarEntity> type, Level level) { super(type, level); }

    public static AttributeSupplier.Builder createAttributes() {
        // Registry placeholders only; an authored avatar definition is required before placement.
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 2)
                .add(Attributes.ARMOR, 0)
                .add(Attributes.FOLLOW_RANGE, 16)
                .add(Attributes.SCALE, 1);
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(TEXTURE_VARIANT, 0);
        builder.define(SLIM_MODEL, false);
        builder.define(ACTIVITY_POSE, "NONE");
        builder.define(ACTIVITY_PROP, ItemStack.EMPTY);
    }

    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1, true) {
            @Override public boolean canUse() { return canFight() && super.canUse(); }
            @Override public boolean canContinueToUse() { return canFight() && super.canContinueToUse(); }
        });
        goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 1) {
            @Override public boolean canUse() {
                return activityPose().equals("NONE") && !NpcActivityRuntime.active(GodAvatarEntity.this)
                        && !NpcSparring.INSTANCE.active(GodAvatarEntity.this)
                        && !NpcSparring.INSTANCE.awaiting(GodAvatarEntity.this)
                        && moveTarget == null && visitTarget == null && currentDefinition()
                        .map(value -> value.movement().wander()).orElse(false) && super.canUse();
            }
        });
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this) {
            @Override public boolean canUse() {
                return !NpcSparring.INSTANCE.active(GodAvatarEntity.this) && currentDefinition().map(value -> value.combat().enabled()
                        && value.combat().retaliate()).orElse(false) && super.canUse();
            }
        });
    }

    /** Never synced to clients or used as a player-visible name. */
    public Optional<ResourceLocation> godId() { return Optional.ofNullable(godId); }
    public int textureVariant() { return entityData.get(TEXTURE_VARIANT); }
    public boolean slimModel() { return entityData.get(SLIM_MODEL); }
    /** Real server inventory. Visual props are never inserted into this container. */
    public SimpleContainer activityInventory() { return activityInventory; }
    public String activityPose() { return entityData.get(ACTIVITY_POSE); }
    public ItemStack activityProp() { return entityData.get(ACTIVITY_PROP).copy(); }
    public boolean activitySeated() { return activityPose().equals("SIT") || activityPose().equals("READ"); }
    public void setActivityVisual(String pose, ItemStack prop) {
        requireActivityServer();
        if (!java.util.Set.of("NONE", "OBSERVE", "SIT", "READ", "EAT", "DRINK", "WORK", "TRAIN",
                "RITUAL", "PLAY", "PERFORM", "TALK").contains(pose)) throw new IllegalArgumentException("Unknown activity pose");
        entityData.set(ACTIVITY_POSE, pose);
        // Cosmetic network DTO only: never disclose book text, names, inventory or arbitrary custom NBT.
        ItemStack cosmetic = prop == null || prop.isEmpty() ? ItemStack.EMPTY : new ItemStack(prop.getItem());
        if (!cosmetic.isEmpty()) {
            if (prop.has(DataComponents.CUSTOM_MODEL_DATA)) cosmetic.set(DataComponents.CUSTOM_MODEL_DATA, prop.get(DataComponents.CUSTOM_MODEL_DATA));
            if (prop.has(DataComponents.DYED_COLOR)) cosmetic.set(DataComponents.DYED_COLOR, prop.get(DataComponents.DYED_COLOR));
        }
        entityData.set(ACTIVITY_PROP, cosmetic);
    }
    public void clearActivityVisual() { setActivityVisual("NONE", ItemStack.EMPTY); }
    private void requireActivityServer() {
        if (!(level() instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread())
            throw new IllegalStateException("Activity mutation requires server thread");
    }
    @Override protected EntityDimensions getDefaultDimensions(Pose pose) {
        EntityDimensions base = super.getDefaultDimensions(pose);
        return entityData != null && entityData.get(ACTIVITY_POSE) != null && activitySeated() ? base.scale(1, .65F) : base;
    }
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (ACTIVITY_POSE.equals(key)) refreshDimensions();
    }
    private void interruptActivity(String reason) {
        NpcSparring.INSTANCE.interrupt(this, reason);
        NpcActivityRuntime.interrupt(this, reason);
    }

    void bind(ResourceLocation id) {
        if (!(level() instanceof ServerLevel)) throw new IllegalStateException("God avatar binding requires server");
        if (godId != null && !godId.equals(id)) throw new IllegalStateException("God avatar ID is immutable");
        godId = id;
        appliedAvatarGeneration = -1;
        refreshDefinition();
        setPersistenceRequired();
        setCustomName(null);
        setCustomNameVisible(false);
    }

    public Optional<GodAvatarDefinition> currentDefinition() {
        if (godId == null || GodDefinitionManager.INSTANCE.find(godId).isEmpty()) return Optional.empty();
        return GodAvatarDefinitionManager.INSTANCE.find(godId);
    }

    public boolean hasAuthoritativeBinding() {
        return level() instanceof ServerLevel serverLevel && godId != null
                && GodAvatarRegistryState.get(serverLevel.getServer()).owns(godId, getUUID());
    }

    @Override public Component getName() {
        return Component.translatable("display.mythictrpg.unknown_god");
    }

    @Override protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (player instanceof ServerPlayer serverPlayer) GodAvatarService.INSTANCE.interact(this, serverPlayer);
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override public boolean hurt(DamageSource source, float amount) {
        if (!level().isClientSide) {
            if (NpcSparring.INSTANCE.avatarDamage(this, source)) return false;
            if (amount > 0) NpcActivityRuntime.interrupt(this, "DAMAGE");
        }
        if (!level().isClientSide && !currentDefinition().map(value -> value.combat().damageable()).orElse(false))
            return false;
        return super.hurt(source, amount);
    }

    @Override public void remove(Entity.RemovalReason reason) {
        if (!level().isClientSide) interruptActivity(reason == RemovalReason.UNLOADED_TO_CHUNK
                || reason == RemovalReason.UNLOADED_WITH_PLAYER ? "UNLOADED" : "REMOVED");
        if (reason == Entity.RemovalReason.KILLED || reason == Entity.RemovalReason.DISCARDED)
            releaseBinding();
        super.remove(reason);
    }

    private void releaseBinding() {
        if (godId != null && level() instanceof ServerLevel serverLevel)
            GodAvatarRegistryState.get(serverLevel.getServer()).release(godId, getUUID());
    }

    @Override public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel serverLevel)) return;
        if (tickCount % 20 != 0) return;
        refreshDefinition();
        var definition = currentDefinition().orElse(null);
        if (!hasAuthoritativeBinding() || definition == null) {
            interruptActivity("BINDING_LOST");
            if (homeStructure != null) finishHomeVisit("CANCELLED");
            getNavigation().stop();
            setTarget(null);
            raidTargetOwner = null;
            return;
        }
        if (homeStructure != null && !GodHomeVisitService.INSTANCE.travelCurrent(this)) {
            finishHomeVisit("CANCELLED");
        }
        if (raidTargetOwner != null && (godId == null
                || !GodAvatarRegistryState.get(serverLevel.getServer()).raidOwner(godId)
                        .filter(raidTargetOwner::equals).isPresent()
                || !definition.combat().raidControl())) {
            setTarget(null);
            raidTargetOwner = null;
        }
        if (getTarget() != null && (!definition.combat().enabled() || !getTarget().isAlive()
                || getTarget().level() != level()
                || distanceToSqr(getTarget()) > squared(definition.stats().followRange()))) {
            setTarget(null);
            raidTargetOwner = null;
        }
        followOrder(serverLevel, definition);
    }

    private void refreshDefinition() {
        if (level().isClientSide) return;
        long avatarGeneration = GodAvatarDefinitionManager.INSTANCE.generation();
        long godGeneration = GodDefinitionManager.INSTANCE.generation();
        if (appliedAvatarGeneration == avatarGeneration && appliedGodGeneration == godGeneration) return;
        if (appliedAvatarGeneration >= 0) interruptActivity("DEFINITION_RELOAD");
        appliedAvatarGeneration = avatarGeneration;
        appliedGodGeneration = godGeneration;
        var definition = currentDefinition().orElse(null);
        if (definition == null) {
            entityData.set(TEXTURE_VARIANT, 0);
            entityData.set(SLIM_MODEL, false);
            getAttribute(Attributes.SCALE).setBaseValue(1);
            getNavigation().stop();
            setTarget(null);
            return;
        }
        entityData.set(TEXTURE_VARIANT, definition.appearance().textureVariant());
        entityData.set(SLIM_MODEL, definition.appearance().model() == GodAvatarDefinition.SkinModel.SLIM);
        getAttribute(Attributes.SCALE).setBaseValue(definition.appearance().scale());
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(definition.stats().maxHealth());
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(definition.stats().movementSpeed());
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(definition.stats().attackDamage());
        getAttribute(Attributes.ARMOR).setBaseValue(definition.stats().armor());
        getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(definition.stats().followRange());
        setHealth(Math.min(getHealth(), getMaxHealth()));
        if (!definition.combat().enabled()) setTarget(null);
        if (!definition.movement().enabled()) clearOrder();
    }

    private boolean canFight() {
        return !NpcSparring.INSTANCE.active(this) && hasAuthoritativeBinding() && currentDefinition()
                .map(value -> value.combat().enabled()).orElse(false);
    }

    void moveTo(BlockPos target) {
        interruptActivity("MOVE_ORDER");
        abandonHomeVisit(); orderRevision++;
        moveTarget = target.immutable();
        visitTarget = null;
    }

    void visit(UUID playerId) {
        interruptActivity("VISIT_ORDER");
        abandonHomeVisit(); orderRevision++;
        visitTarget = playerId;
        moveTarget = null;
    }

    void directRaidTarget(ServerPlayer player, UUID attemptId) {
        interruptActivity("RAID_ORDER");
        if (homeStructure != null) finishHomeVisit("CANCELLED");
        orderRevision++;
        raidTargetOwner = attemptId;
        setTarget(player);
    }

    void clearRaidTarget() {
        raidTargetOwner = null;
        setTarget(null);
    }

    void clearOrder() {
        interruptActivity("CLEAR_ORDER");
        if (moveTarget != null || visitTarget != null) orderRevision++;
        abandonHomeVisit();
        moveTarget = null;
        visitTarget = null;
        getNavigation().stop();
    }

    public boolean busyForVisit() { return moveTarget != null || visitTarget != null || getTarget() != null || raidTargetOwner != null
            || NpcActivityRuntime.active(this) || NpcSparring.INSTANCE.active(this) || NpcSparring.INSTANCE.awaiting(this); }
    public long orderRevision() { return orderRevision; }
    public long nextVisitDecision() { return nextVisitDecision; }
    void deferVisitDecision(long until) { nextVisitDecision = Math.max(nextVisitDecision, until); }
    UUID homeStructure() { return homeStructure; }
    UUID homeOwner() { return homeOwner; }
    long homeDeadline() { return homeDeadline; }
    BlockPos homeDestination() { return moveTarget; }
    public String homeVisitStatus() { return homeVisitStatus; }
    UUID lastHomeOwner() { return lastHomeOwner; }
    long homeStatusTime() { return homeStatusTime; }
    void startHomeVisit(UUID structure, UUID owner, BlockPos destination, long deadline) {
        moveTo(destination); homeStructure = structure; homeOwner = owner; homeDeadline = deadline;
        lastHomeOwner = owner; homeVisitStatus = "TRAVELLING"; homeStatusTime = getServer().overworld().getGameTime();
    }
    private void abandonHomeVisit() {
        if (homeStructure != null) { homeVisitStatus = "CANCELLED"; homeStatusTime = getServer().overworld().getGameTime(); }
        homeStructure = null; homeOwner = null; homeDeadline = 0;
    }
    private void finishHomeVisit(String status) {
        UUID owner = homeOwner;
        clearOrder(); homeVisitStatus = status;
        if (owner != null && getServer() != null)
            GodHomeVisitService.INSTANCE.notifyOutcome(this, owner, status);
    }

    private void followOrder(ServerLevel level, GodAvatarDefinition definition) {
        if (!definition.movement().enabled()) {
            if (moveTarget != null || visitTarget != null) clearOrder();
            return;
        }
        if (visitTarget != null) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(visitTarget);
            if (player == null || player.level() != level || !player.isAlive()
                    || distanceToSqr(player) > squared(definition.movement().maxVisitDistance())) {
                clearOrder();
                return;
            }
            if (distanceToSqr(player) <= 4) { clearOrder(); return; }
            getNavigation().moveTo(player, definition.movement().navigationSpeed());
        } else if (moveTarget != null) {
            if (distanceToSqr(moveTarget.getX() + 0.5, moveTarget.getY(), moveTarget.getZ() + 0.5) <= 2.25
                    && (homeStructure == null || GodHomeVisitService.INSTANCE.insideDestination(this))) {
                if (homeStructure != null) finishHomeVisit("ARRIVED"); else clearOrder();
                return;
            }
            if (homeStructure != null && !GodHomeVisitService.navigationChunksLoaded(this)) { finishHomeVisit("NO_PATH"); return; }
            boolean moving = getNavigation().moveTo(moveTarget.getX() + 0.5, moveTarget.getY(), moveTarget.getZ() + 0.5,
                    definition.movement().navigationSpeed());
            if (!moving && homeStructure != null) finishHomeVisit("NO_PATH");
        }
    }

    private static double squared(double value) { return value * value; }

    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        CompoundTag inventory = new CompoundTag();
        ContainerHelper.saveAllItems(inventory, activityInventory.getItems(), registryAccess());
        tag.put("ActivityInventory", inventory);
        tag.putBoolean("ActivityInventoryDropped", activityInventoryDropped);
        if (godId != null) tag.putString("GodId", godId.toString());
        if (moveTarget != null) tag.putLong("MoveTarget", moveTarget.asLong());
        if (visitTarget != null) tag.putUUID("VisitTarget", visitTarget);
        tag.putLong("NextHomeVisitDecision", nextVisitDecision);
        tag.putString("HomeVisitStatus", homeVisitStatus);
        if (lastHomeOwner != null) tag.putUUID("LastHomeVisitOwner", lastHomeOwner);
        tag.putLong("HomeVisitStatusTime", homeStatusTime);
        if (homeStructure != null) {
            tag.putUUID("HomeVisitStructure", homeStructure); tag.putUUID("HomeVisitOwner", homeOwner);
            tag.putLong("HomeVisitDeadline", homeDeadline);
        }
    }

    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        activityInventory.clearContent();
        activityInventoryDropped = tag.getBoolean("ActivityInventoryDropped");
        if (!activityInventoryDropped && tag.contains("ActivityInventory", Tag.TAG_COMPOUND))
            ContainerHelper.loadAllItems(tag.getCompound("ActivityInventory"), activityInventory.getItems(), registryAccess());
        entityData.set(ACTIVITY_POSE, "NONE");
        entityData.set(ACTIVITY_PROP, ItemStack.EMPTY);
        godId = ResourceLocation.tryParse(tag.getString("GodId"));
        moveTarget = tag.contains("MoveTarget", Tag.TAG_ANY_NUMERIC) ? BlockPos.of(tag.getLong("MoveTarget")) : null;
        visitTarget = tag.hasUUID("VisitTarget") ? tag.getUUID("VisitTarget") : null;
        nextVisitDecision = Math.max(0, tag.getLong("NextHomeVisitDecision"));
        lastHomeOwner = tag.hasUUID("LastHomeVisitOwner") ? tag.getUUID("LastHomeVisitOwner") : null;
        homeStatusTime = Math.max(0, tag.getLong("HomeVisitStatusTime"));
        homeVisitStatus = tag.getString("HomeVisitStatus");
        if (!java.util.Set.of("NONE", "TRAVELLING", "ARRIVED", "CANCELLED", "NO_PATH").contains(homeVisitStatus)) homeVisitStatus = "NONE";
        homeStructure = null; homeOwner = null; homeDeadline = 0; orderRevision++;
        if (tag.contains("HomeVisitStructure")) {
            if (tag.hasUUID("HomeVisitStructure") && tag.hasUUID("HomeVisitOwner")
                    && tag.contains("HomeVisitDeadline", Tag.TAG_LONG) && moveTarget != null && visitTarget == null) {
                homeStructure = tag.getUUID("HomeVisitStructure"); homeOwner = tag.getUUID("HomeVisitOwner");
                homeDeadline = tag.getLong("HomeVisitDeadline"); homeVisitStatus = "TRAVELLING";
            } else { moveTarget = null; visitTarget = null; homeVisitStatus = "CANCELLED"; }
        }
        appliedAvatarGeneration = -1;
        appliedGodGeneration = -1;
        setCustomName(null);
        setCustomNameVisible(false);
        setPersistenceRequired();
    }

    @Override protected void dropEquipment() {
        super.dropEquipment();
        if (activityInventoryDropped || level().isClientSide) return;
        activityInventoryDropped = true;
        for (int slot = 0; slot < activityInventory.getContainerSize(); slot++) {
            ItemStack stack = activityInventory.removeItemNoUpdate(slot);
            if (!stack.isEmpty()) spawnAtLocation(stack);
        }
    }
}
