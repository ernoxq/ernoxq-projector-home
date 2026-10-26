package md.grin.projector

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings

data class AppEntry(val label: String, val pkg: String, val component: ComponentName)

data class TileSpec(
    val key: String,
    val label: String,
    val hint: String,
    val candidates: List<String>,
    val keywords: List<String> = emptyList(),
    val dark: Boolean = false,
    val fallbackIcon: Int = R.drawable.ic_grid,
)

/** Finding and opening apps. Works the same for the home screen and the phone remote. */
object Apps {
    const val AIRPLAY = "io.github.jqssun.airplay"

    val tiles = listOf(
        TileSpec("youtube", "YouTube", "Видео", listOf("com.google.android.youtube.tv", "com.google.android.youtube", "com.liskovsoft.smarttubetv.beta", "com.teamsmart.videomanager.tv"), listOf("youtube")),
        TileSpec("netflix", "Netflix", "Фильмы и сериалы", listOf("com.netflix.ninja", "com.netflix.mediaclient"), listOf("netflix")),
        TileSpec("spotify", "Spotify", "Музыка", listOf("com.spotify.tv.android", "com.spotify.music"), listOf("spotify"), fallbackIcon = R.drawable.ic_music),
        TileSpec("cast", "Трансляция", "iPhone и Mac", listOf(AIRPLAY, "com.ionitech.airscreen"), listOf("airplay", "airscreen", "mirror", "cast"), dark = true, fallbackIcon = R.drawable.ic_cast),
        TileSpec("hdmi", "HDMI", "Внешний вход", emptyList(), listOf("hdmi", "source", "signal", "input", "tvinput"), fallbackIcon = R.drawable.ic_hdmi),
        TileSpec("files", "Файлы", "Флешка и загрузки", listOf("com.softwinner.TvdFileManager", "com.android.documentsui"), listOf("file", "файл", "explorer", "media center", "mediacenter"), fallbackIcon = R.drawable.ic_folder),
    )

    private var cache: List<AppEntry>? = null

    fun invalidate() { cache = null }

    /** Every app that can be opened, from both the TV and the phone-style launcher lists. */
    fun all(ctx: Context): List<AppEntry> {
        cache?.let { return it }
        val pm = ctx.packageManager
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == ctx.packageName || !seen.add(pkg)) continue
                out += AppEntry(ri.loadLabel(pm).toString(), pkg, ComponentName(pkg, ri.activityInfo.name))
            }
        }
        out.sortBy { it.label.lowercase() }
        cache = out
        return out
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean = all(ctx).any { it.pkg == pkg }

    /** Which app a tile opens: the user's own choice first, then known packages, then a name match. */
    fun resolve(ctx: Context, spec: TileSpec): AppEntry? {
        val prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
        val apps = all(ctx)
        prefs.getString("tile_${spec.key}", null)?.let { chosen -> apps.firstOrNull { it.pkg == chosen }?.let { return it } }
        for (c in spec.candidates) apps.firstOrNull { it.pkg == c }?.let { return it }
        for (k in spec.keywords) {
            apps.firstOrNull { it.label.lowercase().contains(k) || it.pkg.lowercase().contains(k) }?.let { return it }
        }
        return null
    }

    fun choose(ctx: Context, spec: TileSpec, pkg: String) {
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putString("tile_${spec.key}", pkg).apply()
    }

    fun icon(ctx: Context, entry: AppEntry): Drawable? = try {
        ctx.packageManager.getActivityIcon(entry.component)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun banner(ctx: Context, entry: AppEntry): Drawable? = try {
        ctx.packageManager.getActivityBanner(entry.component) ?: ctx.packageManager.getApplicationBanner(entry.pkg)
    } catch (e: Exception) {
        null
    }

    /** Opens an app. Falls back to ADB when Android blocks a start from the background (phone remote). */
    fun launch(ctx: Context, entry: AppEntry): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = entry.component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        val started = try {
            ctx.startActivity(intent); true
        } catch (e: Exception) {
            false
        }
        if (!started || !App.isForeground) {
            Thread { Adb.shell(ctx, "am start -n ${entry.component.flattenToShortString()}") }.start()
        }
        return true
    }

    fun launchKey(ctx: Context, key: String): Boolean {
        if (key == "settings") return openSettings(ctx)
        if (key == "home") return goHome(ctx)
        if (key == "apps") {
            ctx.startActivity(Intent(ctx, AllAppsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
        val spec = tiles.firstOrNull { it.key == key }
        val entry = if (spec != null) resolve(ctx, spec) else all(ctx).firstOrNull { it.pkg == key }
        if (entry != null) return launch(ctx, entry)
        if (key == "hdmi") return openSettings(ctx)
        return false
    }

    fun goHome(ctx: Context): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {}
        if (!App.isForeground) Thread { Adb.key(ctx, 3) }.start()
        return true
    }

    fun openSettings(ctx: Context): Boolean {
        val tries = listOf(
            Intent(Settings.ACTION_SETTINGS),
            ctx.packageManager.getLaunchIntentForPackage("com.htc.htcsettings"),
            ctx.packageManager.getLeanbackLaunchIntentForPackage("com.android.tv.settings"),
        )
        for (i in tries) {
            if (i == null) continue
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
            } catch (_: Exception) {}
        }
        return false
    }
}
