#!/usr/bin/env python3
"""R28 采样器：单装机、运行期切变体、交叉采样，出可比结论。

## 为什么不用旧办法（旧办法错在哪）

旧采样是"装 A 版跑 N 次 → 装 B 版跑 N 次"。三个缺陷，本轮都实测撞到过：

1. **不交叉**：慢时段整段落在一侧。R31 同锚点、同 `tableHit` 下并存
   983/961/949 与 3177ms —— 3.3 倍离群，顺序采样无法摊到两侧。
2. **锚点漂移**：采样途中翻页会改写续读位置。R31 锚点走过
   `ch4@3242 → ch3@2075 → ch7@12982 → ch7@12091`，跨锚点不可比。
   我曾把 `ch7@12091` 的 719ms 与 `ch7@12982` 的 964ms 放一起比过。
3. **无法确认变体生效**：intent extra 解析失败会静默忽略（见 AbSwitch.apply），
   所以每跑必须回读日志里的 `ab=` 自报，而不是相信命令。

本脚本用 `adb shell am start --es ab ...` 交替变体，**一次装机**完成 A/B。

## 用法

    python3 tools/ab_probe.py --n 9
    python3 tools/ab_probe.py --n 9 --spec-a 'warm=0' --spec-b 'warm=3'
    python3 tools/ab_probe.py --report          # 只解析已落盘日志，不跑

变体规格见 `AbSwitch.apply` 的 KDoc。当前只有 `warm=N`（锚页预热块数，
0 = 生产行为；历史实测 `warm=3` 端到端净 +50ms，是本采样器的**已知答案**校验项）。

## 判读规则（重要）

- 脚本**拒绝**在锚点不一致或变体未生效时下结论，直接非零退出。
- `ctl=` 是控制量探针，只当**离群标记**用，不做归一化除法
  （纯整数负载与塑形/IO/GC 不是同一资源，比例关系不保证线性）。
- 报告给中位数与 MAD（median absolute deviation），不给均值——
  3 倍离群会把均值拖走，而均值正是上一轮判错的原因之一。
- `openT` 是端到端墙钟，含 parse/IO/GC/后台线程。真排版成本看
  `sStyles`+`sSkia`，两者分开列，不合并。
"""

import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import time

DEV = os.environ.get("AB_DEV", "172.16.0.203:5555")
PKG = "orilumn.reader"
ACT = "orilumn.reader/.MainActivity"
OUT = "/tmp/ab/probe.jsonl"

LAUNCH = re.compile(
    r"jump: (?P<where>\w+) -> ch=(?P<ch>\d+) path=(?P<path>\S+) "
    r"tableHit=(?P<hit>\w+) temp=(?P<temp>\w+)"
    r"(?: openT=(?P<openT>\d+)ms anchor=(?P<anchor>\S+)"
    r" ab=(?P<ab>\S+) ctl=(?P<ctl>[\d.]+)ms)?"
)


def adb(*a, timeout=180):
    r = subprocess.run(["adb", "-s", DEV] + list(a), capture_output=True,
                       text=True, timeout=timeout)
    if r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(a)} -> {r.stderr.strip() or r.returncode}")
    return r.stdout


def log_files():
    """全部落盘日志，按 mtime 新→旧。**轮转会产生 `.1.txt` 等新文件**，
    只取"最新那一个"会在跨轮转时读到旧文件——R28 首次长跑就因此 14 跑全部
    "无新落位行"（恰好在轮转点上，新行进了 .1.txt 而脚本只盯着主文件）。"""
    out = adb("shell", "run-as", PKG, "ls", "-t", "files/logs")
    return [x.split()[-1] for x in out.splitlines()
            if x.split() and x.split()[-1].endswith(".txt")]


_DATED = re.compile(r"日志_(\d{4})(\d{2})(\d{2})")


def _day(name):
    """从日志文件名取日期；轮转副本（`.1.txt`）沿用主文件日期。"""
    m = _DATED.search(name)
    if not m:
        return 0
    y, mo, d = map(int, m.groups())
    return y * 10000 + mo * 100 + d


def read_all_logs(tag_day=False):
    """拼接全部落盘日志。`tag_day=True` 时给每行前置一个带**日期**的时间戳，
    使跨零点/跨文件的先后判断正确（见 `_ts` 的坑）。"""
    parts = []
    for name in reversed(log_files()):  # 旧→新，拼接后整体单调
        body = read_log(name)
        if not tag_day:
            parts.append(body)
            continue
        base = _day(name)
        for line in body.splitlines():
            t = _ts(line)
            if t is None:
                parts.append(line)
            else:
                # base 以 1000 放大后与毫秒时刻相加，单个整数即"绝对毫秒"。
                parts.append(f"\x00{base * 86_400_000 + t} {line}")
    return "\n".join(parts)


