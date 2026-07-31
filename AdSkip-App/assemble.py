#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
将 aapt2 link 产出的 app-base.apk 与 classes.dex / assets 组装成未签名的 APK。

用法:
    python assemble.py <app-base.apk> <classes.dex> <assets-dir> <unsigned.apk>

步骤:
    1. 解压 app-base.apk 到临时目录
    2. 把 classes.dex 放到 APK 根
    3. 把 assets/ 目录（含 index.html）递归拷入 APK 的 assets/
    4. 用 zipfile（ZIP_DEFLATED）重新打包成 unsigned.apk
"""

import os
import sys
import shutil
import zipfile


def copytree(src, dst):
    """递归拷贝目录（src 下所有文件/子目录到 dst）。"""
    if not os.path.exists(dst):
        os.makedirs(dst)
    for item in os.listdir(src):
        s = os.path.join(src, item)
        d = os.path.join(dst, item)
        if os.path.isdir(s):
            copytree(s, d)
        else:
            shutil.copy2(s, d)


def main():
    if len(sys.argv) != 5:
        print("usage: assemble.py <app-base.apk> <classes.dex> <assets-dir> <unsigned.apk>")
        sys.exit(2)

    base_apk = sys.argv[1]
    dex_file = sys.argv[2]
    assets_dir = sys.argv[3]
    out_apk = sys.argv[4]

    tmp_dir = out_apk + ".assemble.tmp"
    if os.path.exists(tmp_dir):
        shutil.rmtree(tmp_dir)
    os.makedirs(tmp_dir)

    # 1) 解压 base
    with zipfile.ZipFile(base_apk, "r") as z:
        z.extractall(tmp_dir)

    # 2) 放入 classes.dex（APK 根）
    shutil.copyfile(dex_file, os.path.join(tmp_dir, "classes.dex"))

    # 3) 放入 assets（递归）
    if os.path.isdir(assets_dir):
        copytree(assets_dir, os.path.join(tmp_dir, "assets"))
    else:
        print("warn: assets dir not found: %s" % assets_dir)

    # 4) 重新打包（DEFLATED）
    with zipfile.ZipFile(out_apk, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _dirs, files in os.walk(tmp_dir):
            for f in files:
                full = os.path.join(root, f)
                rel = os.path.relpath(full, tmp_dir)
                z.write(full, rel)

    shutil.rmtree(tmp_dir)
    print("assembled -> %s" % out_apk)


if __name__ == "__main__":
    main()
