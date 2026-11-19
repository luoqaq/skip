package com.luo.skip

/**
 * 轻量 Application：零 UI / Compose / 图标初始化。
 * 双进程都会加载本类（主进程 + :service），保证 :service 常驻内存不加载 UI 框架。
 */
class SkipApp : android.app.Application()
