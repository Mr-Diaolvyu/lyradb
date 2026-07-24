package io.github.lexaquila.lyradb.mobile.network

import retrofit2.Response
import retrofit2.http.*

/** 后端接口镜像（企业版契约） */
interface ApiService {

    @GET("app/info")
    suspend fun info(): Response<AppInfo>

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Response<AuthUser>

    @GET("auth/me")
    suspend fun me(): Response<AuthUser>

    @POST("auth/logout")
    suspend fun logout(): Response<Void>

    @POST("auth/workspace")
    suspend fun switchWorkspace(@Body body: Map<String, String>): Response<Void>

    @GET("grants/mine")
    suspend fun grantsMine(): Response<List<LogicalGrant>>

    @POST("ent/query")
    suspend fun query(@Body body: Map<String, String?>): Response<QueryResult>

    @POST("ai/chat")
    suspend fun aiChat(@Body body: AiChatRequest): Response<AiChatResponse>

    @GET("approvals/pending")
    suspend fun approvalsPending(): Response<List<ApprovalRequest>>

    @GET("approvals")
    @JvmSuppressWildcards
    suspend fun approvals(@Query("mine") mine: Boolean): Response<List<ApprovalRequest>>

    @POST("approvals/{id}/approve")
    suspend fun approve(@Path("id") id: String, @Body body: Map<String, String?>): Response<ApprovalRequest>

    @POST("approvals/{id}/reject")
    suspend fun reject(@Path("id") id: String, @Body body: Map<String, String?>): Response<ApprovalRequest>

    @GET("audit/mine")
    suspend fun auditMine(@Query("page") page: Int, @Query("size") size: Int): Response<AuditPage>
}
