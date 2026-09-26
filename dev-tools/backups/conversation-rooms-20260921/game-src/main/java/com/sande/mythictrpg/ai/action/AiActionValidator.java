package com.sande.mythictrpg.ai.action;

@FunctionalInterface
public interface AiActionValidator {
    AiActionValidation validate(AiActionContext context, AiActionProposal proposal);
}
