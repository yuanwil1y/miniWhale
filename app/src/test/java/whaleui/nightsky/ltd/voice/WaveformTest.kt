package whaleui.nightsky.ltd.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * [Waveform] 的纯函数单测。
 *
 * 注意：[Waveform.fromMp3] **不测**——它依赖 Android 的 MediaExtractor / MediaCodec 解码器，只能在真机/模拟器验证。
 */
class WaveformTest {

    private val delta = 0.0001f

    @Test
    fun flatHasRequestedLength() {
        assertEquals(Waveform.DEFAULT_BUCKETS, Waveform.flat().size)
        assertEquals(8, Waveform.flat(8).size)
        assertEquals(0, Waveform.flat(0).size)
        assertTrue(Waveform.flat(-1).isEmpty())
        assertTrue(Waveform.flat().all { abs(it - 0.06f) < delta })
    }

    @Test
    fun emptyPcmReturnsFlatWaveform() {
        val waveform = Waveform.fromPcm(ShortArray(0))

        assertEquals(64, waveform.size)
        assertTrue(waveform.all { abs(it - 0.06f) < delta })
    }

    @Test
    fun silentPcmReturnsFlatWaveform() {
        assertEquals(Waveform.flat(), Waveform.fromPcm(ShortArray(1_000)))
    }

    @Test
    fun pcmHonoursBucketCount() {
        val pcm = sineWave(samples = 2_000)

        assertEquals(16, Waveform.fromPcm(pcm, buckets = 16).size)
        assertEquals(64, Waveform.fromPcm(pcm).size)
        assertTrue(Waveform.fromPcm(pcm, buckets = 0).isEmpty())
        assertTrue(Waveform.fromPcm(pcm, buckets = -3).isEmpty())
    }

    @Test
    fun pcmValuesStayInsideRange() {
        val waveform = Waveform.fromPcm(sineWave(samples = 4_000), buckets = 64)

        assertEquals(64, waveform.size)
        waveform.forEach { value ->
            assertTrue("value=$value", value >= 0.06f - delta && value <= 0.96f + delta)
        }
        assertEquals(0.96f, waveform.maxOrNull()!!, delta)
        assertTrue(waveform.minOrNull()!! >= 0.06f - delta)
    }

    @Test
    fun louderHalfProducesTallerBars() {
        val quiet = ShortArray(2_000) { 1_500 }
        val loud = ShortArray(2_000) { 28_000 }
        val waveform = Waveform.fromPcm(quiet + loud, buckets = 64)

        val quietHalfMax = waveform.take(32).maxOrNull()!!
        val loudHalfMin = waveform.drop(32).minOrNull()!!
        assertTrue("quietMax=$quietHalfMax loudMin=$loudHalfMin", quietHalfMax < loudHalfMin)
        assertEquals(0.96f, loudHalfMin, delta)
    }

    @Test
    fun negativeSamplesUseMagnitude() {
        val positive = ShortArray(1_000) { 10_000 }
        val negative = ShortArray(1_000) { (-10_000).toShort() }

        assertEquals(Waveform.fromPcm(positive, 32), Waveform.fromPcm(negative, 32))
    }

    @Test
    fun constantAmplitudeReachesTopBar() {
        val waveform = Waveform.fromPcm(ShortArray(1_000) { 10_000 }, buckets = 32)

        waveform.forEach { assertEquals(0.96f, it, delta) }
    }

    private fun sineWave(samples: Int, amplitude: Short = 20_000, period: Int = 32): ShortArray =
        ShortArray(samples) { index ->
            (amplitude.toInt() * sin(2 * PI * index / period)).toInt().toShort()
        }
}
