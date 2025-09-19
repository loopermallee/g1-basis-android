package io.texne.g1.hub.ui.todo

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.any
import io.texne.g1.hub.todo.TodoItem
import io.texne.g1.hub.todo.TodoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodoViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun refreshDelegatesToRepository() = runTest(dispatcher) {
        val repository = createRepositoryMock()
        val viewModel = TodoViewModel(repository)

        advanceUntilIdle()
        coVerify(exactly = 1) { repository.refresh() }

        viewModel.refresh()
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.refresh() }
    }

    @Test
    fun addTaskTrimsAndDelegates() = runTest(dispatcher) {
        val repository = createRepositoryMock()
        coEvery { repository.addTask(any(), any()) } returns sampleItem()
        val viewModel = TodoViewModel(repository)

        advanceUntilIdle()

        viewModel.addTask("  Finish spec  ")
        advanceUntilIdle()

        coVerify { repository.addTask("Finish spec", "Finish spec") }
    }

    @Test
    fun addTaskSkipsBlankInput() = runTest(dispatcher) {
        val repository = createRepositoryMock()
        val viewModel = TodoViewModel(repository)

        advanceUntilIdle()

        viewModel.addTask("   ")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.refresh() }
        coVerify(exactly = 0) { repository.addTask(any(), any()) }
    }

    @Test
    fun toggleArchiveAndRestoreDelegateToRepository() = runTest(dispatcher) {
        val repository = createRepositoryMock()
        coEvery { repository.toggleTask(any()) } returns sampleItem()
        coEvery { repository.archiveTask(any()) } returns sampleItem()
        coEvery { repository.restoreTask(any()) } returns sampleItem()
        val viewModel = TodoViewModel(repository)

        advanceUntilIdle()

        viewModel.toggleTask("id1")
        viewModel.archiveTask("id1")
        viewModel.restoreTask("id1")
        advanceUntilIdle()

        coVerify { repository.toggleTask("id1") }
        coVerify { repository.archiveTask("id1") }
        coVerify { repository.restoreTask("id1") }
    }

    private fun createRepositoryMock(): TodoRepository {
        val repository = mockk<TodoRepository>(relaxed = true)
        every { repository.activeTasks } returns MutableStateFlow(emptyList())
        every { repository.archivedTasks } returns MutableStateFlow(emptyList())
        coEvery { repository.refresh() } returns Unit
        return repository
    }

    private fun sampleItem(): TodoItem = TodoItem(
        id = "id",
        shortText = "short",
        fullText = "full",
        isDone = false,
        archivedAt = null,
        position = 0
    )
}
