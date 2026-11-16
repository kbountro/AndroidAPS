package app.aaps.core.objects.extensions

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OneMinuteDataExtensionTest {

    // Newest first, [spacingSec] apart.
    private fun readings(spacingSec: Long, count: Int = 4, sensor: SourceSensor = SourceSensor.SYAI_TAG): List<GV> =
        (0 until count).map {
            GV(timestamp = 1_000_000_000L - it * spacingSec * 1000, value = 120.0, raw = 0.0, noise = 0.0, trendArrow = TrendArrow.FLAT, sourceSensor = sensor)
        }

    @Test
    fun oneMinuteReadingsHaveOneMinuteCadence() {
        assertThat(readings(60).hasOneMinuteCadence()).isTrue()
    }

    @Test
    fun fiveMinuteReadingsDoNotHaveOneMinuteCadence() {
        assertThat(readings(300).hasOneMinuteCadence()).isFalse()
    }

    @Test
    fun theNewestFourMustSpanLessThanTenMinutes() {
        assertThat(readings(200).hasOneMinuteCadence()).isFalse() // exactly 600 s
        assertThat(readings(199).hasOneMinuteCadence()).isTrue()
    }

    @Test
    fun threeReadingsAreNotEnough() {
        assertThat(readings(60, count = 3).hasOneMinuteCadence()).isFalse()
    }

    @Test
    fun aLibreNeedsNoSetting() {
        var asked = false
        assertThat(readings(60, sensor = SourceSensor.LIBRE_2).isOneMinuteRawData { asked = true; false }).isTrue()
        assertThat(asked).isFalse()
    }

    @Test
    fun otherSensorsFollowTheSetting() {
        assertThat(readings(60).isOneMinuteRawData { true }).isTrue()
        assertThat(readings(60).isOneMinuteRawData { false }).isFalse()
    }

    @Test
    fun theSettingIsNotReadForSlowReadings() {
        var asked = false
        assertThat(readings(300).isOneMinuteRawData { asked = true; true }).isFalse()
        assertThat(asked).isFalse()
    }
}
