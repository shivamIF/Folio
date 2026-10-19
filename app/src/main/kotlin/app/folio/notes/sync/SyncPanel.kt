package app.folio.notes.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.launch

/**
 * The "Auto sync" row for Settings: tapping the words opens the Google Drive panel; the
 * switch turns automatic syncing on and off (turning it on without a linked Drive opens
 * the panel to link one).
 */
@Composable
fun AutoSyncRow(sync: SyncManager) {
    val s by sync.status.collectAsState()
    var panel by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.weight(1f).clickable { panel = true }.padding(vertical = 4.dp).testTag("auto-sync-label"),
        ) {
            Text("Auto sync", style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    s.needsSignIn -> "Sign in again to keep syncing"
                    s.account == null -> "Tap to link Google Drive"
                    else -> "Google Drive · ${s.account}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (s.needsSignIn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = s.autoSync && s.account != null,
            onCheckedChange = { on -> if (on && s.account == null) panel = true else sync.setAutoSync(on) },
            modifier = Modifier.semantics { contentDescription = "Auto sync" },
        )
    }
    if (panel) DrivePanel(sync, onDismiss = { panel = false })
}

/** Link, change or unlink the Google account whose Drive holds the notebooks; sync now; status. */
@Composable
fun DrivePanel(sync: SyncManager, onDismiss: () -> Unit) {
    val s by sync.status.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun linked(account: String) {
        sync.setAccount(account)
        sync.setAutoSync(true)
        sync.requestSync(force = true)
        message = null
    }

    // Step 2: Google's consent screen for Drive's app folder.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val account = pending ?: return@rememberLauncherForActivityResult
        if (res.resultCode == Activity.RESULT_OK) {
            val r = runCatching { Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(res.data) }.getOrNull()
            if (r?.accessToken != null) linked(account) else message = "Google didn't grant access to Drive."
        } else message = "Not linked: access to Drive wasn't allowed."
    }
    // Step 1: pick the Google account.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val account = res.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) ?: return@rememberLauncherForActivityResult
        pending = account
        scope.launch {
            try {
                val r = DriveAuth.authorize(ctx, account)
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) consent.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                else if (r.accessToken != null) linked(account)
                else message = "Google sign-in didn't return access."
            } catch (e: Exception) {
                message = "Google sign-in isn't set up for this app yet (docs/SYNC_SETUP.md): ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }
    fun chooseAccount() = pick.launch(
        AccountManager.newChooseAccountIntent(s.account?.let { Account(it, "com.google") }, null, arrayOf("com.google"), null, null, null, null),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Google Drive sync") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Notebooks sync between your devices through a private folder in your own Google Drive. " +
                        "It's free and uses your Drive storage; other apps can't see the folder.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(if (s.account != null) "Linked to ${s.account}" else "Not linked", style = MaterialTheme.typography.titleSmall)
                if (s.account != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto sync", Modifier.weight(1f))
                        Switch(s.autoSync, onCheckedChange = sync::setAutoSync)
                    }
                    Text(
                        when {
                            s.running -> "Syncing…"
                            s.lastSyncAt != null -> "Last synced " + DateUtils.getRelativeTimeSpanString(s.lastSyncAt!!) + (s.lastResult?.let { " · $it" } ?: "")
                            else -> "Not synced yet"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("sync-status"),
                    )
                    if (s.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                (message ?: s.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (s.account == null || s.needsSignIn) {
                    Button(onClick = ::chooseAccount, modifier = Modifier.fillMaxWidth()) {
                        Text(if (s.account == null) "Link Google Drive" else "Sign in again")
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { sync.requestSync(force = true) }, enabled = !s.running) { Text("Sync now") }
                        OutlinedButton(onClick = ::chooseAccount) { Text("Change account") }
                    }
                    TextButton(onClick = { sync.setAutoSync(false); sync.setAccount(null) }) {
                        Text("Unlink (keeps the copy in Drive)")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
