package app.aaps.core.interfaces.aps

import kotlinx.serialization.Serializable

@Serializable
data class GlucoseStatusAutoIsf(
    override val glucose: Double,
    override val noise: Double = 0.0,
    override val delta: Double = 0.0,
    override val shortAvgDelta: Double = 0.0,
    override val longAvgDelta: Double = 0.0,
    override val date: Long = 0L,
    /** past duration of BG above 150 mg/dL within the last 60 minutes, counted back from now while the 5-minute average
     *  ending at each reading is above 150 mg/dL and less than 5% above the average of the newer ones: the time at its
     *  current level, the rise to it included, since it last fell (AutoISF's DuraIsfWindow, which gives the steps); counted
     *  on the 1-minute readings on AutoISF's raw path, else on the 5-minute grid; 0 when the 5-minute average ending at the
     *  newest reading is 150 mg/dL or less */
    val duraISFminutes: Double = 0.0,
    /** average BG of the readings in [duraISFminutes]; when nothing counts because BG is not above 150 mg/dL, the 5-minute
     *  average ending at the newest reading that showed it */
    val duraISFaverage: Double = 0.0,
    /** AutoISF's ISF curve averaged over the time in [duraISFminutes] (0 when nothing counts): dura_ISF is
     *  1 + (duraISFminutes / 60) * dura_ISF_weight * duraISFcurveAverage */
    val duraISFcurveAverage: Double = 0.0,
    /** where the count of [duraISFminutes] starts, which says why it reaches back no further (null when not known) */
    val duraISFstart: DuraIsfStart? = null,
    /** past duration of BG approximating parabola shape */
    val parabolaMinutes: Double = 0.0,
    /** parabola derived last delta, i.e. from -5m to now */
    val deltaPl: Double = 0.0,
    /** parabola derived next delta, i.e. from now to 5m into the future */
    val deltaPn: Double = 0.0,
    /** parabola derived current BG acceleration */
    val bgAcceleration: Double = 0.0,
    /** coefficient a0 in approximation parabola formula
     *  BG = a0 + a1 * Time + a2 * Time^2
     *  where 1 unit of Time is 5 minutes */
    val a0: Double = 0.0,
    /** coefficient a1 in approximation parabola formula
     *  BG = a0 + a1 * Time + a2 * Time^2
     *  where 1 unit of Time is 5 minutes */
    val a1: Double = 0.0,
    /** coefficient a2 in approximation parabola formula
     *  BG = a0 + a1 * Time + a2 * Time^2
     *  where 1 unit of Time is 5 minutes */
    val a2: Double = 0.0,
    /** correlation coefficient, i.e. quality of parabola fit */
    val corrSqu: Double = 0.0
) : GlucoseStatus