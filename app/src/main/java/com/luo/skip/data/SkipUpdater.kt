package com.luo.skip.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

private const val MAX_UPDATE_BYTES = 512L * 1024 * 1024
private const val MAX_MANIFEST_BYTES = 64 * 1024

data class AppRelease(
    val schemaVersion: Int,
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val size: Long,
    val sha256: String,
    val url: String,
    val notes: String,
    val publishedAt: String,
)

private val REQUIRED_RELEASE_FIELDS = setOf(
    "schemaVersion",
    "applicationId",
    "versionCode",
    "versionName",
    "minSdk",
    "size",
    "sha256",
    "url",
    "notes",
    "publishedAt",
)

internal fun validateHttpsUrl(value: String): URI {
    require(value.isNotBlank() && value == value.trim() && !value.any { it in "?#\\ " }) {
        "更新地址必须是 HTTPS，且不能包含凭据或查询参数。"
    }
    val uri = try {
        URI(value)
    } catch (_: Exception) {
        throw IllegalArgumentException("更新地址格式不正确。")
    }
    require(
        uri.scheme?.lowercase() == "https" &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null
    ) {
        "更新地址必须是 HTTPS，且不能包含凭据或查询参数。"
    }
    return uri
}

internal fun effectivePort(uri: URI): Int = if (uri.port != -1) uri.port else 443

internal fun parseRelease(bytes: ByteArray, manifestUrl: String, applicationId: String): AppRelease {
    require(bytes.size <= MAX_MANIFEST_BYTES) { "更新信息过大，请稍后重试。" }
    val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    val json = try {
        JSONObject(text)
    } catch (_: Exception) {
        throw IllegalArgumentException("更新信息格式不正确，请稍后重试。")
    }
    val keys = json.keys().asSequence().toSet()
    require(keys.containsAll(REQUIRED_RELEASE_FIELDS)) { "更新信息不完整或格式不正确。" }

    val release = AppRelease(
        schemaVersion = json.optInt("schemaVersion", -1),
        applicationId = json.optString("applicationId", ""),
        versionCode = json.optLong("versionCode", -1L),
        versionName = json.optString("versionName", ""),
        minSdk = json.optInt("minSdk", -1),
        size = json.optLong("size", -1L),
        sha256 = json.optString("sha256", ""),
        url = json.optString("url", ""),
        notes = json.optString("notes", ""),
        publishedAt = json.optString("publishedAt", ""),
    )

    val origin = validateHttpsUrl(manifestUrl)
    val apk = validateHttpsUrl(release.url)
    require(
        apk.host.equals(origin.host, ignoreCase = true) &&
            effectivePort(apk) == effectivePort(origin) &&
            apk.path.endsWith(".apk")
    ) { "安装包来源与更新服务不一致。" }
    require(release.schemaVersion == 1 && release.applicationId == applicationId) { "更新包不适用于当前应用。" }
    require(
        release.versionCode in 1..2_100_000_000L &&
            release.versionName.isNotBlank() &&
            release.versionName.length <= 100 &&
            release.minSdk in 26..1000 &&
            release.size in 1..MAX_UPDATE_BYTES &&
            release.sha256.matches(Regex("[a-f0-9]{64}")) &&
            release.notes.length <= 8000
    ) { "更新信息不完整或格式不正确。" }
    try {
        Instant.parse(release.publishedAt)
    } catch (_: Exception) {
        throw IllegalArgumentException("更新发布时间格式不正确。")
    }
    return release
}

internal fun releaseToJson(release: AppRelease): JSONObject = JSONObject()
    .put("schemaVersion", release.schemaVersion)
    .put("applicationId", release.applicationId)
    .put("versionCode", release.versionCode)
    .put("versionName", release.versionName)
    .put("minSdk", release.minSdk)
    .put("size", release.size)
    .put("sha256", release.sha256)
    .put("url", release.url)
    .put("notes", release.notes)
    .put("publishedAt", release.publishedAt)

@Suppress("DEPRECATION")
internal fun PackageInfo.updateVersionCode(): Long =
    if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

@Suppress("DEPRECATION")
private fun PackageInfo.signerHashes(): Set<String> {
    val signers = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
    return signers.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
}

/**
 * 远程应用内更新器（与 stock 项目同协议，零第三方网络库依赖，仅在主进程用户触发时工作）。
 */
