package com.samsung.genuiapp

import java.io.File

object CpuThreadHeuristics {
    private const val CPU_SYSFS_PATH = "/sys/devices/system/cpu"
    private const val BIG_CORE_THRESHOLD_KHZ = 2_000_000L
    private val CPU_DIR_PATTERN = Regex("^cpu\\d+")

    data class ThreadConfig(
        val threads: Int,
        val totalCores: Int,
        val highPerformanceCores: Int,
        val usedHighPerformanceOnly: Boolean
    )

    fun recommendedConfig(): ThreadConfig {
        val totalCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val highPerfCores = detectHighPerformanceCores(totalCores)
        val sanitizedPerfCores = highPerfCores.coerceIn(0, totalCores)

        val preferredThreads = when {
            sanitizedPerfCores >= 2 -> sanitizedPerfCores
            totalCores >= 8 -> totalCores - 2
            totalCores >= 4 -> totalCores - 1
            else -> totalCores
        }.coerceIn(1, totalCores)

        val usingPerfOnly = sanitizedPerfCores >= 2 && preferredThreads <= sanitizedPerfCores
        return ThreadConfig(
            threads = preferredThreads,
            totalCores = totalCores,
            highPerformanceCores = sanitizedPerfCores,
            usedHighPerformanceOnly = usingPerfOnly
        )
    }

    private fun detectHighPerformanceCores(totalCores: Int): Int {
        val cpuRoot = File(CPU_SYSFS_PATH)
        val entries = cpuRoot.listFiles() ?: return 0

        var count = 0
        for (entry in entries) {
            if (!CPU_DIR_PATTERN.matches(entry.name)) continue

            val freqFile = File(entry, "cpufreq/cpuinfo_max_freq")
            val freqKhz = runCatching {
                if (freqFile.exists() && freqFile.canRead()) {
                    freqFile.readText().trim().toLong()
                } else {
                    null
                }
            }.getOrNull() ?: continue

            if (freqKhz >= BIG_CORE_THRESHOLD_KHZ) {
                count++
            }
        }

        return count.coerceAtMost(totalCores)
    }
}
