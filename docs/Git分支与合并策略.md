# Git 分支与合并策略

> 2026-09-23 起执行：**取消 fast-forward 单线模式，改为单主线 + 临时功能分支**。
> 历史不可补（见下「历史说明」），叙述从本日起。

## 当前模式：单主线 `main` + `feat/fix` 临时分支

**现在没有稳定版，不搞 main/develop 并行**。只有一条长期分支：

| 分支 | 角色 | 规则 |
|---|---|---|
| `main` | **唯一集成线** | 日常开发合流处；全部历史与当前工作都在其上 |
| `feat/*`、`fix/*`、`refactor/*`、`docs/*` | 临时功能分支 | 从 `main` 切、`--no-ff` 合回即删 |

将来**出现真正的稳定版需求**（要发版维护线/长支持版本）时，再从 `main` 分裂出
`release/x`（或视当时需要转为 main/develop 双线），本文档届时更新——不提前并行。

## 用法

```bash
# 开功能分支（命名对齐提交前缀）
git checkout -b feat/<slug> main

# …开发、提交…

# 合回 main（必须留合并节点）
git checkout main
git merge --no-ff feat/<slug>   # 仓库已配 merge.ff=false，可省略 --no-ff
git branch -d feat/<slug>       # 合完即删，叙事已在合并节点里
```

- 小改（一两句 typo 级）直接落在 `main` 上提交即可，不必开分支。
- 发版时在 `main` 直接打 tag（`v1.0.0` 等），不需要发布分支。

## 多设备/远程同步

- 本项目原则上是 **单写者**；`main` 可能推到远程。
- 与远程同步时若两端分叉，**用 rebase 保持本地提交干净**（合并只发生在特征合入场合，
  不要在 `git pull` 里造噪声提交）：

```bash
git pull --rebase origin main
```

## 本地已固化约束

- `git config merge.ff false`（仓库级）：任何 `git merge` 默认创建合并提交，杜绝回到 fast-forward 单线。

## 历史说明

改策略前的 681 提交全部是 **fast-forward 工作流**产物（功能分支线性提交、FF 合入、
无合并节点）：`git log --merges` 为空、`--graph --all` 呈单线是真实拓扑，并非历史损坏；
reflog 内的改写只有 `reset`（掉落重提交）与 `amend`，无 rebase，且被 reset 掉的提交对象仍可经
`git fsck --no-reflogs --unreachable` / reflog 找回。

**不重建历史**：给旧提交伪造合并节点既不真实也无叙事价值，只会引入重写风险。
功能叙事从第一个 `--no-ff` 合并开始累积——两周后回看，图上的分叉点全部是真实功能并入点。