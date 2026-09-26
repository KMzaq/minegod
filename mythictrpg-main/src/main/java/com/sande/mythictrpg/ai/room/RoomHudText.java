package com.sande.mythictrpg.ai.room;

import java.util.ArrayList;
import java.util.List;

/** Lossless code-point-safe HUD pages. The chat log still receives one logical utterance. */
public final class RoomHudText {
    private RoomHudText() { }
    public static List<String> pages(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> pages = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int remaining = text.codePointCount(start, text.length());
            int end = text.offsetByCodePoints(start, Math.min(180, remaining));
            if (end < text.length()) {
                int preferred = -1;
                for (int i = start; i < end;) {
                    int cp = text.codePointAt(i); i += Character.charCount(cp);
                    if (".!?。！？\n".indexOf(cp) >= 0) preferred = i;
                }
                if (preferred > start) end = preferred;
                else {
                    int space = text.lastIndexOf(' ', end - 1);
                    if (space > start + 60) end = space + 1;
                }
            }
            pages.add(text.substring(start, end)); start = end;
        }
        return List.copyOf(pages);
    }
}
