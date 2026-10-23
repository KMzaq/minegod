package com.sande.mythictrpg.accessory;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.util.*;

public record AccessoryPage(UUID token,boolean open,int page,int total,List<Slot> slots,List<Row> rows,
        String linkTarget,List<Target> targets,String notice)implements CustomPacketPayload{
    public record Slot(UUID entitlement,String name,ItemStack icon){public Slot{icon=icon.copy();if(name.length()>128)throw new IllegalArgumentException("Accessory slot name");}@Override public ItemStack icon(){return icon.copy();}}
    public record Row(UUID entitlement,String name,ItemStack icon,double power,List<String> effects){
        public Row{Objects.requireNonNull(entitlement);icon=icon.copy();effects=List.copyOf(effects);if(name.length()>128||effects.size()>12||effects.stream().anyMatch(s->s.length()>160)||!Double.isFinite(power)||power<0)throw new IllegalArgumentException("Accessory page row");}
        @Override public ItemStack icon(){return icon.copy();}
    }
    public record Target(ResourceLocation id,String name){public Target{if(name.length()>128)throw new IllegalArgumentException("Skill name");}}
    public static final Type<AccessoryPage> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,"accessory_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf,AccessoryPage> CODEC=StreamCodec.ofMember(AccessoryPage::write,AccessoryPage::read);
    public AccessoryPage{slots=List.copyOf(slots);rows=List.copyOf(rows);targets=List.copyOf(targets);if(token==null||page<0||total<0||total>16384||slots.size()!=2||rows.size()>8||targets.size()>128||linkTarget.length()>256||notice.length()>256)throw new IllegalArgumentException("Accessory page bounds");}
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    private void write(RegistryFriendlyByteBuf b){
        b.writeUUID(token);b.writeBoolean(open);b.writeVarInt(page);b.writeVarInt(total);
        for(var s:slots){b.writeBoolean(s.entitlement()!=null);if(s.entitlement()!=null)b.writeUUID(s.entitlement());b.writeUtf(s.name(),128);ItemStack.OPTIONAL_STREAM_CODEC.encode(b,s.icon());}
        b.writeVarInt(rows.size());for(var r:rows){b.writeUUID(r.entitlement());b.writeUtf(r.name(),128);ItemStack.OPTIONAL_STREAM_CODEC.encode(b,r.icon());b.writeDouble(r.power());b.writeVarInt(r.effects().size());r.effects().forEach(e->b.writeUtf(e,160));}
        b.writeUtf(linkTarget,256);b.writeVarInt(targets.size());for(var t:targets){b.writeResourceLocation(t.id());b.writeUtf(t.name(),128);}b.writeUtf(notice,256);
    }
    private static AccessoryPage read(RegistryFriendlyByteBuf b){
        UUID token=b.readUUID();boolean open=b.readBoolean();int page=b.readVarInt(),total=b.readVarInt();List<Slot> slots=new ArrayList<>();
        for(int i=0;i<2;i++)slots.add(new Slot(b.readBoolean()?b.readUUID():null,b.readUtf(128),ItemStack.OPTIONAL_STREAM_CODEC.decode(b)));
        int n=count(b,8);List<Row> rows=new ArrayList<>();for(int i=0;i<n;i++){UUID id=b.readUUID();String name=b.readUtf(128);var icon=ItemStack.OPTIONAL_STREAM_CODEC.decode(b);double power=b.readDouble();int k=count(b,12);List<String> effects=new ArrayList<>();for(int j=0;j<k;j++)effects.add(b.readUtf(160));rows.add(new Row(id,name,icon,power,effects));}
        String link=b.readUtf(256);n=count(b,128);List<Target> targets=new ArrayList<>();for(int i=0;i<n;i++)targets.add(new Target(b.readResourceLocation(),b.readUtf(128)));return new AccessoryPage(token,open,page,total,slots,rows,link,targets,b.readUtf(256));
    }
    private static int count(RegistryFriendlyByteBuf b,int cap){int n=b.readVarInt();if(n<0||n>cap)throw new IllegalArgumentException("Accessory payload count");return n;}
}
