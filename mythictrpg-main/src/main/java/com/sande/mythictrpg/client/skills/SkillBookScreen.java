package com.sande.mythictrpg.client.skills;

import com.sande.mythictrpg.skills.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Locale;

public final class SkillBookScreen extends Screen {
    private SkillBookPayload page;
    public SkillBookScreen(SkillBookPayload page){super(Component.literal("습득 스킬"));this.page=page;}
    public void receive(SkillBookPayload value){page=value;rebuildWidgets();}
    @Override protected void init(){
        int w=Math.min(400,width-16),left=(width-w)/2;
        for(int i=0;i<page.rows().size();i++){
            var row=page.rows().get(i);int y=46+i*28;
            String label=(page.selected().equals(row.id().toString())?"▶ ":"")+row.name()+" · 마나 "+row.manaCost();
            button(left,y,w-66,font.plainSubstrByWidth(label,w-76),()->send(SkillBookRequest.Action.SELECT,page.page(),row.id().toString()));
            button(left+w-62,y,62,"사용",()->{send(SkillBookRequest.Action.CAST,page.page(),row.id().toString());onClose();});
        }
        int y=height-28;
        var previous=button(left,y,50,"이전",()->send(SkillBookRequest.Action.PAGE,page.page()-1,""));previous.active=page.page()>0;
        var next=button(left+54,y,50,"다음",()->send(SkillBookRequest.Action.PAGE,page.page()+1,""));next.active=page.page()+1<page.pages();
        button(left+w-110,y,60,"새로고침",()->send(SkillBookRequest.Action.PAGE,page.page(),""));
        button(left+w-46,y,46,"닫기",this::onClose);
    }
    private Button button(int x,int y,int w,String label,Runnable action){return addRenderableWidget(Button.builder(Component.literal(label),b->action.run()).bounds(x,y,w,18).build());}
    private static void send(SkillBookRequest.Action action,int page,String id){PacketDistributor.sendToServer(new SkillBookRequest(action,page,id));}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partialTick){
        super.render(g,mouseX,mouseY,partialTick);
        g.drawCenteredString(font,title,width/2,8,0xFFFFFF);
        g.drawCenteredString(font,String.format(Locale.ROOT,"마나 %.1f / %.1f · %d / %d",page.mana(),page.maximumMana(),page.page()+1,page.pages()),width/2,24,0xA8D4FF);
        int left=(width-Math.min(400,width-16))/2;
        for(int i=0;i<page.rows().size();i++){
            var row=page.rows().get(i);String detail=String.format(Locale.ROOT,"기본 %.1f초 · 남은 %.1f초",row.cooldownTicks()/20.0,row.remainingTicks()/20.0);
            g.drawString(font,detail,left+3,66+i*28,0xB8B8B8,false);
        }
        if(page.rows().isEmpty())g.drawCenteredString(font,"아직 습득한 스킬이 없습니다.",width/2,65,0xBBBBBB);
        g.drawCenteredString(font,"선택 후 J 키로 사용 · 키 설정에서 변경 가능",width/2,height-42,0xAAAAAA);
    }
    @Override public boolean isPauseScreen(){return false;}
}
