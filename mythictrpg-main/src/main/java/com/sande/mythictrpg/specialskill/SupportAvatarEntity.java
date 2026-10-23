package com.sande.mythictrpg.specialskill;

import com.sande.mythictrpg.godavatar.GodAvatarDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import java.util.Optional;

/** Visual-only, nonpersistent manifestation. It owns no quests, rewards, AI identity or raid boss authority. */
public final class SupportAvatarEntity extends PathfinderMob {
    private static final EntityDataAccessor<Integer> TEXTURE=SynchedEntityData.defineId(SupportAvatarEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> SLIM=SynchedEntityData.defineId(SupportAvatarEntity.class,EntityDataSerializers.BOOLEAN);
    private ResourceLocation providerGod;
    public SupportAvatarEntity(EntityType<? extends SupportAvatarEntity> type,Level level) { super(type,level); setNoAi(true);setNoGravity(true);setInvulnerable(true); }
    public static AttributeSupplier.Builder attributes() { return Mob.createMobAttributes().add(Attributes.MOVEMENT_SPEED,0).add(Attributes.SCALE,1); }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { super.defineSynchedData(builder);builder.define(TEXTURE,0);builder.define(SLIM,false); }
    public int textureVariant() { return entityData.get(TEXTURE); }
    public boolean slimModel() { return entityData.get(SLIM); }
    public Optional<ResourceLocation> providerGodId() { return Optional.ofNullable(providerGod); }
    public void initialize(ResourceLocation godId,Component name,GodAvatarDefinition.Appearance appearance) {
        providerGod=godId;setCustomName(name.copy());setCustomNameVisible(true);
        entityData.set(TEXTURE,appearance.textureVariant());entityData.set(SLIM,appearance.model()==GodAvatarDefinition.SkinModel.SLIM);
        getAttribute(Attributes.SCALE).setBaseValue(appearance.scale());
    }
    @Override public void tick() { super.tick();if(!level().isClientSide&&tickCount>=40)discard(); }
    @Override public boolean hurt(DamageSource source,float amount) { return false; }
    @Override protected InteractionResult mobInteract(Player player,InteractionHand hand) { return InteractionResult.FAIL; }
    @Override public boolean isPickable() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override protected void registerGoals() { }
    @Override public void addAdditionalSaveData(CompoundTag tag) { super.addAdditionalSaveData(tag); }
    @Override public void readAdditionalSaveData(CompoundTag tag) { super.readAdditionalSaveData(tag);if(!level().isClientSide)discard(); }
}
