package io.github.kdroidfilter.seforimapp.network

import dev.nucleusframework.nativehttp.ktor.installNativeSsl
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Provides a configured Ktor HttpClient that uses native OS certificate stores.
 */
object KtorConfig {
    /**
     * Creates a Ktor HttpClient configured with native trusted roots via Nucleus.
     *
     * @param json Custom JSON configuration (default: ignoreUnknownKeys + isLenient)
     * @return Configured HttpClient instance
     */
    fun createHttpClient(
        json: Json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            },
    ): HttpClient =
        HttpClient(CIO) {
            installNativeSsl()
            // Only small GitHub API calls go through this client (bundle downloads use
            // HttpsConnectionFactory); without timeouts a stalled connection hangs onboarding forever.
            install(HttpTimeout) {
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 60_000
                requestTimeoutMillis = 120_000
            }
            install(ContentNegotiation) {
                json(json)
            }
        }
}
