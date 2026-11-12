package com.byeolmajung.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the real MainActivity on the JVM with Robolectric's native graphics and saves screenshots to
 * app/build/screenshots, a quick way to see the app without an emulator. Pleos services are absent here,
 * so the app falls back to preset places, like Google's stock Automotive emulator.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1920dp-h720dp-land-mdpi")
class AppScreenshotTest {

    // Unit tests run with the module directory as the working directory.
    private val outDir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }

    @Test
    fun captureMainScreens() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        // No Pleos services here, as on a stock emulator: refuse the Vehicle SDK's service binding.
        shadowOf(RuntimeEnvironment.getApplication()).declareActionUnbindable("ai.pleos.playground.vehicle.VEHICLE_START")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val root = controller.get().window.decorView

        waitFor { findSky(root)?.snapshot != null }
        click(root, "영양 국제밤하늘보호공원")
        click(root, "영양 국제밤하늘보호공원\n아주 어두운 하늘")
        if (findButton(root, "오늘 밤 9시 하늘")?.isShown == true) click(root, "오늘 밤 9시 하늘")
        shot(root, "1_main")

        click(root, "별자리 선")
        shot(root, "2_constellation_lines")
        click(root, "별자리 선")
        click(root, "별자리 그림")
        shot(root, "2b_constellation_art")
        click(root, "별자리 그림")

        click(root, "별자리 이야기")
        shot(root, "3_story_list")
        click(root, "견우와 직녀\n여름 별자리")
        shot(root, "4_story_page")
        click(root, "다음")
        shot(root, "5_story_page2")
        click(root, "×")

        // Gleo: "글레오야, 직녀성에 대해 알려줘"
        controller.newIntent(Intent("DESCRIBE_SKY_OBJECT").putExtra("ACTION_PARAM", "{\"name\":\"직녀성\"}"))
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertNotNull(findButton(root, "설명 듣기"), "the star panel offers 설명 듣기")
        val response = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents
            .last { it.action == "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE" }
            .getStringExtra("response").orEmpty()
        assertTrue(response.contains("\"status\":\"success\"") && response.contains("Vega"), response)
        shot(root, "6_gleo_describe")
        click(root, "×")

        // Gleo: "글레오야, 달 어디 있어?" turns the view to the Moon (when it is up).
        controller.newIntent(Intent("FIND_SKY_OBJECT").putExtra("ACTION_PARAM", "{\"name\":\"달\"}"))
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        shot(root, "6b_moon")
        click(root, "×")

        click(root, "선루프")
        shot(root, "7_sunroof")
        click(root, "앞유리")

        click(root, "관측 모드")
        shot(root, "8_stargazing_mode")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(condition: () -> Boolean) {
        repeat(200) {
            idle()
            if (condition()) return
            Thread.sleep(50)
        }
        error("timed out")
    }

    private fun click(root: View, label: String) {
        val button = assertNotNull(findButton(root, label), "no button '$label'")
        button.performClick()
        idle()
        // Let the view's turn-to animation finish.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
    }

    private fun findButton(view: View, label: String): Button? = when {
        view is Button && view.text.toString() == label -> view
        view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findButton(view.getChildAt(it), label) }
        else -> null
    }

    private fun findSky(view: View): SkyView? = when (view) {
        is SkyView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findSky(view.getChildAt(it)) }
        else -> null
    }

    private fun shot(root: View, name: String) {
        idle()
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
