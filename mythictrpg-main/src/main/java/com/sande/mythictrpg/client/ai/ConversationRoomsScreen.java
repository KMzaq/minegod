package com.sande.mythictrpg.client.ai;

import com.sande.mythictrpg.network.ConversationRoomsPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Selection only; membership and destination are revalidated by the server. */
public final class ConversationRoomsScreen extends Screen {
    private int page;
    public ConversationRoomsScreen() { super(Component.literal("대화방 — 비밀대화 선택")); }
    @Override protected void init() {
        var data=AiConversationHudController.INSTANCE.rooms();
        int first=page*6;
        if(first>=data.rooms().size()&&page>0) {page=0;first=0;}
        int y=45;
        for(var e:data.rooms().subList(first,Math.min(first+6,data.rooms().size()))) {
            String label="["+e.code()+"] "+(e.isPrivate()?"비밀":"공개")+" · "+e.gods();
            if(data.selectedPrivate().filter(e.id()::equals).isPresent())label="▶ "+label;
            var button=Button.builder(Component.literal(label),b->{
                if(minecraft!=null&&minecraft.getConnection()!=null) minecraft.getConnection().sendCommand("mythroom select "+e.id());
                onClose();
            }).bounds(width/2-150,y,300,20).build();
            button.active=e.isPrivate(); addRenderableWidget(button); y+=25;
        }
        if(page>0)addRenderableWidget(Button.builder(Component.literal("이전"),b->{page--;rebuildWidgets();}).bounds(width/2-150,y+5,70,20).build());
        if((page+1)*6<data.rooms().size())addRenderableWidget(Button.builder(Component.literal("다음"),b->{page++;rebuildWidgets();}).bounds(width/2+80,y+5,70,20).build());
        addRenderableWidget(Button.builder(Component.literal("닫기"),b->onClose()).bounds(width/2-45,height-35,90,20).build());
    }
    @Override public void render(GuiGraphics g,int x,int y,float delta) {
        super.render(g,x,y,delta);
        g.drawCenteredString(font,title,width/2,15,0xFFFFFFFF);
        g.drawCenteredString(font,Component.literal("일반 채팅: 공개 / 비밀: /s 할말 / 나가기: /mythroom leave <방코드>"),width/2,30,0xFFBBBBBB);
    }
    @Override public boolean isPauseScreen(){return false;}
}
