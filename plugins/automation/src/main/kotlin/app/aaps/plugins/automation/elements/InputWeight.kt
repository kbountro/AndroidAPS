package app.aaps.plugins.automation.elements

import android.view.Gravity
import android.widget.LinearLayout
import app.aaps.core.ui.elements.NumberPicker
import java.text.DecimalFormat

/**
 * A number picker for an AutoISF weight: [value], from [min] to [max] in steps of [step], shown as [format].
 * The defaults suit bgAccel_ISF_weight and bgBrake_ISF_weight (0 to 1); pp_ISF_weight's range is much smaller.
 */
class InputWeight(
    var value: Double = 0.0, // not working on app start: sp.getDouble(R.string.key_bgAccel_ISF_weight, 0.0)
    private val min: Double = 0.0,
    private val max: Double = 1.0,
    private val step: Double = 0.05,
    private val format: String = "0.00"
) : Element {

    override fun addToLayout(root: LinearLayout) {
        root.addView(
            NumberPicker(root.context, null).also {
                it.setParams(value, min, max, step, DecimalFormat(format), true, root.findViewById(app.aaps.core.ui.R.id.ok))
                it.setOnValueChangedListener { v: Double -> value = v }
                it.gravity = Gravity.CENTER_HORIZONTAL
            })
    }
}
