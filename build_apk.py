# -*- coding: utf-8 -*-
"""
build_apk.py —— 不用 Gradle，手工构建签名 APK
流程: aapt2 compile -> aapt2 link -> javac -> d8 -> 打包 dex -> zipalign -> apksigner

⚠️ aapt2 不支持中文路径，所以先在纯英文临时目录里构建，最后把 APK 拷回来。
"""
import os
import io
import shutil
import subprocess
import sys
import tempfile
import zipfile

# ★ Windows 控制台默认 GBK，aapt2/工具链偶尔吐出场外字符（U+FFFD）会把 print 打死。
#   统一走 UTF-8，errors=replace，绝不让编码问题毁掉一次成功的构建。
try:
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.abspath(__file__))
SDK = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
BT = os.path.join(SDK, "build-tools", "36.0.0")
ANDROID_JAR = os.path.join(SDK, "platforms", "android-37.0", "android.jar")

AAPT2 = os.path.join(BT, "aapt2.exe")
D8 = os.path.join(BT, "d8.bat")
ZIPALIGN = os.path.join(BT, "zipalign.exe")
APKSIGNER = os.path.join(BT, "apksigner.bat")

# 全部构建都在这个纯英文目录里进行（中文路径会让 aapt2 崩）
BUILD = os.path.join(tempfile.gettempdir(), "ledao_apk_build")
APP = os.path.join(BUILD, "app")
SRC_APP = os.path.join(ROOT, "app")
OUT = os.path.join(ROOT, "LedaoTester.apk")
# ★★★ 签名密钥必须放在**项目目录**里，不能放 %TEMP%。
#   踩过的坑（2026-10-04）：%TEMP% 被系统清理后 keytool 又生成了一把新密钥，
#   于是 `adb install -r` 报 INSTALL_FAILED_UPDATE_INCOMPATIBLE ——
#   签名对不上，只能卸载重装，手机里存的身份/设置全丢。
KEYS_DIR = os.path.join(ROOT, "work", "keys")
KS = os.path.join(KEYS_DIR, "ledao.keystore")
KS_LEGACY = os.path.join(BUILD, "ledao.keystore")
KS_PASS = "ledao123"


def run(cmd, desc, check=True):
    print("\n>>> %s" % desc)
    print("    " + " ".join('"%s"' % c if " " in str(c) else str(c) for c in cmd))
    r = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
    out = (r.stdout or "") + (r.stderr or "")
    if out.strip():
        for ln in out.strip().split("\n")[:25]:
            print("    | " + ln)
    if check and r.returncode != 0:
        print("!! 失败 (exit %d)" % r.returncode)
        sys.exit(r.returncode)
    return r


