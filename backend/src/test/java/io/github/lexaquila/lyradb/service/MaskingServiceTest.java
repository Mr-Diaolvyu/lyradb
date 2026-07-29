
package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.MaskingRule;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import io.github.lexaquila.lyradb.repository.MaskingRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaskingServiceTest {

    @Mock
    private MaskingRuleRepository repository;

    @Mock
    private DataSourceRepository dataSourceRepository;

    @Mock
    private ApprovalSecurityContextService approvalSecurityContextService;

    private MaskingService maskingService;

    @BeforeEach
    void setUp() {
        maskingService =
                new MaskingService(repository, dataSourceRepository,
                        approvalSecurityContextService);
    }

    @Test
    void masksAliasFromSourceColumnLineage() {
        MaskingRule rule = rule(
                "public.users", "email", "PARTIAL");
        when(repository
                .findByWorkspaceIdAndDataSourceIdIsNullAndEnabledTrue(
                        "workspace-1"))
                .thenReturn(List.of());
        when(repository
                .findByWorkspaceIdAndDataSourceIdAndEnabledTrue(
                        "workspace-1", "source-1"))
                .thenReturn(List.of(rule));

        SqlParseUtil.Analysis analysis =
                new SqlParseUtil.Analysis(
                        SqlParseUtil.StatementType.READ,
                        Set.of("public.users"),
                        Map.of("mail",
                                Set.of(new SqlParseUtil.SourceColumn(
                                        "public.users", "email"))),
                        true);
        MaskingService.MaskingPlan plan = maskingService.preparePlan(
                "workspace-1", "source-1", analysis,
                List.of("mail", "display_name"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("mail", "alice@example.com");
        row.put("display_name", "Alice");

        plan.apply(row);

        assertEquals("a****m", row.get("mail"));
        assertEquals("Alice", row.get("display_name"));
    }

    @Test
    void incompleteLineageFailsClosedForRelevantSensitiveTable() {
        MaskingRule rule = rule(
                "public.users", "email", "FULL");
        when(repository
                .findByWorkspaceIdAndDataSourceIdIsNullAndEnabledTrue(
                        "workspace-1"))
                .thenReturn(List.of(rule));
        when(repository
                .findByWorkspaceIdAndDataSourceIdAndEnabledTrue(
                        "workspace-1", "source-1"))
                .thenReturn(List.of());

        SqlParseUtil.Analysis analysis =
                new SqlParseUtil.Analysis(
                        SqlParseUtil.StatementType.READ,
                        Set.of("public.users"), Map.of(), false);
        MaskingService.MaskingPlan plan = maskingService.preparePlan(
                "workspace-1", "source-1", analysis,
                List.of("derived_email", "other_value"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("derived_email", "alice@example.com");
        row.put("other_value", "visible-before-mask");

        plan.apply(row);

        assertEquals("******", row.get("derived_email"));
        assertEquals("******", row.get("other_value"));
    }

    private static MaskingRule rule(
            String tablePattern, String columnPattern,
            String maskType) {
        MaskingRule rule = new MaskingRule();
        rule.setWorkspaceId("workspace-1");
        rule.setTablePattern(tablePattern);
        rule.setColumnPattern(columnPattern);
        rule.setMaskType(maskType);
        rule.setEnabled(true);
        return rule;
    }
}
