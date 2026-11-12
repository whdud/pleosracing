package com.kepler22b.sky

/**
 * Gleo AI voice commands (Pleos Gleo Interface). Gleo calls one of [Action] with parameters it extracts
 * from what the user said; the app does the work and answers with a [Response] that Gleo turns into speech.
 *
 * This file holds the platform-independent part: finding what the user named, and building responses.
 * The Android app wires it to intents and the response broadcast.
 */
object Gleo {

    enum class Action(val functionName: String) {
        /** "글레오야, 이 별 뭐야?" / "직녀성에 대해 알려줘" */
        DESCRIBE_SKY_OBJECT("DESCRIBE_SKY_OBJECT"),

        /** "글레오야, 목성 어디 있어?" */
        FIND_SKY_OBJECT("FIND_SKY_OBJECT"),

        /** "글레오야, 견우와 직녀 이야기 들려줘" */
        TELL_CONSTELLATION_STORY("TELL_CONSTELLATION_STORY");

        companion object {
            fun of(functionName: String): Action? = entries.firstOrNull { it.functionName == functionName }
        }
    }

    enum class Status(val json: String) { SUCCESS("success"), ASKING("asking"), FAIL("fail") }

    /** How Gleo delivers the answer: "voice" (Gleo speaks), or "silent" when the app speaks itself. */
    enum class Mode(val json: String) { VOICE("voice"), SILENT("silent") }

    /**
     * The callback for Gleo. [message] is a short English sentence, as the Gleo guide asks; [data] carries the
     * facts Gleo needs to compose its spoken answer, in Korean.
     */
    data class Response(
        val action: Action,
        val status: Status,
        val message: String,
        val data: Map<String, String> = emptyMap(),
        val mode: Mode = Mode.VOICE,
    ) {
        /** Callback Schema JSON: data is a string holding a JSON array of one object. */
        fun toJson(): String = buildString {
            append("{\"functionName\":").append(quote(action.functionName))
            append(",\"status\":").append(quote(status.json))
            append(",\"message\":").append(quote(message))
            if (data.isNotEmpty()) {
                val array = data.entries.joinToString(",", "[{", "}]") { (k, v) -> "${quote(k)}:${quote(v)}" }
                append(",\"data\":").append(quote(array))
            }
            append(",\"mode\":").append(quote(mode.json))
            append("}")
        }
    }

    /** Finds a star, planet, the Moon, the Sun or a constellation by a spoken name such as "직녀성", "베가", "오리온". */
    fun find(name: String, snapshot: SkySnapshot): SkyObject? {
        val q = normalize(name)
        if (q.isEmpty()) return null
        if (q.startsWith("케플러") || q.startsWith("kepler")) return SkyObject.Kepler22b
        snapshot.bodies.firstOrNull { (body, _) -> normalize(body.nameKo) == q || body.id == q }
            ?.let { return SkyObject.BodyObject(it.first) }
        val stars = snapshot.catalog.stars
        stars.indices.firstOrNull { i ->
            val s = stars[i]
            val ko = s.nameKo ?: return@firstOrNull false
            // "직녀성 (베가)" answers to both "직녀성" and "베가".
            ko.split(" (", ")").map(::normalize).any { it.isNotEmpty() && it == q } || normalize(s.properName) == q
        }?.let { return SkyObject.StarObject(it, stars[it]) }
        // 고니 is the native Korean word for 백조, and some books use 고니자리.
        val asked = q.replace("고니", "백조")
        snapshot.catalog.constellations.firstOrNull { c ->
            normalize(c.nameKo) == asked || normalize(c.nameKo).removeSuffix("자리") == asked || normalize(c.nameEn) == q
        }?.let { return SkyObject.ConstellationObject(it) }
        return null
    }

