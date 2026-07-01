package sg.act.domain

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import sg.act.domain.ui.acceptance.AcceptanceScreen
import sg.act.domain.ui.chat.ChatScreen
import sg.act.domain.ui.chat.ChatViewModel
import sg.act.domain.ui.settings.SettingsScreen
import sg.act.domain.ui.settings.SettingsViewModel
import sg.act.domain.ui.theme.DomainTheme

private object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as DomainApp).container

        setContent {
            DomainTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var accepted by remember { mutableStateOf(container.acceptanceStore.isAccepted()) }
                    if (!accepted) {
                        AcceptanceScreen(
                            onAccept = { container.acceptTerms(); accepted = true },
                            onDecline = { finish() },
                        )
                        return@Surface
                    }
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = Routes.CHAT) {
                        composable(Routes.CHAT) {
                            val vm: ChatViewModel = viewModel(
                                factory = ChatViewModel.Factory(
                                    container.repository,
                                    container.modelManager,
                                    container.modelProfileStore,
                                ),
                            )
                            ChatScreen(
                                viewModel = vm,
                                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                            )
                        }
                        composable(Routes.SETTINGS) {
                            val vm: SettingsViewModel = viewModel(
                                factory = SettingsViewModel.Factory(
                                    application,
                                    container.repository,
                                    container.modelManager,
                                    container.deviceCapabilities,
                                    container.modelProfileStore,
                                ),
                            )
                            SettingsScreen(
                                viewModel = vm,
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
}
