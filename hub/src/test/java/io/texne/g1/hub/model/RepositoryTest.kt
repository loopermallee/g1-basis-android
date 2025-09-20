package io.texne.g1.hub.model

import android.content.Context
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.basis.client.G1ServiceManager
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryTest {

    @MockK(relaxed = true)
    lateinit var context: Context

    @MockK(relaxed = true)
    lateinit var service: G1ServiceManager

    private lateinit var repository: Repository

    private val connectedGlasses = G1ServiceCommon.Glasses(
        id = "glasses-id",
        name = "Glasses",
        status = G1ServiceCommon.GlassesStatus.CONNECTED,
        batteryPercentage = 90,
        leftStatus = G1ServiceCommon.GlassesStatus.CONNECTED,
        rightStatus = G1ServiceCommon.GlassesStatus.CONNECTED,
        leftBatteryPercentage = 90,
        rightBatteryPercentage = 90
    )

    @BeforeTest
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        every { service.listConnectedGlasses() } returns listOf(connectedGlasses)
        repository = Repository(context)
        repository.setServiceManagerForTest(service)
    }

    @Test
    fun `interactive pages display first page without timed sequence`() = runTest {
        val pages = listOf(
            listOf("Task 1", "Task 2"),
            listOf("Task 3")
        )
        val formattedPage = slot<G1ServiceCommon.FormattedPage>()
        coEvery { service.displayFormattedPage(connectedGlasses.id, capture(formattedPage)) } returns true

        val result = repository.displayCenteredOnConnectedGlasses(pages, holdMillis = null)

        assertTrue(result)
        coVerify(exactly = 1) { service.displayFormattedPage(connectedGlasses.id, any()) }
        coVerify(exactly = 0) { service.displayFormattedPageSequence(any(), any()) }
        assertEquals(pages.first(), formattedPage.captured.lines.map { it.text })
    }

    @Test
    fun `multi-page responses ignore holdMillis and display first page immediately`() = runTest {
        val pages = listOf(listOf("Task 1"), listOf("Task 2"))
        val formattedPage = slot<G1ServiceCommon.FormattedPage>()
        coEvery { service.displayFormattedPage(connectedGlasses.id, capture(formattedPage)) } returns true

        val result = repository.displayCenteredOnConnectedGlasses(pages, holdMillis = 2_500L)

        assertTrue(result)
        coVerify(exactly = 1) { service.displayFormattedPage(connectedGlasses.id, any()) }
        coVerify(exactly = 0) { service.displayFormattedPageSequence(any(), any()) }
        assertEquals(pages.first(), formattedPage.captured.lines.map { it.text })
    }

    @Test
    fun `displayCenteredPageOnConnectedGlasses shows requested page`() = runTest {
        val pages = listOf(
            listOf("Task 1"),
            listOf("Task 2", "Task 3", "Task 4", "Task 5", "Task 6")
        )
        val formattedPage = slot<G1ServiceCommon.FormattedPage>()
        coEvery { service.displayFormattedPage(connectedGlasses.id, capture(formattedPage)) } returns true

        val result = repository.displayCenteredPageOnConnectedGlasses(pages, pageIndex = 1)

        assertTrue(result)
        coVerify(exactly = 1) { service.displayFormattedPage(connectedGlasses.id, any()) }
        assertEquals(listOf("Task 2", "Task 3", "Task 4", "Task 5"), formattedPage.captured.lines.map { it.text })
    }

    @Test
    fun `displayCenteredPageOnConnectedGlasses returns false for invalid index`() = runTest {
        val pages = listOf(listOf("Task 1"), listOf("Task 2"))

        val result = repository.displayCenteredPageOnConnectedGlasses(pages, pageIndex = 5)

        assertFalse(result)
        coVerify(exactly = 0) { service.displayFormattedPage(any(), any()) }
    }
}
