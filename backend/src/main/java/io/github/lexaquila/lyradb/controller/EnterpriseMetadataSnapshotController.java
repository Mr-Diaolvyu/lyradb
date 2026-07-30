package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.EnterpriseMetadataSnapshotService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import io.github.lexaquila.lyradb.service.MetadataSnapshotSessionStore;
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
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 企业 AI 可显式选择、预览和下载的授权元数据快照。
 */
@RestController
@RequestMapping("/ai/metadata/snapshots")
public class EnterpriseMetadataSnapshotController {

    private final EnterpriseMetadataSnapshotService snapshotService;
    private final SecurityUtil securityUtil;
    private final AuditService auditService;

    public EnterpriseMetadataSnapshotController(
            EnterpriseMetadataSnapshotService snapshotService,
            SecurityUtil securityUtil,
            AuditService auditService) {
        this.snapshotService = snapshotService;
        this.securityUtil = securityUtil;
        this.auditService = auditService;
    }

    @PostMapping
    public EnterpriseMetadataSnapshotService.CaptureResult capture(
            @RequestBody
                    EnterpriseMetadataSnapshotService.CaptureRequest request,
            HttpSession session) {
        String workspaceId =
                securityUtil.requireCurrentWorkspace(session);
        User owner = securityUtil.requireCurrentUser();
        EnterpriseMetadataSnapshotService.CaptureResult result =
                snapshotService.capture(workspaceId, owner, request);
        auditService.recordCurrentMetadata(
                workspaceId, "AI_METADATA_SNAPSHOT_CREATE",
                result.dataSourceId(), result.grantedSourceName(), result.id(),
                new MetadataSnapshotSessionStore.MapScope(
                        result.database(), result.schemas(), result.tables()),
                result.contentSha256());
        return result;
    }

    @GetMapping("/{snapshotId}/download")
    public ResponseEntity<byte[]> download(
            @PathVariable String snapshotId,
            @RequestParam(value = "format", defaultValue = "json")
                    String format,
            HttpSession session) {
        String workspaceId =
                securityUtil.requireCurrentWorkspace(session);
        User owner = securityUtil.requireCurrentUser();
        EnterpriseMetadataSnapshotService.RenderedSnapshot rendered =
                snapshotService.download(
                        workspaceId, owner, snapshotId, format);
        auditService.recordCurrentMetadata(
                workspaceId, "AI_METADATA_DOWNLOAD",
                rendered.session().dataSourceId(),
                rendered.session().grantedSourceName(),
                rendered.session().id(), rendered.session().scope(),
                rendered.contentSha256());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                rendered.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(rendered.fileName(), StandardCharsets.UTF_8)
                .build());
        headers.set("X-Content-Type-Options", "nosniff");
        headers.setCacheControl("no-store, private");
        headers.setPragma("no-cache");
        return ResponseEntity.ok()
                .headers(headers)
                .body(rendered.content());
    }
}
