package com.layer.app.ui.update

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.layer.app.R
import com.layer.app.data.UpdateCheckResult

@Composable
fun UpdateAvailableDialog(
    update: UpdateCheckResult.Available,
    onDismiss: () -> Unit,
    onLater: () -> Unit,
) {
    val context = LocalContext.current
    val url = update.apkUrl?.ifBlank { null } ?: update.pageUrl
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_available_title)) },
        text = {
            Text(stringResource(R.string.update_available_body, update.installed, update.latest))
        },
        confirmButton = {
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.update_download)) }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) }
        },
    )
}
