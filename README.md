# Aestus Specula · 掌上 AI 伴侣

一个 APK = 聊天 + 多会话 + 感知工具 + Proactive Agent。全跑在手机上，零外部服务器。

> 前端 PWA 基于 [Tidal Echo](https://github.com/anhe2021212-spec/Tidal_Echo)（MIT License），通信层改用 Android JS Bridge，砍掉了 Ktor relay 和 SSE。

---

## 架构

```
打开 App → PWA 聊天（WebView）
              ↕ JS Bridge（@JavascriptInterface）
         PwaBridge → SQLite / DataStore / Engine
         Engine → 定时主动循环 + 工具调用（步数/天气/日历/电量）
         ProactiveService → ForegroundService 保活
```

**不需要 Ktor / Netty / SSE / 端口。** 没有网络层在中间，消息直通。

## 功能

| 功能 | 状态 |
|------|------|
| 聊天（发送 / LLM 回复 / 上下文） | ✅ |
| 多会话（新建 / 切换 / 删除） | ✅ |
| 感知工具（步数 / 天气 / 日历 / 电量 / 设备 / 时间） | ✅ |
| Proactive Agent（定时主动 + 手动触发 + 防重入） | ✅ |
| AI 名字动态化 | ✅ |
| 设置面板（LLM / 名字 / 城市 / System Prompt / 间隔） | ✅ |
| 调试日志面板 | ✅ |
| 记忆系统 | 🚧 |
| 智能体角色卡 | 🚧 |
| 搜索 / 推送 / 多 LLM | 🔮 |

## 技术栈

| 层 | 选型 |
|---|------|
| UI | WebView + Tidal Echo PWA（HTML/CSS/JS） |
| 原生桥 | `@JavascriptInterface` JS Bridge |
| 存储 | SQLite + DataStore |
| LLM 调用 | OkHttp → OpenAI 兼容 API |
| 后台 | ForegroundService |
| 感知 | Android SDK（Sensor / Calendar / Battery / OkHttp for weather）|

## 快速开始

用 Android Studio 打开本项目，Sync Gradle，Run。

首次打开 App 直接进入 PWA→ 点头部 ⋮ → 设置 → 填入 LLM API Key → 保存并启动 → 发消息测试。

### 开发

修改 PWA 前端（`web/index.html`）后，运行：

```bash
cd dev   # Python 依赖已装
start_all.bat
```

浏览器打开 `http://localhost:3011` 即可预览 PWA 效果，零编译时间。

## 路线图

- **Phase 0** ✅ 架构：砍 Ktor relay / JS Bridge / Engine 瘦身
- **Phase 1** 🚧 功能：感知工具 ✅ / 多会话 ✅ / 名字动态化 ✅ / 记忆系统 / 智能体角色卡
- **Phase 2** 🔮 进阶：搜索 / 推送 / 多 LLM / UI 美化
- **Phase 3** 🔮 分发：导入导出 / 混淆 / 图标

## License

MIT License。Android 后端代码为独立实现。

### 致谢上游

- **[Tidal Echo](https://github.com/anhe2021212-spec/Tidal_Echo)** (MIT) — PWA 聊天前端
- **[InternalBeyond](https://github.com/Sui-IB/InternalBeyond)** — UI 组件设计参考（glass-card / 会话切换模式 / 调参器灵感）
- **[Ombre Brain](https://github.com/ceshihaox-dotcom/OmbreBrain-folio)** — 记忆系统设计参考（情感坐标 / 权重池 / dream 循环），尚未实现
