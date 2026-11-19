package com.luo.skip.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 原子文件存储 + 环形日志。双进程共享 filesDir 下的 JSON 文件：
 * - config.json      全部配置 + 排除名单
 * - logs.json        跳过日志（环形 50 条）+ 今日/累计计数
 * - feedback.json    误跳现场（环形 30 条）
 * - custom_rules.json 用户导入的自定义规则
 *
 * 写入策略：先写同目录临时文件，再 renameTo 覆盖，保证任一时刻文件完整。
 * 线程安全：所有调用方在 IO 线程调用，内部对读-改-写序列加锁。
 */
object SkipStore {

    private val lock = Any()
    private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun todayKey(): String = LocalDate.now().format(dayFormat)
    fun formatTime(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
            .format(timeFormat)

    // ---------- 文件工具 ----------

    private fun configFile(ctx: Context) = File(ctx.filesDir, "config.json")
    private fun logFile(ctx: Context) = File(ctx.filesDir, "logs.json")
    private fun feedbackFile(ctx: Context) = File(ctx.filesDir, "feedback.json")
    private fun customRulesFile(ctx: Context) = File(ctx.filesDir, "custom_rules.json")

    private fun atomicWrite(file: File, text: String) {
        val dir = file.parentFile ?: return
        if (!dir.exists()) dir.mkdirs()
        val tmp = File(dir, "${file.name}.${android.os.Process.myPid()}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            // renameTo 在目标存在时的部分文件系统上会失败，先删再试一次
            file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }

    private fun readText(file: File): String? =
        if (file.exists()) try { file.readText() } catch (_: Exception) { null } else null

    // ---------- config.json ----------

    fun loadConfig(ctx: Context): SkipConfig = synchronized(lock) {
        val json = readText(configFile(ctx))?.let {
            try { JSONObject(it) } catch (_: Exception) { null }
        } ?: return defaultConfig()
        try {
            SkipConfig(
                paused = json.optBoolean("paused", false),
                preciseEnabled = json.optBoolean("preciseEnabled", true),
                genericEnabled = json.optBoolean("genericEnabled", true),
                vibrateEnabled = json.optBoolean("vibrateEnabled", false),
                skipToastEnabled = json.optBoolean("skipToastEnabled", true),
                themeMode = json.optString("themeMode", "system").takeIf { it in setOf("system", "light", "dark") } ?: "system",
                devMode = json.optBoolean("devMode", false),
                devModeUnlocked = json.optBoolean("devModeUnlocked", false),
                onboarded = json.optBoolean("onboarded", false),
                autoStartConfirmed = json.optBoolean("autoStartConfirmed", false),
                lockConfirmed = json.optBoolean("lockConfirmed", false),
                serviceEverEnabled = json.optBoolean("serviceEverEnabled", false),
                excludedPackages = json.optJSONArray("excluded")
                    ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it, null) } }?.toSet()
                    ?: DEFAULT_EXCLUDED,
            )
        } catch (_: Exception) {
            defaultConfig()
        }
    }

    private fun defaultConfig() = SkipConfig(excludedPackages = DEFAULT_EXCLUDED)

    fun saveConfig(ctx: Context, config: SkipConfig) = synchronized(lock) {
        val json = JSONObject()
            .put("paused", config.paused)
            .put("preciseEnabled", config.preciseEnabled)
            .put("genericEnabled", config.genericEnabled)
            .put("vibrateEnabled", config.vibrateEnabled)
            .put("skipToastEnabled", config.skipToastEnabled)
            .put("themeMode", config.themeMode)
            .put("devMode", config.devMode)
            .put("devModeUnlocked", config.devModeUnlocked)
            .put("onboarded", config.onboarded)
            .put("autoStartConfirmed", config.autoStartConfirmed)
            .put("lockConfirmed", config.lockConfirmed)
            .put("serviceEverEnabled", config.serviceEverEnabled)
            .put("excluded", JSONArray(config.excludedPackages.toSortedSet()))
        atomicWrite(configFile(ctx), json.toString())
    }

    // ---------- logs.json（环形 50 条 + 计数） ----------

    fun loadLogs(ctx: Context): SkipLogData = synchronized(lock) {
        val json = readText(logFile(ctx))?.let {
            try { JSONObject(it) } catch (_: Exception) { null }
        } ?: return SkipLogData()
        try {
            val today = todayKey()
            var todayCount = json.optInt("todayCount", 0)
            if (json.optString("lastDay") != today) todayCount = 0
            val entries = json.optJSONArray("logs")?.let { parseEntries(it) } ?: emptyList()
            SkipLogData(
                entries = entries,
                todayCount = todayCount,
                totalCount = json.optLong("totalCount", 0),
                lastDay = json.optString("lastDay", ""),
            )
        } catch (_: Exception) {
            SkipLogData()
        }
    }

    private fun parseEntries(arr: JSONArray): List<SkipLogEntry> {
        val list = mutableListOf<SkipLogEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list += SkipLogEntry(
                packageName = o.optString("packageName"),
                appName = o.optString("appName"),
                timeMillis = o.optLong("timeMillis"),
                hitType = o.optString("hitType", "precise"),
                buttonText = o.optString("buttonText"),
                durationMs = o.optLong("durationMs"),
                activityName = o.optString("activityName").takeIf { it.isNotEmpty() },
                viewId = o.optString("viewId").takeIf { it.isNotEmpty() },
            )
        }
        return list
    }

    private fun entryJson(e: SkipLogEntry): JSONObject = JSONObject()
        .put("packageName", e.packageName)
        .put("appName", e.appName)
        .put("timeMillis", e.timeMillis)
        .put("hitType", e.hitType)
        .put("buttonText", e.buttonText)
        .put("durationMs", e.durationMs)
        .put("activityName", e.activityName ?: "")
        .put("viewId", e.viewId ?: "")

    /** :service 跳过成功后写入。环形保留最近 50 条，按天归零今日计数。 */
    fun appendLog(ctx: Context, entry: SkipLogEntry) = synchronized(lock) {
        val old = readRawLogs(ctx)
        val today = todayKey()
        val todayCount = if (old.lastDay == today) old.todayCount + 1 else 1
        val entries = (listOf(entry) + old.entries).take(50)
        val data = SkipLogData(entries, todayCount, old.totalCount + 1, today)
        val json = JSONObject()
            .put("todayCount", todayCount)
            .put("totalCount", data.totalCount)
            .put("lastDay", today)
            .put("logs", JSONArray(entries.map { entryJson(it) }))
        atomicWrite(logFile(ctx), json.toString())
    }

    private fun readRawLogs(ctx: Context): SkipLogData {
        val json = readText(logFile(ctx))?.let {
            try { JSONObject(it) } catch (_: Exception) { null }
        } ?: return SkipLogData()
        return try {
            val today = todayKey()
            var todayCount = json.optInt("todayCount", 0)
            if (json.optString("lastDay") != today) todayCount = 0
            SkipLogData(
                entries = json.optJSONArray("logs")?.let { parseEntries(it) } ?: emptyList(),
                todayCount = todayCount,
                totalCount = json.optLong("totalCount", 0),
                lastDay = json.optString("lastDay", ""),
            )
        } catch (_: Exception) { SkipLogData() }
    }

    fun clearLogs(ctx: Context) = synchronized(lock) {
        val json = JSONObject()
            .put("todayCount", 0)
            .put("totalCount", 0)
            .put("lastDay", todayKey())
            .put("logs", JSONArray())
        atomicWrite(logFile(ctx), json.toString())
    }

    // ---------- feedback.json（环形 30 条） ----------

    fun loadFeedback(ctx: Context): List<SkipFeedback> = synchronized(lock) {
        val json = readText(feedbackFile(ctx))?.let {
            try { JSONObject(it) } catch (_: Exception) { null }
        } ?: return emptyList()
        try {
            json.optJSONArray("feedback")?.let { arr ->
                (0 until arr.length()).mapNotNull { o0 ->
                    val o = arr.optJSONObject(o0) ?: return@mapNotNull null
                    SkipFeedback(
                        packageName = o.optString("packageName"),
                        appName = o.optString("appName"),
                        timeMillis = o.optLong("timeMillis"),
                        buttonText = o.optString("buttonText"),
                        activityName = o.optString("activityName").takeIf { it.isNotEmpty() },
                        viewId = o.optString("viewId").takeIf { it.isNotEmpty() },
                    )
                }
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    fun appendFeedback(ctx: Context, fb: SkipFeedback) = synchronized(lock) {
        val old = loadFeedback(ctx)
        val list = (listOf(fb) + old).take(30)
        val json = JSONObject().put("feedback", JSONArray(list.map {
            JSONObject()
                .put("packageName", it.packageName)
                .put("appName", it.appName)
                .put("timeMillis", it.timeMillis)
                .put("buttonText", it.buttonText)
                .put("activityName", it.activityName ?: "")
                .put("viewId", it.viewId ?: "")
        }))
        atomicWrite(feedbackFile(ctx), json.toString())
    }

    // ---------- 规则 ----------

    /** 读取内置规则（assets/default_rules.json）。 */
    fun loadDefaultRules(ctx: Context): List<SkipRule> {
        val text = try {
            ctx.assets.open("default_rules.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return emptyList()
        }
        return parseRules(text)
    }

    /** 读取自定义规则（custom_rules.json），可能为空。 */
    fun loadCustomRules(ctx: Context): List<SkipRule> {
        val text = readText(customRulesFile(ctx)) ?: return emptyList()
        return parseRules(text)
    }

    /** 内置 + 自定义合并：同 id 时自定义优先。 */
    fun loadAllRules(ctx: Context): List<SkipRule> {
        val custom = loadCustomRules(ctx).associateBy { it.id }
        val merged = LinkedHashMap<String, SkipRule>()
        loadDefaultRules(ctx).forEach { merged[it.id] = custom[it.id] ?: it }
        custom.forEach { (id, rule) -> merged.putIfAbsent(id, rule) }
        return merged.values.toList()
    }

    /** 导入规则文件：整体替换自定义规则（与内置规则合并使用）。返回导入条数。 */
    fun importRules(ctx: Context, jsonText: String): Int {
        val rules = parseRules(jsonText)
        if (rules.isEmpty()) return 0
        val json = JSONObject()
            .put("version", 1)
            .put("rules", JSONArray(rules.map {
                JSONObject()
                    .put("id", it.id)
                    .put("appName", it.appName)
                    .put("packageName", it.packageName)
                    .put("type", it.type)
                    .put("viewId", it.viewId ?: "")
                    .put("texts", JSONArray(it.texts))
            }))
        atomicWrite(customRulesFile(ctx), json.toString())
        return rules.size
    }

    fun customRulesCount(ctx: Context): Int = loadCustomRules(ctx).size

    private fun parseRules(text: String): List<SkipRule> = try {
        val root = JSONObject(text)
        val arr = root.optJSONArray("rules") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val pkg = o.optString("packageName").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            SkipRule(
                id = o.optString("id", pkg),
                appName = o.optString("appName", pkg),
                packageName = pkg,
                viewId = o.optString("viewId").takeIf { it.isNotEmpty() },
                texts = o.optJSONArray("texts")?.let { ta ->
                    (0 until ta.length()).mapNotNull { ta.optString(it, null) }.filter { it.isNotEmpty() }
                }.takeIf { !it.isNullOrEmpty() } ?: listOf("跳过"),
                type = o.optString("type", "splash"),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
