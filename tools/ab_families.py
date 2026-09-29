#!/usr/bin/env python3
"""R29 家族级对比：把 `asm` 的边家族细分（`eBox`/`eWid`/`eCol`/`eSty`/`eRad`/`eBrd`）
按开书落位行的**自报变体**分臂，报中位数 + 范围。

为什么不并进 ab_detail.py：那一支的取数口径是「DISK-HIT / asm / jump: open 三段按时间配对」，
而家族级比较只需要 `asm` 行 + 紧随其后的 `jump: open` 的 `ab=`，口径不同、校验也不同。
混在一起会让两个脚本都变脆。

用法：
    adb -s <dev> shell 'run-as orilumn.reader cat files/logs/日志_YYYYMMDD.txt' \
      | python3 tools/ab_families.py --window 350
"""
import argparse
import re
import statistics
import sys

TS = re.compile(r"^[WEDI] (\d\d):(\d\d):(\d\d)\.(\d\d\d)")
KEYS = ("shape", "sStyles", "cMatch", "cBuild", "cEdge",
        "eBox", "eWid", "eCol", "eSty", "eRad", "eBrd", "sEls")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--window", type=int, default=350, metavar="SEC",
                    help="只看最后一跑往前这么多秒（默认 350 ≈ 16 跑开书的跨度）")
    ap.add_argument("--ch", default="", metavar="N",
                    help="只取该章的 asm 行（不给就混进其他章，中位数会被拉飞——"
                         "DISK-HIT 后台预排的 ch0/ch7 那些行 eBrd=0，就是它们污染的）")
    a = ap.parse_args()

    lines = sys.stdin.read().split("\n")
    recs = []
    for i, l in enumerate(lines):
        m = TS.match(l)
        if not m:
            continue
        t = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3))
        recs.append((t, i, l))
    if not recs:
        print("无带时间戳的行", file=sys.stderr)
        return 1

    # 最后一个自报变体的落位行当窗口右端。
    end = None
    for t, i, l in recs:
        if "jump: open" in l and "ab=" in l:
            end = t
    if end is None:
        print("无落位行", file=sys.stderr)
        return 1
    cut = end - a.window

    # 配对：**asm 归给其后第一个落位行**。
    # 真实顺序是 DISK-HIT → asm(锚页) → jump: open，所以 asm 出现在落位行**之前**；
    # 按"归给上一个落位"配会把这一跑的 asm 挂到上一跑头上（我第一版就这么错的，
    # 结果两臂中位数一个全是 0、一个正常——纯粹是配对错位，不是变体效应）。
    arms, pend = {}, []
    for t, i, l in recs:
        if t < cut:
            continue
        if "asm ch=" in l and "eBrd=" in l:
            # 只取真塑形那次（sBlocks>0）。DISK-HIT 命中后后台还会发一批
            # `sBlocks=0` 的预排 asm，各字段全是 0，混进来中位数就废了。
            mb = re.search(r"\bsBlocks=(\d+)", l)
            if not mb or int(mb.group(1)) <= 0:
                continue
            # 只比同一个章：跨章的块数、border 密度都不同。
            if a.ch and not re.search(rf"\bch={a.ch}\b", l):
                continue
            d = {k: int(v) for k, v in re.findall(r"(\w+)=(\d+)ms", l)}
            for k, v in re.findall(r"\b(eBrd|sEls|sBlocks)=(\d+)", l):
                d[k] = int(v)
            pend.append(d)
        elif "jump: open" in l:
            m = re.search(r"ab=(\S+)", l)
            if not m or not pend:
                pend = []
                continue
            arms.setdefault(m.group(1), []).extend(pend)
            pend = []

    if len(arms) < 2:
        print(f"只识别到 {len(arms)} 个变体 {sorted(arms)}，无法对比（放宽 --window）", file=sys.stderr)
        return 1

    print(f"窗口 {cut}s..{end}s（右侧限最后一跑）\n")
    for ab, rows in sorted(arms.items()):
        print(f"--- ab={ab}  n={len(rows)} ---")
        for k in KEYS:
            xs = [r[k] for r in rows if k in r]
            if xs:
                print(f"  {k:8} 中位 {statistics.median(xs):>7.1f}   范围 [{min(xs)}..{max(xs)}]")
        print()

    names = sorted(arms)
    if len(names) == 2:
        A, B = arms[names[0]], arms[names[1]]
        print(f"差（{names[1]} - {names[0]}）：")
        for k in KEYS:
            xa = [r[k] for r in A if k in r]
            xb = [r[k] for r in B if k in r]
            if xa and xb:
                d = statistics.median(xb) - statistics.median(xa)
                print(f"  {k:8} {d:>+7.1f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
