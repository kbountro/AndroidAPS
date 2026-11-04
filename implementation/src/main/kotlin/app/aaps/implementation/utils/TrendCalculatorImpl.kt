package app.aaps.implementation.utils

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import dagger.Reusable
import javax.inject.Inject

@Reusable
class TrendCalculatorImpl @Inject constructor(
    private val rh: ResourceHelper,
    private val preferences: Preferences,
    private val activePlugin: ActivePlugin
) : TrendCalculator {

    override fun getTrendArrow(autosensDataStore: AutosensDataStore): TrendArrow? {
        val data = autosensDataStore.getBucketedDataTableCopy() ?: return null
        if (data.isEmpty()) return null
        val glucoseValue = data[0]
        return when {
            glucoseValue.value != glucoseValue.recalculated -> calculateDirection(data) // always recalculate after smoothing
            ignoresSourceArrow()                            -> calculateDirection(data)
            glucoseValue.trendArrow != TrendArrow.NONE      -> glucoseValue.trendArrow
            else                                            -> calculateDirection(data)
        }
    }

    /**
     * xDrip+ works its arrow out from its own readings, which never saw AAPS's smoothing (the source smoothing in the
     * xDrip+ plugin, the smoothing plugin). A smoothing plugin that leaves the newest reading as it came (none, average)
     * would otherwise let that arrow through. Only read once bucketed data exist, so the BG source is selected by then.
     */
    private fun ignoresSourceArrow(): Boolean =
        activePlugin.activeBgSource is XDripSource && preferences.get(BooleanKey.BgSourceXdripCalculateTrend)

    override fun getTrendDescription(autosensDataStore: AutosensDataStore): String {
        return when (getTrendArrow(autosensDataStore)) {
            TrendArrow.DOUBLE_DOWN     -> rh.gs(app.aaps.core.ui.R.string.a11y_arrow_double_down)
            TrendArrow.SINGLE_DOWN     -> rh.gs(app.aaps.core.ui.R.string.a11y_arrow_single_down)
            TrendArrow.FORTY_FIVE_DOWN -> rh.gs(app.aaps.core.ui.R.string.a11y_arrow_forty_five_down)
            TrendArrow.FLAT            -> rh.gs(app.aaps.core.ui.R.string.a11y_arrow_flat)
            TrendArrow.FORTY_FIVE_UP   -> rh.gs(app.aaps.core.ui.R.string.a11y_arrow_forty_five_up)
            TrendArrow.SINGLE_UP       -> rh.gs(app.aaps.core.ui.R.string.a11y_arrow_single_up)
            TrendArrow.DOUBLE_UP       -> rh.gs(app.aaps.core.ui.R.string.a11y_arrow_double_up)
            TrendArrow.NONE            -> rh.gs(app.aaps.core.ui.R.string.a11y_arrow_none)
            else                       -> rh.gs(app.aaps.core.ui.R.string.a11y_arrow_unknown)
        }
    }

    private fun calculateDirection(readings: MutableList<InMemoryGlucoseValue>): TrendArrow {

        if (readings.size < 2)
            return TrendArrow.NONE
        val current = readings[0]
        val previous = readings[1]

        // Avoid division by 0
        val slope =
            if (current.timestamp == previous.timestamp) 0.0
            else (previous.recalculated - current.recalculated) / (previous.timestamp - current.timestamp)

        val slopeByMinute = slope * 60000

        return when {
            slopeByMinute <= -3.5 -> TrendArrow.DOUBLE_DOWN
            slopeByMinute <= -2   -> TrendArrow.SINGLE_DOWN
            slopeByMinute <= -1   -> TrendArrow.FORTY_FIVE_DOWN
            slopeByMinute <= 1    -> TrendArrow.FLAT
            slopeByMinute <= 2    -> TrendArrow.FORTY_FIVE_UP
            slopeByMinute <= 3.5  -> TrendArrow.SINGLE_UP
            slopeByMinute <= 40   -> TrendArrow.DOUBLE_UP
            else                  -> TrendArrow.NONE
        }
    }
}