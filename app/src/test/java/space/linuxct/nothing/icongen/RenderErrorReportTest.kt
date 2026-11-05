package space.linuxct.nothing.icongen

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderErrorReportTest {
    @Test fun listsEachAppWithItsExceptionAndTwoFrames() {
        val failure = IllegalStateException("bad bounds").apply {
            stackTrace = arrayOf(
                StackTraceElement("a.Renderer", "draw", "Renderer.kt", 10),
                StackTraceElement("a.Pipeline", "glyph", "Pipeline.kt", 20),
                StackTraceElement("a.Engine", "generate", "Engine.kt", 30),
            )
        }
        val noFrames = RuntimeException("boom").apply { stackTrace = emptyArray() }

        val report = IconPackBuildService.renderErrorReport(listOf(
            "com.example/.Main" to failure,
            "org.other/.Launcher" to noFrames,
        ))

        assertEquals("""
            Take a screenshot of this error and submit it to the author.

            com.example/.Main
            java.lang.IllegalStateException: bad bounds
              at a.Renderer.draw(Renderer.kt:10)
              at a.Pipeline.glyph(Pipeline.kt:20)

            org.other/.Launcher
            java.lang.RuntimeException: boom
        """.trimIndent(), report)
    }
}
