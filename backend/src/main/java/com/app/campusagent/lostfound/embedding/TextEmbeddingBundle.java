package com.app.campusagent.lostfound.embedding;

public record TextEmbeddingBundle(
        StoredEmbedding semantic,
        StoredEmbedding crossModal,
        boolean crossModalAvailable) {
}
