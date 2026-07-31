#!/usr/bin/env bash
# 覆盖率报告 —— **只统计逻辑层**。
#
#   ./scripts/coverage.sh              出报告，与上次基线对比
#   ./scripts/coverage.sh --save       把本次结果存为新基线
#
# 为什么 UI 不进分母：ui/ 有约 7000 行 Compose 屏幕代码，把它算进去，core 层从 80% 掉到 60%
# 时总数字可能只动两个百分点 —— 退化被稀释成噪音，等于白测。UI 的错一眼能看见，
# 逻辑的错是静默的，所以分母只留逻辑层。
# 例外：MainViewModel 虽在 ui/ 包下却是纯逻辑（规则归一化、block/allow 互斥、
# 编辑保留 addedAt），必须计入 —— 它错了没人看得出来。
set -uo pipefail
cd "$(dirname "$0")/.."

SAVE=0
[ "${1:-}" = "--save" ] && SAVE=1

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  for c in "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    [ -x "$c/bin/java" ] && export JAVA_HOME="$c" && break
  done
fi

BASE="scripts/.coverage-baseline.json"
XML="app/build/reports/kover/report.xml"

echo "$(tput setaf 6 2>/dev/null || true)▶ 覆盖率（仅逻辑层）$(tput sgr0 2>/dev/null || true)"
./gradlew :app:koverXmlReport -q > /tmp/hxmy-kover.log 2>&1 || {
  echo "  ✗ Kover 报告生成失败，见 /tmp/hxmy-kover.log"; exit 1; }

python3 - "$XML" "$BASE" "$SAVE" <<'PY'
import sys, os, json, xml.etree.ElementTree as ET
xml_path, base_path, save = sys.argv[1], sys.argv[2], sys.argv[3] == '1'
G, R, Y, RST, B = '\033[32m', '\033[31m', '\033[33m', '\033[0m', '\033[1m'
if not os.path.exists(xml_path):
    print(f"  {R}✗ 找不到 {xml_path}{RST}"); sys.exit(1)

root = ET.parse(xml_path).getroot()
# Kover 输出 JaCoCo 格式：<package name="com/mzstd/hxmyproxy/core/rules"><counter type="LINE" .../>
groups = {}
for pkg in root.iter('package'):
    name = (pkg.get('name') or '').replace('/', '.')
    short = name.replace('com.mzstd.hxmyproxy.', '') or '(root)'
    cov = mis = 0
    for c in pkg.findall('counter'):
        if c.get('type') == 'LINE':
            cov += int(c.get('covered', 0)); mis += int(c.get('missed', 0))
    if cov + mis == 0:
        continue
    groups[short] = (cov, cov + mis)

if not groups:
    print(f"  {Y}报告里没有可统计的包 —— 检查 Kover 的 excludes 是不是把全部代码都排除了{RST}")
    sys.exit(1)

base = {}
if os.path.exists(base_path):
    try: base = json.load(open(base_path)).get('groups', {})
    except Exception: pass

def pct(c, t): return 100.0 * c / t if t else 0.0

tot_c = sum(v[0] for v in groups.values())
tot_t = sum(v[1] for v in groups.values())

print(f"\n  {B}{'模块':<26} {'行覆盖':>8}  {'已覆盖/总行':>14}   {'较基线':>8}{RST}")
print("  " + "─" * 64)
regressions = []
for name in sorted(groups):
    c, t = groups[name]
    p = pct(c, t)
    color = G if p >= 70 else (Y if p >= 40 else R)
    delta = ''
    if name in base:
        d = p - base[name]
        if abs(d) >= 0.05:
            dc = G if d > 0 else R
            delta = f"{dc}{d:+.1f}%{RST}"
            if d <= -1.0: regressions.append((name, d))
        else:
            delta = '  ·'
    else:
        delta = f"{Y}新{RST}"
    print(f"  {name:<26} {color}{p:>7.1f}%{RST}  {c:>6}/{t:<7}   {delta:>8}")

print("  " + "─" * 64)
tp = pct(tot_c, tot_t)
bt = base.get('__total__')
dt = f"  ({tp - bt:+.1f}%)" if bt is not None else ''
print(f"  {B}{'合计':<26} {tp:>7.1f}%  {tot_c:>6}/{tot_t:<7}{dt}{RST}")

if regressions:
    print(f"\n  {R}⚠ 退步的模块：{RST}")
    for n, d in sorted(regressions, key=lambda x: x[1]):
        print(f"     {n}  {d:+.1f}%")
    print("     （改了逻辑但没补测试？还是删了测试？）")

print(f"""
  {Y}⚠ 口径：本数字**只来自 JVM 单元测试**，不含仪器测试。{RST}
     所以下面这些包的数字被**系统性低估**，0% 不等于没测：
       data.repository   —— RuleRepositoryTest / SettingsRepositoryInstrumentedTest
                            / CredentialStoreInstrumentedTest 实际覆盖了它
       core.network      —— UnderlyingNetworkProviderTest / BindSocketSpikeTest / MdnsRegistrationTest
       core.proxy        —— ProxyLoadTest 另有 9 个端到端用例（含 CONNECT 隧道 / SOCKS5 / 50 并发 / 8MB 吞吐）
     它们要 Android 运行时（Context/DataStore/ConnectivityManager/Keystore），JVM 里跑不了。
     跑那部分：./scripts/test.sh --device""")

if save:
    out = {k: pct(*v) for k, v in groups.items()}
    out['__total__'] = tp
    json.dump({'groups': out}, open(base_path, 'w'), indent=2, ensure_ascii=False)
    print(f"\n  {G}✓ 已存为新基线：{base_path}{RST}")
else:
    print(f"\n  （用 ./scripts/coverage.sh --save 把本次存为基线）")
PY
