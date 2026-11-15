package app.folio.desktop.sync

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.Desktop
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Google hasn't been set up, or the user has to sign in (again). */
class NeedsSignIn(message: String) : Exception(message)

/**
 * Google sign-in for the Mac: OAuth 2.0 for installed apps (the system browser, a loopback
 * redirect on 127.0.0.1 and PKCE). Only the `drive.appdata` scope is asked for, the same
 * private app folder the tablet and phone sync through.
 *
 * It needs a "Desktop app" OAuth client from the same Google Cloud project as the Android
 * app (Drive's app folder belongs to the project): its id and secret go in [credentialsFile]
 * as `{"client_id": "...", "client_secret": "..."}`. The refresh token is kept in
 * [tokenFile], readable only by the user.
 */
class GoogleSignIn(dir: File) {
    val credentialsFile = File(dir, "google-oauth.json")
    private val tokenFile = File(dir, "google-token.json")
    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private var access: String? = null
    private var accessUntil = 0L

    private fun read(f: File): JsonObject? = runCatching { json.parseToJsonElement(f.readText()).jsonObject }.getOrNull()
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    /** Client id and secret; the file may be ours or the one Google's console downloads (`{"installed": {...}}`). */
    private fun client(): Pair<String, String>? {
        val o = read(credentialsFile) ?: return null
        val c = (o["installed"] as? JsonObject) ?: o
        return (c.str("client_id") ?: return null) to (c.str("client_secret") ?: "")
    }

    val configured: Boolean get() = client() != null
    val signedIn: Boolean get() = read(tokenFile)?.str("refresh_token") != null

    fun signOut() { tokenFile.delete(); access = null }

    /** Opens the browser for the user to approve, and waits (up to three minutes) for Google to send them back. */
    fun signIn() {
        val (id, secret) = client() ?: throw NeedsSignIn("Google sign-in isn't set up on this Mac yet.")
        val verifier = randomUrlSafe(48)
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomUrlSafe(16)
        val code = CompletableFuture<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val q = (ex.requestURI.rawQuery ?: "").split('&').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }.associate { it[0] to java.net.URLDecoder.decode(it[1], "UTF-8") }
            val ok = q["state"] == state && q["code"] != null
            val body = (if (ok) "Folio is signed in. You can close this tab." else "Sign-in didn't finish (${q["error"] ?: "unexpected reply"}). Close this tab and try again in Folio.").toByteArray()
            ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            if (ok) code.complete(q.getValue("code")) else if (q.isNotEmpty()) code.completeExceptionally(NeedsSignIn("Google sign-in was cancelled or refused."))
        }
        server.start()
        try {
            val redirect = "http://127.0.0.1:${server.address.port}"
            val url = "https://accounts.google.com/o/oauth2/v2/auth?" + form(
                "client_id" to id, "redirect_uri" to redirect, "response_type" to "code", "scope" to SCOPE,
                "code_challenge" to challenge, "code_challenge_method" to "S256", "state" to state, "access_type" to "offline", "prompt" to "consent",
            )
            Desktop.getDesktop().browse(URI(url))
            val got = try { code.get(3, TimeUnit.MINUTES) } catch (e: java.util.concurrent.TimeoutException) { throw NeedsSignIn("Sign-in timed out. Try again.") }
                catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
            val r = post(form("client_id" to id, "client_secret" to secret, "code" to got, "code_verifier" to verifier, "grant_type" to "authorization_code", "redirect_uri" to redirect))
            val refresh = r.str("refresh_token") ?: throw NeedsSignIn("Google didn't grant ongoing access. Try signing in again.")
            tokenFile.parentFile.mkdirs()
            tokenFile.writeText(buildJsonObject { put("refresh_token", refresh) }.toString())
            runCatching { Files.setPosixFilePermissions(tokenFile.toPath(), PosixFilePermissions.fromString("rw-------")) }
            remember(r)
        } finally {
            server.stop(0)
        }
    }

    /** An access token for Drive, refreshed when it is about to expire or [forceRefresh] (after a 401). */
    @Synchronized
    fun token(forceRefresh: Boolean): String {
        access?.let { if (!forceRefresh && System.currentTimeMillis() < accessUntil) return it }
        val (id, secret) = client() ?: throw NeedsSignIn("Google sign-in isn't set up on this Mac yet.")
        val refresh = read(tokenFile)?.str("refresh_token") ?: throw NeedsSignIn("Sign in to Google to sync.")
        val r = try { post(form("client_id" to id, "client_secret" to secret, "refresh_token" to refresh, "grant_type" to "refresh_token")) }
            catch (e: NeedsSignIn) { signOut(); throw NeedsSignIn("Google access ended. Sign in again to keep syncing.") }
        return remember(r)
    }

    private fun remember(r: JsonObject): String {
        val t = r.str("access_token") ?: throw NeedsSignIn("Google didn't return an access token.")
        access = t
        accessUntil = System.currentTimeMillis() + ((r.str("expires_in")?.toLongOrNull() ?: 3600L) - 60L) * 1000L
        return t
    }

    private fun post(body: String): JsonObject {
        val res = http.send(
            HttpRequest.newBuilder(URI("https://oauth2.googleapis.com/token")).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val o = runCatching { json.parseToJsonElement(res.body()).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        if (res.statusCode() == 400 || res.statusCode() == 401) throw NeedsSignIn(o.str("error_description") ?: o.str("error") ?: "Google refused the sign-in.")
        if (res.statusCode() !in 200..299) throw java.io.IOException("Google sign-in failed (HTTP ${res.statusCode()}).")
        return o
    }

    private fun form(vararg p: Pair<String, String>) = p.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun randomUrlSafe(bytes: Int) = b64(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

    companion object { const val SCOPE = "https://www.googleapis.com/auth/drive.appdata" }
}
