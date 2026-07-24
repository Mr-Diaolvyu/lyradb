package io.github.lexaquila.lyradb.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lexaquila.lyradb.mobile.network.ApiClient
import io.github.lexaquila.lyradb.mobile.network.AiChatRequest
import io.github.lexaquila.lyradb.mobile.network.AiChatResponse
import io.github.lexaquila.lyradb.mobile.network.ApprovalRequest
import io.github.lexaquila.lyradb.mobile.network.AuditLog
import io.github.lexaquila.lyradb.mobile.network.AuthUser
import io.github.lexaquila.lyradb.mobile.network.LoginRequest
import kotlinx.coroutines.launch

// ===== 登录 =====
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onSuccess: (AuthUser) -> Unit) {
    var u by remember { mutableStateOf("admin") }
    var p by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("LyraDB 企业版", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(u, { u = it }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(p, { p = it }, label = { Text("密码") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            scope.launch {
                loading = true; err = ""
                try {
                    val resp = ApiClient.api.login(LoginRequest(u, p))
                    if (resp.isSuccessful) resp.body()?.let { onSuccess(it) } else err = "登录失败"
                } catch (e: Exception) { err = e.message ?: "网络错误" }
                loading = false
            }
        }, modifier = Modifier.fillMaxWidth(), enabled = !loading) {
            Text(if (loading) "登录中…" else "登录")
        }
        if (err.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(err, color = MaterialTheme.colorScheme.error) }
    }
}

// ===== 主容器（底部导航） =====
@Composable
fun MainScaffold(user: AuthUser?, onLogout: () -> Unit, content: @Composable (String) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("数据源", "AI", "审批", "审计")
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(user?.displayName ?: "LyraDB") }, actions = {
                TextButton(onClick = onLogout) { Text("登出") }
            })
        },
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, label = { Text(t) },
                        icon = { Text((i + 1).toString()) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> MyDataSourcesScreen()
                1 -> AiChatScreen()
                2 -> ApprovalsScreen()
                3 -> AuditScreen()
            }
        }
    }
}

// ===== 我的数据源（逻辑，无连接信息） =====
@Composable
fun MyDataSourcesScreen() {
    var list by remember { mutableStateOf<List<io.github.lexaquila.lyradb.mobile.network.LogicalGrant>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        scope.launch {
            try { list = ApiClient.api.grantsMine().body() ?: emptyList() } catch (_: Exception) {}
            loading = false
        }
    }
    if (loading) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
    else if (list.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("暂无授权数据源") }
    else LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        items(list) { g ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(g.grantedSourceName, style = MaterialTheme.typography.titleMedium)
                    Text("能力: ${g.sqlCapability} · 上限 ${g.maxRowsPerQuery} 行")
                    g.allowedTables?.let { Text("可访问表: $it") }
                }
            }
        }
    }
}

// ===== AI 对话 =====
@Composable
fun AiChatScreen() {
    var msg by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf<AiChatResponse?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(msg, { msg = it }, label = { Text("用自然语言问数据…") },
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            scope.launch {
                loading = true; reply = null
                try {
                    reply = ApiClient.api.aiChat(AiChatRequest("订单库只读", msg, emptyList())).body()
                } catch (e: Exception) { reply = AiChatResponse(error = e.message) }
                loading = false
            }
        }, enabled = msg.isNotBlank() && !loading) { Text(if (loading) "思考中…" else "发送") }
        Spacer(Modifier.height(12.dp))
        reply?.let { r ->
            r.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            r.explanation?.let { Text(it) }
            r.sql?.let { Text("SQL:\n$it", style = MaterialTheme.typography.bodySmall) }
            if (r.needsApproval == true) Text("⚠ 该 DML 需审批")
        }
    }
}

// ===== 审批中心 =====
@Composable
fun ApprovalsScreen() {
    var list by remember { mutableStateOf<List<ApprovalRequest>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    fun load() { scope.launch {
        try { list = ApiClient.api.approvalsPending().body() ?: emptyList() } catch (_: Exception) {}
        loading = false
    } }
    LaunchedEffect(Unit) { load() }
    if (loading) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
    else LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        items(list) { a ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${a.operationType} · ${a.grantedSourceName ?: ""}")
                    Text("申请人: ${a.applicantName ?: ""} · 理由: ${a.reason ?: ""}")
                    Row {
                        Button(onClick = { scope.launch {
                            try { ApiClient.api.approve(a.id, mapOf("comment" to "同意")) } catch (_: Exception) {}
                            load()
                        } }) { Text("批准") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { scope.launch {
                            try { ApiClient.api.reject(a.id, mapOf("comment" to "驳回")) } catch (_: Exception) {}
                            load()
                        } }, colors = ButtonDefaults.buttonColors()) { Text("驳回") }
                    }
                }
            }
        }
    }
}

// ===== 操作审计 =====
@Composable
fun AuditScreen() {
    var rows by remember { mutableStateOf<List<AuditLog>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { scope.launch {
        try { rows = ApiClient.api.auditMine(0, 50).body()?.content ?: emptyList() } catch (_: Exception) {}
        loading = false
    } }
    if (loading) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
    else LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        items(rows) { a ->
            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text("${a.operationType} · ${a.grantedSourceName ?: ""} · ${if (a.success == true) "成功" else "失败"}")
                    Text("耗时 ${a.elapsedMs ?: 0}ms · ${a.createdAt ?: ""}",
                        style = MaterialTheme.typography.bodySmall)
                    a.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
