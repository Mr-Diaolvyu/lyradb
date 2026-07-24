package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.QueryHistory;
import io.github.lexaquila.lyradb.repository.QueryHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 查询历史服务单元测试
 */
class QueryHistoryServiceTest {

    private QueryHistoryRepository repository;
    private QueryHistoryService service;

    @BeforeEach
    void setUp() {
        repository = mock(QueryHistoryRepository.class);
        service = new QueryHistoryService(repository);
    }

    @Test
    void recordCapturesFields() {
        ArgumentCaptor<QueryHistory> captor = ArgumentCaptor.forClass(QueryHistory.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        QueryHistory saved = service.record("c1", "MYSQL", "SELECT 1", 12L, 1L, true, null);
        assertNotNull(saved);
        assertEquals("c1", saved.getConnectionId());
        assertEquals("MYSQL", saved.getDbType());
        assertEquals("SELECT 1", saved.getSql());
        assertEquals(12L, saved.getDurationMs());
        assertEquals(1L, saved.getRowCount());
        assertTrue(saved.getSuccess());
    }

    @Test
    void recordFailureStoresTruncatedError() {
        String longMsg = "x".repeat(3000);
        service.record("c1", "MYSQL", "BAD", 0L, 0L, false, longMsg);
        ArgumentCaptor<QueryHistory> captor = ArgumentCaptor.forClass(QueryHistory.class);
        verify(repository).save(captor.capture());
        QueryHistory saved = captor.getValue();
        assertFalse(saved.getSuccess());
        assertNotNull(saved.getErrorMessage());
        assertTrue(saved.getErrorMessage().length() <= 2000);
    }

    @Test
    void listByConnectionAndFavorite() {
        when(repository.findByConnectionIdAndFavoriteTrueOrderByExecutedAtDesc("c1"))
                .thenReturn(List.of());
        List<QueryHistory> result = service.list("c1", true);
        assertNotNull(result);
        verify(repository).findByConnectionIdAndFavoriteTrueOrderByExecutedAtDesc("c1");
    }

    @Test
    void searchDelegatesWhenKeywordEmpty() {
        when(repository.findAllByOrderByExecutedAtDesc()).thenReturn(List.of());
        service.search("   ");
        verify(repository).findAllByOrderByExecutedAtDesc();
    }

    @Test
    void toggleFavoriteFlips() {
        QueryHistory h = new QueryHistory();
        h.setId("h1");
        h.setFavorite(false);
        when(repository.findById("h1")).thenReturn(java.util.Optional.of(h));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        QueryHistory toggled = service.toggleFavorite("h1");
        assertTrue(toggled.getFavorite());
    }

    @Test
    void clearByConnectionDelegates() {
        service.clear("c1");
        verify(repository).deleteByConnectionId("c1");
    }

    @Test
    void clearAllWhenNoConnection() {
        service.clear(null);
        verify(repository).deleteAll();
    }
}
