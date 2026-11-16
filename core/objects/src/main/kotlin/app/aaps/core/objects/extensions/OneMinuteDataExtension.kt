package app.aaps.core.objects.extensions

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor

/**
 * The sensors whose readings AutoISF treats as 1-minute data without the setting "Treat this CGM as
 * 1-minute raw data" (BooleanKey.ApsAutoIsfForce1MinuteRaw).
 */
fun SourceSensor.isLibre1Minute(): Boolean = text == "Libre2" || text == "Libre2 Native" || text == "Libre3"

/**
 * True when these readings (newest first) really come about every minute: the newest 4 within 10 minutes.
 * A 5-minute CGM fails this, also with one early reading. Asking for only the newest 3 within 3 minutes let
 * one late reading drop a 1-minute CGM onto the 5-minute grid.
 */
fun List<GV>.hasOneMinuteCadence(): Boolean = size > 3 && this[0].timestamp - this[3].timestamp < 10 * 60_000L

/**
 * True when AutoISF's 1-minute path applies to these readings (newest first): they come about every minute
 * and the sensor is a Libre 2/3, or [forced] says this CGM delivers 1-minute data. [forced] is only read when
 * the readings really are that dense and the sensor is not a Libre, so a preference lookup can be passed in.
 */
fun List<GV>.isOneMinuteRawData(forced: () -> Boolean): Boolean =
    hasOneMinuteCadence() && (this[0].sourceSensor.isLibre1Minute() || forced())
