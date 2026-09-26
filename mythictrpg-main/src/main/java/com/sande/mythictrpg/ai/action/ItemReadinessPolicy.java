package com.sande.mythictrpg.ai.action;

import java.util.Locale;

/** Shared lexical readiness hint. Actual transfer still requires inventory validation and confirmation. */
public final class ItemReadinessPolicy {
    private ItemReadinessPolicy() { }
    public static boolean declared(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        for (String phrase : new String[]{"준비됐", "준비했", "준비 끝", "가져왔", "챙겨왔", "다 모았",
                "여기 있어", "여기있어", "건넬게", "건네줄게", "받아", "넣어뒀", "넣어 놨", "상자에 넣었"}) {
            if (normalized.contains(phrase)) return true;
        }
        return false;
    }
}