def _ts_full(line):
    """带日期的落盘时间（毫秒），仅对 `_ts_full` 标注过的行有效。"""
    if not line.startswith("\x00"):
        return None
    return int(line[1:line.index(" ")])


def _strip_marks(text):
    return "\n".join(x[1:] if x.startswith("\x00") else x
                     for x in text.splitlines())


def read_log(name):
    return adb("shell", "run-as", PKG, "cat", f"files/logs/{name}")


def launch(spec):
    """冷开书。变体经 intent extra 传入；不传 spec 即生产默认。"""
    adb("shell", "am", "force-stop", PKG)
    cmd = ["shell", "am", "start", "-n", ACT]
    if spec:
        cmd += ["--es", "ab", spec]
    adb(*cmd)
    time.sleep(9.0)  # 落位后留出后台预排的时间，让两轮都稳在同一状态


def parse(text):
    rows = []
    for line in text.splitlines():
        m = LAUNCH.search(line)
        if not m or m.group("openT") is None:
            continue
        g = m.group
        try:
            rows.append({
                "anchor": g("anchor"), "ab": g("ab"),
                "ctl": float(g("ctl")), "openT": int(g("openT")),
                "hit": g("hit"), "temp": g("temp"),
            })
        except (TypeError, ValueError):
            continue
    return rows


def _ts(line):
    """落盘日志行的**时刻**（毫秒），只取时分秒。

    **只用于同一文件内的先后**。跨零点、跨文件都不成立：
    `00:0x` 折算成 0~60000 小于 23:xx 的 85000+。R28 采样正好在 00:0x，
    14 跑全被判成"无新落位行"，而日志里数据其实齐全；
    `--since 00:00:30` 又把 23:xx 的行全放行，61 条里 47 条是历史数据。
    **跨文件/跨零点一律用 `read_all_logs(tag_day=True)` 的带日期时间戳。**
    """
    m = re.match(r"^[WEDI] (\d\d):(\d\d):(\d\d)\.(\d\d\d)", line)
    if not m:
        return None
    h, mi, s, ms = map(int, m.groups())
    return ((h * 60 + mi) * 60 + s) * 1000 + ms


def _latest_day(marked):
    """带日期标记文本里出现的最大日期（YYYYMMDD）。"""
    days = [t // 86_400_000 for t in
            (x for x in (_ts_full(l) for l in marked.splitlines()) if x is not None)]
    return max(days) if days else 0


def _abs_from_spec(spec, default_day):
    """`HH:MM:SS` 或 `YYYYMMDD@HH:MM:SS` → 绝对毫秒；不带日期用 [default_day]。"""
    m = re.match(r"^(?:(\d{8})@)?(\d\d):(\d\d):(\d\d)$", spec)
    if not m:
        raise RuntimeError(f"--since 格式应为 [YYYYMMDD@]HH:MM:SS，收到 {spec!r}")
    day = int(m.group(1)) if m.group(1) else default_day
    h, mi, s = map(int, m.groups()[1:])
    return day * 86_400_000 + ((h * 60 + mi) * 60 + s) * 1000


def _ts_now():
    """设备当前时刻，换算到与 `_ts_full` 同一基准的**绝对毫秒**。

    基准：`日期整数(YYYYMMDD) * 86400 + 当日时刻毫秒`。
    跨零点、跨文件、跨轮转副本都比较成立（`read_all_logs(tag_day=True)` 同基准）。
    """
    out = adb("shell", "date", "+%Y%m%d%H%M%S").strip()
    if not re.match(r"^\d{14}$", out):
        raise RuntimeError(f"无法解析设备时间：{out!r}")
    day = int(out[:8])
    tod = ((int(out[8:10]) * 60 + int(out[10:12])) * 60 + int(out[12:14])) * 1000
    return day * 86_400_000 + tod


def collect(n, spec_a, spec_b):
    """交叉采样：每轮 A、B 各一跑，顺序交替，避免慢时段整段落在一侧。"""
    # 水位取开跑前设备时间并退 3s，容忍 force-stop 与首行落盘之间的秒级抖动。
    # 用**带日期**的时间戳，跨零点/跨文件都成立。
    cutoff = _ts_now() - 3000
    recs = []
    for i in range(n):
        for tag, spec in (("A", spec_a), ("B", spec_b)):
            launch(spec)
            marked = read_all_logs(tag_day=True)
            fresh = [_strip_marks(x) for x in marked.splitlines()
                     if (t := _ts_full(x)) is not None and t > cutoff]
            rows = parse("\n".join(fresh))
            if not rows:
                print(f"  第 {i+1} 轮 {tag}: 无新落位行，跳过", file=sys.stderr)
                continue
            cutoff = max(t for t in (_ts_full(x) for x in marked.splitlines())
                         if t is not None)
            r = rows[-1]
            r.update(round=i, arm=tag, want=spec or "(default)")
            recs.append(r)
            print(f"  第 {i+1} 轮 {tag}: openT={r['openT']}ms "
                  f"anchor={r['anchor']} ab={r['ab']} ctl={r['ctl']}ms")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "a") as f:
        for r in recs:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    return recs


