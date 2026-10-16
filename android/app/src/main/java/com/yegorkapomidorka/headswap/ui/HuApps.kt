package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R

/**
 * Каталог чужих приложений, которые Android Auto показывает на «dev-ГУ» (Headswap представляется как Desktop Head Unit,
 * в AA включены «Неизвестные источники»). Ничего не скачиваем и не ставим сами: только ссылка на релизы и «Открыть».
 * Пакет должен быть и в `<queries>` манифеста, иначе на API 30+ его не видно.
 */
class HuApp(
    val name: String,
    val pkg: String,
    val url: String,
    val descRes: Int,
    /** Строка-требование («Нужен Android 15+») или null. */
    val reqRes: Int? = null,
    /** Минимальный SDK_INT телефона; 0 - без ограничения. */
    val minSdk: Int = 0,
)

/** Что показать про приложение: стоит ли оно и пойдёт ли на этом телефоне. */
data class HuAppStatus(val installed: Boolean, val unsupported: Boolean)

object HuApps {
    val CATALOG: List<HuApp> = listOf(
        HuApp(
            "Fermata Auto", "me.aap.fermata.auto.dear.google.why",
            "https://github.com/AndreyPavlenko/Fermata/releases", R.string.apps_fermata_desc,
        ),
        HuApp(
            "ParkedTube", "dev.codex.aaparkedyoutube",
            "https://github.com/gadmin2151/ParkedTube/releases", R.string.apps_parkedtube_desc,
            R.string.apps_aabrowser_req, minSdk = 35,
        ),
        HuApp(
            "AABrowser", "com.kododake.aabrowser",
            "https://github.com/kododake/AABrowser/releases", R.string.apps_aabrowser_desc,
            R.string.apps_aabrowser_req, minSdk = 35,
        ),
    )

    /** Чистая логика статуса: проверка установки и SDK_INT приходят снаружи (в тестах - фейки). */
    fun status(app: HuApp, isInstalled: (String) -> Boolean, sdkInt: Int): HuAppStatus =
        HuAppStatus(isInstalled(app.pkg), sdkInt < app.minSdk)

    fun installedCount(isInstalled: (String) -> Boolean, sdkInt: Int): Int =
        CATALOG.count { status(it, isInstalled, sdkInt).installed }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun status(ctx: Context, app: HuApp): HuAppStatus = status(app, { isInstalled(ctx, it) }, Build.VERSION.SDK_INT)

    /** Intent запуска или null: у приложения может не быть своей иконки (тогда кнопку «Открыть» не показываем). */
    fun launchIntent(ctx: Context, app: HuApp): Intent? =
        ctx.packageManager.getLaunchIntentForPackage(app.pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun download(ctx: Context, app: HuApp) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.e("${app.name}: браузер не открылся: ${e.message}")
        }
    }

    fun open(ctx: Context, app: HuApp) {
        try {
            launchIntent(ctx, app)?.let { ctx.startActivity(it) }
        } catch (e: Exception) {
            AppLog.e("${app.name}: не запустился: ${e.message}")
        }
    }
}
