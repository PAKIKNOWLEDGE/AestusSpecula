# Aestus Specula

## 设计哲学

### 1. 默认不越界
System prompt 只陈述"我是谁"，不指导"我怎么做"。工具描述保持中性，模型自己决定是否使用。

### 2. 用户说了算
所有文本（system prompt、名字、工具描述）最终都可在 UI 编辑。当前做不到的不紧急，但新建功能时必须预留可编辑接口。

### 3. 工具箱，不是指令集
工具只是摆在桌上的工具，模型自己决定什么时候打开、用不用。

### 4. 本地优先
一切数据存手机本地，不上云。未来可选的云同步遵循"默认本地，用户主动选择"原则。

---

## 技术架构

```
Android APK
├── WebView (Tidal Echo PWA) — 聊天 UI
├── Ktor Relay (localhost:9199) — 消息落库 + SSE 扇出
├── Engine — Agent loop + tool calling + 主动决策
├── McpTools — 手机传感器 / 使用统计
├── MemoryManager — 记忆读写
└── Database — SQLite (messages + memories)
```

### 数据流

```
用户在 PWA 打字 → relay /app/send → SQLite落库(channelFlow)
  → Engine 收到 → 组上下文 → 调 LLM(带 tools)
    → 如需工具 → 执行 → 结果喂回 LLM → 最终回复
  → relay /channel/out → SQLite落库 → SSE推回 PWA
```

---

## 开发指南

### 分支
- 所有开发在 main，直接推送
- 不做 PR 流程，每人 fork 自己改

### 编码规范
- 不放行为引导进 system prompt
- tool description 用名词性短语，不写"获取"、"保存"
- 不删用户数据（只标记 resolved/archived）
- 每个新功能检查：用户能不能在 UI 里改这个？

### 测试
- Android 代码 → Android Studio Build → Run 到手机
- PWA 前端 → 浏览器 http://localhost:3011 (dev/)
- Relay API → curl http://localhost:3011/healthz

---

## 安全须知
- API key 存 DataStore（加密存储）
- Relay Secret 不落日志
- 所有数据只存本机 SQLite
- 无外部服务器依赖（除 LLM API 调用外）
