# Aestus Specula · 掌上 AI 伴侣

> **基于 [Tidal Echo](https://github.com/anhe2021212-spec/Tidal_Echo) 的 PWA 前端**，将后端重写为纯 Android 原生应用。
> 所有逻辑跑在手机上，无需 VPS，无需 PC，无需 HTTPS。

一个 Android APK = 聊天 UI + LLM 引擎 + 工具调用 + 后台服务。打开即用，插电即走。

---

## 🧭 定位

### 与上游 Tidal Echo 的关系

| | Tidal Echo (上游) | Aestus Specula (本 fork) |
|---|---|---|
| 前端 | ✅ 同一套 PWA | ✅ 复用同一套 PWA |
| 后端 | FastAPI + VPS（需 Python/nginx/HTTPS） | ❌ 不需要 |
| AI 大脑 | Claude Code channel 插件 / bridge 脚本 | ❌ 不需要 |
| 运行设备 | 手机 + VPS + PC 三件套 | 📱 **一部手机搞定** |
| 部署难度 | 需要 Linux VPS + 域名 + 证书 | 📲 **装 APK 即用** |
| 消息推送 | Web Push（需 VAPID 密钥） | ✅ ForegroundService 原生保活 |

### 核心功能

- 📱 **PWA 聊天界面**（Tidal Echo 前端，完整 Markdown / 图片 / 语音 / 深色模式）
- 🧠 **LLM 引擎**（调用 DeepSeek / OpenAI / 任何兼容 API）—— 你的 AI 伴侣就在手机里
- 🤖 **Proactive Agent**—— AI 可在你设定的间隔**主动发消息**，无需你提问
- 🔌 **工具调用**—— 模型能读步数、使用统计等手机数据，后续可扩展
- 📦 **纯单机**—— 一切存在手机 SQLite，不走外部服务器

---

## 🏗️ 架构

```
┌──────────────────────────────────────────────┐
│  Android APK                                  │
│                                               │
│  ┌───────────────────────────┐                │
│  │  WebView                  │               │
│  │  (Tidal Echo PWA)         │               │
│  └────────┬──────────────────┘                │
│           │ HTTP/SSE (127.0.0.1)              │
│  ┌────────▼──────────────────┐                │
│  │  Ktor Relay Server        │               │
│  │  (嵌入式，只绑 localhost)  │                │
│  │  ├ 提供 PWA 静态文件  │               │
│  │  ├ /app/history · /app/stream · /app/send  │
│  │  └ SQLite 消息存储 │               │
│  └───────────────────────────┘                │
│           ↕ 直接调用                           │
│  ┌───────────────────────────┐                │
│  │  Engine (Agent 循环)      │               │
│  │  ├ 监听新消息 → 调 LLM    │               │
│  │  ├ 定时主动决策           │               │
│  │  ├ 工具调用 (步数/搜索/记忆) │             │
│  │  └ 回复推送回 PWA         │               │
│  └───────────────────────────┘                │
└──────────────────────────────────────────────┘
```

### 技术栈

| 组件 | 选型 |
|---|---|
| UI 壳 | Jetpack Compose（设置页）+ WebView（PWA 聊天） |
| 嵌入式服务器 | Ktor (Netty) |
| 消息存储 | SQLite |
| 配置 | DataStore |
| LLM 调用 | OkHttp → OpenAI 兼容 API |
| 后台保活 | ForegroundService |
| 前端 PWA | [Tidal Echo](https://github.com/anhe2021212-spec/Tidal_Echo) (HTML/CSS/JS) |

---

## 🚀 快速开始

### 开发

用 Android Studio 打开本项目，Sync Gradle，Run。

首次启动会显示**设置页**，填入：

| 字段 | 说明 |
|------|------|
| Relay Secret | PWA 登录密钥（随便设） |
| LLM API Key | DeepSeek / OpenAI 等 API key |
| LLM API Base URL | 例如 `https://api.deepseek.com/v1` |
| Model | 例如 `deepseek-v4-flash` |
| AI 名称 / 你的名称 | 对话中显示的名字 |
| 主动间隔（秒） | 多少秒后 AI 可主动发消息 |

点「保存并启动」→ 后台服务运行 → 点「打开聊天」→ WebView 加载 PWA → 输入 Relay Secret 登录 → 开始聊天。

### 小技巧

开发时可以在电脑浏览器直接测 PWA + API（不需要每次 Build APK）：
- 启动 App 的 Relay 后，在 PC 浏览器打开 `http://手机IP:9199`
- PWA 会自动请求 relay API，所见即所得

---

## 🗺️ 开发路线

### Phase 0 · 核心打磨

- [x] 项目骨架 + Gradle 构建通过
- [x] Ktor Relay（静态文件 / API / SSE）
- [x] PWA 聊天 UI 嵌入
- [x] Engine 监听 + 调 LLM + 回复
- [x] Eninge 改用 channelFlow 实时监听（不再轮询 DB）
- [ ] **Agent Loop (tool calling)** — 模型可调用工具（记忆/搜索/手机数据），形成完整 agent 循环
- [ ] **多会话体系** — conversations 表 + 会话列表 + 切换
- [ ] **智能体(Agent)系统** — 角色卡 + 独立 prompt + 头像
- [ ] **Web 开发模式** — PC 浏览器直连 relay API，不用 Build APK

### Phase 1 · 日常可用

- [ ] **记忆系统** — AI 自动摘要 + 记忆 tool（模型自己存/取）+ 上下文注入
- [ ] **Engine 状态面板** — 决策日志 / reasoning 展示 / 手动触发
- [ ] **图片上传 + 多模态** — PWA 发图，多模态模型能看懂

### Phase 2 · 进阶能力

- [ ] **搜索集成** — Tavily / Exa，模型需要最新知识时可搜
- [ ] **锁屏推送** — AI 主动发消息时通知用户
- [ ] **多 LLM 提供者切换** — 下拉选 DeepSeek / OpenAI / Gemini
- [ ] **设置 UI 美化** — 分组卡片 + 连接状态

### Phase 3 · 分发准备

- [ ] **数据导入导出** — SQLite 备份 / 角色卡分享
- [ ] **ProGuard 混淆** / **崩溃上报**
- [ ] **应用图标 / 名称**

---

## 📄 License

[MIT](LICENSE)

- PWA 前端源自 **[Tidal Echo](https://github.com/anhe2021212-spec/Tidal_Echo)**（MIT License），感谢原作者
- Android 后端代码为独立实现，同样以 MIT 协议开源
