package com.sande.mythictrpg.client.accessory;

import com.sande.mythictrpg.accessory.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Exactly two free accessory equipment slots, backed by server-owned reward entitlement IDs. */
public final class AccessoryScreen extends Screen{
    private AccessoryPage page;private long sequence;private int scroll;
    public AccessoryScreen(AccessoryPage value){super(Component.literal("장신구 관리"));page=value;}
    public void receive(AccessoryPage value){if(!value.token().equals(page.token()))return;page=value;rebuildWidgets();}
    @Override protected void init(){
        int left=Math.max(8,(width-410)/2),available=Math.min(410,width-16);
        for(int slot=0;slot<2;slot++){final int index=slot;int x=left+slot*(available/2);button(x+26,32,Math.max(40,available/2-32),"해제",()->send(AccessoryRequest.Action.UNEQUIP,index,"",page.page()),page.slots().get(slot).entitlement()!=null);}
        String target=page.targets().stream().filter(t->t.id().toString().equals(page.linkTarget())).map(AccessoryPage.Target::name).findFirst().orElse("없음");
        button(left,62,available,"연계 대상: "+target+"  (클릭하여 변경)",this::cycleTarget,!page.targets().isEmpty());
        int visible=Math.max(1,Math.min(8,(height-152)/29));scroll=Math.min(scroll,Math.max(0,page.rows().size()-visible));
        for(int i=scroll;i<Math.min(page.rows().size(),scroll+visible);i++){
            var row=page.rows().get(i);int y=100+(i-scroll)*29;
            button(left+available-142,y,44,"재지급",()->send(AccessoryRequest.Action.REISSUE,0,row.entitlement().toString(),page.page()),true);
            button(left+available-94,y,44,"1칸",()->send(AccessoryRequest.Action.EQUIP,0,row.entitlement().toString(),page.page()),true);
            button(left+available-46,y,44,"2칸",()->send(AccessoryRequest.Action.EQUIP,1,row.entitlement().toString(),page.page()),true);
        }
        button(left,height-29,50,"이전",()->{scroll=0;send(AccessoryRequest.Action.PAGE,0,"",Math.max(0,page.page()-1));},page.page()>0);
        button(left+54,height-29,50,"다음",()->{scroll=0;send(AccessoryRequest.Action.PAGE,0,"",page.page()+1);},(page.page()+1)*8<page.total());
        button(left+available-156,height-29,90,"새로고침",()->send(AccessoryRequest.Action.RESTORE,0,"",page.page()),true);
        button(left+available-60,height-29,60,"닫기",this::onClose,true);
    }
    private void cycleTarget(){
        int index=-1;for(int i=0;i<page.targets().size();i++)if(page.targets().get(i).id().toString().equals(page.linkTarget())){index=i;break;}
        String next=index+1>=page.targets().size()?"":page.targets().get(index+1).id().toString();send(AccessoryRequest.Action.LINK,0,next,page.page());
    }
    private void send(AccessoryRequest.Action action,int slot,String target,int number){PacketDistributor.sendToServer(new AccessoryRequest(page.token(),++sequence,action,slot,target,number));}
    private void button(int x,int y,int w,String label,Runnable action,boolean active){var button=Button.builder(Component.literal(label),b->action.run()).bounds(x,y,w,20).build();button.active=active;addRenderableWidget(button);}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partial){
        super.render(g,mouseX,mouseY,partial);int left=Math.max(8,(width-410)/2),available=Math.min(410,width-16);
        g.drawCenteredString(font,title,width/2,8,0xFFFFFF);
        for(int i=0;i<2;i++){var slot=page.slots().get(i);int x=left+i*(available/2);g.fill(x-2,30,x+20,53,0xFF506070);g.renderItem(slot.icon(),x+1,33);g.drawString(font,font.plainSubstrByWidth(slot.name(),available/2-5),x,21,0xD0E4FF);}
        g.drawString(font,page.notice().isEmpty()?"전투 중 교체 가능 · 같은 보상 한 개를 두 칸에 장착할 수 없습니다.":page.notice(),left,86,0xB7C9D9);
        int visible=Math.max(1,Math.min(8,(height-152)/29));
        for(int i=scroll;i<Math.min(page.rows().size(),scroll+visible);i++){
            var row=page.rows().get(i);int y=100+(i-scroll)*29;g.renderItem(row.icon(),left,y+1);
            g.drawString(font,font.plainSubstrByWidth(row.name(),Math.max(20,available-172)),left+22,y,0xFFFFFF);
            g.drawString(font,String.format(Locale.ROOT,"고정 전투력 %.1f",row.power()),left+22,y+11,0xAABED0);
            if(mouseX>=left&&mouseX<left+available-148&&mouseY>=y&&mouseY<y+25)
                g.renderComponentTooltip(font,row.effects().stream().map(Component::literal).map(c->(Component)c).toList(),mouseX,mouseY);
        }
        g.drawString(font,(page.page()+1)+" / "+Math.max(1,(page.total()+7)/8),left+110,height-23,0xAAAAAA);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){scroll=Math.max(0,Math.min(7,scroll-(int)vertical));rebuildWidgets();return true;}
    @Override public void onClose(){send(AccessoryRequest.Action.CLOSE,0,"",page.page());super.onClose();}
    @Override public boolean isPauseScreen(){return false;}
}
