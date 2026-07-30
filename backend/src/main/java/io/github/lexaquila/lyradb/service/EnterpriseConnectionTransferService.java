package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.driver.DriverRegistry;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageCodec;
import io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageEntry;
import io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageException;
import io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageReadResult;
import io.github.lexaquila.lyradb.transfer.connection.CredentialExportPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 企业数据源与共享连接配置包之间的安全适配层。
 */
@Service
public class EnterpriseConnectionTransferService {

    private static final int MAX_IMPORT_BYTES = 10 * 1024 * 1024;
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_LENGTH = 1_024;

    private final DataSourceRepository dataSourceRepository;
    private final DataSourceService dataSourceService;
    private final DataSourceTransferApprovalService transferApprovalService;
    private final ApprovalService approvalService;
    private final ApprovalSecurityContextService securityContextService;
    private final DataSourceImportPreviewStore previewStore;
    private final CredentialService credentialService;
    private final DriverRegistry driverRegistry;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ConnectionPackageCodec codec = new ConnectionPackageCodec();

    public EnterpriseConnectionTransferService(
            DataSourceRepository dataSourceRepository,
            DataSourceService dataSourceService,
            DataSourceTransferApprovalService transferApprovalService,
            ApprovalService approvalService,
            ApprovalSecurityContextService securityContextService,
            DataSourceImportPreviewStore previewStore,
            CredentialService credentialService,
            DriverRegistry driverRegistry,
            AuditService auditService,
            ObjectMapper objectMapper) {
        this.dataSourceRepository = dataSourceRepository;
        this.dataSourceService = dataSourceService;
        this.transferApprovalService = transferApprovalService;
        this.approvalService = approvalService;
        this.securityContextService = securityContextService;
        this.previewStore = previewStore;
        this.credentialService = credentialService;
        this.driverRegistry = driverRegistry;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * 领取审批、生成连接包、写入终态与审计处于同一事务；响应产生前完成提交。
     */
    @Transactional(rollbackFor = ConnectionPackageException.class)
    public ExportFile exportApproved(
            String approvalId, User applicant, String workspaceId,
            char[] exportPassword, boolean plaintextRiskConfirmed)
            throws ConnectionPackageException {
        DataSourceTransferApprovalService.Claim claim =
                transferApprovalService.claim(
                        approvalId, applicant, workspaceId);
        validateDownloadPassword(claim.credentialMode(), exportPassword,
                plaintextRiskConfirmed);
        List<ConnectionPackageEntry> entries = transferEntries(
                workspaceId, claim.dataSourceIds(),
                claim.credentialMode());
        byte[] content = switch (claim.credentialMode()) {
            case OMIT -> codec.exportWithoutCredentials(entries);
            case PLAINTEXT ->
                    codec.exportWithPlaintextCredentials(entries);
            case PASSWORD_ENCRYPTED ->
                    codec.exportWithPassword(entries, exportPassword);
        };

        approvalService.markExecutionResult(
                approvalId, true,
                "已生成 " + entries.size() + " 个数据源连接配置");
        auditService.recordCurrentWithApproval(
                workspaceId,
                DataSourceTransferApprovalService.OPERATION,
                "DATA_SOURCE_EXPORT_DOWNLOAD",
                null, claim.approval().getGrantedSourceName(),
                true, null, approvalId);
        return new ExportFile(
                content,
                "lyradb-connections-" + approvalId + ".json",
                "application/json;charset=UTF-8");
    }

    public ImportPreview previewImport(
            String workspaceId, User owner, byte[] source,
            char[] packagePassword) throws ConnectionPackageException {
        if (source == null || source.length == 0
                || source.length > MAX_IMPORT_BYTES) {
            throw new IllegalArgumentException(
                    "连接配置包不能为空且不得超过 10 MiB");
        }
        ConnectionPackageReadResult parsed =
                codec.read(source, packagePassword);
        for (ConnectionPackageEntry entry : parsed.connections()) {
            if (driverRegistry.getDriverInfo(entry.dbType()) == null) {
                throw new IllegalArgumentException(
                        "不支持的数据源类型: " + entry.dbType());
            }
            requireDisplayName(entry);
        }

        DataSourceImportPreviewStore.PreviewSession session =
                previewStore.create(
                        owner.getId(), workspaceId,
                        parsed.credentialPolicy(), parsed.risk(),
                        parsed.connections());
        List<ImportPreviewItem> items = new ArrayList<>();
        for (int index = 0;
             index < parsed.connections().size(); index++) {
            ConnectionPackageEntry entry =
                    parsed.connections().get(index);
            String displayName = requireDisplayName(entry);
            List<DataSource> conflicts = dataSourceRepository
                    .findByWorkspaceIdAndDisplayNameIgnoreCase(
                            workspaceId, displayName);
            items.add(new ImportPreviewItem(
                    entryKey(index), displayName, entry.dbType(),
                    List.copyOf(entry.parameters().keySet()),
                    List.copyOf(entry.credentialKeys()),
                    !entry.credentials().isEmpty(),
                    !conflicts.isEmpty(),
                    conflicts.isEmpty() ? null
                            : conflicts.get(0).getDisplayName()));
        }
        return new ImportPreview(
                session.token(), session.expiresAt(),
                session.credentialPolicy().name(),
                session.risk().name(), List.copyOf(items));
    }

    @Transactional
    public ImportApplyResult applyImport(
            String workspaceId, User owner, String previewToken,
            List<ImportDecision> decisions) {
        DataSourceImportPreviewStore.PreviewSession session =
                previewStore.consume(
                        previewToken, owner.getId(), workspaceId);
        Map<String, ImportDecision> byKey =
                validateDecisions(session.entries(), decisions);
        securityContextService.lockWorkspace(workspaceId);

        int created = 0;
        int overwritten = 0;
        int skipped = 0;
        Set<String> reservedNames = new HashSet<>();
        for (int index = 0; index < session.entries().size(); index++) {
            ConnectionPackageEntry entry = session.entries().get(index);
            ImportDecision decision = byKey.get(entryKey(index));
            ImportAction action = normalizeAction(decision.action());
            if (action == ImportAction.SKIP) {
                skipped++;
                continue;
            }

            String originalName = requireDisplayName(entry);
            String finalName = action == ImportAction.RENAME
                    ? normalizeDisplayName(decision.newDisplayName())
                    : originalName;
            String nameKey = finalName.toLowerCase(Locale.ROOT);
            if (!reservedNames.add(nameKey)) {
                throw new IllegalArgumentException(
                        "同一批次的最终数据源名称不能重复: " + finalName);
            }

            List<DataSource> conflicts = dataSourceRepository
                    .findByWorkspaceIdAndDisplayNameIgnoreCase(
                            workspaceId, finalName);
            if (conflicts.size() > 1) {
                throw new IllegalStateException(
                        "工作空间存在重复数据源名称，无法安全导入");
            }
            Map<String, Object> parameters =
                    new LinkedHashMap<>(entry.mergedParameters());

            if (action == ImportAction.RENAME) {
                if (!conflicts.isEmpty()) {
                    throw new IllegalStateException(
                            "重命名后的数据源名称已存在: " + finalName);
                }
                dataSourceService.create(
                        workspaceId, entry.dbType(), finalName,
                        parameters, entry.description(), owner.getId(),
                        entry.credentialKeys());
                created++;
                continue;
            }

            if (conflicts.isEmpty()) {
                dataSourceService.create(
                        workspaceId, entry.dbType(), finalName,
                        parameters, entry.description(), owner.getId(),
                        entry.credentialKeys());
                created++;
                continue;
            }

            DataSource target = conflicts.get(0);
            if (!entry.dbType().equalsIgnoreCase(target.getDbType())) {
                throw new IllegalArgumentException(
                        "覆盖导入不能改变数据源类型: " + finalName);
            }
            dataSourceService.replaceImportedConfiguration(
                    target.getId(), finalName,
                    entry.description(), parameters,
                    entry.credentialKeys());
            overwritten++;
        }

        String summary = "created=" + created
                + ",overwritten=" + overwritten
                + ",skipped=" + skipped;
        auditService.recordCurrent(
                workspaceId, "DATA_SOURCE_IMPORT_APPLY",
                null, summary, true, null);
        return new ImportApplyResult(created, overwritten, skipped);
    }

    private List<ConnectionPackageEntry> transferEntries(
            String workspaceId, List<String> orderedIds,
            CredentialExportPolicy policy) {
        Map<String, DataSource> sources = new HashMap<>();
        for (DataSource source : dataSourceRepository.findAllById(orderedIds)) {
            if (!workspaceId.equals(source.getWorkspaceId())) {
                throw new RuntimeException(
                        "数据源不属于当前工作空间");
            }
            sources.put(source.getId(), source);
        }
        if (sources.size() != orderedIds.size()) {
            throw new RuntimeException(
                    "部分数据源不存在或不可访问");
        }

        List<ConnectionPackageEntry> entries =
                new ArrayList<>(orderedIds.size());
        for (String id : orderedIds) {
            DataSource source = sources.get(id);
            Map<String, Object> stored =
                    parseParams(source.getConnectionParamsJson());
            Set<String> credentialKeys = new LinkedHashSet<>();
            stored.keySet().stream()
                    .filter(key -> credentialService.isSensitiveField(key)
                            || credentialService.isEncryptedValue(stored.get(key)))
                    .forEach(credentialKeys::add);
            Map<String, Object> parameters =
                    policy == CredentialExportPolicy.OMIT
                            ? stored
                            : credentialService.decryptSensitiveFields(
                                    stored);
            entries.add(ConnectionPackageEntry.fromMixedParameters(
                    "", source.getDisplayName(), source.getDbType(),
                    source.getDisplayName(), parameters,
                    credentialKeys, "", "", source.getDescription(),
                    List.of(), false, 0, false));
        }
        return List.copyOf(entries);
    }

    private Map<String, Object> parseParams(String json) {
        try {
            return objectMapper.readValue(
                    json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "数据源参数存储格式损坏", exception);
        }
    }

    private static Map<String, ImportDecision> validateDecisions(
            List<ConnectionPackageEntry> entries,
            List<ImportDecision> decisions) {
        if (decisions == null || decisions.size() != entries.size()) {
            throw new IllegalArgumentException(
                    "必须为每个导入项提供一次冲突决策");
        }
        Map<String, ImportDecision> result = new LinkedHashMap<>();
        for (ImportDecision decision : decisions) {
            if (decision == null || decision.entryKey() == null
                    || result.put(decision.entryKey(), decision) != null) {
                throw new IllegalArgumentException(
                        "导入冲突决策包含空值或重复项");
            }
        }
        for (int index = 0; index < entries.size(); index++) {
            if (!result.containsKey(entryKey(index))) {
                throw new IllegalArgumentException(
                        "导入冲突决策与预览不匹配");
            }
        }
        return result;
    }

    private static ImportAction normalizeAction(String value) {
        try {
            return ImportAction.valueOf(
                    value == null ? "" : value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "action 仅支持 SKIP/RENAME/OVERWRITE");
        }
    }

    private static String requireDisplayName(
            ConnectionPackageEntry entry) {
        String value = entry.displayName().isBlank()
                ? entry.name() : entry.displayName();
        return normalizeDisplayName(value);
    }

    private static String normalizeDisplayName(String value) {
        if (value == null || value.isBlank()
                || value.trim().length() > 100) {
            throw new IllegalArgumentException(
                    "数据源显示名不能为空且不得超过 100 字符");
        }
        return value.trim();
    }

    private static void validateDownloadPassword(
            CredentialExportPolicy policy, char[] password,
            boolean plaintextRiskConfirmed) {
        int length = password == null ? 0 : password.length;
        if (policy == CredentialExportPolicy.PLAINTEXT
                && !plaintextRiskConfirmed) {
            throw new IllegalArgumentException(
                    "\u660e\u6587\u4e0b\u8f7d\u5fc5\u987b\u518d\u6b21\u786e\u8ba4\u51ed\u636e\u6cc4\u9732\u98ce\u9669");
        }
        if (policy != CredentialExportPolicy.PLAINTEXT
                && plaintextRiskConfirmed) {
            throw new IllegalArgumentException(
                    "\u975e\u660e\u6587\u6a21\u5f0f\u4e0d\u5f97\u8bbe\u7f6e\u660e\u6587\u98ce\u9669\u786e\u8ba4");
        }
        if (policy == CredentialExportPolicy.PASSWORD_ENCRYPTED) {
            if (length < MIN_PASSWORD_LENGTH
                    || length > MAX_PASSWORD_LENGTH) {
                throw new IllegalArgumentException(
                        "加密导出口令长度必须为 12 到 1024 个字符");
            }
        } else if (length > 0) {
            throw new IllegalArgumentException(
                    "当前凭据模式不接受导出口令");
        }
    }

    private static String entryKey(int index) {
        return "entry-" + (index + 1);
    }

    public record ExportFile(
            byte[] content, String fileName, String contentType) {
    }

    public record ImportPreview(
            String previewToken,
            java.time.LocalDateTime expiresAt,
            String credentialPolicy,
            String riskCode,
            List<ImportPreviewItem> items) {
    }

    public record ImportPreviewItem(
            String entryKey,
            String displayName,
            String dbType,
            List<String> parameterKeys,
            List<String> credentialKeys,
            boolean credentialsIncluded,
            boolean conflict,
            String existingDisplayName) {
    }

    public record ImportDecision(
            String entryKey, String action, String newDisplayName) {
    }

    public record ImportApplyResult(
            int created, int overwritten, int skipped) {
    }

    private enum ImportAction {
        SKIP,
        RENAME,
        OVERWRITE
    }
}
