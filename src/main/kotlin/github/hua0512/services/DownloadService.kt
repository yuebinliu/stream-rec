package github.hua0512.services

import github.hua0512.app.App
import github.hua0512.data.StreamData
import github.hua0512.data.Streamer
import github.hua0512.data.StreamingPlatform
import github.hua0512.plugins.base.Download
import github.hua0512.plugins.danmu.douyin.DouyinDanmu
import github.hua0512.plugins.danmu.huya.HuyaDanmu
import github.hua0512.plugins.download.Douyin
import github.hua0512.plugins.download.Huya
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.coroutines.coroutineContext
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class DownloadService(val app: App, val uploadService: UploadService) {

  companion object {
    @JvmStatic
    private val logger: Logger = LoggerFactory.getLogger(DownloadService::class.java)
  }

  private fun getPlaformDownloader(platform: StreamingPlatform): Download = when (platform) {
    StreamingPlatform.HUYA -> Huya(app, HuyaDanmu(app))
    StreamingPlatform.DOUYIN -> Douyin(app, DouyinDanmu(app))
    else -> throw Exception("Platform not supported")
  }

  suspend fun run() = coroutineScope {
    val streamers = app.config.streamers
    val tasks = streamers.filter {
      !it.isLive && it.isActivated
    }.map {
      logger.info("Checking streamer ${it.name}")
      async {
        logger.debug("Adding job, current context : {}", coroutineContext)
        downloadStreamer(it)
      }
    }
    awaitAll(*tasks.toTypedArray())
  }

  private suspend fun downloadStreamer(streamer: Streamer) {
    val job = coroutineContext[Job]
    logger.debug("current job : {}", job)
    val coroutineName = CoroutineName("Streamer-${streamer.name}")
    val newContext = coroutineContext + coroutineName
    logger.debug("New context : {}", newContext)
    val newJob = SupervisorJob(job)
    newJob.invokeOnCompletion {
      logger.debug("Job completed : $it")
    }
    val newScope = CoroutineScope(newContext + newJob)
    logger.info("Starting download for streamer ${streamer.name}")
    newScope.launch {
      logger.debug("Launching coroutine : {}", this.coroutineContext)
      if (streamer.isLive) {
        logger.info("Streamer ${streamer.name} is already live")
        return@launch
      }
      val plugin = getPlaformDownloader(streamer.platform)
      val streamDataList = mutableListOf<StreamData>()
      var retryCount = 0
      val retryDelay = app.config.downloadRetryDelay
      val maxRetry = app.config.maxDownloadRetries
      while (true) {

        if (retryCount > maxRetry) {
          // stream is not live or without data
          if (streamDataList.isEmpty()) {
            retryCount = 0
            streamer.isLive = false
            continue
          }
          // stream finished with data
          logger.error("Max retry reached for ${streamer.name}")
          // call onStreamingFinished callback with the copy of the list
          streamer.downloadConfig?.onStreamingFinished?.let { it(streamDataList.toList()) }
          retryCount = 0
          streamer.isLive = false
          streamDataList.clear()
          delay(1.toDuration(DurationUnit.MINUTES))
          continue
        }
        val isLive = try {
          // check if streamer is live
          plugin.shouldDownload(streamer)
        } catch (e: Exception) {
          logger.error("Error while checking if ${streamer.name} is live : ${e.message}")
          false
        }

        if (isLive) {
          streamer.isLive = true
          // stream is live, start downloading
          val streamsData = try {
            plugin.download()
          } catch (e: Exception) {
            logger.error("Error while getting stream data for ${streamer.name} : ${e.message}")
            emptyList()
          }
          retryCount = 0
          logger.info("Final stream data : $streamsData")
          if (streamsData.isEmpty()) {
            logger.error("No data found for ${streamer.name}")
            continue
          }
          streamDataList.addAll(streamsData)
          logger.info("Stream for ${streamer.name} has ended")
        } else {
          logger.info("Streamer ${streamer.name} is not live")
        }
        retryCount++
        // if a data list is not empty, then it means the stream has ended
        // wait 30 seconds before checking again
        // otherwise wait 1 minute
        val duration = if (streamDataList.isNotEmpty()) {
          retryDelay.toDuration(DurationUnit.SECONDS)
        } else {
          1.toDuration(DurationUnit.MINUTES)
        }
        delay(duration)
      }
    }
  }
}