package io.github.lexaquila.lyradb.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiProviderEmbeddingParsingTest {

    @Test
    void responseIsOrderedByProviderIndex() {
        List<List<Double>> result = AiProviderService.extractEmbeddings(
                Map.of("data", List.of(
                        Map.of("index", 1,
                                "embedding", List.of(0.0, 1.0)),
                        Map.of("index", 0,
                                "embedding", List.of(1.0, 0.0)))), 2);

        assertEquals(List.of(1.0, 0.0), result.get(0));
        assertEquals(List.of(0.0, 1.0), result.get(1));
    }

    @Test
    void malformedDimensionFailsClosed() {
        assertThrows(IllegalStateException.class,
                () -> AiProviderService.extractEmbeddings(
                        Map.of("data", List.of(
                                Map.of("index", 0,
                                        "embedding", List.of(1.0)),
                                Map.of("index", 1,
                                        "embedding", List.of(1.0, 2.0)))), 2));
    }
}
