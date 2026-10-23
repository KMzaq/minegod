package com.sande.mythictrpg.quest.reward;

/** Receipt-only currency result; bonus is frozen once, never reapplied to refunds or balance transfers. */
public record ResolvedCurrencyRewardEntry(long baseAmount, long bonusAmount) implements RewardEntry {
    public ResolvedCurrencyRewardEntry {
        new CurrencyRewardEntry(baseAmount);
        if (bonusAmount<0) throw new IllegalArgumentException("Negative gold bonus");
        new CurrencyRewardEntry(Math.addExact(baseAmount,bonusAmount));
    }
    public long amount() { return Math.addExact(baseAmount,bonusAmount); }
    @Override public String description() { return amount()+" 골드"+(bonusAmount>0?" (장신구 추가 "+bonusAmount+")":""); }
}
