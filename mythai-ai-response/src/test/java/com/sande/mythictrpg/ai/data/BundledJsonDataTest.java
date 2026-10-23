package com.sande.mythictrpg.ai.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Real file I/O checks for content-free first startup; no game server or model is started. */
public final class BundledJsonDataTest {
    private static final String ABSENT = "archive-regression/absent-content-catalog.json";
    private static final String CLASS_RESOURCE = "com/sande/mythictrpg/ai/data/BundledJsonData.class";
    private static int checks;

    private BundledJsonDataTest() { }

    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory("mythai-empty-content-");
        Map<String, String> catalogs = new LinkedHashMap<>();
        catalogs.put("ai-personas", "{\"schemaVersion\":1,\"gods\":{}}\n");
        catalogs.put("ai-knowledge", "{\"schemaVersion\":1,\"entries\":[]}\n");
        catalogs.put("npc-agents", "{\"schemaVersion\":1,\"agents\":{}}\n");
        catalogs.put("npc-character-tags", "{\"schemaVersion\":1,\"npcs\":{}}\n");
        catalogs.put("dialogue-examples", "{\"schemaVersion\":1,\"examples\":[]}\n");
        catalogs.put("voice-styles", "{\"schemaVersion\":1,\"styles\":[]}\n");
        for (var catalog : catalogs.entrySet()) {
            Path target = root.resolve("new-server/config/" + catalog.getKey() + ".json");
            BundledJsonData.ensureServerCopy(target, ABSENT, catalog.getValue());
            check(Files.readString(target, StandardCharsets.UTF_8).equals(catalog.getValue()),
                    "missing optional catalog starts empty: " + catalog.getKey());
        }

        Path authored = root.resolve("author-content.json");
        byte[] authoredBytes = "기획자가 작성한 콘텐츠\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(authored, authoredBytes);
        BundledJsonData.ensureServerCopy(authored, ABSENT, catalogs.get("ai-personas"));
        check(Arrays.equals(Files.readAllBytes(authored), authoredBytes),
                "local authoring wins over empty fallback, byte for byte");
        BundledJsonData.ensureServerCopy(authored, CLASS_RESOURCE, catalogs.get("ai-personas"));
        check(Arrays.equals(Files.readAllBytes(authored), authoredBytes),
                "local authoring also wins over an installed bundled resource");
        BundledJsonData.ensureServerCopy(authored, ABSENT);
        check(Arrays.equals(Files.readAllBytes(authored), authoredBytes),
                "strict legacy API preserves an existing local file without looking up a bundle");

        Path malformed = root.resolve("invalid-author-content.json");
        Files.writeString(malformed, "{invalid author json}", StandardCharsets.UTF_8);
        BundledJsonData.ensureServerCopy(malformed, ABSENT, catalogs.get("ai-personas"));
        check(Files.readString(malformed).equals("{invalid author json}"),
                "copy helper never silently replaces invalid user data");

        byte[] bundled;
        try (var input = BundledJsonDataTest.class.getClassLoader().getResourceAsStream(CLASS_RESOURCE)) {
            if (input == null) throw new AssertionError("Test classpath is missing the compiled copy helper");
            bundled = input.readAllBytes();
        }
        Path installed = root.resolve("bundled-resource.bin");
        BundledJsonData.ensureServerCopy(installed, CLASS_RESOURCE, catalogs.get("ai-personas"));
        check(Arrays.equals(Files.readAllBytes(installed), bundled),
                "installed bundle takes precedence over optional empty starter");
        Path strictBundled = root.resolve("strict-bundled-resource.bin");
        BundledJsonData.ensureServerCopy(strictBundled, CLASS_RESOURCE);
        check(Arrays.equals(Files.readAllBytes(strictBundled), bundled),
                "strict legacy API still copies existing bundled bytes");

        Path required = root.resolve("required-content.json");
        boolean rejected = false;
        try {
            BundledJsonData.ensureServerCopy(required, ABSENT);
        } catch (IOException expected) {
            rejected = true;
        }
        check(rejected && !Files.exists(required),
                "required-resource API still rejects missing data without writing a fake catalog");

        System.out.println("BundledJsonDataTest: PASS (" + checks
                + " checks; no game server or model; temporary fixtures " + root + ")");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
