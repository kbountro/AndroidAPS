package app.aaps.plugins.aps.openAPSAutoISF

/**
 * AutoISF's ISF curve: the change in insulin effectiveness at a glucose level, as the factor ISF is divided by, minus 1
 * (0.11 at 200 mg/dL: insulin 10% less effective, ISF / 1.11). Used by bg_ISF through OpenAPSAutoISFPlugin.interpolate(),
 * and by dura_ISF through DuraIsfWindow, which averages it over the last hour's readings.
 *
 * kbountro: Changed polygon according to https://journals.sagepub.com/doi/pdf/10.1177/193229681000400416 (the change after
 * one hour at a steady glucose level).
 * kbountro: Guessed extrapolation of the above study from 200mg/dl to 300mg/dl, 10% -> 20% insulin effectiveness
 * kbountro: Moved bgISF weights out of the interpolate function
 */
internal object AutoIsfCurve {

    //  ...             <----------------------  glucose  ---------------------->
    //val polyX = arrayOf(50.0, 60.0, 80.0, 90.0, 100.0, 110.0, 150.0, 180.0, 200.0)
    //val polyY = arrayOf(-0.5, -0.5, -0.3, -0.2, 0.0, 0.0, 0.5, 0.7, 0.7)
    private val polyX = arrayOf(50.0, 60.0, 70.0, 80.0, 90.0, 150.0, 200.0, 300.0)
    private val polyY = arrayOf(-0.57, -0.28, -0.16, -0.06, 0.0, 0.0, 0.11, 0.25)

    /** The curve at glucose [xdata] (mg/dL): linear between the polygon's points, extended linearly beyond its ends. */
    fun at(xdata: Double): Double {   // interpolate ISF behaviour based on polygons defining nonlinear functions defined by value pairs for ...
        val polymax: Int = polyX.size - 1
        var step = polyX[0]
        var sVal = polyY[0]
        var stepT = polyX[polymax]
        var sValold = polyY[polymax]

        var newVal = 1.0
        var lowVal = 1.0
        val topVal: Double
        val lowX: Double
        val topX: Double
        val myX: Double
        var lowLabl = step

        if (step > xdata) {
            // extrapolate backwards
            stepT = polyX[1]
            sValold = polyY[1]
            lowVal = sVal
            topVal = sValold
            lowX = step
            topX = stepT
            myX = xdata
            newVal = lowVal + (topVal - lowVal) / (topX - lowX) * (myX - lowX)
        } else if (stepT < xdata) {
            // extrapolate forwards
            step = polyX[polymax - 1]
            sVal = polyY[polymax - 1]
            lowVal = sVal
            topVal = sValold
            lowX = step
            topX = stepT
            myX = xdata
            newVal = lowVal + (topVal - lowVal) / (topX - lowX) * (myX - lowX)
        } else {
            // interpolate
            for (i: Int in 0..polymax) {
                step = polyX[i]
                sVal = polyY[i]
                if (step == xdata) {
                    newVal = sVal
                    break
                } else if (step > xdata) {
                    topVal = sVal
                    lowX = lowLabl
                    myX = xdata
                    topX = step
                    newVal = lowVal + (topVal - lowVal) / (topX - lowX) * (myX - lowX)
                    break
                }
                lowVal = sVal
                lowLabl = step
            }
        }
        return newVal
    }
}