    /** Matches a story title loosely: "견우 직녀", "견우와 직녀 이야기" and "오리온" all work. */
    fun findStory(name: String): Story? {
        val q = normalize(name)
        if (q.isEmpty()) return null
        val words = name.split(' ', ',').map(::normalize).filter { it.isNotEmpty() && it != "이야기" }
        return Stories.all.firstOrNull { normalize(it.title) == q }
            ?: Stories.all.firstOrNull { story -> normalize(story.title).contains(q) || q.contains(normalize(story.title)) }
            ?: Stories.all.firstOrNull { story -> words.isNotEmpty() && words.all { normalize(story.title).contains(it) } }
    }

    /** DESCRIBE_SKY_OBJECT: [obj] is the named object, or the one selected on screen when no name was given. */
    fun describe(obj: SkyObject?, snapshot: SkySnapshot, position: Horizontal?): Response {
        obj ?: return Response(
            Action.DESCRIBE_SKY_OBJECT, Status.ASKING,
            "No sky object is selected. Ask the user to tap a star on the screen or to say its name.",
        )
        val d = Describe.of(obj, snapshot.catalog, position)
        return Response(
            Action.DESCRIBE_SKY_OBJECT, Status.SUCCESS,
            "Found ${englishName(obj)} and its story.",
            facts(obj, d, position) + ("story" to Describe.narration(obj, snapshot.catalog, position)),
        )
    }

    /** FIND_SKY_OBJECT: the view has been turned toward [obj]; tell the user where to look. */
    fun found(obj: SkyObject?, name: String, snapshot: SkySnapshot, position: Horizontal?): Response {
        obj ?: return Response(Action.FIND_SKY_OBJECT, Status.FAIL, "No star, planet or constellation named '$name' was found.")
        val d = Describe.of(obj, snapshot.catalog, position)
        val visible = position != null && position.alt > 0
        return Response(
            Action.FIND_SKY_OBJECT, Status.SUCCESS,
            if (visible) "Turned the sky view toward ${englishName(obj)}; it is above the horizon now."
            else "${englishName(obj).replaceFirstChar { it.uppercase() }} is below the horizon now and cannot be seen.",
            facts(obj, d, position),
        )
    }

    /** TELL_CONSTELLATION_STORY: the app shows and reads the story itself, so Gleo stays silent. */
    fun storyStarted(story: Story?, name: String): Response =
        if (story == null) {
            Response(
                Action.TELL_CONSTELLATION_STORY, Status.FAIL,
                "There is no story called '$name'. Available stories: ${Stories.all.joinToString { it.title }}.",
            )
        } else {
            Response(
                Action.TELL_CONSTELLATION_STORY, Status.SUCCESS, "Started reading the story '${story.title}'.",
                mapOf("title" to story.title, "season" to story.season), Mode.SILENT,
            )
        }

    private fun facts(obj: SkyObject, d: Description, position: Horizontal?): Map<String, String> = buildMap {
        put("name", d.title.substringBefore(" (").substringBefore(" ·"))
        put("type", when (obj) {
            is SkyObject.StarObject -> "별"
            is SkyObject.ConstellationObject -> "별자리"
            is SkyObject.BodyObject -> d.subtitle.substringBefore(" ·").ifEmpty { "천체" }
            SkyObject.Kepler22b -> "외계 행성"
        })
        if (obj == SkyObject.Kepler22b) put("distanceLightYears", "640")
        if (obj is SkyObject.StarObject) {
            put("magnitude", Describe.oneDecimal(obj.star.magnitude))
            obj.star.lightYears?.let { put("distanceLightYears", it.toString()) }
        }
        position?.takeIf { it.alt > 0 }?.let { put("where", "${Describe.compass(it.az)}쪽 하늘 ${kotlin.math.round(it.alt).toInt()}도 높이에") }
    }

    private fun englishName(obj: SkyObject): String = when (obj) {
        is SkyObject.StarObject -> obj.star.properName.ifEmpty { "the star HIP ${obj.star.hip}" }
        is SkyObject.ConstellationObject -> "the constellation ${obj.constellation.nameEn}"
        is SkyObject.BodyObject -> "the ${obj.body.id}"
        SkyObject.Kepler22b -> "the exoplanet Kepler-22b"
    }

    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }
}
