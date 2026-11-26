package ca.pkay.rcloneexplorer.workmanager

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TransferProcessTest {
    private class Child : Process() {
        var destroys = 0
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() { destroys++ }
    }

    @Test fun stopDuringLaunchDestroysLateChildAndPreventsAnotherLaunch() {
        val owner = TransferProcess()
        val child = Child()
        val launching = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<String?> {
                owner.run<String>({
                    launching.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                    child
                }) { error("A stopped transfer must never consume the child") }
            }
            assertTrue(launching.await(5, TimeUnit.SECONDS))
            owner.stop()
            resume.countDown()
            assertNull(result.get(5, TimeUnit.SECONDS))
            assertEquals(1, child.destroys)
            assertNull(owner.run({ error("Unexpected relaunch") }) { "unused" })
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun stopDuringUseDestroysOwnedChildExactlyOnce() {
        val owner = TransferProcess()
        val child = Child()
        assertEquals(42, owner.run({ child }) {
            owner.stop()
            assertEquals(1, child.destroys)
            42
        })
        owner.stop()
        assertEquals(1, child.destroys)
    }

    @Test fun failedConsumerReleasesChildAndAllowsNextTransfer() {
        val owner = TransferProcess()
        val failed = Child()
        assertThrows(IllegalStateException::class.java) {
            owner.run<Unit>({ failed }) { error("read failure") }
        }
        val next = Child()
        assertEquals(7, owner.run({ next }) { 7 })
        assertEquals(1, failed.destroys)
        assertEquals(1, next.destroys)
    }
}
