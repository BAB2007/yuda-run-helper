# -*- coding: utf-8 -*-
"""build_apk.py —— 不用 Gradle，手工构建签名 APK（**各个 App 共用这一份**）

流程: aapt2 compile -> aapt2 link -> javac -> d8 -> 打包 dex -> zipalign -> apksigner

⚠️ aapt2 不支持中文路径，所以先在纯英文临时目录里构建，最后把 APK 拷回来。

  这一个脚本负责 APPS 表里的每个 App：
      app/   → 宇达步道乐跑助手          （v1.0.34，原软件，源码一个字节没动）
  版本号**只认各自的 AndroidManifest.xml**（versionCode/versionName），
  这样"改版本"永远只改一个地方，publish.py / _rebuild_repos.py 都从这里读。

用法：
    python build_apk.py            # 全部构建
    python build_apk.py app        # 只构建原版
"""
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

# ★ Windows 控制台默认 GBK，aapt2/工具链偶尔吐出场外字符（U+FFFD）会把 print 打死。
#   统一走 UTF-8，errors=replace，绝不让编码问题毁掉一次成功的构建。
#   ⚠ 放在 main() 里而不是模块级：发布脚本要 `import build_apk` 读上面那张 APPS 表，
#     模块级换掉 sys.stdout 会连累调用方（2026-10-12 改成现在这样）。
def utf8_stdout():
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

APPS = [
    {
        "key": "app",                       # @app:app
        "dir": "app",                       # 源码目录
        "out": "LedaoTester.apk",           # 构建产物（工作区根目录）
        "build": "ledao_apk_build",         # %TEMP% 下的纯英文构建目录
        "name": "宇达步道乐跑助手",           # 仓库里那个目录名 / APK 名的前缀
    },
]

# ★★★ 签名密钥必须放在**项目目录**里，不能放 %TEMP%。
#   踩过的坑（2026-10-04）：%TEMP% 被系统清理后 keytool 又生成了一把新密钥，
#   于是 `adb install -r` 报 INSTALL_FAILED_UPDATE_INCOMPATIBLE ——
#   签名对不上，只能卸载重装，手机里存的身份/设置全丢。
#   （两个 App 用同一把密钥：包名不同，本来就不冲突，将来也好一起维护。）
KEYS_DIR = os.path.join(ROOT, "work", "keys")
KS = os.path.join(KEYS_DIR, "ledao.keystore")
KS_PASS = "ledao123"


def manifest_info(app_dir):
    """从 AndroidManifest.xml 里读 versionCode / versionName —— 版本的唯一出处。"""
    p = os.path.join(ROOT, app_dir, "AndroidManifest.xml")
    t = io.open(p, encoding="utf-8").read()
    code = re.search(r'android:versionCode="([^"]+)"', t)
    name = re.search(r'android:versionName="([^"]+)"', t)
    pkg = re.search(r'package="([^"]+)"', t)
    if not (code and name and pkg):
        print("!! %s 里读不到 package/versionCode/versionName" % p)
        sys.exit(1)
    return code.group(1), name.group(1), pkg.group(1)


def specs():
    """给发布脚本用：每个 App 的 (目录名, 版本, APK 文件名, 构建产物路径)

    ★ 2026-10-16：**目录不在就跳过**。build_apk.py 是公开库/私有库共用的同一份
      白名单文件，公开库里只有 app/ —— 不跳过的话，`python build_apk.py` 会因为
      找不到那些目录直接退出，下载源码的人第一步就卡住（dry-run 时真踩到）。
      哪个条目进哪个库，由 publish.py 按每条的 `# @app:<key>` 标记删（见 APPS 表）。

    ★ 2026-10-17：条目上写 `"publish": False` 的，**绝不进这份清单**。
      这是"哪些文件会进仓库"的第一道闸；publish.py 的 REPOS 表是第二道闸。
    """
    out = []
    for a in APPS:
        # ★ 先过滤"绝不发布"的条目，再判目录存在与否
        if not a.get("publish", True):
            continue
        if not os.path.isdir(os.path.join(ROOT, a["dir"])):
            continue
        code, ver, pkg = manifest_info(a["dir"])
        tag = "v" + re.match(r"[\d.]+", ver).group(0)
        out.append({
            "key": a["key"], "name": a["name"], "version": ver, "code": code,
            "pkg": pkg, "apk": "%s-%s.apk" % (a["name"], tag),
            "repo_dir": a.get("repo_dir", a["name"]),
            "out": os.path.join(ROOT, a["out"]),
        })
    return out


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


