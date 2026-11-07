package com.maxverstappen.racedash

object CircuitMapFiles {
    private val openF1KeysByErgastId = mapOf(
        "albert_park" to 10,
        "bahrain" to 63,
        "shanghai" to 49,
        "suzuka" to 46,
        "jeddah" to 149,
        "miami" to 151,
        "monaco" to 22,
        "catalunya" to 15,
        "villeneuve" to 23,
        "red_bull_ring" to 19,
        "silverstone" to 2,
        "spa" to 7,
        "hungaroring" to 4,
        "zandvoort" to 55,
        "monza" to 39,
        "baku" to 144,
        "marina_bay" to 61,
        "americas" to 9,
        "rodriguez" to 65,
        "interlagos" to 14,
        "vegas" to 152,
        "losail" to 150,
        "yas_marina" to 70,
        "madrid" to 153
    )

    private val filesByCircuitKey = mapOf(
        2 to "gb-1948",
        4 to "hu-1986",
        7 to "be-1925",
        9 to "us-2012",
        10 to "au-1953",
        12 to "my-1999",
        14 to "br-1940",
        15 to "es-1991",
        19 to "at-1969",
        22 to "mc-1929",
        23 to "ca-1978",
        39 to "it-1922",
        46 to "jp-1962",
        49 to "cn-2004",
        55 to "nl-1948",
        61 to "sg-2008",
        63 to "bh-2002",
        65 to "mx-1962",
        70 to "ae-2009",
        144 to "az-2016",
        149 to "sa-2021",
        150 to "qa-2004",
        151 to "us-2022",
        152 to "us-2023",
        153 to "es-2026"
    )

    fun forErgastCircuitId(circuitId: String): Int? = openF1KeysByErgastId[circuitId]

    fun forCircuitKey(circuitKey: Int): String? = filesByCircuitKey[circuitKey]
}
