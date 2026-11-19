package app.aaps.plugins.source

import androidx.preference.PreferenceGroup
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.keys.BooleanKey
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class XdripSourcePluginTest : TestBaseWithProfile() {

    private lateinit var xdripSourcePlugin: XdripSourcePlugin

    @BeforeEach
    fun setup() {
        xdripSourcePlugin = XdripSourcePlugin(rh, aapsLogger)
    }


    @Test fun advancedFilteringSupported() {
        assertThat(xdripSourcePlugin.advancedFilteringSupported()).isFalse()
    }

    @Test
    fun preferenceScreenHasTheTrendSwitch() {
        val screen = preferenceManager.createPreferenceScreen(context)
        xdripSourcePlugin.addPreferenceScreen(preferenceManager, screen, context, null)
        // Not findPreference: it compares keys with TextUtils, which the unit-test android.jar stubs out.
        fun keys(group: PreferenceGroup): List<String?> = (0 until group.preferenceCount).flatMap { index ->
            val preference = group.getPreference(index)
            listOf(preference.key) + if (preference is PreferenceGroup) keys(preference) else emptyList()
        }
        assertThat(keys(screen)).contains(BooleanKey.BgSourceXdripCalculateTrend.key)
    }

    @Test
    fun detectDexcomSourceTest() {
        assertThat(xdripSourcePlugin.advancedFiltering).isFalse()
        xdripSourcePlugin.detectSource(
            GV(
                timestamp = 10000L,
                value = 150.0,
                raw = null,
                noise = null,
                trendArrow = TrendArrow.FORTY_FIVE_DOWN,
                sourceSensor = SourceSensor.DEXCOM_G6_NATIVE_XDRIP
            )
        )
        assertThat(xdripSourcePlugin.advancedFiltering).isTrue()
    }

    @Test
    fun detectLibreSourceTest() {
        assertThat(xdripSourcePlugin.advancedFiltering).isFalse()
        xdripSourcePlugin.detectSource(
            GV(
                timestamp = 10000L,
                value = 150.0,
                raw = null,
                noise = null,
                trendArrow = TrendArrow.FORTY_FIVE_DOWN,
                sourceSensor = SourceSensor.LIBRE_3
            )
        )
        assertThat(xdripSourcePlugin.advancedFiltering).isTrue()
    }
}
