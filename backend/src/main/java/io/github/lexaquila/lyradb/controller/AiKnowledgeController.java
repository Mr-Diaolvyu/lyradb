package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.dto.AiKnowledgeAssetView;
import io.github.lexaquila.lyradb.model.dto.AiKnowledgeDraftRequest;
import io.github.lexaquila.lyradb.model.dto.AiKnowledgeIngestionView;
import io.github.lexaquila.lyradb.model.dto.AiKnowledgeReviewRequest;
import io.github.lexaquila.lyradb.service.AiKnowledgeIngestionService;
import io.github.lexaquila.lyradb.service.AiKnowledgeService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Data Knowledge Core 与团队审核闭环 API。 */
@RestController
@RequestMapping("/ai/knowledge")
public class AiKnowledgeController {

    private final AiKnowledgeService knowledgeService;
    private final AiKnowledgeIngestionService ingestionService;
    private final SecurityUtil securityUtil;

    public AiKnowledgeController(
            AiKnowledgeService knowledgeService,
            AiKnowledgeIngestionService ingestionService,
            SecurityUtil securityUtil) {
        this.knowledgeService = knowledgeService;
        this.ingestionService = ingestionService;
        this.securityUtil = securityUtil;
    }

    @GetMapping("/verified")
    public List<AiKnowledgeAssetView> verified(HttpSession session) {
        return knowledgeService.listVerified(
                securityUtil.requireCurrentWorkspace(session));
    }

    @GetMapping("/mine")
    public List<AiKnowledgeAssetView> mine(HttpSession session) {
        return knowledgeService.listMine(
                securityUtil.requireCurrentWorkspace(session));
    }

    @GetMapping("/review")
    public List<AiKnowledgeAssetView> reviewQueue(HttpSession session) {
        return knowledgeService.listForReview(
                securityUtil.requireCurrentWorkspace(session));
    }

    @PostMapping("/drafts")
    public AiKnowledgeAssetView createDraft(
            @RequestBody AiKnowledgeDraftRequest request,
            HttpSession session) {
        return knowledgeService.createDraft(
                securityUtil.requireCurrentWorkspace(session), request);
    }

    @PostMapping("/{id}/submit")
    public AiKnowledgeAssetView submit(
            @PathVariable String id, HttpSession session) {
        return knowledgeService.submit(
                securityUtil.requireCurrentWorkspace(session), id);
    }

    @PostMapping("/{id}/review")
    public AiKnowledgeAssetView review(
            @PathVariable String id,
            @RequestBody AiKnowledgeReviewRequest request,
            HttpSession session) {
        return knowledgeService.review(
                securityUtil.requireCurrentWorkspace(session), id, request);
    }

    /** 将一次性授权元数据快照转换为待人工审核草稿。 */
    @PostMapping("/ingestions/metadata/{snapshotId}")
    public AiKnowledgeIngestionView ingestMetadata(
            @PathVariable String snapshotId,
            HttpSession session) {
        return ingestionService.ingestMetadata(
                securityUtil.requireCurrentWorkspace(session), snapshotId);
    }
}
