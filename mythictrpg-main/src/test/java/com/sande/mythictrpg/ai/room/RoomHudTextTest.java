package com.sande.mythictrpg.ai.room;

public final class RoomHudTextTest {
    public static void main(String[] args) {
        int checks = 0;
        for (String text : new String[]{"", "안녕.", "긴 문장입니다. 다음 문장입니다! ".repeat(80),
                "가".repeat(2048), "😀".repeat(400), "중간에\n줄바꿈이 있습니다.\n".repeat(30)}) {
            var pages = RoomHudText.pages(text);
            if (!String.join("", pages).equals(text)) throw new AssertionError("Lost dialogue text");
            checks++;
            for (String page : pages) {
                if (page.codePointCount(0, page.length()) > 180 || page.isEmpty()
                        || Character.isLowSurrogate(page.charAt(0)) || Character.isHighSurrogate(page.charAt(page.length()-1)))
                    throw new AssertionError("Invalid HUD page");
                checks++;
            }
        }
        System.out.println("RoomHudTextTest: " + checks + " assertions passed");
    }
}
