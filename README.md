# desk-timer — 离线桌面计时器

Windows 优先的常驻托盘计时器：**一行把一段话说完整**，开始计时、番茄、记一笔、记待办都在同一个输入框里。它只记录时间证据，**不做统计**——分析交给下游工具。全离线：无账号、无网络、无后台服务。

当前版本 **v1.0.0**。日常入口是免安装的 `D:\timer-app\desk-timer\desk-timer.exe`（不需要 JDK）。

## 它做什么

- **快速捕获**：`25m 写周报 #工作 @文档站` 一句话给出时长、任务、标签、项目；解析在本地做完，失败也不弹窗追问。
- **计时面板**：大号剩余时间 + 状态行，可以钉住置顶、可以拖到屏幕任意位置并记住。
- **多计时并发**：面板列出所有在跑的段，`↑`/`↓` 换选中，动作键只作用在选中那一段上。
- **番茄钟**：`p` 前缀开始（默认 25 分，`p40` 直接给 40 分），工作段结束后由你按一键才进入短休 5 分 / 每 4 轮长休 20 分。
- **到点提醒**：正常情况把面板抬起来并响一声（声音绑 Windows 的"新短信通知"事件，换音与静音在系统声音设置里做）；你在看全屏或开了勿扰时不抢焦点，但会照样记下"这次没被看到"。
- **随手记与待办**：`! 正文` 记一笔（可以挂在正在跑的那段上），`+ 标题` 记一条待办，待办完成时可以选择"顺手补录这段时间"。
- **对账**：崩溃或合盖休眠重启后，它不猜——把不可信的那几段列出来让你确认或改时间。
- **主题**：暗 / 亮 / 跟随系统；开机自启默认关，勾一下才写注册表。

## 怎么用

**唤起**：`Ctrl+Alt+F12`（可在托盘右键→设置里改，当场生效），或托盘左键。优先顺序是 计时面板 → 待确认卡 → 待办卡 → 捕获窗。

一个输入框三种用途：

| 写法 | 效果 |
|---|---|
| `25m 写周报 #工作 @文档站` | 开始 25 分钟倒计时 |
| `1h30m 改文案` / `90分钟 改文案` / `p40 读论文` | 时长支持 `h/小时`、`m/分钟`、`s/秒`，中英单位同义 |
| `14:30 站会` | 倒计时到今天的这个时刻（已过则算到明天） |
| `p 25m 写周报` / `p40 读论文` | 番茄工作段（`p` 后要接空格、数字或到此为止；`Portal`、`Python` 这类以 p 开头的单词不会被吃掉） |
| `! 刚才被需求变更打断了` | 记一笔随手记，**不开始计时** |
| `+ 整理发布清单` | 记一条待办，**不开始计时** |

`#标签` 与 `@项目` 都可选；全角 `！＃＠` 与半角同义。

**计时面板的键**（提示条上就是这一套，改键之后跟着变）：

| 键 | 动作 |
|---|---|
| `空格` | 暂停 / 继续选中那一段 |
| `E` / `Enter` | 结束这一段（记为完成） |
| `X` | 放弃这一段（保留证据，不算完成；番茄的话不进轮次） |
| `H` / `Esc` | 收起面板（计时继续） |
| `N` / `→` | 记一笔随手记，或在同一个输入框里另起一段 |
| `↑` / `↓` | 换选中的段（并发时用） |
| `R` | 番茄工作段结束后开始休息 |
| `P` | 钉住置顶 |
| `M` | 勿扰一小时（再按取消） |
| `L` | 打开笔记卡（`F` 置顶，`#词` 只搜标签，`@词` 只搜项目） |

结束时长不足 5 秒且还没到点的那一下按键，会被记成"疑似误触"的放弃而不是完成——时间账里不会被塞进 0 分钟的段。

## 数据落点与导出契约

