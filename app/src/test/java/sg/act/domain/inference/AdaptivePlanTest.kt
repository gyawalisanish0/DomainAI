package sg.act.domain.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePlanTest {

    private companion object {
        /** A healthy 8-core, 8 GB phone with plenty free and nothing throttling. */
        fun flagship(
            totalRamMb: Long = 8_000,
            availableRamMb: Long = 4_000,
            isLowRamDevice: Boolean = false,
            cores: Int = 8,
            thermalStatus: Int = DeviceSnapshot.THERMAL_NONE,
            powerSaveMode: Boolean = false,
        ) = DeviceSnapshot(
            totalRamMb = totalRamMb,
            availableRamMb = availableRamMb,
            isLowRamDevice = isLowRamDevice,
            cores = cores,
            thermalStatus = thermalStatus,
            powerSaveMode = powerSaveMode,
        )
    }

    // --- Baseline: hardware alone decides ---------------------------------------

    @Test
    fun `a healthy flagship gets the full hardware plan`() {
        val plan = Adaptive.plan(flagship())
        assertEquals(4, plan.autoThreads)   // half of 8 cores
        assertEquals(6, plan.maxThreads)
        assertEquals(8192, plan.autoContextTokens)
        assertEquals(16384, plan.maxContextTokens)
        assertEquals(1024, plan.batchSize)  // 8 GB tier
        assertTrue("nothing should have constrained this", plan.constraints.isEmpty())
    }

    @Test
    fun `a four-core mid-range phone scales down`() {
        val plan = Adaptive.plan(flagship(totalRamMb = 3_500, availableRamMb = 2_500, cores = 4))
        assertEquals(2, plan.autoThreads)
        assertEquals(4, plan.maxThreads)
        assertEquals(4096, plan.autoContextTokens)
        assertEquals(8192, plan.maxContextTokens)
        assertEquals(512, plan.batchSize)
    }

    @Test
    fun `the vendor's low-RAM flag pins the smallest context tier`() {
        // 8 GB of RAM doesn't override the flag: the vendor knows its own device.
        val plan = Adaptive.plan(flagship(isLowRamDevice = true))
        assertEquals(2048, plan.autoContextTokens)
        assertEquals(4096, plan.maxContextTokens)
        assertTrue(plan.constraints.contains(Constraint.LOW_RAM_DEVICE))
    }

    @Test
    fun `batch size follows the total-RAM tier`() {
        fun batchFor(mb: Long) =
            Adaptive.plan(flagship(totalRamMb = mb, availableRamMb = mb / 2)).batchSize
        assertEquals(512, batchFor(4_000))
        assertEquals(1024, batchFor(8_000))
        assertEquals(2048, batchFor(16_000))
        assertEquals(4096, batchFor(64_000))
    }

    // --- Thermal and battery saver bias Auto only -------------------------------

    @Test
    fun `severe throttling halves Auto threads`() {
        val plan = Adaptive.plan(flagship(thermalStatus = DeviceSnapshot.THERMAL_SEVERE))
        assertEquals(2, plan.autoThreads)
        assertTrue(plan.constraints.contains(Constraint.THERMAL))
    }

    @Test
    fun `throttling does not lower the ceiling the user may pick`() {
        // The documented split: thermal state is transient and only moves Auto. A
        // user who typed 6 keeps 6.
        val plan = Adaptive.plan(flagship(thermalStatus = DeviceSnapshot.THERMAL_CRITICAL))
        assertEquals(6, plan.maxThreads)
        assertEquals(16384, plan.maxContextTokens)
    }

    @Test
    fun `light and moderate throttling change nothing`() {
        for (status in listOf(DeviceSnapshot.THERMAL_LIGHT, DeviceSnapshot.THERMAL_MODERATE)) {
            val plan = Adaptive.plan(flagship(thermalStatus = status))
            assertEquals("status $status", 4, plan.autoThreads)
            assertFalse("status $status", plan.constraints.contains(Constraint.THERMAL))
        }
    }

    @Test
    fun `an unavailable thermal reading is not treated as throttling`() {
        // Below API 29 there is no thermal API at all; -1 must not read as "hot".
        val plan = Adaptive.plan(flagship(thermalStatus = DeviceSnapshot.THERMAL_UNKNOWN))
        assertEquals(4, plan.autoThreads)
        assertTrue(plan.constraints.isEmpty())
    }

    @Test
    fun `battery saver halves Auto threads`() {
        val plan = Adaptive.plan(flagship(powerSaveMode = true))
        assertEquals(2, plan.autoThreads)
        assertTrue(plan.constraints.contains(Constraint.POWER_SAVE))
    }

    @Test
    fun `throttling and battery saver together halve once, not twice`() {
        val plan = Adaptive.plan(
            flagship(thermalStatus = DeviceSnapshot.THERMAL_SEVERE, powerSaveMode = true),
        )
        assertEquals(2, plan.autoThreads)
        assertEquals(setOf(Constraint.THERMAL, Constraint.POWER_SAVE), plan.constraints)
    }

    @Test
    fun `Auto threads never drop below two`() {
        val plan = Adaptive.plan(
            flagship(cores = 2, thermalStatus = DeviceSnapshot.THERMAL_EMERGENCY),
        )
        assertEquals(Adaptive.MIN_THREADS, plan.autoThreads)
        assertTrue(plan.maxThreads >= Adaptive.MIN_THREADS)
    }

    @Test
    fun `a single-core device still gets a usable plan`() {
        val plan = Adaptive.plan(flagship(cores = 1))
        assertEquals(2, plan.autoThreads)
        assertEquals(2, plan.maxThreads)
    }

    // --- Free memory moves the ceilings ----------------------------------------

    @Test
    fun `an 8 GB phone with almost nothing free is treated as a small device`() {
        // The point of sampling live: total RAM says 16384 is fine, free RAM says no.
        val plan = Adaptive.plan(flagship(availableRamMb = 500))
        assertEquals(2048, plan.maxContextTokens)
        assertEquals(2048, plan.autoContextTokens)
        assertTrue(plan.constraints.contains(Constraint.FREE_MEMORY))
    }

    @Test
    fun `free memory clamps the ceiling in steps`() {
        assertEquals(4096, Adaptive.plan(flagship(availableRamMb = 1_000)).maxContextTokens)
        assertEquals(8192, Adaptive.plan(flagship(availableRamMb = 2_000)).maxContextTokens)
        assertEquals(16384, Adaptive.plan(flagship(availableRamMb = 3_000)).maxContextTokens)
    }

    @Test
    fun `ample free memory raises no constraint`() {
        val plan = Adaptive.plan(flagship(availableRamMb = 6_000))
        assertFalse(plan.constraints.contains(Constraint.FREE_MEMORY))
        assertEquals(16384, plan.maxContextTokens)
    }

    @Test
    fun `tight free memory cuts the prompt batch to the floor`() {
        // A 16 GB device would otherwise reserve a 2048-token compute buffer.
        val plan = Adaptive.plan(flagship(totalRamMb = 16_000, availableRamMb = 1_400))
        assertEquals(Adaptive.MIN_BATCH, plan.batchSize)
        assertTrue(plan.constraints.contains(Constraint.FREE_MEMORY))
    }

    @Test
    fun `the batch is left alone just above the tight-memory threshold`() {
        val plan = Adaptive.plan(
            flagship(totalRamMb = 16_000, availableRamMb = Adaptive.TIGHT_MEMORY_MB.toLong()),
        )
        assertEquals(2048, plan.batchSize)
    }

    // --- Invariants -------------------------------------------------------------

    @Test
    fun `Auto never exceeds its own ceiling, across the whole input space`() {
        val rams = listOf(1_500L, 2_999L, 3_000L, 5_999L, 6_000L, 8_000L, 16_000L, 64_000L)
        val frees = listOf(100L, 599L, 600L, 1_199L, 1_200L, 2_399L, 2_400L, 30_000L)
        for (total in rams) for (free in frees) for (low in listOf(false, true)) {
            for (cores in 1..12) for (thermal in -1..6) for (saver in listOf(false, true)) {
                val plan = Adaptive.plan(
                    DeviceSnapshot(total, free, low, cores, thermal, saver),
                )
                val where = "total=$total free=$free low=$low cores=$cores " +
                    "thermal=$thermal saver=$saver"
                assertTrue("$where: auto ctx over max", plan.autoContextTokens <= plan.maxContextTokens)
                assertTrue("$where: auto threads over max", plan.autoThreads <= plan.maxThreads)
                assertTrue("$where: threads under floor", plan.autoThreads >= Adaptive.MIN_THREADS)
                assertTrue("$where: batch under floor", plan.batchSize >= Adaptive.MIN_BATCH)
                assertTrue("$where: context must be positive", plan.autoContextTokens > 0)
            }
        }
    }

    // --- Labels shown in Settings and the bug report ---------------------------

    @Test
    fun `thermal statuses are named, and anything else is unknown`() {
        assertEquals("none", DeviceSnapshot.thermalName(DeviceSnapshot.THERMAL_NONE))
        assertEquals("severe", DeviceSnapshot.thermalName(DeviceSnapshot.THERMAL_SEVERE))
        assertEquals("shutdown", DeviceSnapshot.thermalName(DeviceSnapshot.THERMAL_SHUTDOWN))
        assertEquals("unknown", DeviceSnapshot.thermalName(DeviceSnapshot.THERMAL_UNKNOWN))
        assertEquals("unknown", DeviceSnapshot.thermalName(99))
    }

    @Test
    fun `every constraint has a label`() {
        for (constraint in Constraint.entries) {
            assertTrue(constraint.name, constraint.label.isNotBlank())
        }
    }
}
