package com.kepler22b.sky

import kotlin.math.roundToInt

/** Title and short, child-friendly description for a selected object. */
data class Description(val title: String, val subtitle: String, val body: String, val story: Story?)

object Describe {

    private val PLANET_FACTS = mapOf(
        "mercury" to "태양에서 가장 가까운 행성이에요. 해가 진 직후나 뜨기 직전에 아주 잠깐만 볼 수 있어서 찾기가 어려워요.",
        "venus" to "밤하늘에서 달 다음으로 밝은 천체예요. 새벽에 보이면 '샛별', 저녁에 보이면 '개밥바라기'라고 불렀어요.",
        "mars" to "붉게 빛나는 행성이에요. 땅이 녹슨 철 성분을 품고 있어서 붉게 보인대요.",
        "jupiter" to "태양계에서 가장 큰 행성이에요. 지구가 1,300개 넘게 들어갈 만큼 커요. 쌍안경으로 보면 옆에 작은 위성들이 보여요.",
        "saturn" to "멋진 고리를 가진 행성이에요. 작은 망원경으로도 고리를 볼 수 있답니다.",
    )

    fun of(obj: SkyObject, catalog: SkyCatalog, position: Horizontal?): Description = when (obj) {
        is SkyObject.StarObject -> star(obj.star, catalog, position)
        is SkyObject.BodyObject -> body(obj.body, position)
        is SkyObject.ConstellationObject -> constellation(obj.constellation, position)
        SkyObject.Kepler22b -> Description(
            title = "케플러-22b",
            subtitle = listOfNotNull("외계 행성 · 백조자리", where(position)).joinToString(" · "),
            body = KEPLER_22B,
            story = null,
        )
    }

    private const val KEPLER_22B =
        "이 앱의 이름이 된 행성이에요. 2011년 케플러 우주망원경이 태양 같은 별의 '생명가능지대'에서 처음 확인한 행성이에요. " +
            "생명가능지대는 별에서 너무 가깝지도 멀지도 않아서 물이 액체로 있을 수 있는 곳이에요." +
            "\n\n지구보다 두 배쯤 크고, 약 290일에 한 번 별을 한 바퀴 돌아요. 지구에서 약 640광년 떨어져 있어요." +
            "\n\n이 행성이 도는 별은 너무 희미해서 맨눈으로는 보이지 않아요. 동그라미가 바로 그 별이 있는 자리예요."

    private fun star(star: Star, catalog: SkyCatalog, position: Horizontal?): Description {
        val constellation = catalog.constellation(star.constellation)
        val title = star.nameKo ?: star.properName.ifEmpty { "${constellation?.nameKo ?: "이름 없는"}의 별" }
        val body = buildString {
            append("밝기는 ${oneDecimal(star.magnitude)}등급이에요. ")
            append(brightnessWords(star.magnitude))
            star.lightYears?.let {
                append("\n\n지구에서 약 ${thousands(it)}광년 떨어져 있어요. 지금 보는 이 별빛은 ${thousands(it)}년 전에 출발한 빛이에요!")
            }
            append("\n\n").append(colorWords(star.colorIndex))
        }
        return Description(title, listOfNotNull(constellation?.nameKo, where(position)).joinToString(" · "), body, Stories.forConstellation(star.constellation))
    }

    private fun body(body: Body, position: Horizontal?): Description = when (body.kind) {
        BodyKind.MOON -> Description(
            title = "달 · ${moonPhaseName(body.phase, body.waxing)}",
            subtitle = listOfNotNull("${(body.phase * 100).roundToInt()}% 밝게 빛나요", where(position)).joinToString(" · "),
            body = "달은 스스로 빛나지 않고 햇빛을 받아 빛나요. 매일 조금씩 모양이 바뀌어 약 29.5일마다 다시 보름달이 돼요." +
                if (body.phase > 0.8) "\n\n달이 밝은 날에는 희미한 별이 잘 보이지 않아요." else "",
            story = null,
        )
        BodyKind.SUN -> Description("태양", where(position) ?: "", "태양은 스스로 빛나는 별이에요. 절대로 맨눈이나 망원경으로 직접 보면 안 돼요!", null)
        BodyKind.PLANET -> Description(body.nameKo, listOfNotNull("행성", where(position)).joinToString(" · "), PLANET_FACTS[body.id].orEmpty(), null)
    }

    private fun constellation(c: Constellation, position: Horizontal?): Description {
        val story = Stories.forConstellation(c.abbr)
        val body = if (story != null) "이 별자리에는 '${story.title}' 이야기가 있어요. 이야기를 들으면서 하늘에서 찾아볼까요?" else "${c.nameEn} 별자리예요. 별을 눌러 이름을 알아보세요."
        return Description(c.nameKo, where(position) ?: "", body, story)
    }