def mad(xs):
    if not xs:
        return 0.0
    m = statistics.median(xs)
    return statistics.median([abs(x - m) for x in xs])


def expected_describe(spec):
    """把 `--spec-a/--spec-b` 归一化成 `AbSwitch.describe()` 应当自报的字样。

    口径必须与引擎侧一致（`AbSwitch.apply` + `describe`），否则会把
    **已经生效**的变体误判成"未生效"而白扔一批样本：
      - 具名开关只记**名字**，不记 `=1`：`slowRegex=1` → `slowRegex`
      - 具名开关**排序在前**，`warm=N` 追加在后：`warm=3,slowRegex=1` → `slowRegex,warm=3`
      - `warm=0` 不列出 → `none`（生产默认）
      - 未知键 / 值非 1|on 的键被引擎静默忽略，这里同样不列
    """
    if not spec or not spec.strip():
        return "none"
    flags, warm = [], 0
    for part in re.split(r"[,; ]", spec):
        if not part:
            continue
        kv = part.split("=", 1)
        if len(kv) != 2:
            continue
        k, v = kv[0].strip(), kv[1].strip()
        if k == "warm":
            n = int(v) if v.lstrip("+-").isdigit() else 0
            warm = max(0, min(64, n))
        elif v in ("1", "on"):
            flags.append(k)
    parts = sorted(set(flags))
    if warm:
        parts.append(f"warm={warm}")
    return ",".join(parts) if parts else "none"


def verdict(recs, spec_a, spec_b):
    """按锚点分组；不可比就拒绝出结论。"""
    problems = []
    # 自报变体与引擎实际读到的必须一致。R28 曾因日志配对错位，一度得出
    # "变体全反了"（自报 warm=3 的跑 warmB=0）——那是 ab_detail 的错，不是开关的错。
    # 交叉校验放这里，避免下一次又把假象当结论。
    if recs and "warmB_asm" in recs[0]:
        bad = [(r["ab"], r["warmB_asm"]) for r in recs if r["warmB_asm"] is not None
               and str(r["warmB_asm"]) not in r["ab"]]
        if bad:
            problems.append(f"日志自报变体与 asm 的 warmB 不符：{bad[:3]}——配对错位，不要据此下结论")

    anchors = {r["anchor"] for r in recs}
    if len(anchors) > 1:
        problems.append(f"锚点不一致 {sorted(anchors)}——跨锚点不可比，本次不出结论")

    arms = {}
    for r in recs:
        arms.setdefault(r["arm"], []).append(r)

    # 变体是否真的生效：回读日志自报的 ab，而不是相信命令。
    # 注意 `warm=0` 的**期望自报值是 `none`**（AbSwitch.describe 只在值非 0 时列出），
    # 那正是"生产默认生效"的正确表现，别把它误判成未生效。R28 首次采样就误判过。
    # 分臂已由日志自报定（见 main），两臂自报值必须**互不相同**——相同就等于没分臂。
    reported = {arm: {r["ab"] for r in arms.get(arm, [])} for arm in arms}
    flat = [v for s in reported.values() for v in s]
    if len(flat) > 1 and len(set(flat)) == 1:
        problems.append(f"两臂自报变体相同（{flat[0]}）——实际没分臂，样本无效")
    for arm, spec in (("A", spec_a), ("B", spec_b)):
        if not spec:
            continue
        want = expected_describe(spec)
        got = reported.get(arm, set())
        if not got:
            problems.append(f"臂 {arm} 无样本")
        elif got != {want}:
            problems.append(f"臂 {arm} 变体未按预期生效：期望 {want}，实得 {sorted(got)}")

    # 控制量只当**离群标记**：MAD 相对 >100% 才判设备不稳。
    # 别把它当硬门槛：标定 4000 轮后自身只有 1.5~5ms，几毫秒的绝对抖动就占一半，
    # R33 那批 4000 轮前的 55~80ms 长尾才是真信号。收窄到 <100% 后 A/B 判读才可用。
    for arm, rs in sorted(arms.items()):
        ctl = [r["ctl"] for r in rs]
        if ctl and mad(ctl) / statistics.median(ctl) > 1.0:
            problems.append(
                f"臂 {arm} 控制量离散过大（中位 {statistics.median(ctl):.2f}ms，"
                f"MAD {mad(ctl):.2f}ms，相对 {mad(ctl)/statistics.median(ctl)*100:.0f}%）"
                f"——设备状态不稳，样本不可用")

    if problems:
        print("\n拒绝出结论：")
        for p in problems:
            print(f"  - {p}")
        return 2

    print(f"\n锚点一致：{anchors.pop()}   每臂 n={len(arms['A'])}/{len(arms['B'])}")
    stats = {}
    for arm in ("A", "B"):
        xs = sorted(r["openT"] for r in arms[arm])
        ctl = [r["ctl"] for r in arms[arm]]
        stats[arm] = statistics.median(xs)
        print(f"  {arm} openT  中位 {stats[arm]:.0f}ms  "
              f"[{xs[0]}..{xs[-1]}]  MAD {mad(xs):.0f}ms   "
              f"ctl 中位 {statistics.median(ctl):.2f}ms MAD {mad(ctl):.2f}ms")
    d = stats["B"] - stats["A"]
    pct = d / stats["A"] * 100 if stats["A"] else 0.0
    allctl = [r["ctl"] for r in recs]
    print(f"\nB - A = {d:+.0f}ms ({pct:+.1f}%)   "
          f"（控制量全程中位 {statistics.median(allctl):.2f}ms "
          f"MAD {mad(allctl):.2f}ms，仅作离群标记，不做归一化）")
    print("提示：openT 是端到端墙钟。真排版成本另看日志里的 sStyles+sSkia。")
    return 0


