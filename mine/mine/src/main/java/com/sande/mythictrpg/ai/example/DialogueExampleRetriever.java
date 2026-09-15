package com.sande.mythictrpg.ai.example;

import java.util.List;

/**
 * Replaceable example-search boundary. A weighted tag implementation is used first; an embedding/vector retriever
 * can implement this interface later without changing prompt assembly.
 */
@FunctionalInterface
public interface DialogueExampleRetriever {
    List<DialogueExampleSnippet> retrieve(ExampleRetrievalQuery query);
}
