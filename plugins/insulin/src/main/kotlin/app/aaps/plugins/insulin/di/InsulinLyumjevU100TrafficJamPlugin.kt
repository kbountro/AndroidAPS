package app.aaps.plugins.insulin

import android.content.Context
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InsulinLyumjevU100TrafficJamPlugin @Inject constructor(
    preferences: Preferences,
    rh: ResourceHelper,
    profileFunction: ProfileFunction,
    rxBus: RxBus,
    aapsLogger: AAPSLogger,
    config: Config,
    hardLimits: HardLimits,
    uiInteraction: UiInteraction
) : InsulinOrefBasePlugin(preferences, rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction) {

    override val id get(): Insulin.InsulinType = Insulin.InsulinType.OREF_LYUMJEV_U100_TRAFFIC_JAM
    override val friendlyName get(): String = rh.gs(R.string.lyumjev_U100_Traffic_Jam)

    override fun configuration(): JSONObject =
        JSONObject()
            .put(DoubleKey.InsulinTrafficJamSpeedMultiplier, preferences)

    override fun applyConfiguration(configuration: JSONObject) {
        configuration
            .store(DoubleKey.InsulinTrafficJamSpeedMultiplier, preferences)
    }

    override fun commentStandardText(): String = rh.gs(R.string.lyumjev_U100_Traffic_Jam)

    override val peak = 45

    init {
        pluginDescription
            .pluginIcon(R.drawable.ic_insulin)
            .pluginName(R.string.lyumjev_U100_Traffic_Jam)
            .description(R.string.description_insulin_lyumjev_U100_Traffic_Jam)
            .preferencesId(PluginDescription.PREFERENCE_SCREEN)
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "insulin_traffic_jam_speed_settings"
            title = rh.gs(R.string.insulin_traffic_jam_speed_settings)
            initialExpandedChildrenCount = 0
            addPreference(
                AdaptiveDoublePreference(
                    ctx = context, doubleKey = DoubleKey.InsulinTrafficJamSpeedMultiplier,
                    dialogMessage = R.string.insulin_traffic_jam_speed_multiplier_summary, title = R.string.insulin_traffic_jam_speed_multiplier_title
                )
            )
        }
    }
}