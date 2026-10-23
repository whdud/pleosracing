package com.byeolmajung.sky

import java.io.BufferedReader

/** A catalog star. Position is J2000. */
data class Star(
    val hip: Int,
    val position: Equatorial,
    val magnitude: Double,
    val colorIndex: Double,
    val constellation: String,
    val properName: String,
    /** Distance in light years, null when unknown. */
    val lightYears: Int?,
) {
    /** Korean display name for well-known stars, otherwise null. */
    val nameKo: String? get() = StarNames.korean(properName)
}

data class Constellation(
    val abbr: String,
    val nameKo: String,
    val nameEn: String,
    val label: Equatorial,
    val lines: List<List<Equatorial>>,
)

class SkyCatalog(val stars: List<Star>, val constellations: List<Constellation>) {

    private val byAbbr = constellations.associateBy { it.abbr }

    fun constellation(abbr: String): Constellation? = byAbbr[abbr]

    companion object {
        /** Stars are kept faintest first, which is also the order they are painted in (bright ones on top). */
        fun parse(starsCsv: BufferedReader, constellationsTxt: BufferedReader): SkyCatalog =
            SkyCatalog(parseStars(starsCsv).sortedByDescending { it.magnitude }, parseConstellations(constellationsTxt))

        fun parseStars(reader: BufferedReader): List<Star> = reader.useLines { lines ->
            lines.drop(1).filter { it.isNotBlank() }.map { line ->
                val f = line.split(',')
                Star(
                    hip = f[0].toInt(),
                    position = Equatorial(f[1].toDouble(), f[2].toDouble()),
                    magnitude = f[3].toDouble(),
                    colorIndex = f[4].toDouble(),
                    constellation = f[5],
                    properName = f.getOrElse(6) { "" },
                    lightYears = f.getOrNull(7)?.toIntOrNull()?.takeIf { it > 0 },
                )
            }.toList()
        }

        fun parseConstellations(reader: BufferedReader): List<Constellation> {
            val result = mutableListOf<Constellation>()
            var header: List<String>? = null
            var lines = mutableListOf<List<Equatorial>>()

            fun flush() {
                val h = header ?: return
                result += Constellation(h[1], h[2], h[3], Equatorial(h[4].toDouble(), h[5].toDouble()), lines)
            }

            reader.useLines { input ->
                input.filter { it.isNotBlank() }.forEach { line ->
                    when {
                        line.startsWith("C|") -> {
                            flush()
                            header = line.split('|')
                            lines = mutableListOf()
                        }
                        line.startsWith("L|") -> lines += line.substring(2).split(',').map { p ->
                            val (ra, dec) = p.trim().split(' ')
                            Equatorial(ra.toDouble(), dec.toDouble())
                        }
                    }
                }
            }
            flush()
            return result
        }
    }
}

object StarNames {
    private val KO = mapOf(
        "Sirius" to "시리우스",
        "Canopus" to "카노푸스",
        "Arcturus" to "아르크투루스",
        "Rigil Kentaurus" to "리길 켄타우루스",
        "Vega" to "직녀성 (베가)",
        "Capella" to "카펠라",
        "Rigel" to "리겔",
        "Procyon" to "프로키온",
        "Achernar" to "아케르나르",
        "Betelgeuse" to "베텔게우스",
        "Hadar" to "하다르",
        "Altair" to "견우성 (알타이르)",
        "Acrux" to "아크룩스",
        "Aldebaran" to "알데바란",
        "Spica" to "스피카",
        "Antares" to "안타레스",
        "Pollux" to "폴룩스",
        "Fomalhaut" to "포말하우트",
        "Deneb" to "데네브",
        "Mimosa" to "미모사",
        "Regulus" to "레굴루스",
        "Adhara" to "아다라",
        "Castor" to "카스토르",
        "Shaula" to "샤울라",
        "Bellatrix" to "벨라트릭스",
        "Elnath" to "엘나트",
        "Alnilam" to "알닐람",
        "Alnitak" to "알니탁",
        "Mintaka" to "민타카",
        "Alioth" to "알리오스",
        "Dubhe" to "두베",
        "Merak" to "메라크",
        "Phecda" to "페크다",
        "Megrez" to "메그레즈",
        "Mizar" to "미자르",
        "Alkaid" to "알카이드",
        "Polaris" to "북극성 (폴라리스)",
        "Mirfak" to "미르파크",
        "Algol" to "알골",
        "Alpheratz" to "알페라츠",
        "Schedar" to "셰다르",
        "Caph" to "카프",
        "Albireo" to "알비레오",
        "Denebola" to "데네볼라",
        "Algieba" to "알기에바",
        "Kochab" to "코카브",
        "Alphard" to "알파르드",
        "Hamal" to "하말",
        "Markab" to "마르카브",
        "Saiph" to "사이프",
        "Alcyone" to "알키오네 (플레이아데스)",
    )

    fun korean(properName: String): String? = KO[properName]
}