def main():
    print("=" * 70)
    print("构建 宇达步道乐跑助手 v1.0.23(测试版) APK")
    print("=" * 70)
    print("源目录 : %s" % SRC_APP)
    print("构建目录: %s   (纯英文，规避 aapt2 中文路径问题)" % BUILD)
    print("android.jar: %s" % ANDROID_JAR)
    if not os.path.exists(ANDROID_JAR):
        print("!! 找不到 android.jar")
        sys.exit(1)

    # ---- 0) 把源码镜像到纯英文临时目录
    if os.path.isdir(BUILD):
        # 只清子目录，保留已生成的 keystore
        for name in ("app", "compiled", "gen", "classes", "dex",
                     "app-unaligned.apk", "app-withdex.apk", "app-aligned.apk"):
            p = os.path.join(BUILD, name)
            if os.path.isdir(p):
                shutil.rmtree(p, ignore_errors=True)
            elif os.path.exists(p):
                os.remove(p)
    os.makedirs(BUILD, exist_ok=True)
    shutil.copytree(SRC_APP, APP)
    for d in ("compiled", "gen", "classes", "dex"):
        os.makedirs(os.path.join(BUILD, d), exist_ok=True)
    print("\n>>> 源码已镜像到构建目录")

    # ---- 1) 编译资源
    res = os.path.join(APP, "res")
    compiled = os.path.join(BUILD, "compiled")
    if os.path.isdir(res):
        run([AAPT2, "compile", "--dir", res, "-o", compiled + os.sep],
            "aapt2 compile 资源")

    # ---- 2) 链接资源，生成 未签名 APK + R.java
    unaligned = os.path.join(BUILD, "app-unaligned.apk")
    flat = [os.path.join(compiled, f) for f in os.listdir(compiled)] \
        if os.path.isdir(compiled) else []
    cmd = [AAPT2, "link", "-o", unaligned,
           "-I", ANDROID_JAR,
           "--manifest", os.path.join(APP, "AndroidManifest.xml"),
           "--java", os.path.join(BUILD, "gen"),
           "--min-sdk-version", "21",
           "--target-sdk-version", "28",
           "--version-code", "123", "--version-name", "1.0.23(测试版)"]
    # ★ 必须显式指定 assets 目录，否则证书等资源不会被打进 APK
    assets = os.path.join(APP, "assets")
    if os.path.isdir(assets):
        cmd += ["-A", assets]
        print("\n    assets 目录: %s (%d 个文件)"
              % (assets, len(os.listdir(assets))))
    cmd += flat
    run(cmd, "aapt2 link（生成资源 APK）")

    # ---- 3) 编译 Java
    srcs = []
    for base, _d, fs in os.walk(os.path.join(APP, "src")):
        srcs += [os.path.join(base, f) for f in fs if f.endswith(".java")]
    gen = os.path.join(BUILD, "gen")
    for base, _d, fs in os.walk(gen):
        srcs += [os.path.join(base, f) for f in fs if f.endswith(".java")]
    classes = os.path.join(BUILD, "classes")
    print("\n源文件 %d 个" % len(srcs))
    run(["javac", "--release", "11", "-encoding", "UTF-8", "-nowarn",
         "-cp", ANDROID_JAR, "-d", classes] + srcs, "javac 编译")

    # ---- 4) d8 转 dex
    dexdir = os.path.join(BUILD, "dex")
    classfiles = []
    for base, _d, fs in os.walk(classes):
        classfiles += [os.path.join(base, f) for f in fs if f.endswith(".class")]
    run([D8, "--release", "--min-api", "21", "--lib", ANDROID_JAR,
         "--output", dexdir] + classfiles, "d8 生成 classes.dex")

    # ---- 5) 把 dex 塞进 APK
    aligned_in = os.path.join(BUILD, "app-withdex.apk")
    dexfile = os.path.join(dexdir, "classes.dex")
    if not os.path.exists(dexfile):
        print("!! 没生成 classes.dex")
        sys.exit(1)
    print("\n>>> 打包 classes.dex 进 APK")
    zin = zipfile.ZipFile(unaligned, "r")
    zout = zipfile.ZipFile(aligned_in, "w", zipfile.ZIP_DEFLATED)
    for item in zin.infolist():
        if item.filename == "classes.dex":
            continue
        zout.writestr(item, zin.read(item.filename))
    zout.write(dexfile, "classes.dex")
    zout.close()
    zin.close()
    print("    classes.dex %.1f KB" % (os.path.getsize(dexfile) / 1024.0))

    # ---- 6) zipalign
    aligned = os.path.join(BUILD, "app-aligned.apk")
    run([ZIPALIGN, "-f", "-p", "4", aligned_in, aligned], "zipalign 对齐")

    # ---- 7) 生成签名密钥（**放在项目里**，见上面 KEYS_DIR 的说明）
    os.makedirs(KEYS_DIR, exist_ok=True)
    if not os.path.exists(KS) and os.path.exists(KS_LEGACY):
        # 老版本把密钥放在 %TEMP%：第一次跑新版时搬过来，别又生成一把新的
        shutil.copy2(KS_LEGACY, KS)
        print("\n>>> 已把 %TEMP% 里的旧密钥迁到 %s" % KS)
    if not os.path.exists(KS):
        run(["keytool", "-genkeypair", "-v", "-keystore", KS,
             "-alias", "ledao", "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "10000", "-storepass", KS_PASS, "-keypass", KS_PASS,
             "-dname", "CN=Ledao Tester, OU=Course, O=Lab, L=Handan, ST=Hebei, C=CN"],
            "生成签名密钥")
    print("\n签名密钥: %s" % KS)

    # ---- 8) 签名（输出到项目目录）
    run([APKSIGNER, "sign", "--ks", KS, "--ks-key-alias", "ledao",
         "--ks-pass", "pass:" + KS_PASS, "--key-pass", "pass:" + KS_PASS,
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--out", OUT, aligned], "apksigner 签名")

    # ---- 9) 校验
    run([APKSIGNER, "verify", "--print-certs", OUT], "签名校验", check=False)
    run([AAPT2, "dump", "badging", OUT], "APK 信息", check=False)

    print("\n" + "=" * 70)
    print("[OK] 构建完成: %s  (%.1f MB)" % (OUT, os.path.getsize(OUT) / 1024 / 1024))
    print("=" * 70)


if __name__ == "__main__":
    main()

