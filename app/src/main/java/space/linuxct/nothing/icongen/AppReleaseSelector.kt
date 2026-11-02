package space.linuxct.nothing.icongen

import org.json.JSONArray
import org.json.JSONObject
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Tag format: build-{VERSION_NAME}-{VERSION_CODE}.{EPOCH}. Updates are ordered by versionCode. */
internal object AppReleaseSelector {
    private const val REPOSITORY = "https://github.com/linuxct/nothing-icongen"
    private val tagPattern = Regex("build-([0-9]+\\.[0-9]+\\.[0-9]+\\.([0-9]+))-([0-9]+)\\.([0-9]+)")

    data class BuildIdentity(val versionCode: Int)

    data class Release(val versionCode: Int, val epoch: Long, val pageUrl: String)

    val installedBuild: BuildIdentity
        get() = BuildIdentity(BuildConfig.VERSION_CODE)

    fun select(json: String, installed: BuildIdentity): Release? {
        if (installed.versionCode <= 0) return null
        val releases = try { JSONArray(json) } catch (_: Exception) { return null }
        var newest: Release? = null
        for (index in 0 until releases.length()) {
            val release = parse(releases.optJSONObject(index) ?: continue) ?: continue
            if (release.versionCode <= installed.versionCode) continue
            val previous = newest
            if (previous == null || release.versionCode > previous.versionCode ||
                (release.versionCode == previous.versionCode && release.epoch > previous.epoch)) {
                newest = release
            }
        }
        return newest
    }

    private fun parse(json: JSONObject): Release? {
        if (json.opt("draft") != false) return null
        val tag = json.optString("tag_name")
        val m = tagPattern.matchEntire(tag) ?: return null
        val versionCode = m.groupValues[2].toIntOrNull() ?: return null
        val epoch = m.groupValues[4].toLongOrNull() ?: return null
        val pageUrl = "$REPOSITORY/releases/tag/$tag"
        if (json.optString("html_url") != pageUrl) return null
        val assets = json.optJSONArray("assets") ?: return null
        var hasApk = false
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name") == "IconGen.apk" && asset.optString("state") == "uploaded" &&
                asset.optLong("size", 0) > 0 &&
                asset.optString("browser_download_url") == "$REPOSITORY/releases/download/$tag/IconGen.apk") {
                hasApk = true
                break
            }
        }
        if (!hasApk) return null
        return Release(versionCode, epoch, pageUrl)
    }
}