package io.github.lexaquila.lyradb.transfer.connection;

/**
 * 连接配置包的机器可读凭据风险标识。
 */
public enum ConnectionPackageRisk {

    CREDENTIALS_OMITTED(false, false, true),
    PLAINTEXT_DATABASE_CREDENTIALS(true, false, false),
    PASSWORD_ENCRYPTED_DATABASE_CREDENTIALS(false, true, false);

    private final boolean plaintextDatabaseCredentials;
    private final boolean credentialsEncrypted;
    private final boolean credentialsOmitted;

    ConnectionPackageRisk(boolean plaintextDatabaseCredentials,
            boolean credentialsEncrypted, boolean credentialsOmitted) {
        this.plaintextDatabaseCredentials = plaintextDatabaseCredentials;
        this.credentialsEncrypted = credentialsEncrypted;
        this.credentialsOmitted = credentialsOmitted;
    }

    public boolean hasPlaintextDatabaseCredentials() {
        return plaintextDatabaseCredentials;
    }

    public boolean hasEncryptedCredentials() {
        return credentialsEncrypted;
    }

    public boolean hasOmittedCredentials() {
        return credentialsOmitted;
    }

    static ConnectionPackageRisk forPolicy(CredentialExportPolicy policy) {
        return switch (policy) {
            case OMIT -> CREDENTIALS_OMITTED;
            case PLAINTEXT -> PLAINTEXT_DATABASE_CREDENTIALS;
            case PASSWORD_ENCRYPTED -> PASSWORD_ENCRYPTED_DATABASE_CREDENTIALS;
        };
    }
}
