package com.sande.mythictrpg.quest.reward;

import com.sande.mythictrpg.economy.CurrencyState;

public record CurrencyRewardEntry(long amount) implements RewardEntry {
    public CurrencyRewardEntry {
        if (amount < 1L || amount > CurrencyState.MAX_BALANCE) {
            throw new IllegalArgumentException("Currency reward must be 1.." + CurrencyState.MAX_BALANCE);
        }
    }

    @Override
    public String description() {
        return amount + " 골드";
    }
}
