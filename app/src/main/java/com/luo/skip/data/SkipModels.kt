package com.luo.skip.data

/** 精准规则（内置 assets/default_rules.json 与自定义 custom_rules.json 共用协议）。 */
data class SkipRule(
    val id: String,
    val appName: String,
    val packageName: String,
    val viewId: String? = null,
    val texts: List<String> = listOf("跳过"),
    val type: String = "splash",
    /** 针对屏蔽无障碍点击的开屏页，匹配到跳过节点后直接触发应用内首页路由（如 damai://home）。 */
    val intentUri: String? = null,
)

const val DEFAULT_WINDOW_SECONDS = 10
const val MIN_WINDOW_SECONDS = 1
const val MAX_WINDOW_SECONDS = 60

/** 全部配置（config.json，双进程原子读写）。 */
data class SkipConfig(
    /** 首页「已暂停」态。 */
    val paused: Boolean = false,
    /** 精准规则开关。 */
    val preciseEnabled: Boolean = true,
    /** 通用识别开关。 */
    val genericEnabled: Boolean = true,
    /** 开屏检测窗口时长（秒），默认 10 秒。 */
    val windowSeconds: Int = DEFAULT_WINDOW_SECONDS,
    /** 跳过成功轻震动。 */
    val vibrateEnabled: Boolean = false,
    /** 跳过成功顶部轻提示（无障碍浮层，默认开启）。 */
    val skipToastEnabled: Boolean = true,
    /** 外观主题：system / light / dark。 */
    val themeMode: String = "system",
    /** 开发者模式（连点版本号解锁后可开）。 */
    val devMode: Boolean = false,
    /** 是否已解锁开发者模式（7 连点版本号）。 */
    val devModeUnlocked: Boolean = false,
    /** 首次引导是否完成。 */
    val onboarded: Boolean = false,
    /** 自启动手动确认。 */
    val autoStartConfirmed: Boolean = false,
    /** 多任务锁定手动确认。 */
    val lockConfirmed: Boolean = false,
    /** 服务曾经连接过（用于首页区分未开启/已断开）。 */
    val serviceEverEnabled: Boolean = false,
    /** 排除名单（首次初始化为默认支付/银行/政务清单）。 */
    val excludedPackages: Set<String> = DEFAULT_EXCLUDED,
)

/** 单条跳过记录（logs.json，环形 50 条）。 */
data class SkipLogEntry(
    val packageName: String,
    val appName: String,
    val timeMillis: Long,
    /** precise = 精准规则，generic = 通用识别。 */
    val hitType: String,
    val buttonText: String,
    val durationMs: Long,
    val activityName: String? = null,
    val viewId: String? = null,
)

/** logs.json 的整体结构（记录 + 计数）。 */
data class SkipLogData(
    val entries: List<SkipLogEntry> = emptyList(),
    val todayCount: Int = 0,
    val totalCount: Long = 0,
    /** 今日计数归属的日期 key（yyyy-MM-dd），跨天自动归零。 */
    val lastDay: String = "",
)

/** 「这次跳错了」误跳现场（feedback.json，环形 30 条）。 */
data class SkipFeedback(
    val packageName: String,
    val appName: String,
    val timeMillis: Long,
    val buttonText: String,
    val activityName: String? = null,
    val viewId: String? = null,
)

/** 默认排除清单：支付、银行、政务类，避免付款时误点。 */
val DEFAULT_EXCLUDED: Set<String> = setOf(
    // 支付 / 钱包
    "com.tencent.mm",                 // 微信
    "com.eg.android.AlipayGphone",    // 支付宝
    "com.unionpay",                   // 云闪付
    // 银行
    "com.icbc",                       // 工商银行
    "com.chinamworld.main",           // 建设银行
    "com.android.bankabc",            // 农业银行
    "com.chinamworld.bocmbci",        // 中国银行
    "com.cmbchina.ccd.pluto.cmbActivity", // 招商银行
    "com.yitong.mbank.psbc",          // 邮储银行
    "com.bankcomm.BankComm",          // 交通银行
    "com.spdb.mobilebank.per",        // 浦发银行
    "com.cmbc.mbanking",              // 民生银行
    "com.ecitic.bank.mobile",         // 中信银行
    "com.cebbank.mobilebank",         // 光大银行
    "com.pingan.lifecircle",          // 平安口袋银行
    // 政务 / 民生
    "cn.gov.tax.its",                 // 个人所得税
    "com.MobileTicket",               // 铁路12306
)

/** 默认排除项在排除页展示的原因文案。 */
val DEFAULT_EXCLUDED_WHY: Map<String, String> = mapOf(
    "com.tencent.mm" to "默认排除 · 通讯",
    "com.eg.android.AlipayGphone" to "默认排除 · 支付",
    "com.unionpay" to "默认排除 · 支付",
    "cn.gov.tax.its" to "默认排除 · 政务",
    "com.MobileTicket" to "默认排除 · 政务",
    "com.icbc" to "默认排除 · 银行",
    "com.chinamworld.main" to "默认排除 · 银行",
    "com.android.bankabc" to "默认排除 · 银行",
    "com.chinamworld.bocmbci" to "默认排除 · 银行",
    "com.cmbchina.ccd.pluto.cmbActivity" to "默认排除 · 银行",
    "com.yitong.mbank.psbc" to "默认排除 · 银行",
    "com.bankcomm.BankComm" to "默认排除 · 银行",
    "com.spdb.mobilebank.per" to "默认排除 · 银行",
    "com.cmbc.mbanking" to "默认排除 · 银行",
    "com.ecitic.bank.mobile" to "默认排除 · 银行",
    "com.cebbank.mobilebank" to "默认排除 · 银行",
    "com.pingan.lifecircle" to "默认排除 · 银行",
)
