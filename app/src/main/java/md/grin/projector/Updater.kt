package md.grin.projector

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Over-the-air updates: every new GitHub build is a release tagged v1.0.<build number>.
 * The launcher checks it, downloads the APK and installs it silently through its own ADB link,
 * or with the normal Android "Update?" prompt if ADB isn't available.
 */
object Updater {
    private const val REPO = "ernoxq/ernoxq-projector-home"
    @Volatile var status = ""
        private set
    @Volatile private var busy = false

    fun currentBuild(ctx: Context): Long = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    private fun latest(): Pair<Int, String>? = try {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        c.disconnect()
        val build = o.getString("tag_name").substringAfterLast('.').toInt()
        val assets = o.getJSONArray("assets")
        var url: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name") == "projector-home.apk") url = a.getString("browser_download_url")
        }
        url?.let { build to it }
    } catch (e: Exception) {
        null
    }

    /** Blocking; call from a background thread. Returns true if an update was installed or offered. */
    fun check(ctx: Context): Boolean {
        if (busy) return false
        busy = true
        try {
            status = "Проверяю обновления…"
            val (build, url) = latest() ?: run { status = "Нет связи с GitHub"; return false }
            if (build <= currentBuild(ctx)) {
                status = "Стоит последняя версия (1.0.${currentBuild(ctx)})"
                return false
            }
            status = "Скачиваю версию 1.0.$build…"
            val dir = ctx.getExternalFilesDir(null) ?: ctx.cacheDir
            val file = File(dir, "update.apk")
            download(url, file)
            status = "Устанавливаю 1.0.$build…"
            if (Adb.install(ctx, file)) return true
            installWithPrompt(ctx, file)
            return true
        } catch (e: Exception) {
            status = "Не удалось обновить: ${e.message}"
            return false
        } finally {
            busy = false
        }
    }

    private fun download(url: String, to: File) {
        var u = URL(url)
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            val code = c.responseCode
            if (code in 300..399 && hops < 5) {
                u = URL(u, c.getHeaderField("Location"))
                c.disconnect()
                hops++
                continue
            }
            break
        }
        c.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
        c.disconnect()
    }

    private fun installWithPrompt(ctx: Context, file: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("app", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val pending = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            s.commit(pending.intentSender)
        }
    }
}

/** Shows the system "Update?" screen when Android asks for confirmation. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
