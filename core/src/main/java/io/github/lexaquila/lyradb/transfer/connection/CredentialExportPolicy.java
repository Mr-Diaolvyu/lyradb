package io.github.lexaquila.lyradb.transfer.connection;

/**
 * 连接配置包中的数据库凭据处理策略。
 *
 * <p>调用方必须显式选择策略；共享格式不提供会隐式携带凭据的默认值。</p>
 */
public enum CredentialExportPolicy {

    /** 不导出数据库凭据值，仅保留凭据字段名，供导入端提示用户重新填写。 */
    OMIT,

    /** 明文导出数据库凭据，属于高风险模式，格式中会写入机器可读风险标识。 */
    PLAINTEXT,

    /** 使用用户提供的导出口令加密包含凭据的完整连接列表。 */
    PASSWORD_ENCRYPTED
}
