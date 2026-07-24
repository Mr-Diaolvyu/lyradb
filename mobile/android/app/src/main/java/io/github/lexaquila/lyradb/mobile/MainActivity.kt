package io.github.lexaquila.lyradb.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.lexaquila.lyradb.mobile.network.ApiClient
import io.github.lexaquila.lyradb.mobile.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppNav()
                }
            }
        }
    }
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf<io.github.lexaquila.lyradb.mobile.network.AuthUser?>(null) }
    var loading by remember { mutableStateOf(true) }
    var authRequired by remember { mutableStateOf(false) }

    // 启动探测 edition + 恢复会话
    LaunchedEffect(Unit) {
        scope.launch {
            try {
                val info = ApiClient.api.info().body()
                authRequired = info?.authRequired == true
                if (authRequired) {
                    val me = ApiClient.api.me().body()
                    user = me
                }
            } catch (_: Exception) {}
            loading = false
        }
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator()
        }
        authRequired && user == null -> LoginScreen(onSuccess = { user = it })
        else -> MainScaffold(
            user = user,
            onLogout = {
                scope.launch { try { ApiClient.api.logout() } catch (_: Exception) {} }
                user = null
            },
            content = { route ->
                NavHost(nav, startDestination = "sources") {
                    composable("sources") { MyDataSourcesScreen() }
                    composable("ai") { AiChatScreen() }
                    composable("approvals") { ApprovalsScreen() }
                    composable("audit") { AuditScreen() }
                }
            }
        )
    }
}
