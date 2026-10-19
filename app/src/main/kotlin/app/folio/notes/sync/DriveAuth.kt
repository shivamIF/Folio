package app.folio.notes.sync

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google authorisation for Drive sync via Play services. Only `drive.appdata` is requested:
 * Folio's own hidden folder in the user's Drive, which other apps (and Folio) can't see beyond.
 * No client secret lives in the app; Google matches the app by package name and signing key
 * (registered once by the user in their own free Google Cloud project, see docs/SYNC_SETUP.md).
 */
object DriveAuth {
    val SCOPE = Scope("https://www.googleapis.com/auth/drive.appdata")

    class NeedsSignIn : Exception("Google sign-in needed")

    private fun request(account: String) = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(SCOPE))
        .setAccount(Account(account, GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE))
        .build()

    /** Interactive: may return a result that needs the user (`hasResolution()`). */
    suspend fun authorize(context: Context, account: String): AuthorizationResult = suspendCancellableCoroutine { c ->
        Identity.getAuthorizationClient(context).authorize(request(account))
            .addOnSuccessListener { c.resume(it) }
            .addOnFailureListener { c.resumeWithException(it) }
    }

    private class Cached(val account: String, val token: String, val at: Long)
    @Volatile private var cached: Cached? = null

    /**
     * Background thread: an access token, or [NeedsSignIn] if the user must approve again.
     * Reused for [TOKEN_REUSE_MS] (Google's tokens last an hour); a 401 asks with [forceRefresh].
     */
    fun token(context: Context, account: String, forceRefresh: Boolean): String {
        val c = cached
        val now = android.os.SystemClock.elapsedRealtime()
        if (!forceRefresh && c != null && c.account == account && now - c.at < TOKEN_REUSE_MS) return c.token
        if (forceRefresh) c?.let { runCatching { GoogleAuthUtil.clearToken(context, it.token) } }
        val r = Tasks.await(Identity.getAuthorizationClient(context).authorize(request(account)))
        if (r.hasResolution()) throw NeedsSignIn()
        return (r.accessToken ?: throw NeedsSignIn()).also { cached = Cached(account, it, now) }
    }

    private const val TOKEN_REUSE_MS = 40 * 60_000L
}
