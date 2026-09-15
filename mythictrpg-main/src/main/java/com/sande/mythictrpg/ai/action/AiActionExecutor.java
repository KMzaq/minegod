package com.sande.mythictrpg.ai.action;

@FunctionalInterface
public interface AiActionExecutor {
    AiActionExecution execute(AiActionContext context, AiActionProposal proposal);
}
