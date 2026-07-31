#!/usr/bin/env bash
# AdSkip-VPN 构建入口（镜像 AdSkip-App/build.sh，仅 APP 路径不同）。
#
# 背景：部分 Git Bash 环境下，MSYS 对中文路径与工具 PATH 的处理不稳定
#（mkdir/cp/grep 可能不在 PATH、C:/ 前缀可能被当作相对路径、python 可能解析到 MSYS 自带版本，
# 且 MSYS 自带 python 会把中文 argv 解析成乱码导致 build.py 失败）。
# 因此真正的构建逻辑放在 build.py 中：用 Windows 原生绝对路径 + subprocess 调用各工具，
# 不经过 MSYS 路径转换层，跨环境可复现。
#
# 本脚本仅负责启动 build.py。依次尝试多种可用的 python 调用方式，任一成功即退出。
# 仅依据退出码判断：
#   0     = 成功；
#   126/127= 该 python 无法执行（命令找不到 / 无权限），尝试下一候选；
#   其余非零 = 真实构建失败（立即退出，避免掩盖错误）。不依赖 grep 等可能缺失的命令。
export PYTHONUTF8=1
export LANG=C.UTF-8

APP="E:/root模块/AdSkipVPN"

# 优先使用 workbuddy 自带 python（Windows 原生，正确接收中文 argv，不依赖 MSYS 转换）。
# 注意：C:/ 形式（正斜杠、盘符大写）在本机实测最稳；/c/ 形式作为兼容兜底。
P1="C:/Users/86137/.workbuddy/binaries/python/versions/3.13.12/python.exe"
P2="/c/Users/86137/.workbuddy/binaries/python/versions/3.13.12/python.exe"

run_candidate() {
  local exe="$1"
  "$exe" "$APP/build.py" > /tmp/adskip_vpn_build.log 2>&1
  local rc=$?
  if [ "$rc" -eq 0 ]; then
    return 0
  fi
  # 126/127 表示该候选无法执行（找不到 / 无权限），尝试下一个
  if [ "$rc" -eq 127 ] || [ "$rc" -eq 126 ]; then
    return 2
  fi
  # 其余非零：真实构建失败，立即失败（不掩盖错误）
  return 1
}

for exe in "$P1" "$P2"; do
  if run_candidate "$exe"; then
    echo "build.sh: done via $exe"
    exit 0
  fi
done

# 兜底：PATH 中的 python（MSYS 自带版本可能把中文路径解析为乱码而失败，仅作最后尝试）
for exe in python python3; do
  if run_candidate "$exe"; then
    echo "build.sh: done via $exe"
    exit 0
  fi
done

echo "build.sh: all python candidates failed (see /tmp/adskip_vpn_build.log)" >&2
exit 1
