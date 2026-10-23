package com.sande.mythictrpg.skills;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

public record SkillBookPayload(boolean open,int page,int pages,double mana,double maximumMana,String selected,List<Row> rows) implements CustomPacketPayload {
    public static final int PAGE_SIZE=5;
    public record Row(ResourceLocation id,String name,int manaCost,int cooldownTicks,int remainingTicks) {
        public Row {Objects.requireNonNull(id);if(name==null||name.isBlank()||name.length()>128||manaCost<0||manaCost>1_000_000||cooldownTicks<0||remainingTicks<0)throw new IllegalArgumentException("Invalid skill row");}
    }
    public static final Type<SkillBookPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,"skill_book"));
    public static final StreamCodec<RegistryFriendlyByteBuf,SkillBookPayload> CODEC=StreamCodec.ofMember(SkillBookPayload::write,SkillBookPayload::read);
    public SkillBookPayload {
        rows=List.copyOf(rows);
        if(page<0||pages<1||pages>1024||page>=pages||rows.size()>PAGE_SIZE||selected==null||selected.length()>256
                ||!Double.isFinite(maximumMana)||maximumMana<1||maximumMana>1_000_000||!Double.isFinite(mana)||mana<0||mana>maximumMana)
            throw new IllegalArgumentException("Invalid skill book");
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    private void write(RegistryFriendlyByteBuf b){
        b.writeBoolean(open);b.writeVarInt(page);b.writeVarInt(pages);b.writeDouble(mana);b.writeDouble(maximumMana);b.writeUtf(selected,256);b.writeVarInt(rows.size());
        for(var r:rows){b.writeResourceLocation(r.id());b.writeUtf(r.name(),128);b.writeVarInt(r.manaCost());b.writeVarInt(r.cooldownTicks());b.writeVarInt(r.remainingTicks());}
    }
    private static SkillBookPayload read(RegistryFriendlyByteBuf b){
        boolean open=b.readBoolean();int page=b.readVarInt(),pages=b.readVarInt();double mana=b.readDouble(),max=b.readDouble();String selected=b.readUtf(256);int count=b.readVarInt();
        if(count<0||count>PAGE_SIZE)throw new IllegalArgumentException("Invalid skill page size");
        var rows=new ArrayList<Row>();for(int i=0;i<count;i++)rows.add(new Row(b.readResourceLocation(),b.readUtf(128),b.readVarInt(),b.readVarInt(),b.readVarInt()));
        return new SkillBookPayload(open,page,pages,mana,max,selected,rows);
    }
}
