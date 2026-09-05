package com.layer.app.ui.home

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.layer.app.R
import com.layer.core.config.VpnConnectionParser

sealed interface AddConnectionUi {
    data object Closed : AddConnectionUi
    data object Choose : AddConnectionUi
    data class Link(val initial: String = "", val error: String? = null) : AddConnectionUi
}

object QrScanCanceled : Exception("canceled")

fun isQrScanUserDismissed(error: Throwable): Boolean {
    if (error is QrScanCanceled) return true
    val status = (error as? ApiException)?.statusCode
    if (status == CommonStatusCodes.CANCELED || status == CommonStatusCodes.INTERRUPTED) return true
    val message = error.message.orEmpty().lowercase()
    return "cancel" in message || "cancelled" in message || "canceled" in message
}

fun qrScanUserMessage(context: Context, error: Throwable): String? {
    if (isQrScanUserDismissed(error)) return null
    if (error is ApiException) return context.getString(R.string.qr_failed)
    return error.message?.trim()?.ifBlank { null } ?: context.getString(R.string.qr_failed)
}

fun scanConnectionQr(context: Context, onResult: (Result<String>) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .enableAutoZoom()
        .build()
    val scanner = runCatching {
        GmsBarcodeScanning.getClient(context, options)
    }.getOrElse { error ->
        onResult(Result.failure(error))
        return
    }
    val main = ContextCompat.getMainExecutor(context)
    runCatching {
        scanner.startScan()
            .addOnSuccessListener(main) { barcode ->
                val raw = barcode.rawValue ?: barcode.displayValue
                if (raw.isNullOrBlank()) {
                    onResult(Result.failure(IllegalStateException(context.getString(R.string.qr_empty))))
                } else {
                    onResult(VpnConnectionParser.parseQr(raw).map { it.raw })
                }
            }
            .addOnCanceledListener(main) { onResult(Result.failure(QrScanCanceled)) }
            .addOnFailureListener(main) { error -> onResult(Result.failure(error)) }
    }.onFailure { error ->
        onResult(Result.failure(error))
    }
}

@Composable
fun AddMethodChooser(
    onQr: () -> Unit,
    onLink: () -> Unit,
    onDismiss: () -> Unit,
) {
    val itemColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_title)) },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.add_qr)) },
                    supportingContent = { Text(stringResource(R.string.add_qr_subtitle)) },
                    leadingContent = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null) },
                    colors = itemColors,
                    modifier = Modifier.clickable(onClick = onQr),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.add_link)) },
                    supportingContent = { Text(stringResource(R.string.add_link_subtitle)) },
                    leadingContent = { Icon(Icons.Outlined.Link, contentDescription = null) },
                    colors = itemColors,
                    modifier = Modifier.clickable(onClick = onLink),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun AddLinkDialog(
    initial: String = "",
    initialError: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String, (Result<Unit>) -> Unit) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf(initialError) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.add_link_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.add_link_label)) },
                    placeholder = { Text(stringResource(R.string.add_link_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    enabled = !busy,
                )
                if (error != null) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    onConfirm(value) { result ->
                        busy = false
                        if (result.isFailure) {
                            error = result.exceptionOrNull()?.message
                        }
                    }
                },
            ) { Text(if (busy) stringResource(R.string.add_saving) else stringResource(R.string.action_add)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
