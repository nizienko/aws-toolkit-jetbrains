// Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.cloudwatch.logs

import com.intellij.testFramework.ProjectRule
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ListTableModel
import com.nhaarman.mockitokotlin2.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.debug.junit4.CoroutinesTimeout
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient
import software.amazon.awssdk.services.cloudwatchlogs.model.GetLogEventsRequest
import software.amazon.awssdk.services.cloudwatchlogs.model.GetLogEventsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.OutputLogEvent
import software.aws.toolkits.jetbrains.core.MockClientManagerRule
import software.aws.toolkits.jetbrains.utils.waitForFalse
import software.aws.toolkits.jetbrains.utils.waitForModelToBeAtLeast
import software.aws.toolkits.jetbrains.utils.waitForTrue
import software.aws.toolkits.resources.message
import java.time.Duration
import java.util.concurrent.CompletableFuture

// ExperimentalCoroutinesApi is needed for TestCoroutineScope
@ExperimentalCoroutinesApi
class LogStreamListActorTest {
    @JvmField
    @Rule
    val projectRule = ProjectRule()

    @JvmField
    @Rule
    val mockClientManagerRule = MockClientManagerRule(projectRule)

    @JvmField
    @Rule
    val timeout = CoroutinesTimeout.seconds(15)

    private val testCoroutineScope: TestCoroutineScope = TestCoroutineScope()

    @After
    fun after() {
        testCoroutineScope.cleanupTestCoroutines()
    }

    @Test
    fun modelIsPopulated() {
        val mockClient = mockClientManagerRule.create<CloudWatchLogsAsyncClient>()
        whenever(mockClient.getLogEvents(Mockito.any<GetLogEventsRequest>()))
            .thenReturn(CompletableFuture.completedFuture(GetLogEventsResponse.builder().events(OutputLogEvent.builder().message("message").build()).build()))
        val tableModel = ListTableModel<LogStreamEntry>()
        val table = TableView<LogStreamEntry>(tableModel)
        val coroutine = LogStreamListActor(projectRule.project, table, "abc", "def")
        runBlocking {
            coroutine.channel.send(LogStreamActor.Message.LOAD_INITIAL())
            tableModel.waitForModelToBeAtLeast(1)
        }
        assertThat(tableModel.items.size).isOne()
        assertThat(tableModel.items.first().message).isEqualTo("message")
        assertThat(table.emptyText.text).isEqualTo(message("cloudwatch.logs.no_events"))
    }

    @Test
    fun modelIsPopulatedRange() {
        val mockClient = mockClientManagerRule.create<CloudWatchLogsAsyncClient>()
        whenever(mockClient.getLogEvents(Mockito.any<GetLogEventsRequest>()))
            .thenReturn(CompletableFuture.completedFuture(GetLogEventsResponse.builder().events(OutputLogEvent.builder().message("message").build()).build()))
        val tableModel = ListTableModel<LogStreamEntry>()
        val table = TableView<LogStreamEntry>(tableModel)
        val coroutine = LogStreamListActor(projectRule.project, table, "abc", "def")
        runBlocking {
            coroutine.channel.send(LogStreamActor.Message.LOAD_INITIAL_RANGE(0L, Duration.ofMillis(0)))
            tableModel.waitForModelToBeAtLeast(1)
        }

        assertThat(tableModel.items.size).isOne()
        assertThat(tableModel.items.first().message).isEqualTo("message")
        assertThat(table.emptyText.text).isEqualTo(message("cloudwatch.logs.no_events"))
    }

    @Test
    fun emptyTableOnExceptionThrown() {
        val mockClient = mockClientManagerRule.create<CloudWatchLogsAsyncClient>()
        whenever(mockClient.getLogEvents(Mockito.any<GetLogEventsRequest>())).then { throw IllegalStateException("network broke") }
        val tableModel = ListTableModel<LogStreamEntry>()
        val table = TableView<LogStreamEntry>(tableModel)
        val coroutine = LogStreamListActor(projectRule.project, table, "abc", "def")
        runBlocking {
            coroutine.channel.send(LogStreamActor.Message.LOAD_INITIAL())
            waitForTrue { table.emptyText.text == message("cloudwatch.logs.no_events") }
        }
        assertThat(tableModel.items).isEmpty()
    }

    @Test
    fun emptyTableOnExceptionThrownRange() {
        val mockClient = mockClientManagerRule.create<CloudWatchLogsAsyncClient>()
        whenever(mockClient.getLogEvents(Mockito.any<GetLogEventsRequest>())).then { throw IllegalStateException("network broke") }
        val tableModel = ListTableModel<LogStreamEntry>()
        val table = TableView<LogStreamEntry>(tableModel)
        val coroutine = LogStreamListActor(projectRule.project, table, "abc", "def")
        runBlocking {
            coroutine.channel.send(LogStreamActor.Message.LOAD_INITIAL_RANGE(0L, Duration.ofMillis(0)))
            waitForTrue { table.emptyText.text == message("cloudwatch.logs.no_events") }
        }
        assertThat(tableModel.items).isEmpty()
    }

