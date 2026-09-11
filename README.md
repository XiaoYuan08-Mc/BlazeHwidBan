<div align="center">

# 🛡️ BlazeHwidBan

### 封的是设备，不是账号

换小号 ✕　换客户端 ✕　换 IP ✕

[![Release](https://img.shields.io/github/v/release/XiaoYuan08-Mc/BlazeHwidBan?style=for-the-badge&label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC&color=e74c3c)](https://github.com/XiaoYuan08-Mc/BlazeHwidBan/releases)
[![Downloads](https://img.shields.io/github/downloads/XiaoYuan08-Mc/BlazeHwidBan/total?style=for-the-badge&label=%E4%B8%8B%E8%BD%BD&color=9b59b6)](https://github.com/XiaoYuan08-Mc/BlazeHwidBan/releases)
[![Stars](https://img.shields.io/github/stars/XiaoYuan08-Mc/BlazeHwidBan?style=for-the-badge&label=Stars&color=f1c40f)](https://github.com/XiaoYuan08-Mc/BlazeHwidBan/stargazers)
[![License](https://img.shields.io/github/license/XiaoYuan08-Mc/BlazeHwidBan?style=for-the-badge&label=%E8%AE%B8%E5%8F%AF&color=2ecc71)](LICENSE)

[![Paper](https://img.shields.io/badge/Paper-26.x-2196f3?style=for-the-badge)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-25-ed8b00?style=for-the-badge&logo=openjdk&logoColor=white)](https://adoptium.net)
[![Fabric](https://img.shields.io/badge/Fabric-MC%2026.2-dbd0b4?style=for-the-badge)](https://fabricmc.net)

[**⬇ 下载**](https://github.com/XiaoYuan08-Mc/BlazeHwidBan/releases)　·　[快速开始](#-快速开始)　·　[功能一览](#-功能一览)　·　[工作原理](#-工作原理)　·　[指令](#-指令)　·　[常见问题](#-常见问题)

</div>

---

## 💡 为什么需要它

Minecraft 的传统封禁封的是**账号**——玩家注册个小号，一键回到服务器。

本项目把封禁下沉到**设备层**：

| | 传统封禁 | BlazeHwidBan |
|---|:---:|:---:|
| 封禁对象 | 玩家名 / UUID | **这台电脑** |
| 换小号 | 失效 ❌ | 无效 ✅ |
| 换客户端 | 失效 ❌ | 无效 ✅ |
| 原版玩家 | 可封 | 可封（指纹识别）✅ |
| 与反作弊联动 | 手动 | **自动** ✅ |

> 服务端拿不到玩家硬件信息，客户端也拿不到封禁库，所以需要**两端配合**：一个服务端插件 + 一个客户端模组。

---

## ✨ 功能一览

<table>
<tr><td width="33%" valign="top">

**🔒 封禁核心**

- 机器码封禁（设备级）
- 客户端指纹兜底
- 临时封禁（`7d12h` 可组合）
- 封禁生效自动踢出**同机在线账号**
- 同机新账号进服提醒管理员

</td><td width="33%" valign="top">

**🔗 智能联动**

- 反作弊联动（LiteBans / Vulcan / Spartan / Grim 等）
- 原生 `/ban`、`/pardon` **双向同步**
- 启动时自动转换既有原版封禁
- 外挂客户端识别（品牌 + 模组双重检查）

</td><td width="33%" valign="top">

**⚙️ 服务器友好**

- 面向 Paper 26.x（Java 25）
- 配置自动合并（升级不用手改）
- 审计日志 · 封禁画面 · 豁免权限
- 全部文案可自定义（MiniMessage）

</td></tr>
</table>

---

## 🔍 工作原理

### 两种身份标识

```
┌────────────────────────────────────────────┐
│  ① 真实机器码（强标识）                       │
│     玩家电脑 OS 级稳定标识                    │
│       → 本机计算 SHA-256                     │
│       → 只把哈希发给服务器                    │
│     原始硬件信息不出玩家电脑                  │
│     仅装了配套模组的玩家拥有 ✅               │
├────────────────────────────────────────────┤
│  ② 客户端指纹（弱标识）                       │
│     品牌 + 语言 + 设置 + 频道 → 哈希          │
│     所有玩家都有（含原版客户端）✅            │
│     改客户端设置会变化，仅作兜底              │
└────────────────────────────────────────────┘
```

### 玩家进服的判定流程

```
玩家连接服务器
      │
      ├─ ① 读客户端品牌 ──▶ 命中外挂端黑名单？──▶ 🚫 拦截
      │
      ├─ ② 收到配套模组上报（机器码 + 模组列表）
      │        ├─ 模组在黑名单？──────────────▶ 🚫 拦截
      │        └─ 机器码在封禁库？────────────▶ 🚫 踢出（显示封禁画面）
      │
      ├─ ③ 未装模组（宽限 5~8 秒）
      │        ├─ require-mod 开启？──────────▶ 🚫 拒绝进入
      │        └─ 否则用指纹兜底判定
      │
      └─ ✅ 放行 ──▶ 同机关联检查 ──▶ 命中则提醒在线管理员
```

### 反作弊联动怎么工作

各反作弊插件的事件 API 不统一，但它们的"自动处罚"本质都是**执行一条命令**，所以本项目监听命令：

```
反作弊判定作弊 ─▶ 执行它配置的 ban 命令 ─▶ 本插件识别命令 ─▶ 补封机器码 ─▶ 踢出同机账号
```

只需把反作弊的处罚命令写成 **ban 类**（写成 `kick` 不会联动，避免误伤）。

---

## 🚀 快速开始

### 1️⃣ 服务端安装

从 [**Releases**](https://github.com/XiaoYuan08-Mc/BlazeHwidBan/releases) 下载，放进服务器的 `plugins/` 文件夹：

| 文件 | 环境要求 |
|:--|:--|
| `BlazeHwidBan-1.0.0-mc26.jar` | Paper **26.x** 服务器 · Java 25 |

重启服务器即可，配置文件自动生成。

### 2️⃣ 玩家端安装（可选，但强烈建议）

装了才能识别**真实机器码**（否则只能用指纹兜底）：

1. 下载 `mod.blazehwid-1.0.0-mc26.2.jar`
2. 放进 `.minecraft/mods/` 文件夹
3. 同时需要 [**Fabric API**](https://modrinth.com/mod/fabric-api)

> 玩家端**不是必须**的 —— 不装模组的玩家一样能被指纹识别与封禁。

### 3️⃣ 开始使用

```bash
/hwidban ban 玩家名 使用外挂      # 封禁（自动联动封机器码）
/hwidban alt 玩家名              # 查这个人的其他小号
/hwidban list                   # 查看封禁列表
```

---

## 📜 指令

主命令 `/hwidban`（别名 `/hban`）· 权限 `hwidban.admin`（默认仅 OP）

| 指令 | 作用 |
|:--|:--|
| `/hwidban ban <玩家> [理由]` | 封禁玩家（联动封其全部机器码与指纹） |
| `/hwidban tempban <玩家> <时长> [理由]` | 临时封禁，时长可组合如 `1d12h` |
| `/hwidban banhwid <64位机器码> [理由]` | 直接封一个机器码 |
| `/hwidban unban <机器码前缀\|玩家名>` | 解封（同步解除原版封禁） |
| `/hwidban check <玩家>` | 查看该玩家的机器码档案与状态 |
| `/hwidban alt <玩家>` | 同机账号情报（同一台电脑上的其他账号） |
| `/hwidban list [页码]` | 封禁列表分页 |
| `/hwidban info` | 插件状态概览 |
| `/hwidban reload` | 重载配置文件 |

| 权限 | 默认 | 说明 |
|:--|:--|:--|
| `hwidban.admin` | OP | 全部管理指令 |
| `hwidban.exempt` | 无人拥有 | 豁免机器码/指纹校验（需手动授予） |

---

## 🔗 反作弊配置示例

<details>
<summary><b>点击展开：Grim / LiteBans / Vulcan / Spartan 的写法</b></summary>

<br>

**内置识别（开箱即用，无需配置）**

```
原版      /ban  /pardon
LiteBans  ban / tempban / ipban / unban
Vulcan    /vulcan ban
Spartan   /spartan ban / tempban / unban
```

**Grim / Matrix / Intave / NCP（处罚命令由管理员自定义）**

```yaml
# Grim 的 punishments.yml
ban %player% 作弊
```

```yaml
# 也可以直连封机器码
hwidban ban %player% 反作弊判定
```

> ⚠️ 处罚写成 `kick` 不会触发联动（踢出 ≠ 封禁）。

</details>

---

## ⚙️ 配置要点

<details>
<summary><b>点击展开：config.yml 关键配置项</b></summary>

<br>

```yaml
# 指纹盐值：auto = 首次启动自动生成（改了会让所有已采集指纹失效）
salt: "auto"

# 反作弊联动
anticheat-sync:
  enabled: true
  only-console: true      # 只认控制台/管理员执行的命令（防玩家乱敲造成误封）

# 外挂客户端拦截（三道防线）
client-guard:
  enabled: true
  action: kick            # kick=踢出 / alert=仅提醒 / none=不处理
  blocked-brands: [...]   # ① 品牌黑名单（对所有客户端生效）
  blocked-mods: [...]     # ② 模组黑名单（需玩家装配套模组）
  require-mod: false      # ③ 强制装模组（默认关，最强防护）

# 原生封禁联动
sync-vanilla-ban: true
sync-vanilla-unban: true

# 严格模式：一台机器绑定首个账号（网吧会误伤，默认关）
strict-mode: false
```

升级时插件会**自动补齐缺失的配置项**，已有自定义与盐值不受影响。

</details>

---

## 📂 项目结构

```
├─ BlazeHwidBan/          服务端插件源码（Maven）
│  └─ src/main/java/cn/blaze/hwidban/
│     ├─ HwidBanPlugin         主类
│     ├─ command/              指令实现
│     ├─ hwid/                 封禁库与档案
│     ├─ fingerprint/          客户端指纹
│     ├─ listener/             进服/消息/客户端拦截/原生联动
│     └─ util/                 哈希与文案
├─ BlazeHwidMod/          客户端模组源码（Gradle + Fabric Loom）
├─ 发布/                   成品 jar 与完整文档
│  ├─ 先看我-文件说明.txt        每个 jar 放哪、干什么
│  ├─ 更新与补丁报告.txt         版本历史
│  └─ 项目报告.md               完整技术报告
└─ README.md
```

<details>
<summary><b>点击展开：自行构建</b></summary>

<br>

**服务端插件**（JDK 21 或 25 + Maven）

```bash
cd BlazeHwidBan
mvn clean package
# 产物：target/BlazeHwidBan-1.0.0.jar
```

**客户端模组**（JDK 21+）

```bash
cd BlazeHwidMod
./gradlew build
# 产物：build/libs/blazehwid-1.0.0.jar
```

</details>

---

## ❓ 常见问题

<details>
<summary><b>玩家不装模组能被封吗？</b></summary>
<br>
能。不装模组的玩家用"客户端指纹"识别（弱标识，但足以拦住多数换号行为）；装了模组则是"真实机器码"（强标识，换号无效）。
</details>

<details>
<summary><b>会误封网吧 / 共用电脑吗？</b></summary>
<br>
默认不会。只有开启 <code>strict-mode</code>（一台机器绑定首个账号）才可能，该模式默认关闭。
</details>

<details>
<summary><b>模组会泄露我的硬件信息吗？</b></summary>
<br>
不会。模组只在本机计算 SHA-256 哈希，服务器收到的是一串哈希值，原始硬件信息不出你的电脑。源码完全公开，可自行审查。
</details>

<details>
<summary><b>和反作弊插件冲突吗？</b></summary>
<br>
不冲突。反作弊负责检测，本插件负责把封禁落到设备层，两者互补。
</details>

<details>
<summary><b>封禁数据存在哪里？</b></summary>
<br>
<code>plugins/BlazeHwidBan/</code> 下：<code>bans.json</code>（封禁库）、<code>profiles.json</code>（玩家档案）、<code>bans.log</code>（审计日志）。
</details>

---

## ⚠️ 诚实说明

- **客户端上报内容理论上可被篡改** —— 品牌与模组检查是提高门槛，不是绝对防线；配合反作弊行为检测才是完整纵深。
- **部分外挂端不改品牌标识** —— 此时靠模组黑名单与机器码兜底。
- **指纹是弱标识** —— 玩家修改客户端设置会变化，仅用于原版玩家兜底。

---

<div align="center">

**如果这个项目帮到了你的服务器，欢迎点个 ⭐ Star**

[⬇ 下载最新版](https://github.com/XiaoYuan08-Mc/BlazeHwidBan/releases)　·　[报告问题](https://github.com/XiaoYuan08-Mc/BlazeHwidBan/issues)　·　[MIT License](LICENSE)

<sub>BlazeHwidBan · Paper 服务端插件 + Fabric 客户端模组 · v1.0.0</sub>

</div>
