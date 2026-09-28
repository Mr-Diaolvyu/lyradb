package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.model.entity.AiProviderConfig;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiMaskingRuleDraftServiceTest {
    @Test
    void generatesDisplayOnlyDraftWithoutPersistingOrReadingSource() throws Exception {
        DataSourceRepository sources = mock(DataSourceRepository.class);
        AiProviderService providers = mock(AiProviderService.class);
        DataSource source = new DataSource();
        source.setId("source-1");
        source.setWorkspaceId("ws-1");
        source.setDisplayName("业务数据源");
        source.setDbType("MYSQL");
        when(sources.findById("source-1")).thenReturn(Optional.of(source));
        AiProviderConfig provider = new AiProviderConfig();
        when(providers.resolveDefault("ws-1")).thenReturn(provider);
        when(providers.chat(any(), any())).thenReturn("""
                {"tablePattern":"","columnPattern":"mobile,phone*",
                 "maskType":"HASH","remark":"手机号","explanation":"仅展示端摘要"}
                """);

        AiMaskingRuleDraftService.Draft draft = new AiMaskingRuleDraftService(
                sources, providers, new ObjectMapper()).generate(
                "ws-1", "source-1", "所有表的手机号字段加密");

        assertThat(draft.dataSourceId()).isEqualTo("source-1");
        assertThat(draft.tablePattern()).isEmpty();
        assertThat(draft.columnPattern()).isEqualTo("mobile,phone*");
        assertThat(draft.maskType()).isEqualTo("HASH");
        verify(sources, never()).save(any());
    }

    @Test
    void rejectsCrossWorkspaceSourceBeforeCallingModel() {
        DataSourceRepository sources = mock(DataSourceRepository.class);
        AiProviderService providers = mock(AiProviderService.class);
        DataSource source = new DataSource();
        source.setWorkspaceId("other-ws");
        when(sources.findById("source-1")).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> new AiMaskingRuleDraftService(
                sources, providers, new ObjectMapper()).generate(
                "ws-1", "source-1", "手机号加密"))
                .hasMessageContaining("不属于当前工作空间");
        verify(providers, never()).chat(any(), any());
    }
}
