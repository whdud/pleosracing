package com.byeolmajung.sky

/** A constellation story for children. Written for this app; the legends themselves are traditional. */
data class Story(
    val id: String,
    val title: String,
    val constellations: List<String>,
    val season: String,
    /** Where to point the view while telling the story (J2000). */
    val focus: Equatorial,
    val pages: List<String>,
    /** Pages that look somewhere other than [focus], by page index. */
    val pageFocus: Map<Int, Equatorial> = emptyMap(),
) {
    fun focusFor(page: Int): Equatorial = pageFocus[page] ?: focus
}

object Stories {
    val all = listOf(
        Story(
            id = "gyeonwoo_jiknyeo",
            title = "견우와 직녀",
            constellations = listOf("Lyr", "Aql"),
            season = "여름",
            focus = Equatorial(288.5, 24.0),
            pages = listOf(
                "옛날 옛적, 하늘나라 임금님에게는 베를 아주 잘 짜는 딸 직녀가 있었어요. 직녀가 짠 옷감은 구름처럼 가볍고 무지개처럼 고왔지요.",
                "은하수 건너편에는 소를 돌보는 부지런한 목동 견우가 살았어요. 두 사람은 처음 만난 날부터 서로를 무척 좋아하게 되었답니다.",
                "그런데 둘은 함께 노느라 베 짜는 일도, 소 돌보는 일도 다 잊어버렸어요. 화가 난 임금님은 두 사람을 은하수 양쪽 끝으로 떨어뜨려 놓았어요.",
                "일 년에 딱 한 번, 칠월 칠석이 되면 까치와 까마귀들이 날아와 은하수 위에 다리를 놓아 주었어요. 이 다리를 '오작교'라고 불러요.",
                "여름밤 하늘을 보면 밝게 빛나는 직녀성과 견우성이 은하수를 사이에 두고 마주 보고 있어요. 두 별은 실제로 15광년쯤 떨어져 있어서, 빛의 속도로 달려도 15년이나 걸린대요!",
            ),
        ),
        Story(
            id = "bukdu_brothers",
            title = "북두칠성이 된 일곱 형제",
            constellations = listOf("UMa"),
            season = "봄",
            focus = Equatorial(178.0, 55.0),
            pages = listOf(
                "옛날 어느 마을에 어머니와 일곱 아들이 살았어요. 어머니는 밤마다 개울 건너 마을에 일을 하러 다녀오셨어요.",
                "차가운 개울물을 맨발로 건너다 보니 어머니의 발은 늘 꽁꽁 얼어 있었어요. 그 모습을 본 일곱 형제는 어머니를 도울 방법을 궁리했지요.",
                "형제들은 밤에 몰래 커다란 돌을 하나씩 날라 개울에 징검다리를 놓았어요. 다음 날 어머니는 발을 적시지 않고 개울을 건널 수 있었답니다.",
                "어머니는 하늘을 보며 '이 다리를 놓아 준 고마운 이들이 하늘의 별이 되게 해 주세요' 하고 빌었어요. 그렇게 일곱 형제는 북쪽 하늘의 일곱 별, 북두칠성이 되었대요.",
                "북두칠성은 국자 모양이에요. 국자 끝의 두 별을 이은 길이를 다섯 번 늘이면 북극성을 찾을 수 있어요. 옛날 사람들은 이렇게 길을 찾았답니다.",
            ),
        ),
        Story(
            id = "orion_scorpion",
            title = "오리온과 전갈",
            constellations = listOf("Ori", "Sco"),
            season = "겨울",
            focus = Equatorial(83.8, 2.0),
            pages = listOf(
                "오리온은 세상에서 가장 힘센 사냥꾼이었어요. 하지만 늘 '세상에 나보다 강한 동물은 없어!' 하고 뽐내곤 했지요.",
                "그 말을 들은 대지의 여신은 작은 전갈 한 마리를 보냈어요. 그 작은 전갈이 오리온의 발꿈치를 톡 쏘아, 힘센 오리온도 쓰러지고 말았어요.",
                "신들은 오리온과 전갈을 모두 하늘의 별자리로 만들었어요. 대신 둘이 다시는 다투지 않도록 하늘의 반대편에 멀리 떨어뜨려 놓았답니다.",
                "그래서 겨울밤에는 오리온자리가, 여름밤에는 전갈자리가 떠요. 전갈자리가 동쪽에서 떠오르면 오리온자리는 서쪽으로 숨어 버리지요.",
                "오리온자리의 어깨에 있는 붉은 별은 베텔게우스, 발에 있는 푸른 별은 리겔이에요. 별의 색깔이 다른 건 별의 온도가 다르기 때문이에요. 푸른 별이 더 뜨거워요!",
            ),
        ),
        Story(
            id = "perseus_andromeda",
            title = "페르세우스와 안드로메다 공주",
            // Cetus is the sea monster of the story.
            constellations = listOf("Per", "And", "Cas", "Cep", "Cet"),
            season = "가을",
            focus = Equatorial(15.0, 50.0),
            pages = listOf(
                "옛날 에티오피아에는 케페우스 왕과 카시오페이아 왕비, 그리고 아름다운 안드로메다 공주가 살았어요.",
                "왕비가 '우리 딸이 바다의 요정들보다 더 아름답다'고 자랑하자, 화가 난 바다의 신이 커다란 바다 괴물을 보냈어요.",
                "괴물을 달래려고 공주는 바닷가 바위에 묶이게 되었어요. 바로 그때, 날개 달린 신발을 신은 용감한 영웅 페르세우스가 하늘을 날아왔어요!",
                "페르세우스는 괴물을 물리치고 공주를 구했어요. 두 사람은 결혼해서 행복하게 살았고, 온 가족이 함께 가을 하늘의 별자리가 되었답니다.",
                "가을밤 북쪽 하늘에서 W 모양의 별 다섯 개를 찾아보세요. 그게 카시오페이아자리예요. 그 옆에 안드로메다자리와 페르세우스자리가 나란히 있어요.",
            ),
            // The sea monster page turns south to Cetus, far below the royal family.
            pageFocus = mapOf(1 to Equatorial(28.0, -6.0)),
        ),
    )

