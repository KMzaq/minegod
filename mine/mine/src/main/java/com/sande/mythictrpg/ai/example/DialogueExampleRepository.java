package com.sande.mythictrpg.ai.example;

import java.util.List;

/** Replaceable source for reusable dialogue examples (JSON initially, database/search later). */
@FunctionalInterface
public interface DialogueExampleRepository {
    List<DialogueExample> all();
}
