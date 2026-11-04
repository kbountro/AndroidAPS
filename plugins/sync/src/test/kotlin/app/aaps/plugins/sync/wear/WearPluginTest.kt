package app.aaps.plugins.sync.wear

import androidx.preference.PreferenceGroup
import app.aaps.core.keys.BooleanKey
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.sync.tidepool.utils.RateLimit
import app.aaps.plugins.sync.wear.wearintegration.DataHandlerMobile
import app.aaps.plugins.sync.wear.wearintegration.DataLayerListenerServiceMobileHelper
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock

class WearPluginTest : TestBaseWithProfile() {

    @Mock lateinit var dataHandlerMobile: DataHandlerMobile
    @Mock lateinit var dataLayerListenerServiceMobileHelper: DataLayerListenerServiceMobileHelper

    private lateinit var wearPlugin: WearPlugin
    private lateinit var rateLimit: RateLimit

    init {
        addInjector {
            if (it is AdaptiveSwitchPreference) {
                it.preferences = preferences
                it.config = config
            }
        }
    }

    @BeforeEach fun prepare() {
        rateLimit = RateLimit(dateUtil)
        wearPlugin = WearPlugin(aapsLogger, rh, aapsSchedulers, preferences, fabricPrivacy, rxBus, context, dataHandlerMobile, dataLayerListenerServiceMobileHelper, config)
    }

    @Test
    fun preferenceScreenTest() {
        val screen = preferenceManager.createPreferenceScreen(context)
        wearPlugin.addPreferenceScreen(preferenceManager, screen, context, null)
        assertThat(screen.preferenceCount).isGreaterThan(0)
    }

    @Test
    fun preferenceScreenHasTheDataSentOptions() {
        val screen = preferenceManager.createPreferenceScreen(context)
        wearPlugin.addPreferenceScreen(preferenceManager, screen, context, "wear_data_sent_settings")
        // Not findPreference: it compares keys with TextUtils, which the unit-test android.jar stubs out.
        fun keys(group: PreferenceGroup): List<String?> = (0 until group.preferenceCount).flatMap { index ->
            val preference = group.getPreference(index)
            listOf(preference.key) + if (preference is PreferenceGroup) keys(preference) else emptyList()
        }
        assertThat(keys(screen)).containsAtLeast(BooleanKey.WearBgEveryFiveMinutes.key, BooleanKey.WearActionButtonsOnlyWhenChanged.key, BooleanKey.WearSendBgTable.key)
    }
}