- 运行时数据：`D:\timer-data\`（不在本仓库里，也不进版本控制）。
- 消费方**只读** `D:\timer-data\exports\` 下的文件：`time-entries.jsonl`、`notes.jsonl`、`todos.jsonl`、`csv` / `excel.csv` / `daily-*.md`，以及 `manifest.json`；不打开 `db\timer.db`，也不读本仓库源码。
- 版本靠 `manifest.json` 的 `schema_version` 判定，不认就报错而不是猜；`docs/verify-contract.mjs` 是一个独立消费者，可以直接跑它来看自家导出能不能被读。
- 每条时间条目都带算好的 `checkin_date`（本地 08:00 前的记录归前一天），消费方直接用，不必自己按自然日重算。
- 要往计时器里记一段：只允许往 `D:\timer-data\inbox\inbox.jsonl` 追加整行（写法同上表的时长部分），应用最迟一个导出周期后把它落成条目并去重。

## 命令

```bat
run.cmd                    :: 双击启动（从源码构建后运行，开发入口）
run.cmd ctrl+alt+t         :: 换一个快捷键启动
run.cmd selftest           :: 构建 + 测试 + 端到端自检
```

```powershell
mvn -B test                                                          # 单元测试
powershell -NoProfile -ExecutionPolicy Bypass -File ./run-app.ps1    # 构建 + 测试 + 运行时自检（写沙箱数据目录）
powershell -NoProfile -ExecutionPolicy Bypass -File ./package.ps1     # jpackage 便携包 + 打包后自检
powershell -NoProfile -ExecutionPolicy Bypass -File ./package-zip.ps1 # 把便携包压成 zip，并解压真跑一次证明能用
powershell -NoProfile -ExecutionPolicy Bypass -File ./package.ps1 -InstallTo 'D:\timer-app'   # 装到日常入口
powershell -NoProfile -ExecutionPolicy Bypass -File ./measure.ps1     # 空闲驻留内存
powershell -NoProfile -ExecutionPolicy Bypass -File ./restore-drill.ps1   # 从备份 zip 真恢复一次
node docs/verify-contract.mjs <某个 exports 目录>                     # 校验导出契约
```

Windows 上请直接复制上面的命令行执行；本仓库不提供 `npm install` 之类的自动安装步骤，也不会替你修改系统设置。

## 技术栈与目录

Java 17 + JavaFX 17 + `sqlite-jdbc` + JNativeHook（全局快捷键）+ JNA（Win32 探针）+ `jpackage`（免安装目录，不需要管理员权限）。构建是 Maven，脚本保持纯 ASCII。

| 路径 | 内容 |
|---|---|
| `src/main/java/desktimer/` | `App`（常驻主进程）、`core.time`（时钟与归日）、`core.parse`（捕获语法）、`db`（仓储与迁移）、`export`（导出契约）、`platform`（快捷键/托盘/全屏/声音/注册表）、`ui`（五扇窗与主题） |
| `src/main/resources/schema/` | 建表与迁移 SQL，`user_version` 是唯一的版本位 |
| `src/test/java/` | 单元测试：时间归日、状态机、区间与重叠、schema 契约、迁移、解析语法、键位表、主题对比度、注册表读写 |
| `docs/DESIGN.md` | 设计文档：需求 / UI / schema / 互操作契约 / 选型 |
| `docs/verify-contract.mjs`、`docs/verify-contract-negatives.mjs` | 独立消费者：正向验收与"不认的版本必须报错"的反向验收 |
| `spike/` | 选型阶段的实测代码与数据（内存、快捷键、无边框置顶），不进产品 |
| `package.ps1`、`package-zip.ps1`、`run-app.ps1`、`measure.ps1`、`restore-drill.ps1` | 打包 / 便携 zip 资产 / 自检 / 内存 / 恢复的入口脚本 |

## 约定

- 文件名与目录名全 ASCII，正文 UTF-8。
- 导出是原子写（先 `.tmp` 再 rename）。
- schema 只加列，不改列、不删列。
- 构建产物（`target/`、`out/`、`.idea/`）不进 git。
