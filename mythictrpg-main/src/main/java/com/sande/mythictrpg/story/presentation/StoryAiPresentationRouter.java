package com.sande.mythictrpg.story.presentation;

import com.sande.mythictrpg.story.presentation.StoryAiPresentationContracts.Provider;

import java.util.Optional;

/** Optional-module boundary. The base mod remains fully functional with no provider installed. */
public final class StoryAiPresentationRouter {
    public static final StoryAiPresentationRouter INSTANCE = new StoryAiPresentationRouter();
    private volatile Provider provider;
    private StoryAiPresentationRouter() {}
    public void configureProductionProvider(Provider provider) { this.provider = provider; }
    public Optional<Provider> provider() { return Optional.ofNullable(provider); }
    public void clear() { provider = null; }
}
