#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AdSkip-App 构建脚本（纯 Python 编排，规避 MSYS 中文路径 / 工具 PATH 不稳定问题）。

直接用 Windows 原生绝对路径 + subprocess 调用各构建工具（aapt2 / javac / d8 /
python / zipalign / keytool / apksigner），不经过 MSYS 路径转换层，跨环境可复现。

用法:
    python build.py
"""

import os
import sys
import shutil
import subprocess
import zipfile

SDK_BT = r"C:/Users/86137/AppData/Local/Android/Sdk/build-tools/34.0.0"
SDK_PLAT = r"C:/Users/86137/AppData/Local/Android/Sdk/platforms/android-34"
JAVA = r"C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin"
APP = r"E:/root模块/AdSkip-App"
OUT = r"E:/root模块/AdSkip-KSU/app/AdSkipManager.apk"
WORK = r"C:/Users/86137/AppData/Local/Temp/adskip_build"


def run(cmd, check=True):
    """执行命令；失败则非零退出。"""
    print(">> " + " ".join(cmd))
    r = subprocess.run(cmd)
    if check and r.returncode != 0:
        print("BUILD FAILED (exit %d): %s" % (r.returncode, " ".join(cmd)))
        sys.exit(r.returncode)
    return r


def main():
    # 清理并准备 ASCII 临时构建目录
    if os.path.isdir(WORK):
        shutil.rmtree(WORK)
    os.makedirs(WORK + "/build/obj", exist_ok=True)
    os.makedirs(WORK + "/build/assets", exist_ok=True)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)

    # 拷贝源码/资源/清单到 ASCII 临时目录（python 原生处理 Unicode 路径）
    shutil.copytree(APP + "/res", WORK + "/res")
    shutil.copytree(APP + "/src", WORK + "/src")
    shutil.copytree(APP + "/assets", WORK + "/assets")
    shutil.copy(APP + "/AndroidManifest.xml", WORK + "/AndroidManifest.xml")
    shutil.copy(APP + "/assemble.py", WORK + "/assemble.py")

    # [1/8] aapt2 compile resources
    print("[1/8] aapt2 compile resources")
    run([SDK_BT + "/aapt2.exe", "compile", "-o", WORK + "/build/res.flata",
         "--dir", WORK + "/res"])

    # [2/8] aapt2 link (manifest + res)
    print("[2/8] aapt2 link (manifest + res)")
    run([SDK_BT + "/aapt2.exe", "link", "-o", WORK + "/build/app-base.apk",
         "-I", SDK_PLAT + "/android.jar",
         "--manifest", WORK + "/AndroidManifest.xml",
         "-R", WORK + "/build/res.flata", "--auto-add-overlay"])

    # [3/8] javac
    print("[3/8] javac")
    srcs = []
    for root, _dirs, files in os.walk(WORK + "/src"):
        for f in files:
            if f.endswith(".java"):
                srcs.append(os.path.join(root, f))
    run([JAVA + "/javac.exe", "-encoding", "UTF-8",
         "-cp", SDK_PLAT + "/android.jar", "-d", WORK + "/build/obj"] + srcs)

    # [4/8] d8 -> classes.dex（输出到目录）
    print("[4/8] d8 -> classes.dex")
    classes = []
    for root, _dirs, files in os.walk(WORK + "/build/obj"):
        for f in files:
            if f.endswith(".class"):
                classes.append(os.path.join(root, f))
    os.makedirs(WORK + "/build/dex", exist_ok=True)
    run([JAVA + "/java.exe", "-cp", SDK_BT + "/lib/d8.jar",
         "com.android.tools.r8.D8", "--release",
         "--output", WORK + "/build/dex"] + classes)

    # [5/8] assemble (python: add classes.dex + assets)
    print("[5/8] assemble (python: add classes.dex + assets)")
    run([sys.executable, WORK + "/assemble.py",
         WORK + "/build/app-base.apk",
         WORK + "/build/dex/classes.dex",
         WORK + "/assets",
         WORK + "/build/unsigned.apk"])

    # [6/8] zipalign
    print("[6/8] zipalign")
    run([SDK_BT + "/zipalign.exe", "-p", "4",
         WORK + "/build/unsigned.apk", WORK + "/build/aligned.apk"])

    # [7/8] keystore (one-time, reused if present)
    print("[7/8] keystore (one-time, reused if present)")
    ks = WORK + "/debug.keystore"
    if os.path.isfile(APP + "/debug.keystore"):
        shutil.copy(APP + "/debug.keystore", ks)
    if not os.path.isfile(ks):
        run([JAVA + "/keytool.exe", "-genkeypair", "-v",
             "-keystore", ks, "-alias", "adskip",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-storepass", "android", "-keypass", "android",
             "-dname", "CN=AdSkip,O=AdSkip"])
        shutil.copy(ks, APP + "/debug.keystore")

    # [8/8] apksigner sign
    print("[8/8] apksigner sign -> " + OUT)
    run([JAVA + "/java.exe", "-jar", SDK_BT + "/lib/apksigner.jar", "sign",
         "--ks", ks, "--ks-key-alias", "adskip",
         "--ks-pass", "pass:android", "--key-pass", "pass:android",
         "--out", WORK + "/AdSkipManager.apk", WORK + "/build/aligned.apk"])

    # 复制最终 APK 到中文目标路径
    shutil.copy(WORK + "/AdSkipManager.apk", OUT)

    # self-check
    print("=== self-check: aapt2 dump badging ===")
    run([SDK_BT + "/aapt2.exe", "dump", "badging", WORK + "/AdSkipManager.apk"])
    print("=== self-check: apksigner verify ===")
    run([JAVA + "/java.exe", "-jar", SDK_BT + "/lib/apksigner.jar",
         "verify", WORK + "/AdSkipManager.apk"])

    # 清理临时目录
    shutil.rmtree(WORK)
    print("BUILD OK -> " + OUT)


if __name__ == "__main__":
    main()