class SkipUpdater(
    private val context: Context,
    val manifestUrl: String,
) {
    private val directory: File
        get() = File(context.cacheDir, "app-updates").apply {
            check(isDirectory || mkdirs()) { "无法创建更新下载目录。" }
        }

    @Suppress("DEPRECATION")
    private fun installed(): PackageInfo = context.packageManager.getPackageInfo(
        context.packageName,
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
    )

    suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        check(manifestUrl.isNotEmpty()) { "更新服务尚未配置。" }
        val bytes = request(validateHttpsUrl(manifestUrl)) { conn ->
            val len = conn.contentLengthLong
            require(len <= MAX_MANIFEST_BYTES) { "更新信息过大。" }
            conn.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= MAX_MANIFEST_BYTES) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_MANIFEST_BYTES + 1 - output.size()))
                    if (count == -1) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        val release = parseRelease(bytes, manifestUrl, context.packageName)
        if (release.versionCode <= installed().updateVersionCode()) {
            null
        } else {
            require(release.minSdk <= Build.VERSION.SDK_INT) { "新版本暂不支持当前 Android 系统。" }
            release
        }
    }

    suspend fun download(release: AppRelease, progress: (Long) -> Unit): File = withContext(Dispatchers.IO) {
        parseRelease(releaseToJson(release).toString().toByteArray(Charsets.UTF_8), manifestUrl, context.packageName)
        require(release.versionCode > installed().updateVersionCode()) { "此版本已经安装，无需重复更新。" }
        directory.listFiles().orEmpty().forEach { it.delete() }
        val target = File(directory, "${release.sha256}-${UUID.randomUUID()}.apk")
        val partial = File(target.parentFile, "${target.nameWithoutExtension}.part.apk")
        val downloadCtx = coroutineContext
        try {
            request(validateHttpsUrl(release.url)) { conn ->
                val len = conn.contentLengthLong
                require(len == -1L || len == release.size) { "安装包大小与发布信息不一致。" }
                var count = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            downloadCtx.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            count += read
                            require(count <= release.size) { "下载内容超出安装包大小。" }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            progress(count)
                        }
                    }
                }
                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                require(count == release.size && actualSha == release.sha256) {
                    "安装包校验失败，请重新下载。"
                }
                verifyApk(partial, release)
                downloadCtx.ensureActive()
                check(partial.renameTo(target)) { "无法保存已下载的安装包。" }
            }
            val saved = AtomicFile(File(directory, "ready.json"))
            val stream = saved.startWrite()
            try {
                val payload = JSONObject()
                    .put("release", releaseToJson(release))
                    .put("fileName", target.name)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                stream.write(payload)
                saved.finishWrite(stream)
            } catch (error: Throwable) {
                saved.failWrite(stream)
                throw error
            }
            target
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                partial.delete()
                target.delete()
            }
            throw error
        }
    }

    suspend fun restore(): Pair<AppRelease, File>? = withContext(Dispatchers.IO) {
        val marker = File(directory, "ready.json")
        if (!marker.isFile) return@withContext null
        try {
            require(marker.length() <= MAX_MANIFEST_BYTES + 1024)
            val root = JSONObject(marker.readText())
            val releaseObj = root.getJSONObject("release")
            val fileName = root.optString("fileName", "")
            val release = parseRelease(releaseObj.toString().toByteArray(Charsets.UTF_8), manifestUrl, context.packageName)
            require(fileName.matches(Regex("[a-f0-9]{64}-[a-f0-9-]{36}\\.apk")))
            val apk = File(directory, fileName)
            installIntent(apk, release)
            release to apk
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            marker.delete()
            null
        }
    }

    @Suppress("DEPRECATION")
    internal fun verifyApk(file: File, release: AppRelease) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        require(
            archive != null &&
                archive.packageName == context.packageName &&
                archive.updateVersionCode() == release.versionCode &&
                archive.versionName == release.versionName &&
                archive.applicationInfo?.minSdkVersion == release.minSdk
        ) { "安装包身份或版本与发布信息不一致。" }
        val currentSigners = installed().signerHashes()
        require(currentSigners.isNotEmpty() && archive.signerHashes() == currentSigners) {
            "安装包签名不匹配，已阻止安装。"
        }
    }

    suspend fun installIntent(file: File, release: AppRelease): Intent = withContext(Dispatchers.IO) {
        require(file.canonicalFile.parentFile == directory.canonicalFile && file.extension == "apk" && file.isFile) {
            "安装包已失效，请重新下载。"
        }
        require(release.versionCode > installed().updateVersionCode()) { "此版本已经安装。" }
        require(release.minSdk <= Build.VERSION.SDK_INT) { "新版本暂不支持当前 Android 系统。" }
        require(file.length() == release.size) { "安装包已失效，请重新下载。" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
        require(actualSha == release.sha256) { "安装包已改变，请重新下载。" }
        verifyApk(file, release)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("轻跳更新", uri) }
    }

    private suspend fun <T> request(uri: URI, consume: suspend (HttpsURLConnection) -> T): T {
        val conn = try {
            (URL(uri.toString()).openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Cache-Control", "no-cache")
            }
        } catch (e: IOException) {
            throw IOException("无法连接更新服务，请检查网络后重试。", e)
        }
        return try {
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                throw IOException("无法连接更新服务，请检查网络后重试。", e)
            }
            check(code == 200) {
                if (code == 404) "更新包尚未发布，请稍后重试。" else "更新服务暂不可用（$code）。"
            }
            consume(conn)
        } finally {
            conn.disconnect()
        }
    }
}
