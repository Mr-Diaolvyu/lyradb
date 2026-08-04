package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.dto.AiGatewayTokenIssueRequest;
import io.github.lexaquila.lyradb.model.dto.AiGatewayTokenIssuedView;
import io.github.lexaquila.lyradb.model.dto.AiGatewayTokenView;
import io.github.lexaquila.lyradb.service.AiGatewayTokenService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 登录会话内的 Agent Gateway 身份管理。 */
@RestController
@RequestMapping("/ai/gateway/tokens")
public class AiGatewayAdminController {

    private final AiGatewayTokenService tokenService;
    private final SecurityUtil securityUtil;

    public AiGatewayAdminController(
            AiGatewayTokenService tokenService,
            SecurityUtil securityUtil) {
        this.tokenService = tokenService;
        this.securityUtil = securityUtil;
    }

    @GetMapping
    public List<AiGatewayTokenView> list(HttpSession session) {
        return tokenService.list(
                securityUtil.requireCurrentWorkspace(session));
    }

    @PostMapping
    public AiGatewayTokenIssuedView issue(
            @RequestBody AiGatewayTokenIssueRequest request,
            HttpSession session) {
        return tokenService.issue(
                securityUtil.requireCurrentWorkspace(session), request);
    }

    @PostMapping("/{tokenId}/revoke")
    public AiGatewayTokenView revoke(
            @PathVariable String tokenId,
            HttpSession session) {
        return tokenService.revoke(
                securityUtil.requireCurrentWorkspace(session), tokenId);
    }
}
