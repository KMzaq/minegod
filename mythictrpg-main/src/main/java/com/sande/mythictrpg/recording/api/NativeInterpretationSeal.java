package com.sande.mythictrpg.recording.api;

import com.sande.mythictrpg.ai.api.RoomEvidenceReference;

/** Game-issued identity. Allocation/decoding alone is not registration in an actual game read session. */
public final class NativeInterpretationSeal {
    private final RoomEvidenceReference reference;
    private NativeInterpretationSeal(RoomEvidenceReference reference){NativeInterpretationEvidence.decode(reference);this.reference=reference;}
    public static NativeInterpretationSeal unregistered(RoomEvidenceReference reference){return new NativeInterpretationSeal(reference);}
    public RoomEvidenceReference reference(){return reference;}
    @Override public String toString(){return "NativeInterpretationSeal[opaque,unregistered-unless-game-issued]";}
}
