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

package github.hua0512.plugins.pandalive.download

import github.hua0512.app.App
import github.hua0512.data.config.DownloadConfig
import github.hua0512.data.config.DownloadConfig.PandaliveDownloadConfig
import github.hua0512.data.platform.PandaliveQuality
import github.hua0512.data.stream.StreamInfo
import github.hua0512.plugins.base.Download
import github.hua0512.plugins.pandalive.danmu.PandaliveDanmu
import github.hua0512.utils.nonEmptyOrNull
import github.hua0512.utils.withIOContext

/**
 * Pandalive live stream downloader.
 * @author hua0512
 * @date : 2024/5/10 13:22
 */
class Pandalive(app: App, override val danmu: PandaliveDanmu, override val extractor: PandaliveExtractor) :
  Download<PandaliveDownloadConfig>(app, danmu, extractor) {

  override fun createDownloadConfig() = PandaliveDownloadConfig(
    quality = app.config.pandaliveConfig.quality,
    cookies = app.config.pandaliveConfig.cookies,
  )

  override suspend fun shouldDownload(): Boolean {
    (config.cookies ?: app.config.pandaliveConfig.cookies)?.nonEmptyOrNull()?.also {
      extractor.cookies = it
    }

    val mediaInfo = try {
      withIOContext { extractor.extract() }
    } catch (e: Exception) {
      if (e is IllegalArgumentException || e is UnsupportedOperationException) throw e
      logger.error("Error extracting media info", e)
      return false
    }
    if (mediaInfo.live) {
      // init danmu
      danmu.apply {
        userIdx = extractor.userIdx
        token = extractor.token
      }
    }
    return getStreamInfo(mediaInfo, streamer, config)
  }

  override suspend fun <T : DownloadConfig> T.applyFilters(streams: List<StreamInfo>): StreamInfo {
    this as PandaliveDownloadConfig
    val userPreferredQuality = quality ?: app.config.pandaliveConfig.quality ?: PandaliveQuality.Source
    // source quality should be the first one
    if (userPreferredQuality == PandaliveQuality.Source) {
      return streams.first()
    }
    // resolution quality
    val preferredResolution = userPreferredQuality.value.removePrefix("p").toInt()
    // otherwise, filter by user defined quality
    val selectedStream = streams.filter { it.quality.contains(userPreferredQuality.value) }
    if (selectedStream.isEmpty()) {
      // if no stream found, return the first lower quality than user defined quality
      return streams.map { (it.extras["resolution"].toString().split("x").last().toIntOrNull() ?: 0) to it }.filter {
        it.first < preferredResolution
      }.maxByOrNull {
        it.first
      }?.second?.apply {
        logger.warn("No stream found with quality $userPreferredQuality, using ${this.quality} instead")
      } ?: run {
        logger.warn("No stream found with quality $userPreferredQuality, using the best available")
        streams.first()
      }
    }
    val filteredStream = selectedStream.map {
      (it.extras["resolution"].toString().split("x").last().toIntOrNull() ?: 0) to it
    }.filter {
      it.first >= preferredResolution
    }.minByOrNull {
      it.first
    }?.second ?: run {
      logger.warn("No stream found with quality $userPreferredQuality, using the best available")
      selectedStream.first()
    }
    return filteredStream
  }
}