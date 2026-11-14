package com.example

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Downloads and verifies a SONIX release before handing installation to Android. */
internal object AppUpdateInstaller {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private const val PREFS = "sonix_app_update"
    private const val PENDING_INSTALL = "pending_install_path"

    fun download(
        context: Context,
        update: org.json.JSONObject,
        onProgress: (Int) -> Unit,
        onReady: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val app = context.applicationContext
        val url = update.optString("apk_url")
        val versionCode = update.optLong("version_code", -1L)
        val expectedSha = update.optString("sha256").lowercase()
        if (!url.startsWith("https://") || versionCode <= 0L || !expectedSha.matches(Regex("^[0-9a-f]{64}$"))) {
            onError("بيانات التحديث غير صالحة")
            return
        }
        executor.execute {
            try {
                val fileName = "sonix-update-$versionCode.apk"
                val directory = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: throw IllegalStateException("مساحة تنزيل التطبيق غير متاحة")
                if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("تعذر إنشاء مجلد التنزيل")
                val destination = File(directory, fileName)
                if (destination.exists() && !destination.delete()) throw IllegalStateException("تعذر استبدال ملف التحديث السابق")

                val request = DownloadManager.Request(Uri.parse(url))
                    .setTitle("تحديث SONIX " + update.optString("version_name"))
                    .setDescription("جارٍ تنزيل تحديث التطبيق")
                    .setMimeType("application/vnd.android.package-archive")
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, fileName)

                val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val downloadId = manager.enqueue(request)
                val startedAt = System.currentTimeMillis()
                var completedFile: File? = null

                while (System.currentTimeMillis() - startedAt < 30 * 60 * 1000L) {
                    val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
                    if (cursor == null || !cursor.moveToFirst()) {
                        cursor?.close()
                        throw IllegalStateException("تعذر متابعة تنزيل التحديث")
                    }
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    cursor.close()

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val actual = destination
                            if (!actual.isFile) throw IllegalStateException("ملف APK غير موجود بعد التنزيل")
                            if (sha256(actual) != expectedSha) throw SecurityException("فشل التحقق من SHA-256؛ تم رفض ملف التحديث")
                            verifyApk(app, actual, versionCode)
                            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .putString(PENDING_INSTALL, actual.absolutePath).apply()
                            completedFile = actual
                            break
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val c = manager.query(DownloadManager.Query().setFilterById(downloadId))
                            val reason = c?.use { if (it.moveToFirst()) it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)) else -1 } ?: -1
                            throw IllegalStateException("فشل تنزيل التحديث (رمز $reason)")
                        }
                        else -> {
                            val progress = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else -1
                            mainHandler.post { onProgress(progress) }
                            Thread.sleep(1000L)
                        }
                    }
                }
                val ready = completedFile ?: throw IllegalStateException("انتهت مهلة تنزيل التحديث")
                mainHandler.post { onReady(ready) }
            } catch (error: Exception) {
                mainHandler.post { onError(error.message ?: "تعذر تنزيل التحديث") }
            }
        }
    }

    fun install(context: Context, apk: File): Boolean {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PENDING_INSTALL, apk.absolutePath).apply()
            app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        return try {
            val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PENDING_INSTALL).apply()
            true
        } catch (error: Exception) {
            android.util.Log.e("SONIXUpdate", "Unable to launch Android package installer", error)
            false
        }
    }

    fun resumePendingInstall(context: Context) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) return
        val path = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PENDING_INSTALL, null) ?: return
        val apk = File(path)
        if (apk.isFile) install(app, apk)
        else app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PENDING_INSTALL).apply()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, apk: File, expectedVersionCode: Long) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: throw SecurityException("ملف APK غير صالح")
        if (candidate.packageName != context.packageName) throw SecurityException("ملف التحديث لا يخص تطبيق SONIX الحالي")
        val actualVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) candidate.longVersionCode else candidate.versionCode.toLong()
        if (actualVersionCode != expectedVersionCode) throw SecurityException("رقم إصدار APK لا يطابق الإصدار المنشور")
        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else pm.getPackageInfo(context.packageName, flags)
        val candidateSigners = signerDigests(candidate)
        val installedSigners = signerDigests(installed)
        if (candidateSigners.isEmpty() || installedSigners.isEmpty() || candidateSigners != installedSigners) {
            throw SecurityException("توقيع APK لا يطابق توقيع التطبيق المثبت؛ تم رفض التثبيت")
        }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners ?: emptyArray()
        else info.signatures ?: emptyArray()
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
