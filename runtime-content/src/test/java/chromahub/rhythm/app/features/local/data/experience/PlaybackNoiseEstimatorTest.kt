package chromahub.rhythm.app.features.local.data.experience

import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class PlaybackNoiseEstimatorTest {
    @Test fun alignsNonRoundDelayAndRemovesRoomEchoWithoutSubtractingIndependentAmbient() {
        val random = Random(81)
        val reference = FloatArray(48_000) { (random.nextGaussian() * .15).toFloat() }
        val offset = 17_017
        var ambientPower = 0.0
        val microphone = ShortArray(16_000) { i ->
            val noise = random.nextGaussian() * .03; ambientPower += noise * noise
            ((.6 * reference[offset + i] + .2 * reference[offset + i - 5] + noise) * 32768).toInt().toShort()
        }
        val estimate = requireNotNull(PlaybackNoiseEstimator.estimate(microphone, reference))
        assertEquals(ambientPower / microphone.size, estimate.residualPower, ambientPower / microphone.size * .20)
        assertTrue(estimate.playbackPower > estimate.residualPower * 4)
        assertTrue(estimate.confidence > .4)
    }
    @Test fun invertedAcousticPhaseIsAlsoEstimated() {
        val random = Random(44)
        val reference = FloatArray(24_000) { (random.nextGaussian() * .1).toFloat() }
        val microphone = ShortArray(8_000) { (-.7 * reference[7_113 + it] * 32768).toInt().toShort() }
        val result = requireNotNull(PlaybackNoiseEstimator.estimate(microphone, reference))
        assertTrue(result.residualPower < 1e-7)
    }
    @Test fun unrelatedPlaybackDoesNotEraseTheSurroundingSound() {
        val random = Random(7)
        val reference = FloatArray(32_000) { (random.nextGaussian() * .1).toFloat() }
        val microphone = ShortArray(8_000) { (random.nextGaussian() * 3000).toInt().toShort() }
        assertNull(PlaybackNoiseEstimator.estimate(microphone, reference))
    }
    @Test fun clippingSilenceMissingReferenceAndInvalidSamplesStayUncorrected() {
        assertNull(PlaybackNoiseEstimator.estimate(ShortArray(8_000) { 32767 }, FloatArray(16_000) { .1f }))
        assertNull(PlaybackNoiseEstimator.estimate(ShortArray(8_000), FloatArray(16_000)))
        assertNull(PlaybackNoiseEstimator.estimate(ShortArray(8_000), FloatArray(0)))
        assertNull(PlaybackNoiseEstimator.estimate(ShortArray(8_000), FloatArray(16_000) { Float.NaN }))
    }
}
