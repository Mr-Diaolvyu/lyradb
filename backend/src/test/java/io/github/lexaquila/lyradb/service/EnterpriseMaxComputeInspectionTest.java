package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import io.github.lexaquila.lyradb.model.dto.PartitionMetadataPage;
import io.github.lexaquila.lyradb.model.dto.PartitionMetadata;
import io.github.lexaquila.lyradb.model.dto.TableCommentMetadata;
import io.github.lexaquila.lyradb.model.dto.TableInspection;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnterpriseMaxComputeInspectionTest {

    private EnterpriseQueryService service;
    private DatabaseDriver driver;
    private Object connection;

    @BeforeEach
    void setUp() throws Exception {
        GrantService grantService = mock(GrantService.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        SecurityUtil securityUtil = mock(SecurityUtil.class);
        service = new EnterpriseQueryService(
                grantService, dataSourceService,
                mock(AuditService.class), securityUtil,
                mock(SqlReviewService.class), mock(ApprovalService.class),
                mock(MaskingService.class), new AppProperties());

        User user = new User();
        user.setId("user-1");
        Grant grant = new Grant();
        grant.setId("grant-1");
        grant.setWorkspaceId("workspace-1");
        grant.setUserId("user-1");
        grant.setDataSourceId("source-1");
        grant.setGrantedSourceName("warehouse");
        grant.setAllowedSchemas("project_one");
        grant.setAllowedTables("project_one.fact_orders");
        grant.setMaxRowsPerQuery(200);
        DataSource dataSource = new DataSource();
        dataSource.setId("source-1");
        dataSource.setWorkspaceId("workspace-1");
        dataSource.setDbType("MAXCOMPUTE");

        when(securityUtil.requireCurrentUser()).thenReturn(user);
        when(securityUtil.requireCurrentWorkspace())
                .thenReturn("workspace-1");
        when(grantService.resolveForUser(
                "user-1", "workspace-1", "warehouse"))
                .thenReturn(grant);
        when(dataSourceService.getEntity("source-1"))
                .thenReturn(dataSource);

        driver = mock(DatabaseDriver.class);
        connection = new Object();
        when(dataSourceService.resolveActiveConnection("source-1"))
                .thenReturn(new ConnectionService.ActiveConnection(
                        driver, connection));

        TableCommentMetadata comment = new TableCommentMetadata();
        comment.setRemarks("订单事实表");
        comment.setMetadataSource("MAXCOMPUTE_JAVA_SDK");
        comment.setMetadataStatus("COMPLETE");
        comment.setRemarksStatus("AVAILABLE");
        comment.setMetadataReason("SDK 已返回表注释");
        when(driver.getTableCommentMetadata(
                connection, "project_one", "fact_orders"))
                .thenReturn(comment);

        PartitionMetadataPage partitions = new PartitionMetadataPage();
        partitions.setSchema("project_one");
        partitions.setTable("fact_orders");
        partitions.setPartitioned(true);
        partitions.setPartitionKeys(List.of("ds", "region"));
        partitions.setMetadataSource("MAXCOMPUTE_JAVA_SDK");
        partitions.setMetadataStatus("COMPLETE");
        when(driver.listTablePartitions(
                connection, "project_one", "fact_orders", 0, 1))
                .thenReturn(partitions);
        when(driver.getTableColumns(
                connection, "project_one", "fact_orders"))
                .thenReturn(List.of());
        when(driver.getTableConstraints(
                connection, "project_one", "fact_orders"))
                .thenReturn(List.of());
        when(driver.getTableDDL(
                connection, "project_one", "fact_orders"))
                .thenReturn("CREATE TABLE fact_orders (...)");
    }

    @Test
    void partitionedTableWithoutSelectionFailsClosedAndKeepsCommentEvidence()
            throws Exception {
        TableInspection inspection = service.inspectTable(
                "warehouse", "project_one", "fact_orders",
                "TABLE", 100, true, null);

        assertThat(inspection.getTableComment())
                .isEqualTo("订单事实表");
        assertThat(inspection.getRemarksStatus())
                .isEqualTo("AVAILABLE");
        assertThat(inspection.isPartitioned()).isTrue();
        assertThat(inspection.getPartitionColumns())
                .containsExactly("ds", "region");
        assertThat(inspection.getErrors().get("preview"))
                .contains("必须先选择");
        assertThat(inspection.getPreview()).isNull();
        assertThat(inspection.getPreviewSql()).isBlank();

        verify(driver, never()).buildTablePreviewSql(
                connection, "project_one", "fact_orders", 100);
        verify(driver, never()).buildPartitionPreviewSql(
                connection, "project_one", "fact_orders",
                null, 100);
        verify(driver, never()).executeQuery(
                eq(connection), anyString(), anyInt());
    }

    @Test
    void metadataOnlyInspectionNeverBuildsOrExecutesPreview()
            throws Exception {
        TableInspection inspection = service.inspectTable(
                "warehouse", "project_one", "fact_orders",
                "TABLE", 100, false, null);

        assertThat(inspection.getErrors()).doesNotContainKey("preview");
        assertThat(inspection.getPreview()).isNull();
        verify(driver, never()).buildTablePreviewSql(
                connection, "project_one", "fact_orders", 100);
        verify(driver, never()).executeQuery(
                eq(connection), anyString(), anyInt());
    }

    @Test
    void partitionListKeepsAuthorizationAndClampsResponsePage()
            throws Exception {
        PartitionMetadata partition = new PartitionMetadata();
        partition.setSpec("ds=20260814/region=hangzhou");
        PartitionMetadataPage page = new PartitionMetadataPage();
        page.setSchema("project_one");
        page.setTable("fact_orders");
        page.setPartitioned(true);
        page.setPartitionKeys(List.of("ds", "region"));
        page.setItems(List.of(partition));
        page.setOffset(0);
        page.setLimit(100);
        page.setOrdering("PARTITION_SPEC_DESC");
        page.setMetadataStatus("COMPLETE");
        when(driver.listTablePartitions(
                connection, "project_one", "fact_orders", 0, 100))
                .thenReturn(page);

        var result = service.listTablePartitions(
                "warehouse", "project_one", "fact_orders",
                -20, 999, "");

        assertThat(result.offset()).isZero();
        assertThat(result.limit()).isEqualTo(100);
        assertThat(result.partitions())
                .containsExactly("ds=20260814/region=hangzhou");
        assertThat(result.suggestedPartition())
                .isEqualTo("ds=20260814/region=hangzhou");
        verify(driver).listTablePartitions(
                connection, "project_one", "fact_orders", 0, 100);
    }

    @Test
    void oversizedPartitionFilterIsRejectedBeforeMetadataScan()
            throws Exception {
        assertThatThrownBy(() -> service.listTablePartitions(
                "warehouse", "project_one", "fact_orders",
                0, 50, "x".repeat(201)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("200");

        verify(driver, never()).listTablePartitions(
                connection, "project_one", "fact_orders", 0, 50);
    }
}
