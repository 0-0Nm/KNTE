package com.knte.preview.modelconfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Model configuration screen: endpoint + model + credential input.
 *
 * The credential field is a short-lived, UI-LOCAL draft string held in a plain
 * `remember { mutableStateOf("") }`. That is NOT durable state: a rotation or leaving
 * this composable drops it, and it is deliberately NOT restored — no "true recovery" of
 * a typed key is claimed anywhere. The ViewModel keeps the authoritative in-memory
 * draft and publishes only non-secret presence (`draftCredentialPending`), so after a
 * rotation the pending/replace hints below are driven by STATE, never by the local
 * field, and a VM-kept key can never be saved "silently" while the button happens to
 * read plain "保存": whenever a pending key exists and a stored credential would be
 * replaced, the hint says so and the button reads "替换并保存".
 *
 * Local-field clearing is NOT tied to the save click (that used to drop the typed key
 * even when the save failed). Instead [ModelConfigUiState.secretResetEpoch] — bumped by
 * the VM only when the secret was actually consumed (successful save, successful reload,
 * explicit cancel/discard) — drives a [LaunchedEffect] that clears the local copy. A
 * FAILED save bumps nothing, so the field keeps the typed key and the pending hints
 * stay truthful.
 *
 * Only credential PRESENCE is displayed; the stored key is never shown, never restored
 * and never included in any published state object.
 *
 * Parent wires navigation and the leave gate; that wiring is NOT part of this slice.
 * This composable only exposes hasUnsavedChanges/canLeave through the ViewModel. */
@Composable
fun ModelConfigScreen(viewModel: ModelConfigViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Short-lived, UI-local draft key. Not part of the state flow; not durable.
    var secretDraft by remember { mutableStateOf("") }
    var replaceAsk by remember { mutableStateOf(false) }

    // VM-driven secret reset sync: only a consumed secret (successful save, reload,
    // explicit cancel/discard) may clear the local field copy. A failed save bumps
    // nothing, so the typed key stays both in the VM and in this field.
    LaunchedEffect(state.secretResetEpoch) {
        secretDraft = ""
        replaceAsk = false
    }

    fun attemptSave() {
        // No unconditional local clearing here: the VM owns the draft and keeps it on
        // failure. Clearing happens only via the reset-epoch effect above.
        viewModel.save()
        replaceAsk = false
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("模型接口配置", style = MaterialTheme.typography.titleLarge)
            Text(
                "只保存到本机：HTTPS 接口地址、模型名称与凭据。保存不联网、不获取模型列表、" +
                    "不发送测试请求。凭据以 Keystore 加密后存放，界面不回显已保存密钥。",
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = state.endpoint,
                onValueChange = viewModel::onEndpointChanged,
                enabled = !state.busy && !state.loading,
                label = { Text("HTTPS 接口地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.model,
                onValueChange = viewModel::onModelChanged,
                enabled = !state.busy && !state.loading,
                label = { Text("模型名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = secretDraft,
                onValueChange = {
                    secretDraft = it
                    viewModel.onCredentialChanged(it)
                },
                enabled = !state.busy && !state.loading,
                label = { Text("凭据（密钥）") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                when (state.credentialPresence) {
                    CredentialPresence.Present -> "已保存凭据：有（不回显）。留空保存表示保留原密钥。"
                    CredentialPresence.NotConfigured -> "已保存凭据：无。"
                },
                style = MaterialTheme.typography.bodySmall
            )

            // Pending hints are driven by state.draftCredentialPending, NEVER by the
            // local field: after a rotation the field is empty while the VM still holds
            // the typed key, and the pending/replace intent must stay visible and honest.
            if (state.credentialPresence == CredentialPresence.Present &&
                state.draftCredentialPending
            ) {
                Text(
                    "输入了新的非空密钥：保存将替换已保存凭据。取消则保留原凭据。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.credentialPresence == CredentialPresence.NotConfigured &&
                state.draftCredentialPending
            ) {
                Text(
                    "已输入新的非空密钥：保存将首次配置凭据（不回显密钥）。",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            state.error?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) } }
            state.notice?.let { SelectionContainer { Text(it) } }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = { attemptSave() }, enabled = !state.busy && !state.loading) {
                Text(if (state.credentialPresence == CredentialPresence.Present &&
                    state.draftCredentialPending
                ) "替换并保存" else "保存")
            }
            TextButton(
                onClick = { viewModel.cancel() },
                enabled = !state.busy && !state.loading
            ) { Text("取消") }
            if (state.credentialPresence == CredentialPresence.Present &&
                state.draftCredentialPending
            ) {
                TextButton(
                    onClick = { replaceAsk = true },
                    enabled = !state.busy && !state.loading
                ) { Text("明确替换密钥") }
            }
        }
    }

    if (replaceAsk) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { replaceAsk = false },
            title = { Text("替换已保存凭据？") },
            text = {
                Text(
                    "将用本次输入的非空密钥替换已保存凭据，旧凭据不再可用。" +
                        "保存本身不联网、不测试连接。取消则不写入。"
                )
            },
            confirmButton = {
                TextButton(onClick = { attemptSave() }, enabled = !state.busy && !state.loading) { Text("替换并保存") }
            },
            dismissButton = { TextButton(onClick = { replaceAsk = false }) { Text("取消") } }
        )
    }
}