    /**
     * What "설명 듣기" reads aloud: the object's name, then its legend (the star's own tale, or else its
     * constellation's), then the plain facts for objects without one.
     */
    fun narration(obj: SkyObject, catalog: SkyCatalog, position: Horizontal?): String {
        val d = of(obj, catalog, position)
        val legend = when (obj) {
            is SkyObject.StarObject -> Legends.forStar(obj.star.properName) ?: Legends.forConstellation(obj.star.constellation)
            is SkyObject.ConstellationObject -> Legends.forConstellation(obj.constellation.abbr)
            is SkyObject.BodyObject, SkyObject.Kepler22b -> null
        }
        val name = spokenName(d.title)
        val where = position?.takeIf { it.alt > 0 }?.let { " 지금 ${compass(it.az)}쪽 하늘에 떠 있어요." }.orEmpty()
        // A star's own legend that already names its constellation needs no introduction.
        if (obj is SkyObject.StarObject && legend != null && catalog.constellation(obj.star.constellation)?.nameKo?.let { it in legend } == true) {
            return legend + where
        }
        val intro = when (obj) {
            is SkyObject.StarObject -> catalog.constellation(obj.star.constellation)
                ?.let { "$name${josa(name, "은", "는")} ${it.nameKo}에 있는 별이에요." } ?: "$name${josa(name, "이에요", "예요")}."
            // "22b" is read "이십이 비", so the particle follows a vowel.
            SkyObject.Kepler22b -> "케플러-22b는 백조자리에 있는 외계 행성이에요."
            else -> "$name${josa(name, "이에요", "예요")}."
        }
        return listOf(intro + where, legend ?: d.body).joinToString(" ")
    }

    /** Picks the Korean particle form for [word]: [withFinal] after a final consonant (받침), else [withoutFinal]. */
    internal fun josa(word: String, withFinal: String, withoutFinal: String): String {
        val last = word.trimEnd().lastOrNull() ?: return withFinal
        if (last.code !in 0xAC00..0xD7A3) return withFinal
        return if ((last.code - 0xAC00) % 28 != 0) withFinal else withoutFinal
    }

    /** "직녀성 (베가)" → "직녀성": parentheses read badly aloud. */
    private fun spokenName(title: String) = title.substringBefore(" (").substringBefore(" ·")

    /** "남동쪽 하늘, 높이 32°" */
    fun where(position: Horizontal?): String? {
        val p = position ?: return null
        if (p.alt < 0) return "지금은 지평선 아래"
        return "${compass(p.az)}쪽 하늘, 높이 ${p.alt.roundToInt()}°"
    }

    fun compass(az: Double): String {
        val names = listOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")
        return names[(((az % 360) + 360 + 22.5) / 45).toInt() % 8]
    }

    fun moonPhaseName(phase: Double, waxing: Boolean): String = when {
        phase < 0.03 -> "삭 (보이지 않는 달)"
        phase > 0.97 -> "보름달"
        phase in 0.4..0.6 -> if (waxing) "상현달" else "하현달"
        phase < 0.4 -> if (waxing) "초승달" else "그믐달"
        else -> if (waxing) "차오르는 달" else "기우는 달"
    }

    /** "1.5", "-0.7": platform-independent, unlike String.format. */
    internal fun oneDecimal(x: Double): String {
        val tenths = kotlin.math.round(kotlin.math.abs(x) * 10).toLong()
        return (if (x < 0 && tenths > 0) "-" else "") + "${tenths / 10}.${tenths % 10}"
    }

    /** "1,300" */
    internal fun thousands(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()

    private fun brightnessWords(mag: Double) = when {
        mag < 0.5 -> "밤하늘에서 가장 밝은 별 중 하나예요."
        mag < 2.0 -> "도시에서도 잘 보이는 밝은 별이에요."
        mag < 4.0 -> "어두운 곳에서 잘 보이는 별이에요."
        else -> "아주 어두운 곳에서만 보이는 희미한 별이에요."
    }

    private fun colorWords(bv: Double) = when {
        bv < 0.0 -> "푸르스름한 하얀색이에요. 아주 뜨거운 별이랍니다."
        bv < 0.5 -> "하얗게 빛나요. 태양보다 뜨거운 별이에요."
        bv < 1.0 -> "노르스름하게 빛나요. 우리 태양과 비슷한 색이에요."
        else -> "주황빛, 붉은빛을 띠어요. 태양보다 차가운 별이에요."
    }
}
