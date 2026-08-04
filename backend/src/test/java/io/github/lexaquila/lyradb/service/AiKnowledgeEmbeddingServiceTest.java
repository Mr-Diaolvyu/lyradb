package io.github.lexaquila.lyradb.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiKnowledgeEmbeddingServiceTest {

    @Test
    void cosineRejectsDimensionMismatchAndBoundsSimilarity() {
        assertEquals(1.0, AiKnowledgeEmbeddingService.cosine(
                List.of(1.0, 0.0), List.of(2.0, 0.0)), 0.000001);
        assertEquals(0.0, AiKnowledgeEmbeddingService.cosine(
                List.of(1.0), List.of(1.0, 2.0)), 0.000001);
        assertEquals(0.0, AiKnowledgeEmbeddingService.cosine(
                List.of(1.0, 0.0), List.of(-1.0, 0.0)), 0.000001);
    }
}
