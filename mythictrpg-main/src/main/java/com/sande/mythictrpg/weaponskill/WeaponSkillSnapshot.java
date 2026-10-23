package com.sande.mythictrpg.weaponskill;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import java.util.Objects;

/** Self-only server view. The held variant prevents stale UI from claiming a newly selected weapon. */
public record WeaponSkillSnapshot(double mana, double maximumMana, int selectedSlot, ItemStack held,
        @Nullable ResourceLocation skillId, String skillName, int manaCost, int remainingTicks, boolean actionLocked) implements CustomPacketPayload {
    /** Legacy constructor compatibility only. Live network snapshots send the authoritative maximum explicitly. */
    public static final int MAX_MANA = 200;
    public static final Type<WeaponSkillSnapshot> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "weapon_skill_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WeaponSkillSnapshot> CODEC =
            StreamCodec.ofMember(WeaponSkillSnapshot::write, WeaponSkillSnapshot::read);

    public WeaponSkillSnapshot(double mana,int selectedSlot,ItemStack held,@Nullable ResourceLocation skillId,String skillName,int manaCost,int remainingTicks) {
        this(mana,MAX_MANA,selectedSlot,held,skillId,skillName,manaCost,remainingTicks,false);
    }
    public WeaponSkillSnapshot(double mana,double maximumMana,int selectedSlot,ItemStack held,@Nullable ResourceLocation skillId,String skillName,int manaCost,int remainingTicks) {
        this(mana,maximumMana,selectedSlot,held,skillId,skillName,manaCost,remainingTicks,false);
    }
    public WeaponSkillSnapshot {
        if (!Double.isFinite(maximumMana)||maximumMana<1||maximumMana>ManaAccount.AUTHORING_MAX_MANA
                || !Double.isFinite(mana) || mana < 0 || mana > maximumMana
                || selectedSlot < 0 || selectedSlot > 8 || manaCost < 0 || manaCost > ManaAccount.AUTHORING_MAX_MANA || remainingTicks < 0)
            throw new IllegalArgumentException("Invalid weapon skill snapshot values");
        held = Objects.requireNonNull(held, "held").copyWithCount(held.isEmpty() ? 0 : 1);
        Objects.requireNonNull(skillName, "skillName");
        if (skillName.length() > 128 || skillId != null && (held.isEmpty() || skillName.isBlank()))
            throw new IllegalArgumentException("Invalid weapon skill snapshot identity");
        if (skillId == null && (!skillName.isEmpty() || manaCost != 0 || remainingTicks != 0))
            throw new IllegalArgumentException("Unbound weapon must have no skill details");
    }
    @Override public ItemStack held() { return held.copy(); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** A client presentation gate, never a substitute for the server's acquisition authentication. */
    public boolean matches(int actualSlot, ItemStack actualHeld) {
        return skillId != null && selectedSlot == actualSlot && !actualHeld.isEmpty()
                && ItemStack.isSameItemSameComponents(held, actualHeld);
    }
    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeDouble(mana); buffer.writeDouble(maximumMana); buffer.writeVarInt(selectedSlot);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, held);
        buffer.writeBoolean(skillId != null);
        if (skillId != null) buffer.writeResourceLocation(skillId);
        buffer.writeUtf(skillName, 128); buffer.writeVarInt(manaCost); buffer.writeVarInt(remainingTicks);
        buffer.writeBoolean(actionLocked);
    }
    private static WeaponSkillSnapshot read(RegistryFriendlyByteBuf buffer) {
        double mana = buffer.readDouble(), maximum = buffer.readDouble(); int slot = buffer.readVarInt();
        ItemStack held = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
        ResourceLocation skill = buffer.readBoolean() ? buffer.readResourceLocation() : null;
        return new WeaponSkillSnapshot(mana, maximum, slot, held, skill, buffer.readUtf(128), buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
    }
}
