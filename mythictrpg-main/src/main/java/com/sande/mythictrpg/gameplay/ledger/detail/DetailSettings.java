package com.sande.mythictrpg.gameplay.ledger.detail;

import com.google.gson.JsonParser;
import java.nio.file.*;

/** Independent OFF switch. Interval zero means undecided, never an implicit sampling policy. */
public record DetailSettings(boolean enabled, int movementIntervalTicks, boolean routineDiagnostics) {
    public DetailSettings(boolean enabled, int movementIntervalTicks) { this(enabled,movementIntervalTicks,false); }
    public static final DetailSettings OFF = new DetailSettings(false,0);
    public DetailSettings { if (movementIntervalTicks < 0 || movementIntervalTicks > 72000) throw new IllegalArgumentException("interval"); }
    public static DetailSettings load(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path)>4096) return OFF;
            var j=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (j.get("schemaVersion").getAsInt()!=1 || !j.get("enabled").getAsJsonPrimitive().isBoolean()) return OFF;
            return new DetailSettings(j.get("enabled").getAsBoolean(),j.get("movementIntervalTicks").getAsInt(),
                    j.has("routineDiagnostics") && j.get("routineDiagnostics").getAsBoolean());
        } catch(Exception invalid) { return OFF; }
    }
}
