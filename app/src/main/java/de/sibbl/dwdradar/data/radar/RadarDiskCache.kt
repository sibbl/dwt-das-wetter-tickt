package de.sibbl.dwdradar.data.radar

import java.io.File

class RadarDiskCache(
    cacheDirectory: File,
    private val clock: () -> Long
) {
    private val rootDirectory = File(cacheDirectory, "dwd_radar").apply { mkdirs() }
    private val overviewFile = File(rootDirectory, "animation_overview_v2.json")
    private val assetDirectory = File(rootDirectory, "assets").apply { mkdirs() }

    fun readOverview(maxAgeMillis: Long): String? {
        if (!overviewFile.exists()) {
            return null
        }
        val ageMillis = clock() - overviewFile.lastModified()
        if (ageMillis > maxAgeMillis) {
            return null
        }
        return overviewFile.readText()
    }

    fun writeOverview(json: String) {
        val tempFile = File(rootDirectory, "${overviewFile.name}.tmp")
        tempFile.writeText(json)
        if (overviewFile.exists()) {
            overviewFile.delete()
        }
        if (!tempFile.renameTo(overviewFile)) {
            tempFile.copyTo(overviewFile, overwrite = true)
            tempFile.delete()
        }
    }

    fun assetFile(assetPath: String): File {
        return File(assetDirectory, assetPath.substringAfterLast('/'))
    }
}
