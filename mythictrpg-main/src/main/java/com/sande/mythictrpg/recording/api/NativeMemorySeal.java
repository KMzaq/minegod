package com.sande.mythictrpg.recording.api;

import com.sande.mythictrpg.ai.api.RoomEvidenceReference;

/** Opaque identity, accepted only by its issuing game's Session registry. A parsed pointer is never a grant. */
public final class NativeMemorySeal {
    private final RoomEvidenceReference reference;
    private NativeMemorySeal(RoomEvidenceReference reference) {
        NativeMemoryEvidence.decode(reference); this.reference = reference;
    }
    /** Syntax-only allocation, like an unregistered cursor. The game must explicitly register issuance. */
    public static NativeMemorySeal unregistered(RoomEvidenceReference reference) { return new NativeMemorySeal(reference); }
    public RoomEvidenceReference reference() { return reference; }
    @Override public String toString() { return "NativeMemorySeal[opaque]"; }
}