def main():
    p = argparse.ArgumentParser(description="R28 单装机运行期 A/B 采样器")
    p.add_argument("--n", type=int, default=9, help="交叉轮数（每轮 A、B 各一跑）")
    p.add_argument("--spec-a", default="", help="A 臂变体，空 = 生产默认")
    p.add_argument("--spec-b", default="", help="B 臂变体，空 = 生产默认")
    p.add_argument("--report", action="store_true", help="只读已落盘样本出报告，不跑设备")
    p.add_argument("--since", default="", metavar="[YYYYMMDD@]HH:MM:SS",
                   help="配合 --report：只取该时刻之后的落位行（重读已采样数据用）。"
                        "不带日期时按**最新一天**解释——不带日期的 00:00:30 会把"
                        "前一天 23:xx 的行全放行（R28 踩过：61 条里 47 条是历史）")
    a = p.parse_args()

    if a.report:
        # 优先用设备上的落盘日志（--report 通常在采样后立刻跑）；没有再退回本地 jsonl。
        try:
            marked = read_all_logs(tag_day=True)
            if a.since:
                cut = _abs_from_spec(a.since, _latest_day(marked))
                kept = [_strip_marks(x) for x in marked.splitlines()
                        if (t := _ts_full(x)) is not None and t >= cut]
                text = "\n".join(kept)
            else:
                text = _strip_marks(marked)
            recs = parse(text)
            print(f"从设备落盘日志解析到 {len(recs)} 条落位记录"
                  + (f"（{a.since} 起）" if a.since else ""))
        except Exception as e:  # noqa: BLE001 - 设备不可达时退回本地文件
            print(f"设备日志不可读（{e}），改读 {OUT}", file=sys.stderr)
            if not os.path.exists(OUT):
                print("无样本", file=sys.stderr)
                return 1
            recs = [json.loads(x) for x in open(OUT) if x.strip()]
    else:
        print(f"交叉采样 n={a.n}  A={a.spec_a or '(default)'}  B={a.spec_b or '(default)'}")
        recs = collect(a.n, a.spec_a, a.spec_b)

    # --report 从日志重读时没有 arm 标签（那是 collect 打的），改用**日志自报的变体**分臂。
    # 这比信任命令行参数更严：自报值就是代码里 AbSwitch.describe() 真正生效的东西。
    if recs and "arm" not in recs[0]:
        by_ab = {}
        for r in recs:
            by_ab.setdefault(r["ab"], []).append(r)
        want = {"A": expected_describe(a.spec_a), "B": expected_describe(a.spec_b)}
        if len(by_ab) > 2:
            print(f"日志里出现 {len(by_ab)} 种变体 {sorted(by_ab)}，"
                  f"无法归入 A/B 两臂；请收窄 --since 窗口", file=sys.stderr)
            return 1
        for tag, ab in zip(("A", "B"), sorted(by_ab, key=lambda k: (k != want["B"], k))):
            for r in by_ab[ab]:
                r["arm"] = tag
        print(f"按日志自报变体分臂：{[(t, k) for t, k in zip(('A','B'), sorted(by_ab))]}")
        a.spec_a, a.spec_b = "", ""  # 分臂已由自报值定，期望值改用自报

    if len(recs) < 4:
        print(f"样本不足（{len(recs)}），不出结论", file=sys.stderr)
        return 1
    return verdict(recs, a.spec_a, a.spec_b)


if __name__ == "__main__":
    sys.exit(main())
