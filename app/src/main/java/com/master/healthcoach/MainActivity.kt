package com.master.healthcoach

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.master.healthcoach.data.health.HealthConnectAvailability
import com.master.healthcoach.ui.HealthCoachRoot
import com.master.healthcoach.ui.MainViewModel
import com.master.healthcoach.ui.theme.HealthCoachTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HealthCoachTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val context = LocalContext.current
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = PermissionController.createRequestPermissionResultContract(),
                ) {
                    viewModel.refreshPermissions(syncWhenGranted = true)
                }
                HealthCoachRoot(
                    state = state,
                    onRequestPermissions = {
                        permissionLauncher.launch(state.requiredPermissions)
                    },
                    onOpenHealthConnect = {
                        if (state.availability == HealthConnectAvailability.UPDATE_REQUIRED) {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=com.google.android.apps.healthdata"),
                                ),
                            )
                        } else {
                            context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
                        }
                    },
                    onSync = viewModel::sync,
                    onSaveGoal = viewModel::saveGoal,
                    onSaveApiKey = viewModel::saveApiKey,
                    onClearApiKey = viewModel::clearApiKey,
                    onSendChat = viewModel::sendChat,
                    onAddChatAttachments = viewModel::addChatAttachments,
                    onRemoveChatAttachment = viewModel::removeChatAttachment,
                    onAnalyzeWeek = viewModel::analyzeWeek,
                    onClearLocalData = viewModel::clearLocalData,
                    onMessageShown = viewModel::consumeMessage,
                )
            }
        }
    }
}