def build_one(a):
    code, ver, pkg = manifest_info(a["dir"])
    min_sdk = str(a.get("min_sdk", 21))
    src_app = os.path.join(ROOT, a["dir"])
    build = os.path.join(tempfile.gettempdir(), a["build"])
    app = os.path.join(build, "app")
    out = os.path.join(ROOT, a["out"])

    print("\n" + "=" * 70)
    print("构建 %s v%s（%s，versionCode %s）" % (a["name"], ver, pkg, code))
    print("=" * 70)
    print("源目录 : %s" % src_app)
    print("构建目录: %s   (纯英文，规避 aapt2 中文路径问题)" % build)
    print("android.jar: %s" % ANDROID_JAR)
    if not os.path.exists(ANDROID_JAR):
        print("!! 找不到 android.jar")
        sys.exit(1)

    # ---- 0) 把源码镜像到纯英文临时目录
    if os.path.isdir(build):
        # 只清子目录，保留已生成的 keystore（万一还躺在旧位置）
        for name in ("app", "compiled", "gen", "classes", "dex",
                     "app-unaligned.apk", "app-withdex.apk", "app-aligned.apk"):
            p = os.path.join(build, name)
            if os.path.isdir(p):
                shutil.rmtree(p, ignore_errors=True)
            elif os.path.exists(p):
                os.remove(p)
    os.makedirs(build, exist_ok=True)
    shutil.copytree(src_app, app)
    for d in ("compiled", "gen", "classes", "dex"):
        os.makedirs(os.path.join(build, d), exist_ok=True)
    print("\n>>> 源码已镜像到构建目录")

    # ---- 1) 编译资源
    res = os.path.join(app, "res")
    compiled = os.path.join(build, "compiled")
    if os.path.isdir(res):
        run([AAPT2, "compile", "--dir", res, "-o", compiled + os.sep],
            "aapt2 compile 资源")

    # ---- 2) 链接资源，生成 未签名 APK + R.java
    unaligned = os.path.join(build, "app-unaligned.apk")
    flat = [os.path.join(compiled, f) for f in os.listdir(compiled)] \
        if os.path.isdir(compiled) else []
    cmd = [AAPT2, "link", "-o", unaligned,
           "-I", ANDROID_JAR,
           "--manifest", os.path.join(app, "AndroidManifest.xml"),
           "--java", os.path.join(build, "gen"),
           "--min-sdk-version", min_sdk,
           "--target-sdk-version", "28",
           "--version-code", code, "--version-name", ver]
    # ★ 必须显式指定 assets 目录，否则证书等资源不会被打进 APK
    assets = os.path.join(app, "assets")
    if os.path.isdir(assets):
        cmd += ["-A", assets]
        print("\n    assets 目录: %s (%d 个文件)"
              % (assets, len(os.listdir(assets))))
    cmd += flat
    run(cmd, "aapt2 link（生成资源 APK）")

    # ---- 3) 编译 Java
    srcs = []
    for base, _d, fs in os.walk(os.path.join(app, "src")):
        srcs += [os.path.join(base, f) for f in fs if f.endswith(".java")]
    gen = os.path.join(build, "gen")
    for base, _d, fs in os.walk(gen):
        srcs += [os.path.join(base, f) for f in fs if f.endswith(".java")]
    classes = os.path.join(build, "classes")
    print("\n源文件 %d 个" % len(srcs))
    run(["javac", "--release", "11", "-encoding", "UTF-8", "-nowarn",
         "-cp", ANDROID_JAR, "-d", classes] + srcs, "javac 编译")

    # ---- 4) d8 转 dex
    dexdir = os.path.join(build, "dex")
    classfiles = []
    for base, _d, fs in os.walk(classes):
        classfiles += [os.path.join(base, f) for f in fs if f.endswith(".class")]
    classes_jar = os.path.join(build, "classes.jar")
    if os.path.exists(classes_jar):
        os.remove(classes_jar)
    with zipfile.ZipFile(classes_jar, "w", zipfile.ZIP_DEFLATED) as zj:
        for cf in classfiles:
            zj.write(cf, os.path.relpath(cf, classes).replace(os.sep, "/"))
    print("\nclass %d 个 → classes.jar（%.1f KB）"
          % (len(classfiles), os.path.getsize(classes_jar) / 1024.0))

    dex_cmd = [D8, "--release", "--min-api", min_sdk, "--lib", ANDROID_JAR,
               "--output", dexdir, classes_jar]
    dex_what = "d8 生成 classes.dex"
    run(dex_cmd, dex_what)

    # ---- 5) 把 dex（+ 原生库）塞进 APK
    aligned_in = os.path.join(build, "app-withdex.apk")
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
    aligned = os.path.join(build, "app-aligned.apk")
    run([ZIPALIGN, "-f", "-p", "4", aligned_in, aligned], "zipalign 对齐")

    # ---- 7) 生成签名密钥（**放在项目里**，见上面 KEYS_DIR 的说明）
    os.makedirs(KEYS_DIR, exist_ok=True)
    ks_use = a.get("keystore", KS)
    ks_pass_use = a.get("ks_pass", KS_PASS)
    ks_alias = "ledaoh" if a.get("keystore") else "ledao"
    if not os.path.exists(ks_use):
        run(["keytool", "-genkeypair", "-v", "-keystore", ks_use,
             "-alias", ks_alias, "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "10000", "-storepass", ks_pass_use,
             "-keypass", ks_pass_use,
             "-dname", "CN=%s, OU=Course, O=Lab, L=Handan, ST=Hebei, C=CN"
                       % ("Ledao Hardened" if a.get("keystore") else "Ledao Tester")],
            "生成签名密钥")
    print("\n签名密钥: %s（alias=%s）" % (ks_use, ks_alias))

    # ---- 8) 签名（输出到项目目录）
    run([APKSIGNER, "sign", "--ks", ks_use, "--ks-key-alias", ks_alias,
         "--ks-pass", "pass:" + ks_pass_use, "--key-pass", "pass:" + ks_pass_use,
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--out", out, aligned], "apksigner 签名")

    # ---- 9) 校验
    run([APKSIGNER, "verify", "--print-certs", out], "签名校验", check=False)
    run([AAPT2, "dump", "badging", out], "APK 信息", check=False)

    print("\n" + "=" * 70)
    print("[OK] %s v%s → %s  (%.2f MB)"
          % (a["name"], ver, out, os.path.getsize(out) / 1024 / 1024))
    print("=" * 70)


def main():
    utf8_stdout()
    want = [x for x in sys.argv[1:] if not x.startswith("-")]
    todo = [a for a in APPS if not want or a["key"] in want or a["dir"] in want]
    if not todo:
        print("!! 不认识的参数：%s（可用：%s）"
              % (" ".join(want), " / ".join(a["key"] for a in APPS)))
        sys.exit(1)
    # ★ 目录不在就跳过并说清楚（公开库只有 app/，见 specs() 的注释）
    keep = []
    for a in todo:
        if os.path.isdir(os.path.join(ROOT, a["dir"])):
            keep.append(a)
        else:
            print("（跳过 %s：这个仓库里没有 %s/ —— 公开库里只有原版）"
                  % (a["name"], a["dir"]))
    if not keep:
        print("!! 一个能构建的 App 都没有（是不是在公开库里跑？那里只有 app/）")
        sys.exit(1)
    for a in keep:
        build_one(a)
    print("\n全部完成。")


if __name__ == "__main__":
    main()
