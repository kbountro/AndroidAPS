package app.aaps.core.interfaces.aps

import kotlinx.serialization.Serializable

/** Where AutoISF's dura_ISF count ([GlucoseStatusAutoIsf.duraISFminutes]) starts, going back from the newest reading. */
@Serializable
enum class DuraIsfStart {

    /** Nothing counts: the 5-minute average ending at the newest reading is 150 mg/dL or less. */
    NOT_ABOVE_FLOOR,

    /** Where glucose rose above 150 mg/dL: the next older reading's 5-minute average is 150 or less. */
    RISE,

    /** After glucose last fell by 5% or more. */
    FALL,

    /** An hour back: the count covers the whole hour it looks at. */
    HOUR,

    /** After a gap in the readings. */
    GAP,

    /** At the oldest reading there is. */
    OLDEST
}
