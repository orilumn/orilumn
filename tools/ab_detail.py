#!/usr/bin/env python3
"""把 `asm` 探针行与 `jump: open` / `DISK-HIT shape` 按时间配对，看**分段**而非只看 openT。

`openT` 是端到端墙钟，暖机这类"花掉的钱省掉别处"的效果在它上面会互相抵消。
R26 的 +50ms 是这么算出来的：锚页 shape 584→262ms，同时多付 warm 367ms。
只看 openT 会以为"没变化"，实际上钱花在别处了。

用法：先取日志再喂进来
    adb -s <dev> shell 'run-as orilumn.reader cat files/logs/日志_YYYYMMDD.txt' \
      | python3 tools/ab_detail.py --since 00:00:30
"""
import argparse
import re
import statistics
import sys

TS = re.compile(r"^[WEDI] (\d\d):(\d\d):(\d\d)\.(\d\d\d)")


def f(pat, line, cast=float, default=None):
    m = re.search(pat, line)
    return cast(m.group(1)) if m else default


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", default="", metavar="HH:MM:SS",
                    help="只取该时刻之后（按文件内时分秒，跨零点请用带日期的完整日志）")
    a = ap.parse_args()

    cut = 0
    if a.since:
        h, mi, s = map(int, a.since.split(":"))
        cut = (h * 60 + mi) * 60000 + s * 1000

    events = []
    for line in sys.stdin:
        m = TS.match(line)
        if not m:
            continue
        t = ((int(m.group(1)) * 60 + int(m.group(2))) * 60 + int(m.group(3))) * 1000 \
            + int(m.group(4))
        if t < cut:
            continue
        if "jump: open" in line:
            events.append((t, "open", {
                "t": t, "ab": f(r" ab=(\S+)", line, str, "?"),
                "openT": f(r"openT=(\d+)ms", line, int, 0),
                "ctl": f(r"ctl=([\d.]+)ms", line, float, 0.0),
                "asm": None, "disk": None, "warm": None, "sStyles": None,
                "sSkia": None,
            }))
        elif "asm ch=" in line and f(r"sBlocks=(\d+)", line, int, 0) > 0:
            events.append((t, "asm", {
                "asm": f(r" shape=(\d+)ms", line, int, 0),
                "warm": f(r" warm=(-?\d+)ms", line, int, 0),
                "sStyles": f(r"sStyles=(\d+)ms", line, int, 0),
                "sSkia": f(r"sSkia=(\d+)ms", line, int, 0),
                # R29 细分：cBuild 里「边家族」与 font-family 三连各占多少。
                "cBuild": f(r"cBuild=(\d+)ms", line, int, 0),
                "cEdge": f(r"cEdge=(\d+)ms", line, int, 0),
                "cFont": f(r"cFont=(\d+)ms", line, int, 0),
                "cMatch": f(r"cMatch=(\d+)ms", line, int, 0),
                "warmB": f(r"warmB=(\d+)", line, int, 0),
            }))
        elif "DISK-HIT shape" in line:
            events.append((t, "disk", {"disk": f(r"t=(\d+)ms", line, int, 0)}))

    # **必须按时间排序后再配对**。一次开书的真实顺序是
    #   DISK-HIT shape → asm(锚页) → jump: open
    # 先读后配会把上一跑的 asm 算到下一跑头上。
    events.sort(key=lambda e: e[0])
    recs = []
    for _, kind, payload in events:
        if kind == "open":
            # 落位行自带 ab 自报 = AbSwitch 真正生效的值。以它为准，
            # asm 的 warmB 只作交叉校验——两者不符说明配对错了，别硬填。
            r = payload
            r["warmB_asm"] = None
            r["asms"] = []
            recs.append(r)
        elif recs:
            if kind == "asm":
                recs[-1]["asms"].append(payload)
            else:
                recs[-1]["disk"] = payload["disk"]

    # asm 可能有多条（预热那条 + 锚页那条）。以 `warmB` 为主键分派：
    #  预热行：warm>0 的那条（warm=0 时预热不发生，故只有锚页行）
    #  锚页行：sBlocks>0 且是该章开书落位的那条
    for r in recs:
        # ab 自报串里 `warm=N` 可以**缺席**（`describe()` 只在非 0 时列出），
        # 纯具名开关（`slowRegex`）就是这种形状——缺席即 0，不能直接 `search(...).group(1)`。
        m = re.search(r"warm=(\d+)", r["ab"] or "")
        want_b = int(m.group(1)) if m else 0
        pre = [x for x in r["asms"] if x["warmB"] == want_b and x["warm"] > 0]
        anch = [x for x in r["asms"] if x["warmB"] == want_b]
        r["warmB_asm"] = anch[0]["warmB"] if anch else None
        r["warm"] = pre[0]["warm"] if pre else 0
        base = anch[-1] if anch else (r["asms"][-1] if r["asms"] else {})
        r["asm"] = base.get("asm")
        r["sStyles"] = base.get("sStyles")
        r["sSkia"] = base.get("sSkia")
        r["cBuild"] = base.get("cBuild")
        r["cEdge"] = base.get("cEdge")
        r["cFont"] = base.get("cFont")
        r["cMatch"] = base.get("cMatch")

    if not recs:
        print("无落位行", file=sys.stderr)
        return 1

    hdr = (f"{'时刻':<12}{'变体':<9}{'openT':>8}{'DISK':>8}"
           f"{'anchorShape':>13}{'warm':>8}{'sStyles':>9}{'sSkia':>7}"
           f"{'cMatch':>8}{'cBuild':>8}{'cEdge':>7}{'cFont':>7}")
    print(hdr)
    print("-" * len(hdr))
    for r in recs:
        print(f"{str(r['t']//1000 % 100000):>08}{'.%03d' % (r['t'] % 1000):<4}{r['ab']:<9}"
              f"{r['openT']:>6}ms{(r['disk'] or 0):>6}ms{(r['asm'] if r['asm'] is not None else -1):>11}ms"
              f"{(r['warm'] if r['warm'] is not None else -1):>6}ms"
              f"{(r['sStyles'] if r['sStyles'] is not None else -1):>7}ms"
              f"{(r['sSkia'] if r['sSkia'] is not None else -1):>5}ms"
              f"{(r['cMatch'] if r['cMatch'] is not None else -1):>6}ms"
              f"{(r['cBuild'] if r['cBuild'] is not None else -1):>6}ms"
              f"{(r['cEdge'] if r['cEdge'] is not None else -1):>5}ms"
              f"{(r['cFont'] if r['cFont'] is not None else -1):>5}ms")

    arms = {}
    for r in recs:
        arms.setdefault(r["ab"], []).append(r)
    print()
    for ab, rs in sorted(arms.items()):
        def med(k):
            xs = [r[k] for r in rs if r[k] is not None and r[k] >= 0]
            return statistics.median(xs) if xs else float("nan")
        n = len(rs)
        print(f"{ab:<9} n={n:<3} warmB={med('warmB_asm'):>3.0f}  openT {med('openT'):>6.0f}ms   "
              f"DISK-HIT {med('disk'):>5.0f}ms   anchorShape {med('asm'):>5.0f}ms   "
              f"warm {med('warm'):>4.0f}ms   sStyles {med('sStyles'):>4.0f}ms   "
              f"sSkia {med('sSkia'):>4.0f}ms   cMatch {med('cMatch'):>4.0f}ms   "
              f"cBuild {med('cBuild'):>4.0f}ms   cEdge {med('cEdge'):>4.0f}ms   "
              f"cFont {med('cFont'):>4.0f}ms")
    print("\n注：anchorShape/sStyles/sSkia 只取 sBlocks>0 的那条 asm（真塑形那次）。"
          "\n暖机的效果不在 openT 上体现，要看 DISK-HIT 与 anchorShape 两段："
          "\n多付的 warm 段要从这两段里省回来才算赚。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
