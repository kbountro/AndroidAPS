package app.aaps.plugins.automation.actions

import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.DoubleKey
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.InputWeight
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject

/**
 * Sets AutoISF's postprandial weight, pp_ISF_weight ([DoubleKey.ApsAutoIsfPpWeight]), as [ActionSetAcceWeight] does
 * bgAccel_ISF_weight. Its range (0 to 0.15) is much smaller than the other weights', so the picker steps by 0.005.
 * Through [SP], since Preferences cannot write a preference key.
 */
class ActionSetPpWeight(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var sp: SP

    private val key = DoubleKey.ApsAutoIsfPpWeight
    var new_weight = InputWeight(min = key.min, max = key.max, step = 0.005, format = "0.000")

    override fun friendlyName(): Int = R.string.autoisf_pp_weight
    override fun shortDescription(): String = rh.gs(R.string.automate_set_pp_weight, new_weight.value)
    @DrawableRes override fun icon(): Int = R.drawable.ic_pp_weight

    override fun doAction(callback: Callback) {
        if (sp.getDouble(key.key, key.defaultValue) != new_weight.value) {
            uel.log(
                app.aaps.core.data.ue.Action.PP_WEIGHT_SET,
                Sources.Automation,
                title + ": " + rh.gs(R.string.automate_set_pp_weight, new_weight.value)
            )
            sp.putDouble(key.key, new_weight.value)
            callback.result(pumpEnactResultProvider.get().success(true).comment(R.string.weight_new)).run()
        } else {
            callback.result(pumpEnactResultProvider.get().success(false).comment(R.string.weight_old)).run()
        }
    }

    override fun hasDialog(): Boolean = true

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(LabelWithElement(rh, rh.gs(R.string.autoisf_pp_weight), "", new_weight))
            .build(root)
    }

    override fun toJSON(): String =
        JSONObject()
            .put("type", this.javaClass.simpleName)
            .put("data", JSONObject().put("weight", new_weight.value))
            .toString()

    override fun fromJSON(data: String): Action {
        new_weight.value = JsonHelper.safeGetDouble(JSONObject(data), "weight")
        return this
    }

    override fun isValid(): Boolean = true
}
