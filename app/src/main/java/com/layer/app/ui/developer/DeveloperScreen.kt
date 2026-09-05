package com.layer.app.ui.developer

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.layer.app.BuildConfig
import com.layer.app.R
import com.layer.app.ui.LocalAppContainer
import com.layer.app.ui.components.InfoRow
import com.layer.app.ui.components.TonalCard
import com.layer.core.diagnostics.Branding
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.launch

private val WiredBlack = Color(0xFF0B0B0B)
private val WiredGreen = Color(0xFF7DFF9A)
private val WiredDim = Color(0xFF6FA87C)
private const val OPERATOR_HANDLE = "@jast86"
private const val OPERATOR_URL = "https://t.me/jast86"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperScreen(
    onBack: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val vpn = container.vpnController.status.collectAsStateWithLifecycle().value
    val libbox = remember {
        Branding.libboxVersion(runCatching { Libbox.version() }.getOrNull())
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("NAVI") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = WiredBlack,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "PRESENT DAY\nPRESENT TIME.",
                        color = WiredGreen,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Close the world, open the navi.",
                        color = WiredDim,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Protocol 7 · Layer\noperator $OPERATOR_HANDLE exists in the Wired.",
                        color = WiredGreen,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            TonalCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.navi_build), style = MaterialTheme.typography.titleMedium)
                    InfoRow(stringResource(R.string.navi_app), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    InfoRow(stringResource(R.string.navi_package), context.packageName)
                    InfoRow("libbox", libbox)
                    InfoRow("Android", "${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
                    InfoRow(stringResource(R.string.navi_device), "${Build.MANUFACTURER} ${Build.MODEL}")
                    InfoRow("Debug", BuildConfig.DEBUG.toString())
                    InfoRow("VPN", "${vpn.state} · ${vpn.message}")
                }
            }

            TonalCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.navi_operator), style = MaterialTheme.typography.titleMedium)
                    Text(
                        OPERATOR_HANDLE,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("operator", OPERATOR_URL))
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.navi_link_copied)) }
                        },
                    )
                    Text(
                        "皆でレインを愛そう。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            FilledTonalButton(
                onClick = onDiagnostics,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.navi_open_diagnostics)) }

            Spacer(Modifier.height(24.dp))
        }
    }
}
