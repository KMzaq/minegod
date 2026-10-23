package com.sande.mythictrpg.weaponskill;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.Objects;

/** An input intent only. Held item, ownership, effect, cost and target are resolved by the server. */
public record WeaponSkillRequest(int selectedSlot, ResourceLocation expectedSkill) implements CustomPacketPayload {
    public static final Type<WeaponSkillRequest> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "weapon_skill_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WeaponSkillRequest> CODEC =
            StreamCodec.ofMember(WeaponSkillRequest::write, WeaponSkillRequest::read);

    public WeaponSkillRequest {
        if (selectedSlot < 0 || selectedSlot > 8) throw new IllegalArgumentException("Invalid weapon hotbar slot");
        Objects.requireNonNull(expectedSkill, "expectedSkill");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(selectedSlot); buffer.writeResourceLocation(expectedSkill);
    }
    private static WeaponSkillRequest read(RegistryFriendlyByteBuf buffer) {
        return new WeaponSkillRequest(buffer.readVarInt(), buffer.readResourceLocation());
    }
}
