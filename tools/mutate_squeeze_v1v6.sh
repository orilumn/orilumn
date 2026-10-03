#!/usr/bin/env bash
# 变异验证：Q29 登记的 V1 / V3 / V4 / V6（2026-10-03）。
# 用法：./tools/mutate_squeeze_v1v6.sh
# 每一条：注入变异 → 跑指定测试类 → 记录红了几把 → 还原。跑完必须 git diff 为空。
set -u
cd "$(dirname "$0")/.."

KOTLIN=engine-skia/src/commonMain/kotlin/orilumn/reader/engine/skia

# 测试类（变异后应当变红的锁）
T_SQUEEZE=orilumn.reader.engine.skia.PunctuationSqueezeLockTest
T_BREAKER=orilumn.reader.engine.skia.InhouseParagraphBreakerTest
T_WIRING=orilumn.reader.engine.skia.CjkLatinSpacingWiringTest

declare -a NAMES=() RESULTS=()

run_mutation() {
  local name="$1" tests="$2" file="$3" from="$4" to="$5"
  python3 - "$file" "$from" "$to" <<'PY'
import sys
p, a, b = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(p, encoding='utf-8').read()
assert s.count(a) == 1, f'锚点不唯一({s.count(a)}): {a[:60]}'
open(p, 'w', encoding='utf-8').write(s.replace(a, b))
PY
  if [ $? -ne 0 ]; then RESULTS+=("$name 注入失败"); git checkout -- "$file"; return; fi
  # 跑**整个模块**而不是指定测试类：变异验证要回答的是「有没有任何一把锁抓到」，
  # 只跑自己那几把会漏掉「别的类恰好兜住了」的情况（那本身就是有价值的信息）。
  ./gradlew :engine-skia:jvmTest :common:jvmTest -q >/dev/null 2>&1
  local out
  out=$(python3 - <<'PY'
import re, glob
red = []
for f in glob.glob('engine-skia/build/test-results/jvmTest/*.xml'):
    t = open(f, encoding='utf-8').read()
    if '<failure' in t or '<error ' in t:
        cls = f.split('TEST-')[-1].split('.xml')[0].split('.')[-1]
        n = len(re.findall(r'<(?:failure|error)[ >]', t))
        red.append(f'{cls}×{n}')
print('、'.join(red) if red else '全绿')
PY
)
  NAMES+=("$name"); RESULTS+=("$out")
  git checkout -- "$file"
}

echo "=== 变异验证开始（Q29 的 V1 / V3 / V4 / V6）==="

# V1：删掉「挤不动就不挤」守卫（ratio >= 1 ⇒ 0）
run_mutation "V1 删 ratio>=1⇒0 守卫" "$T_SQUEEZE,$T_BREAKER" \
  "$KOTLIN/InhouseParagraphBreaker.kt" \
  'return if (ratio >= 1f) 0f else ratio' \
  'return ratio'

# V3：实挤量漏乘 ratio（退回满额挤）
run_mutation "V3 slotSqueeze 漏乘 ratio" "$T_SQUEEZE" \
  "$KOTLIN/PunctuationSqueeze.kt" \
  'fun slotSqueeze(capPx: Float, ratio: Float): Float = if (ratio <= 0f) 0f else capPx * ratio' \
  'fun slotSqueeze(capPx: Float, ratio: Float): Float = capPx'

# V4：绘制侧忽略传入的 squeezeRatio（相当于漏传，默认 0）
run_mutation "V4 LineAligner 忽略 squeezeRatio" "$T_SQUEEZE,$T_WIRING" \
  "$KOTLIN/LineAligner.kt" \
  'if (squeeze[k] != 0f) adv[k] -= PunctuationSqueeze.slotSqueeze(squeeze[k], squeezeRatio)' \
  'if (squeeze[k] != 0f) adv[k] -= PunctuationSqueeze.slotSqueeze(squeeze[k], 0f)'

# V6：断行侧链路断点 —— BrokenLine.squeezeRatio 传 0
run_mutation "V6 DrawLine.squeezeRatio 传 0" "$T_SQUEEZE,$T_WIRING" \
  "$KOTLIN/LineWindowDrawer.kt" \
  'squeezeRatio = line.squeezeRatio,' \
  'squeezeRatio = 0f,'

echo
for i in "${!NAMES[@]}"; do
  printf '%-34s %s\n' "${NAMES[$i]}" "${RESULTS[$i]}"
done
echo
echo "=== 还原检查（必须为空）==="
git status --porcelain