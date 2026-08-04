package io.github.lexaquila.lyradb.model.dto;

/** 令牌正文只在签发成功响应中出现一次。 */
public record AiGatewayTokenIssuedView(
        AiGatewayTokenView token,
        String plaintextToken,
        String warning) {
}
