package com.sande.mythictrpg.recording.api;

import java.util.UUID;

/** Opaque handle. A fabricated handle never authorizes anything: the issuing service requires object identity registration. */
public final class ProducerCapability {
    private final UUID nonce;
    private ProducerCapability() { nonce = UUID.randomUUID(); }
    /** Internal game-service issuance; this factory alone grants no permission or registration. */
    public static ProducerCapability unregistered() { return new ProducerCapability(); }
    @Override public String toString() { return "ProducerCapability[opaque]"; }
}
