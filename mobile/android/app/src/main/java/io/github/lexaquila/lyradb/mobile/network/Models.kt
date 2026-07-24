package io.github.lexaquila.lyradb.mobile.network

import com.google.gson.annotations.SerializedName

data class AppInfo(
    val edition: String,
    val version: String,
    val authRequired: Boolean
)

data class AuthUser(
    val username: String,
    val displayName: String?,
    val roles: List<String>,
    val workspaces: List<Workspace>,
    @SerializedName("currentWorkspaceId") val currentWorkspaceId: String? = null
)

data class Workspace(val id: String, val name: String)

data class LoginRequest(val username: String, val password: String)

data class LogicalGrant(
    val id: String,
    val grantedSourceName: String,
    val allowedTables: String?,
    val blockedTables: String?,
    val sqlCapability: String,
    val maxRowsPerQuery: Int,
    val exportApprovedOnly: Boolean
)

data class QueryResult(
    val columns: List<String>,
    val rows: List<Map<String, Any?>>,
    val totalRows: Long,
    val elapsedMs: Long,
    val truncated: Boolean
)

data class AiChatRequest(
    val grantedSourceName: String,
    val message: String,
    val history: List<Map<String, String>>
)

data class AiChatResponse(
    val explanation: String?,
    val sql: String?,
    val error: String?,
    val executed: Boolean?,
    val needsApproval: Boolean?,
    val result: QueryResult?
)

data class ApprovalRequest(
    val id: String,
    val applicantName: String?,
    val operationType: String,
    val grantedSourceName: String?,
    val reason: String?,
    val status: String,
    val expiresAt: String?
)

data class AuditLog(
    val operationType: String,
    val grantedSourceName: String?,
    val username: String?,
    val success: Boolean?,
    val resultRows: Long?,
    val elapsedMs: Long?,
    val errorMessage: String?,
    val createdAt: String?
)

data class AuditPage(
    val content: List<AuditLog>,
    val totalElements: Long
)
