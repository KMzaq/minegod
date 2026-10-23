package com.sande.mythictrpg.accessory;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

public record AccessoryRequest(UUID token,long sequence,Action action,int slot,String target,int page)implements CustomPacketPayload{
    public enum Action{OPEN,PAGE,EQUIP,UNEQUIP,LINK,CLOSE,JUMP,RESTORE,REISSUE}
    public static final UUID NONE=new UUID(0,0);
    public static final Type<AccessoryRequest> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,"accessory_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf,AccessoryRequest> CODEC=StreamCodec.ofMember(AccessoryRequest::write,AccessoryRequest::read);
    public AccessoryRequest{if(token==null||action==null||sequence<0||slot<0||slot>1||page<0||page>2048||target==null||target.length()>256)throw new IllegalArgumentException("Accessory request");}
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    private void write(RegistryFriendlyByteBuf b){b.writeUUID(token);b.writeVarLong(sequence);b.writeEnum(action);b.writeVarInt(slot);b.writeUtf(target,256);b.writeVarInt(page);}
    private static AccessoryRequest read(RegistryFriendlyByteBuf b){return new AccessoryRequest(b.readUUID(),b.readVarLong(),b.readEnum(Action.class),b.readVarInt(),b.readUtf(256),b.readVarInt());}
}
