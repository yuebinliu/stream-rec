/*
 * MIT License
 *
 * Stream-rec  https://github.com/hua0512/stream-rec
 *
 * Copyright (c) 2024 hua0512 (https://github.com/hua0512)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package github.hua0512.app

import github.hua0512.data.config.AppConfig
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.DurationUnit
import kotlin.time.toDuration


class App(val json: Json) {

  companion object {
    @JvmStatic
    val logger: org.slf4j.Logger = LoggerFactory.getLogger(App::class.java)

    @JvmStatic
    val ffmpegPath = (System.getenv("FFMPEG_PATH") ?: "ffmpeg").run {
      // check if is windows
      if (System.getProperty("os.name").contains("win", ignoreCase = true)) {
        "$this.exe"
      } else {
        this
      }
    }

    @JvmStatic
    val streamLinkPath = (System.getenv("STREAMLINK_PATH") ?: "streamlink").run {
      // check if is windows
      if (System.getProperty("os.name").contains("win", ignoreCase = true)) {
        "$this.exe"
      } else {
        this
      }
    }
  }

  val client by lazy {
    HttpClient(CIO) {
      engine {
        pipelining = true
      }
      install(Logging) {
        logger = Logger.DEFAULT
        level = LogLevel.NONE
      }
      BrowserUserAgent()
      install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 5)
        exponentialDelay()
      }
      install(ContentEncoding) {
        gzip(0.9F)
      }

//      install(HttpCookies) {
//        storage = AcceptAllCookiesStorage()
//      }

      install(HttpTimeout) {
        requestTimeoutMillis = 5000
        connectTimeoutMillis = 5000
        socketTimeoutMillis = 30.toDuration(DurationUnit.SECONDS).inWholeMilliseconds
      }
      install(WebSockets) {
        pingInterval = 10_000
      }
    }
  }

  val config: AppConfig
    get() = appFlow.value ?: throw Exception("App config not initialized")

  private val appFlow = MutableStateFlow<AppConfig?>(null)

  fun updateConfig(config: AppConfig) {
    val previous = appFlow.value
    val isChanged = previous != config
    if (isChanged) {
      logger.info("App config changed : {}", config)
    }
    this.appFlow.value = config
  }

  /**
   * Closes the HTTP client.
   */
  fun releaseAll() {
    client.close()
  }
}