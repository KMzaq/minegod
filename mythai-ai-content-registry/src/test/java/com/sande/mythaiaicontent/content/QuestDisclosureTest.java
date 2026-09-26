package com.sande.mythaiaicontent.content;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

/** Static candidate disclosure only: no quest engine, server, rewards, or authored runtime content is changed. */
public final class QuestDisclosureTest {
    private static int checks;
    private static final ResourceLocation A=id("a"), B=id("b"), C=id("c"), LIST=id("list"), QUEST=id("quest");
    private static final UUID PLAYER=new UUID(0,1);
    public static void main(String[] args) throws Exception {
        var legacy=parse("");
        var privateList=parse(",\"disclosure\":{\"mode\":\"PRIVATE_ROOM\",\"allowedGodIds\":[\"test:b\"]}");
        var hidden=parse(",\"disclosure\":{\"mode\":\"NEVER\"}");
        check(legacy.quests().getFirst().disclosure().equals(ContentDisclosure.PUBLIC),"missing policy retains public compatibility");
        check(privateList.quests().getFirst().disclosure().mode()==ContentDisclosure.Mode.PRIVATE_ROOM,"optional policy parsed");
        for (String policy : List.of("null","{\"mode\":\"SOMETIMES\"}","{\"mode\":\"PUBLIC\",\"allowdGodIds\":[]}")) {
            try { parse(",\"disclosure\":"+policy);throw new AssertionError("accepted invalid quest policy"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        var field=AiContentRegistry.class.getDeclaredField("snapshot"); field.setAccessible(true);
        var old=field.get(AiContentRegistry.INSTANCE);
        try {
            install(field,legacy,1);
            check(candidates(true,A,B,C).size()==1,"legacy public candidate preserved");
            check(AiContentRegistry.INSTANCE.publicQuestCandidatesFor(A).size()==1,"legacy completion may use unrestricted public candidate");
            var first=candidates(false,A,B).getFirst().fingerprint();
            install(field,legacy,999);
            check(first.equals(candidates(false,B).getFirst().fingerprint()),"restart/reload counter and author absence do not change evidence");
            check(AiContentRegistry.INSTANCE.audienceQuestCandidatesFor(C,false,List.of(C),Set.of(PLAYER)).isEmpty(),"nonowner cannot invent candidate knowledge");
            install(field,privateList,1);
            check(candidates(false,A,B).size()==1,"explicit permitted private full audience");
            check(candidates(false,B).size()==1,"original source absent current listener still permitted");
            check(candidates(false,A,B,C).isEmpty(),"one unauthorized God removes entire candidate before prompt");
            check(candidates(true,A,B).isEmpty(),"private candidate cannot enter public prompt");
            check(AiContentRegistry.INSTANCE.publicQuestCandidatesFor(A).isEmpty(),"legacy completion cannot restore private candidate text");
            check(AiContentRegistry.INSTANCE.questCandidatesFor(A).size()==1,"raw authoritative lookup unchanged");
            check(!first.equals(AiContentRegistry.INSTANCE.questCandidatesFor(A).getFirst().fingerprint()),"policy change alters portable item fingerprint");
            install(field,hidden,1);
            check(candidates(false,A).isEmpty()&&candidates(true,A).isEmpty(),"NEVER closes all prompt audience types");
            check(AiContentRegistry.INSTANCE.publicQuestCandidatesFor(A).isEmpty(),"NEVER not exposed by legacy completion");
        } finally { field.set(AiContentRegistry.INSTANCE,old); }
        var original=new QuestCandidateDefinition(LIST,id("track"),legacy.quests().getFirst());
        var changedQuest=new QuestDefinition(QUEST,"changed title","quest body",original.quest().objectives(),original.quest().rewards(),List.of(),10);
        check(!original.fingerprint().equals(new QuestCandidateDefinition(LIST,id("track"),changedQuest).fingerprint()),"title/body authority changes revoke evidence");
        var node1=new QuestContentNode("collect_item","objective",Map.of("a","1","b","2"));
        var node2=new QuestContentNode("collect_item","objective",Map.of("b","2","a","1"));
        var q1=new QuestDefinition(QUEST,"title","body",List.of(node1),List.of(node1),List.of(),10,
                new ContentDisclosure(ContentDisclosure.Mode.PUBLIC,Set.of(B,C)));
        var q2=new QuestDefinition(QUEST,"title","body",List.of(node2),List.of(node2),List.of(),10,
                new ContentDisclosure(ContentDisclosure.Mode.PUBLIC,Set.of(C,B)));
        check(new QuestCandidateDefinition(LIST,id("track"),q1).fingerprint().equals(new QuestCandidateDefinition(LIST,id("track"),q2).fingerprint()),"set/map ordering not persistent identity");
        System.out.println("QuestDisclosureTest: "+checks+" checks PASS");
    }
    private static List<QuestCandidateDefinition> candidates(boolean publicly,ResourceLocation... gods) {
        return AiContentRegistry.INSTANCE.audienceQuestCandidatesFor(A,publicly,List.of(gods),Set.of(PLAYER));
    }
    private static void install(java.lang.reflect.Field field,QuestListDefinition list,long generation) throws Exception {
        var profile=new GodContentProfile(id("profile"),A,"A","identity","",List.of(),List.of(),List.of(),List.of(),Map.of(),Map.of(),
                List.of(),List.of(),List.of(),List.of(LIST),List.of(),Map.of());
        field.set(AiContentRegistry.INSTANCE,new AiContentRegistry.Snapshot(Map.of(profile.contentId(),profile),Map.of(A,profile),Map.of(),
                Map.of(),Map.of(),Map.of(LIST,list),Map.of(),Map.of(),Map.of(),generation));
    }
    private static QuestListDefinition parse(String policy) throws Exception {
        var method=AiContentRegistry.class.getDeclaredMethod("parseQuestList",ResourceLocation.class,com.google.gson.JsonObject.class);
        method.setAccessible(true);
        String json="{\"schemaVersion\":2,\"questListId\":\"test:list\",\"progressTrackId\":\"test:track\",\"displayName\":\"list\",\"quests\":["
                +"{\"questId\":\"test:quest\",\"title\":\"title\",\"content\":\"quest body\",\"objectives\":[{\"type\":\"collect_item\",\"description\":\"objective\"}],"
                +"\"rewards\":[{\"type\":\"item\",\"description\":\"reward\"}],\"progressOnClear\":10"+policy+"}]}";
        try {return (QuestListDefinition)method.invoke(null,LIST,JsonParser.parseString(json).getAsJsonObject());}
        catch (InvocationTargetException wrapped) {throw (Exception)wrapped.getCause();}
    }
    private static ResourceLocation id(String path) {return ResourceLocation.fromNamespaceAndPath("test",path);}
    private static void check(boolean valid,String message) {checks++;if(!valid)throw new AssertionError(message);}
}
