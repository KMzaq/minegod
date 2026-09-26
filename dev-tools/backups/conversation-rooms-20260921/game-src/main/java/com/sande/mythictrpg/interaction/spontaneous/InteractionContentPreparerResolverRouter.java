package com.sande.mythictrpg.interaction.spontaneous;

import com.sande.mythictrpg.interaction.api.InteractionSignal;

import java.util.Objects;

public final class InteractionContentPreparerResolverRouter
        implements InteractionContentPreparerResolver {
    public static final InteractionContentPreparerResolverRouter INSTANCE =
            new InteractionContentPreparerResolverRouter();

    private InteractionContentPreparerResolver productionResolver =
            InteractionContentPreparerResolver.UNAVAILABLE;
    private InteractionContentPreparerResolver testOverride;
    private boolean productionResolverConfigured;

    private InteractionContentPreparerResolverRouter() {
    }

    static InteractionContentPreparerResolverRouter forTesting() {
        return new InteractionContentPreparerResolverRouter();
    }

    public synchronized void configureProductionResolver(
            InteractionContentPreparerResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        if (!productionResolverConfigured) {
            productionResolver = resolver;
            productionResolverConfigured = true;
            return;
        }
        if (productionResolver != resolver) {
            throw new IllegalStateException(
                    "Interaction content production resolver is already configured");
        }
    }

    public synchronized void setResolverForTesting(InteractionContentPreparerResolver resolver) {
        testOverride = Objects.requireNonNull(resolver, "resolver");
    }

    public synchronized void clearResolverOverrideForTesting() {
        testOverride = null;
    }

    public synchronized void resetForTesting() {
        clearResolverOverrideForTesting();
    }

    @Override
    public ContentPreparerResolution resolve(InteractionSignal<?> signal) {
        Objects.requireNonNull(signal, "signal");
        InteractionContentPreparerResolver effective;
        synchronized (this) {
            effective = testOverride != null ? testOverride : productionResolver;
        }
        return Objects.requireNonNull(effective.resolve(signal), "content preparer resolution");
    }
}
