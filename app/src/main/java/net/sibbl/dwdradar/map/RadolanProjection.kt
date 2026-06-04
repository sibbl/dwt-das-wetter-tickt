package net.sibbl.dwdradar.map

import net.sibbl.dwdradar.model.GeoPoint
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object RadolanProjection {
    private const val R = 6370.04
    private const val PHI_0_RAD = 60.0 * PI / 180.0
    private const val LAMBDA_0_RAD = 10.0 * PI / 180.0
    private val SIN_PHI_0 = sin(PHI_0_RAD)

    fun project(point: GeoPoint): ProjectedPoint {
        val phi = point.latitude * PI / 180.0
        val lambda = point.longitude * PI / 180.0
        
        val m = (1.0 + SIN_PHI_0) / (1.0 + sin(phi))
        val x = R * m * cos(phi) * sin(lambda - LAMBDA_0_RAD)
        val y = -R * m * cos(phi) * cos(lambda - LAMBDA_0_RAD)
        
        return ProjectedPoint(x = x, y = y)
    }

    fun unproject(point: ProjectedPoint): GeoPoint {
        val x = point.x
        val y = point.y
        
        val rho = sqrt(x * x + y * y)
        val c = R * (1.0 + SIN_PHI_0)
        
        val phi = PI / 2.0 - 2.0 * atan(rho / c)
        val lambda = LAMBDA_0_RAD + atan2(x, -y)
        
        val latitude = phi * 180.0 / PI
        val longitude = lambda * 180.0 / PI
        
        return GeoPoint(latitude = latitude, longitude = longitude)
    }
}
