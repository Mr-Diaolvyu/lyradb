package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.service.ApprovalService;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.DataSourceTransferApprovalService;
import io.github.lexaquila.lyradb.service.EnterpriseConnectionTransferService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import io.github.lexaquila.lyradb.transfer.connection.ConnectionPackageException;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 当前工作空间数据源连接配置的批量导入与审批导出。
 */
@RestController
@RequestMapping("/admin/datasources")
public class AdminDataSourceTransferController {

    private static final int MAX_IMPORT_BYTES = 10 * 1024 * 1024;

    private final DataSourceTransferApprovalService transferApprovalService;
    private final EnterpriseConnectionTransferService transferService;
    private final ApprovalService approvalService;
    private final SecurityUtil securityUtil;
    private final AuditService auditService;

    public AdminDataSourceTransferController(
            DataSourceTransferApprovalService transferApprovalService,
            EnterpriseConnectionTransferService transferService,
            ApprovalService approvalService,
            SecurityUtil securityUtil,
            AuditService auditService) {
        this.transferApprovalService = transferApprovalService;
        this.transferService = transferService;
        this.approvalService = approvalService;
        this.securityUtil = securityUtil;
        this.auditService = auditService;
    }

    @PostMapping("/export-requests")
    public Map<String, Object> requestExport(
            @RequestBody ExportRequest body, HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        String workspaceId =
                securityUtil.requireCurrentWorkspace(session);
        User applicant = securityUtil.requireCurrentUser();
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        ApprovalRequest approval = transferApprovalService.create(
                workspaceId, applicant, body.dataSourceIds(),
                body.credentialMode(),
                body.plaintextRiskConfirmed(), body.reason());
        auditService.recordCurrentWithApproval(
                workspaceId,
                DataSourceTransferApprovalService.OPERATION,
                "DATA_SOURCE_EXPORT_REQUEST",
                null, approval.getGrantedSourceName(),
                true, null, approval.getId());
        return approvalService.toView(approval, true);
    }

    @PostMapping("/exports/{approvalId}/download")
    public ResponseEntity<byte[]> downloadExport(
            @PathVariable String approvalId,
            @RequestBody(required = false) ExportDownloadRequest body,
            HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        String workspaceId =
                securityUtil.requireCurrentWorkspace(session);
        User applicant = securityUtil.requireCurrentUser();
        char[] password = body == null || body.password() == null
                ? new char[0] : body.password();
        try {
            EnterpriseConnectionTransferService.ExportFile file =
                    transferService.exportApproved(
                            approvalId, applicant, workspaceId, password,
                            body != null
                                    && body.plaintextRiskConfirmed());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    file.contentType()));
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(file.fileName(), StandardCharsets.UTF_8)
                    .build());
            headers.set("X-Content-Type-Options", "nosniff");
            headers.setCacheControl("no-store, private");
            headers.setPragma("no-cache");
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(file.content());
        } catch (ConnectionPackageException exception) {
            auditService.recordCurrentWithApproval(
                    workspaceId,
                    DataSourceTransferApprovalService.OPERATION,
                    "DATA_SOURCE_EXPORT_DOWNLOAD",
                    null, null, false,
                    exception.getMessage(), approvalId);
            throw new IllegalStateException(
                    exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            auditService.recordCurrentWithApproval(
                    workspaceId,
                    DataSourceTransferApprovalService.OPERATION,
                    "DATA_SOURCE_EXPORT_DOWNLOAD",
                    null, null, false,
                    safeError(exception), approvalId);
            throw exception;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @GetMapping("/imports/template")
    public ResponseEntity<byte[]> downloadImportTemplate(HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        String workspaceId = securityUtil.requireCurrentWorkspace(session);
        try {
            EnterpriseConnectionTransferService.ExportFile file =
                    transferService.createImportTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(file.contentType()));
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(file.fileName(), StandardCharsets.UTF_8)
                    .build());
            headers.set("X-Content-Type-Options", "nosniff");
            headers.setCacheControl("no-store, private");
            headers.setPragma("no-cache");
            auditService.recordCurrent(workspaceId,
                    "DATA_SOURCE_IMPORT_TEMPLATE_DOWNLOAD",
                    null, null, true, null);
            return ResponseEntity.ok().headers(headers).body(file.content());
        } catch (ConnectionPackageException exception) {
            auditService.recordCurrent(workspaceId,
                    "DATA_SOURCE_IMPORT_TEMPLATE_DOWNLOAD",
                    null, null, false, exception.getMessage());
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    @PostMapping(
            value = "/imports/preview",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EnterpriseConnectionTransferService.ImportPreview previewImport(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "password", required = false)
                    char[] password,
            HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        String workspaceId =
                securityUtil.requireCurrentWorkspace(session);
        User owner = securityUtil.requireCurrentUser();
        char[] safePassword =
                password == null ? new char[0] : password;
        byte[] source = null;
        try {
            if (file == null || file.isEmpty()
                    || file.getSize() > MAX_IMPORT_BYTES) {
                throw new IllegalArgumentException(
                        "连接导入文件不能为空且不得超过 10 MiB");
            }
            source = file.getBytes();
            EnterpriseConnectionTransferService.ImportPreview preview =
                    transferService.previewImport(
                            workspaceId, owner, source, safePassword);
            auditService.recordCurrent(
                    workspaceId, "DATA_SOURCE_IMPORT_PREVIEW",
                    null, preview.credentialPolicy(), true, null);
            return preview;
        } catch (ConnectionPackageException exception) {
            auditService.recordCurrent(
                    workspaceId, "DATA_SOURCE_IMPORT_PREVIEW",
                    null, null, false, exception.getMessage());
            throw new IllegalArgumentException(
                    exception.getMessage(), exception);
        } catch (java.io.IOException exception) {
            auditService.recordCurrent(
                    workspaceId, "DATA_SOURCE_IMPORT_PREVIEW",
                    null, null, false, "文件读取失败");
            throw new IllegalArgumentException(
                    "无法读取连接导入文件", exception);
        } finally {
            Arrays.fill(safePassword, '\0');
            if (source != null) {
                Arrays.fill(source, (byte) 0);
            }
        }
    }

    @PostMapping("/imports/{previewToken}/apply")
    public EnterpriseConnectionTransferService.ImportApplyResult applyImport(
            @PathVariable String previewToken,
            @RequestBody ImportApplyRequest body,
            HttpSession session) {
        securityUtil.requireRole("DS_ADMIN");
        String workspaceId =
                securityUtil.requireCurrentWorkspace(session);
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        try {
            return transferService.applyImport(
                    workspaceId, securityUtil.requireCurrentUser(),
                    previewToken,
                    body.decisions() == null
                            ? List.of() : body.decisions());
        } catch (RuntimeException exception) {
            auditService.recordCurrent(
                    workspaceId, "DATA_SOURCE_IMPORT_APPLY",
                    null, null, false, safeError(exception));
            throw exception;
        }
    }

    private static String safeError(RuntimeException exception) {
        return exception instanceof IllegalArgumentException
                ? "请求未通过安全校验" : "导入未完成";
    }

    public record ExportRequest(
            List<String> dataSourceIds,
            String credentialMode,
            boolean plaintextRiskConfirmed,
            String reason) {
    }

    public record ExportDownloadRequest(
            char[] password,
            boolean plaintextRiskConfirmed) {
    }

    public record ImportApplyRequest(
            List<EnterpriseConnectionTransferService.ImportDecision>
                    decisions) {
    }
}
