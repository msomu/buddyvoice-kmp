package com.msomu.buddyvoice.voice

import java.io.File
import java.util.Properties

/**
 * Proxy settings for the desktop sample app, resolved **at runtime** — never baked
 * into a distributable. Sources, in order:
 *
 * 1. `local.properties` (gitignored) found by walking up from the working directory:
 *    `buddyvoice.proxyBaseUrl` / `buddyvoice.proxyKey` — same keys the Android app uses.
 * 2. Environment variables `BUDDYVOICE_PROXY_BASE_URL` / `BUDDYVOICE_PROXY_KEY`
 *    (the only option for a packaged app launched outside the repo).
 *
 * Returns `null` when no base URL is configured; see desktopApp/README.md.
 */
data class DesktopProxyConfig(
    val proxyBaseUrl: String,
    val proxyKey: String?,
) {
    companion object {

        fun load(): DesktopProxyConfig? {
            val properties = findLocalProperties()?.let { file ->
                Properties().apply { file.inputStream().use { load(it) } }
            }
            val baseUrl = properties?.getProperty("buddyvoice.proxyBaseUrl")?.ifBlank { null }
                ?: System.getenv("BUDDYVOICE_PROXY_BASE_URL")?.ifBlank { null }
                ?: return null
            val key = properties?.getProperty("buddyvoice.proxyKey")?.ifBlank { null }
                ?: System.getenv("BUDDYVOICE_PROXY_KEY")?.ifBlank { null }
            return DesktopProxyConfig(baseUrl, key)
        }

        /**
         * `./gradlew :desktopApp:run` starts in a project directory, not necessarily
         * the repo root, so walk a few levels up looking for `local.properties`.
         */
        private fun findLocalProperties(): File? =
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .take(4)
                .map { File(it, "local.properties") }
                .firstOrNull { it.isFile }
    }
}
