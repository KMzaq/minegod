package com.sande.mythictrpg.ai.data;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Copies editable defaults from the mod JAR only when a server-local data file does not exist. */
public final class BundledJsonData {
    private BundledJsonData() {
    }

    public static void ensureServerCopy(Path target, String bundledResource) throws IOException {
        ensureServerCopy(target, bundledResource, null);
    }

    /** Optional content catalogs may start empty when no bundled authoring data is installed. */
    public static void ensureServerCopy(Path target, String bundledResource, String emptyCatalog) throws IOException {
        if (Files.exists(target)) {
            return;
        }
        Files.createDirectories(target.getParent());
        try (InputStream input = BundledJsonData.class.getClassLoader().getResourceAsStream(bundledResource)) {
            if (input == null) {
                if (emptyCatalog != null) {
                    Files.writeString(target, emptyCatalog, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                    return;
                }
                throw new IOException("Missing bundled data resource: " + bundledResource);
            }
            Files.copy(input, target);
        }
    }
}
