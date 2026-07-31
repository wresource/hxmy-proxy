#!/usr/bin/env bash
# hxmy proxy 测试入口 —— 自己跑，自己出结果。
#
#   ./scripts/test.sh              只跑 JVM 单测（秒级，改完代码随手跑）
#   ./scripts/test.sh --device     再加上仪器测试（需要模拟器/真机，分钟级）
#   ./scripts/test.sh --coverage   带覆盖率报告（只统计逻辑层，见 coverage.sh）
#   ./scripts/test.sh --device --coverage
#
# 失败时直接把异常首行打出来，不用再去翻 build 目录里的 XML。
set -uo pipefail
cd "$(dirname "$0")/.."

RUN_DEVICE=0; RUN_COV=0
for a in "$@"; do
  case "$a" in
    --device|--all) RUN_DEVICE=1 ;;
    --coverage|--cov) RUN_COV=1 ;;
    -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $a（用 --help 看用法）"; exit 2 ;;
  esac
done

# Android Studio 自带 JBR：用户没配 JAVA_HOME 时也能跑。
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  for c in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
           "$HOME/Library/Java/JavaVirtualMachines/jbr/Contents/Home"; do
    [ -x "$c/bin/java" ] && export JAVA_HOME="$c" && break
  done
fi
[ -x "${JAVA_HOME:-/nonexistent}/bin/java" ] || { echo "✗ 找不到 JDK，请设 JAVA_HOME"; exit 1; }
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"

B=$(tput bold 2>/dev/null || true); D=$(tput sgr0 2>/dev/null || true)
R=$(tput setaf 1 2>/dev/null || true); G=$(tput setaf 2 2>/dev/null || true)
Y=$(tput setaf 3 2>/dev/null || true); C=$(tput setaf 6 2>/dev/null || true)

echo "${B}━━━ hxmy proxy 测试 ━━━${D}"

# 解析 JUnit XML 目录，打印统计与失败详情。$1=报告目录 $2=标题
summarize() {
  python3 - "$1" "$2" <<'PY'
import sys, os, glob, xml.etree.ElementTree as ET
GRN, RED, YEL, RST = '\033[32m', '\033[31m', '\033[33m', '\033[0m'
d, title = sys.argv[1], sys.argv[2]
files = glob.glob(os.path.join(d, '**', '*.xml'), recursive=True)
if not files:
    # 没有报告 ≠ 测试都过了：多半是编译失败或任务没跑起来，必须当失败处理。
    print(f"  {RED}✗ 没有找到测试报告（{d}）——测试很可能压根没跑起来，看日志{RST}")
    sys.exit(1)
ok = fail = skip = 0; secs = 0.0; bad = []; skipped = []
for f in files:
    try: root = ET.parse(f).getroot()
    except Exception: continue
    for tc in root.iter('testcase'):
        cls = tc.get('classname', '').split('.')[-1]; name = tc.get('name', '')
        try: secs += float(tc.get('time') or 0)
        except ValueError: pass
        node = tc.find('failure')
        if node is None: node = tc.find('error')
        sk = tc.find('skipped')
        if node is not None:
            fail += 1
            msg = (node.get('message') or (node.text or '')).strip().splitlines()
            bad.append((cls, name, msg[0][:200] if msg else '(无消息)'))
        elif sk is not None:
            skip += 1
            m = (sk.get('message') or '').strip().splitlines()
            skipped.append((cls, name, m[0][:160] if m else ''))
        else:
            ok += 1
fc = RED if fail else ''
sc = YEL if skip else ''
print(f"  {GRN}✅ {ok} 通过{RST}   {fc}❌ {fail} 失败{RST}   {sc}⏭  {skip} 跳过{RST}      {secs:.1f}s")
for cls, name, msg in skipped:
    print(f"  {YEL}⏭  {cls}.{name}{RST}")
    if msg: print(f"       {msg}")
for cls, name, msg in bad:
    print(f"  {RED}❌ {cls}.{name}{RST}")
    print(f"       {msg}")
sys.exit(1 if fail else 0)
PY
}

FAILED=0

echo
echo "${C}▶ JVM 单元测试${D}"
./gradlew :app:testDebugUnitTest --continue -q > /tmp/hxmy-unit.log 2>&1 || true
summarize "app/build/test-results/testDebugUnitTest" "unit" || FAILED=1

if [ "$RUN_DEVICE" = "1" ]; then
  DEV=$(adb devices | awk '/\tdevice$/{print $1}' | head -1)
  echo
  if [ -z "$DEV" ]; then
    echo "${Y}▶ 仪器测试 —— 跳过：没有连接的设备${D}"
    echo "  （启动模拟器：\$ANDROID_HOME/emulator/emulator -avd <name> &）"
  else
    echo "${C}▶ 仪器测试 (${DEV})${D}"
    export ANDROID_SERIAL="$DEV"
    # timeout_msec：单个测试的硬超时。没有它，一个挂死的测试会让整轮永远不返回
    # （曾发生：MainUiTest 因启动屏死锁挂了 7 分钟才被人工发现）。
    rm -rf app/build/outputs/androidTest-results/connected 2>/dev/null
    ./gradlew :app:connectedDebugAndroidTest --continue \
      -Pandroid.testInstrumentationRunnerArguments.timeout_msec=90000 \
      > /tmp/hxmy-device.log 2>&1 || true
    summarize "app/build/outputs/androidTest-results/connected" "device" || FAILED=1
  fi
fi

if [ "$RUN_COV" = "1" ]; then
  echo
  bash "$(dirname "$0")/coverage.sh" || FAILED=1
fi

echo
if [ "$FAILED" = "0" ]; then
  echo "${B}${G}━━━ 结论：全部通过 ━━━${D}"
else
  echo "${B}${R}━━━ 结论：有失败，见上 ━━━${D}"
  echo "完整日志：/tmp/hxmy-unit.log  /tmp/hxmy-device.log"
fi
exit $FAILED
