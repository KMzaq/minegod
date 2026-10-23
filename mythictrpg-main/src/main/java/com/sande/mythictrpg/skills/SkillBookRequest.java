package com.sande.mythictrpg.skills;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.Objects;

public record SkillBookRequest(Action action,int page,String skill) implements CustomPacketPayload {
    public enum Action { OPEN, PAGE, SELECT, CAST, USE_SELECTED }
    public static final Type<SkillBookRequest> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,"skill_book_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf,SkillBookRequest> CODEC=StreamCodec.ofMember(SkillBookRequest::write,SkillBookRequest::read);
    public SkillBookRequest {
        Objects.requireNonNull(action);Objects.requireNonNull(skill);
        if(page<0||page>1023||skill.length()>256||(action==Action.SELECT||action==Action.CAST)&&ResourceLocation.tryParse(skill)==null)
            throw new IllegalArgumentException("Invalid skill request");
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    private void write(RegistryFriendlyByteBuf b){b.writeEnum(action);b.writeVarInt(page);b.writeUtf(skill,256);}
    private static SkillBookRequest read(RegistryFriendlyByteBuf b){return new SkillBookRequest(b.readEnum(Action.class),b.readVarInt(),b.readUtf(256));}
}
