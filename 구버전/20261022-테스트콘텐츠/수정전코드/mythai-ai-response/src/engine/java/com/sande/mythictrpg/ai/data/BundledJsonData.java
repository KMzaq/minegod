package com.sande.mythictrpg.ai.data;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Copies editable defaults from the mod JAR only when a server-local data file does not exist. */
public final class BundledJsonData {
    private BundledJsonData() {
    }

    public static void ensureServerCopy(Path target, String bundledResource) throws IOException {
        if (Files.exists(target)) {
            return;
        }
        Files.createDirectories(target.getParent());
        try (InputStream input = BundledJsonData.class.getClassLoader().getResourceAsStream(bundledResource)) {
            if (input == null) {
                throw new IOException("Missing bundled data resource: " + bundledResource);
            }
            Files.copy(input, target);
        }
    }
}
