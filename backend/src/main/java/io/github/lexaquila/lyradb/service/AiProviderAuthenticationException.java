package io.github.lexaquila.lyradb.service;

/** 外部 AI 服务拒绝了当前 Provider 的认证信息。 */
public class AiProviderAuthenticationException extends RuntimeException {
    public AiProviderAuthenticationException() {
        super("AI Provider 鉴权失败");
    }
}
