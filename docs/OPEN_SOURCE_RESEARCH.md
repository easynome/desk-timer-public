# 开源计时工具定向调研

日期：2026-10-16。目标：学习小型桌面计时器的交互和可靠性设计；本轮未复制外部代码、资源或安装项目。

更新：后续用户已授权整合功能。v1.2 的交互与状态机在本项目独立实现；现已引入 Stretchly v1.22.1 的两个 CC0 WAV（风铃/滴答），来源、作者、许可与哈希见 `src/main/resources/audio/NOTICE.md`。未引入上游实现代码或其他音频；下方为初次调研时的审查范围。

以下为选定文件与功能的定向审查，不能代表整个上游项目已经通过安全或性能审计。提交号来自当天 `git ls-remote`，许可证以仓库文件为依据；采用代码时仍需复查相应版本和文件声明。

| 项目 / 已核对版本 | 本轮检查范围 | 可借鉴的特点 | desk-timer 取舍 |
|---|---|---|---|
| [Pomatez](https://github.com/zidoro/pomatez/tree/c2727800c6272573590911e9ee81637b89e55d5f)，MIT | README、LICENSE；功能说明级检查，未验证实现和占用 | 紧凑模式、进度动画开关、提醒分级、托盘进度 | 优先考虑紧凑视图；后台保持低绘制频率。托盘进度需实测，不直接照搬动画或技术栈 |
| [Stretchly](https://github.com/hovancik/stretchly/tree/3cb3af2d480c352ed903007426fd86e1226bd6fc)，BSD-2-Clause | README、LICENSE、`app/breaksPlanner.js` 定向源码检查 | 提前预告、稍后提醒、离席与勿扰分离、暂停时停止轮询 | 可增加可选预告与一次稍后提醒；提醒时间与真实计时长度分开，不因推迟提醒改写学习时长 |
| [ActivityWatch aw-watcher-afk](https://github.com/ActivityWatch/aw-watcher-afk/tree/2762612e1b058db66c86c37803de12506b102b9b)，MPL-2.0 | LICENSE.txt、`aw_watcher_afk/afk.py` 定向源码检查 | 将离席作为独立事件，单独处理锁屏/解锁和探测错误 | 如加入离席检测，只提供待确认提示或独立证据，先保留原计时；不能把无键鼠输入等同于没有学习 |

## 定向审查发现与迁移条件

Stretchly 的调度器将提醒预告、实际休息和推迟分成不同状态，暂停时停止几个轮询管理器。这种状态分离值得采用。该工具管理休息节奏，desk-timer 记录事实时长，不能直接用“暂停休息调度”的逻辑暂停事实记录。当前桌面项目也没有为每个后台功能引入额外服务的必要。

ActivityWatch 的 AFK 源码在探测失败时保留原状态，并特别处理锁屏后有输入、解锁却没有输入的情况。这说明“空闲多少秒”不足以可靠判定状态。它使用 watcher/client 的事件链路；desk-timer 可以学习状态转换和失败策略，增加整套后台服务须另行量内存、CPU 和权限成本。

Pomatez 的紧凑模式与动画开关是已公开的功能说明，本轮没有做运行验收。因此只能作为交互候选，不能据此断言它更省内存。强制全屏休息和自动开始下一段，与当前低打扰和真实时间记录目标存在冲突，暂不采用。

## 采用前的检查门槛

1. 说明解决的真实问题、入口与关闭方式；默认行为和现有提醒/归日契约兼容。
2. 固定上游提交、许可证、来源文件和归属声明；功能灵感与复制实现分开记录。
3. 阅读实际调用链及失败/停止路径，检查新线程、轮询、网络、自动更新、原生权限和资源文件。
4. 在隔离目录验证正常、全屏、勿扰、暂停、休眠和重启；核对导出仍可消费。
5. 使用相同场景比较空闲/计时/唤窗开销；没有实测的性能收益标为未验证。

当前优先级：修复已发现的数据一致性缺口 → 统一提醒与需求说明 → 按真实使用反馈加入紧凑模式/可选提醒预告 → 再评估离席提示。以上是建议，未在本轮实现这些功能。

## 原始来源

- [Pomatez README（固定提交）](https://github.com/zidoro/pomatez/blob/c2727800c6272573590911e9ee81637b89e55d5f/README.md)、[MIT LICENSE](https://github.com/zidoro/pomatez/blob/c2727800c6272573590911e9ee81637b89e55d5f/LICENSE)。
- [Stretchly README](https://github.com/hovancik/stretchly/blob/3cb3af2d480c352ed903007426fd86e1226bd6fc/README.md)、[调度源码](https://github.com/hovancik/stretchly/blob/3cb3af2d480c352ed903007426fd86e1226bd6fc/app/breaksPlanner.js)、[BSD LICENSE](https://github.com/hovancik/stretchly/blob/3cb3af2d480c352ed903007426fd86e1226bd6fc/LICENSE)。
- [AFK 状态机源码](https://github.com/ActivityWatch/aw-watcher-afk/blob/2762612e1b058db66c86c37803de12506b102b9b/aw_watcher_afk/afk.py)、[MPL LICENSE](https://github.com/ActivityWatch/aw-watcher-afk/blob/2762612e1b058db66c86c37803de12506b102b9b/LICENSE.txt)。