    @Test
    fun loadingForwardAppendsToTable() {
        val mockClient = mockClientManagerRule.create<CloudWatchLogsAsyncClient>()
        whenever(mockClient.getLogEvents(Mockito.any<GetLogEventsRequest>()))
            .thenReturn(CompletableFuture.completedFuture(GetLogEventsResponse.builder().events(OutputLogEvent.builder().message("message").build()).build()))
            .thenReturn(CompletableFuture.completedFuture(GetLogEventsResponse.builder().build()))
            .thenReturn(CompletableFuture.completedFuture(GetLogEventsResponse.builder().events(OutputLogEvent.builder().message("message2").build()).build()))
        val tableModel = ListTableModel<LogStreamEntry>()
        val table = TableView<LogStreamEntry>(tableModel)
        val coroutine = LogStreamListActor(projectRule.project, table, "abc", "def")
        runBlocking {
            coroutine.channel.send(LogStreamActor.Message.LOAD_INITIAL_RANGE(0L, Duration.ofMillis(0)))
            tableModel.waitForModelToBeAtLeast(1)
        }
        assertThat(tableModel.items.size).isOne()
        assertThat(tableModel.items.first().message).isEqualTo("message")
        runBlocking {
            coroutine.channel.send(LogStreamActor.Message.LOAD_FORWARD())
            coroutine.channel.send(LogStreamActor.Message.LOAD_FORWARD())
            tableModel.waitForModelToBeAtLeast(2)
        }
        assertThat(tableModel.items.size).isEqualTo(2)
        assertThat(tableModel.items[1].message).isEqualTo("message2")
    }

    @Test
    fun loadingBackwardsPrependsToTable() {
        val mockClient = mockClientManagerRule.create<CloudWatchLogsAsyncClient>()
        whenever(mockClient.getLogEvents(Mockito.any<GetLogEventsRequest>()))
            .thenReturn(CompletableFuture.completedFuture(GetLogEventsResponse.builder().events(OutputLogEvent.builder().message("message").build()).build()))
            .thenReturn(CompletableFuture.completedFuture(GetLogEventsResponse.builder().build()))
            .thenReturn(CompletableFuture.completedFuture(GetLogEventsResponse.builder().events(OutputLogEvent.builder().message("message2").build()).build()))
        val tableModel = ListTableModel<LogStreamEntry>()
        val table = TableView<LogStreamEntry>(tableModel)
        val coroutine = LogStreamListActor(projectRule.project, table, "abc", "def")
        runBlocking {
            coroutine.channel.send(LogStreamActor.Message.LOAD_INITIAL_RANGE(0L, Duration.ofMillis(0)))
            tableModel.waitForModelToBeAtLeast(1)
        }
        assertThat(tableModel.items.size).isOne()
        assertThat(tableModel.items.first().message).isEqualTo("message")
        runBlocking {
            coroutine.channel.send(LogStreamActor.Message.LOAD_BACKWARD())
            coroutine.channel.send(LogStreamActor.Message.LOAD_BACKWARD())
            tableModel.waitForModelToBeAtLeast(2)
        }
        assertThat(tableModel.items.size).isEqualTo(2)
        assertThat(tableModel.items.first().message).isEqualTo("message2")
        assertThat(tableModel.items[1].message).isEqualTo("message")
    }

    @Test
    fun writeChannelAndCoroutineIsDisposed() {
        mockClientManagerRule.create<CloudWatchLogsAsyncClient>()
        val tableModel = ListTableModel<LogStreamEntry>()
        val table = TableView<LogStreamEntry>(tableModel)
        val coroutine = LogStreamListActor(projectRule.project, table, "abc", "def")
        val channel = coroutine.channel
        coroutine.dispose()
        assertThatThrownBy {
            runBlocking {
                channel.send(LogStreamActor.Message.LOAD_BACKWARD())
            }
        }.isInstanceOf(ClosedSendChannelException::class.java)
        assertThat(coroutine.isActive).isFalse()
    }

    @Test
    fun loadInitialFilterThrows() {
        mockClientManagerRule.create<CloudWatchLogsAsyncClient>()
        val tableModel = ListTableModel<LogStreamEntry>()
        val table = TableView<LogStreamEntry>(tableModel)
        val actor = LogStreamListActor(projectRule.project, table, "abc", "def")
        runBlocking {
            actor.channel.send(LogStreamActor.Message.LOAD_INITIAL_FILTER("abc"))
            waitForFalse { actor.isActive }
        }
    }
}
