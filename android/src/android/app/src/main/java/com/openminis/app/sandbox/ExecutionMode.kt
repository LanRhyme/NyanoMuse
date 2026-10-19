package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Execution mode for shell commands and file operations in NyanoMuse.
 *
 * ROOT: Direct root access via `su` on Android host (uid=0).
 * HOST_SHELL: Direct unprivileged access via `/system/bin/sh`.
 * PROOT_SANDBOX: Isolated Alpine Linux container via PRoot.
 */
enum class ExecutionMode(val key: String, val title: String, val desc: String) {
    ROOT("root", "Root 模式", "通过 su 直通 Android 原生系统，具备 uid=0 顶级权限与全盘穿透"),
    HOST_SHELL("host_shell", "宿主普通 Shell", "通过 /system/bin/sh 运行于 Android 原生宿主用户环境"),
    PROOT_SANDBOX("proot", "PRoot 沙箱", "运行于内置 Alpine Linux 隔离容器环境");

    companion object {
        private const val TAG = "ExecutionMode"
        private const val PREFS_NAME = "nyanomuse_execution_prefs"
        private const val KEY_MODE = "execution_mode"

        @Volatile
        private var cachedMode: ExecutionMode? = null

        fun getMode(context: Context): ExecutionMode {
            cachedMode?.let { return it }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedKey = prefs.getString(KEY_MODE, null)
            val mode = if (savedKey != null) {
                entries.find { it.key == savedKey } ?: defaultMode()
            } else {
                defaultMode()
            }
            cachedMode = mode
            return mode
        }

        fun setMode(context: Context, mode: ExecutionMode) {
            cachedMode = mode
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, mode.key)
                .apply()
            Log.i(TAG, "Execution mode set to: ${mode.name}")
        }

        private fun defaultMode(): ExecutionMode {
            return if (isSuAvailable()) ROOT else PROOT_SANDBOX
        }

        fun isSuAvailable(): Boolean {
            val paths = arrayOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/data/adb/ksu/bin/su",
                "/data/adb/magisk/su"
            )
            for (p in paths) {
                if (File(p).exists()) return true
            }
            val pathEnv = System.getenv("PATH") ?: ""
            for (dir in pathEnv.split(":")) {
                if (File(dir, "su").exists()) return true
            }
            return false
        }
    }
}
