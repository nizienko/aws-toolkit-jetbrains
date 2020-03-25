// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.cloudwatch.logs

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtilRt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient
import software.amazon.awssdk.services.cloudwatchlogs.model.GetLogEventsRequest
import software.aws.toolkits.core.utils.error
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.jetbrains.services.cloudwatch.logs.actions.OpenStreamInEditor
import software.aws.toolkits.jetbrains.services.cloudwatch.logs.actions.buildStringFromLogsOutput
import software.aws.toolkits.jetbrains.utils.ApplicationThreadPoolScope
import software.aws.toolkits.jetbrains.utils.getCoroutineUiContext
import software.aws.toolkits.jetbrains.utils.notifyError
import software.aws.toolkits.jetbrains.utils.notifyInfo
import software.aws.toolkits.resources.message
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.streams.asSequence

class LogStreamDownloadTask(project: Project, val client: CloudWatchLogsClient, val logGroup: String, val logStream: String) :
    Task.Backgroundable(project, message("cloudwatch.logs.opening_in_editor", logStream), true),
    CoroutineScope by ApplicationThreadPoolScope("OpenLogStreamInEditor") {
    private val edt = getCoroutineUiContext()

    override fun run(indicator: ProgressIndicator) = runBlocking {
        // Default content load limit is 20MB, default per page is 1MB/10000 log entries. so we load MaxLength/1MB
        // until we give up and prompt the user to save to file
        val maxPages = FileUtilRt.getUserContentLoadLimit() / (1 * FileUtilRt.MEGABYTE)
        val startTime = Instant.now()
        val buffer = StringBuilder()
        val request = GetLogEventsRequest
            .builder()
            .startFromHead(true)
            .logGroupName(logGroup)
            .logStreamName(logStream)
            .endTime(startTime.toEpochMilli())
        val getRequest = client.getLogEventsPaginator(request.build())
        getRequest.stream().asSequence().forEachIndexed { index, it ->
            indicator.checkCanceled()
            buffer.append(it.events().buildStringFromLogsOutput())
            // This might look off by 1 because for example if we are at index 20, it's the
            // 21st iteration, but at this point we won't try to open in a file so we bail from
            // streaming at the correct time
            if (index >= maxPages) {
                runBlocking {
                    request.nextToken(it.nextForwardToken())
                    if (promptWriteToFile() == Messages.OK) {
                        ProgressManager.getInstance().run(
                            LogStreamDownloadToFileTask(
                                project,
                                client,
                                logGroup,
                                logStream,
                                buffer.toString(),
                                request.build()
                            )
                        )
                    }
                    // Cancel this Task no matter what. If the user has agreed to download to a file,
                    // the download to a file task will handle everything from here
                    indicator.cancel()
                }
            }
        }

        OpenStreamInEditor.open(project, edt, logStream, buffer.toString())
    }

    private suspend fun promptWriteToFile(): Int = withContext(edt) {
        return@withContext Messages.showOkCancelDialog(
            project,
            message("cloudwatch.logs.stream_too_big_message", logStream),
            message("cloudwatch.logs.stream_too_big"),
            message("cloudwatch.logs.stream_save_to_file", logStream),
            Messages.CANCEL_BUTTON,
            AllIcons.General.QuestionDialog
        )
    }
}

class LogStreamDownloadToFileTask(
    project: Project,
    private val client: CloudWatchLogsClient,
    private val logGroup: String,
    private val logStream: String,
    private val buffer: String,
    private val request: GetLogEventsRequest? = null
) : Task.Backgroundable(project, message("cloudwatch.logs.saving_to_disk", logStream), true) {
    private val edt = getCoroutineUiContext()

    override fun run(indicator: ProgressIndicator) = runBlocking {
        val startTime = Instant.now()
        val finalRequest = request ?: GetLogEventsRequest
            .builder()
            .startFromHead(true)
            .logGroupName(logGroup)
            .logStreamName(logStream)
            .endTime(startTime.toEpochMilli())
            .build()
        promptToDownload(indicator, finalRequest, buffer)
    }

    private suspend fun promptToDownload(indicator: ProgressIndicator, request: GetLogEventsRequest, buffer: String) {
        val descriptor = FileSaverDescriptor(message("s3.download.object.action"), message("s3.download.object.description"))
        val saveLocation = withContext(edt) {
            val destination = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            destination.save(null, null)
        }
        if (saveLocation != null) {
            streamLogStreamToFile(indicator, request, saveLocation.file, buffer)
        }
    }

    private fun streamLogStreamToFile(indicator: ProgressIndicator, request: GetLogEventsRequest, file: File, buffer: String) {
        try {
            // Delete the existing file if one exists so we don't append to it
            Files.deleteIfExists(file.toPath())
            file.appendText(buffer)
            val getRequest = client.getLogEventsPaginator(request)
            getRequest.stream().forEach {
                indicator.checkCanceled()
                val str = it.events().buildStringFromLogsOutput()
                file.appendText(str)
            }
            notifyInfo(
                project = project,
                title = message("aws.notification.title"),
                content = message("cloudwatch.logs.saving_to_disk_succeeded", logStream, file.path)
            )
        } catch (e: Exception) {
            LOG.error(e) { "Exception thrown while downloading large log stream" }
            e.notifyError(project = project, title = message("cloudwatch.logs.saving_to_disk_failed", logStream))
        }
    }

    companion object {
        val LOG = getLogger<LogStreamDownloadToFileTask>()
    }
}