    fun forConstellation(abbr: String): Story? = all.firstOrNull { abbr in it.constellations }
}

/** Finds when a part of the sky is well placed for viewing. */
object Visibility {

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    /**
     * The next evening-to-dawn time (checked hourly from 8 p.m. to 4 a.m. local time) when [target] is at least
     * [minAltitude] high and the sky is dark, searching up to a year ahead. Returns null if never.
     *
     * @param utcOffsetMinutes the observer's time zone offset, e.g. 540 for Korea.
     */
    fun nextGoodTime(target: Equatorial, observer: Observer, fromMillis: Long, utcOffsetMinutes: Int, minAltitude: Double = 35.0): Long? {
        if (isGood(target, observer, fromMillis, minAltitude)) return fromMillis
        val offset = utcOffsetMinutes * 60_000L
        val localMidnight = Math2.floorDiv(fromMillis + offset, DAY) * DAY - offset
        for (day in 0..366) {
            for (hour in 20..28) {
                val t = localMidnight + day * DAY + hour * HOUR
                if (t > fromMillis && isGood(target, observer, t, minAltitude)) return t
            }
        }
        return null
    }

    private fun isGood(target: Equatorial, observer: Observer, millis: Long, minAltitude: Double): Boolean {
        val tf = HorizonTransform(millis, observer)
        if (tf.toHorizontal(target).alt < minAltitude) return false
        val sun = SolarSystem.bodies(millis).first { it.kind == BodyKind.SUN }
        return tf.toHorizontalOfDate(sun.equatorial).alt < -12
    }
}

internal object Math2 {
    fun floorMod(a: Int, b: Int): Int = ((a % b) + b) % b

    fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
    }
}
