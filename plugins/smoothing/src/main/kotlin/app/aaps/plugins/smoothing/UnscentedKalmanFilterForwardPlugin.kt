package app.aaps.plugins.smoothing

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.SP
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The same adaptive Unscented Kalman Filter as [UnscentedKalmanFilterPlugin], forward pass only: no RTS
 * backward pass, so every value uses only the readings up to its own time and all values carry the same
 * small lag. With the backward pass, older values are revised but the newest cannot be, and that
 * difference bends the recent curve - a steady rise reads as braking to AutoISF's parabola fit.
 */
@Singleton
class UnscentedKalmanFilterForwardPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    rxBus: RxBus,
    aapsSchedulers: AapsSchedulers,
    persistenceLayer: PersistenceLayer,
    sp: SP
) : UnscentedKalmanFilterPlugin(
    aapsLogger, rh, rxBus, aapsSchedulers, persistenceLayer, sp,
    R.string.UKF_name, R.string.description_UKF, backwardPass = false, keyPrefix = "ukf_fwd_"
)
