# Tidal Echo · 前端 PWA 部署文档

手机上那一端——把它「添加到主屏幕」就是一个独立的私密聊天 App，和你电脑上的 AI 伴侣（Claude Code + channel 插件）通过你自己的 relay 后端对话。

> 这是从一对 AI 伴侣的自用系统里抽出来、**彻底脱敏**的可复用前端。没有任何域名、密钥、私人称呼写死在代码里——名字走 `CONFIG`，地址走相对路径 + 你的 nginx 前缀。

纯静态：一个 `index.html`（自带全部 CSS/JS，无构建步骤）、一个 `sw.js`、一个 `manifest.webmanifest`、几张主题图。丢进 nginx 就能跑。

---

## 0. 前置

先把 **后端** 跑起来（见 `../backend/DEPLOY.md`）：relay 在 `https://你的域名/relay` 应答、冒烟测试通过。前端只是它的客户端。

> **HTTPS 必须**：PWA 安装、Service Worker、Web Push 三者都强制要求 https。

---

## 1. 放文件

把本目录（`web/`）整个部署到你 nginx 指向的静态目录，例如 `/var/www/companion-web/`：

```bash
rsync -av web/  root@你的VPS:/var/www/companion-web/
```

nginx 把它挂在 `/chat/`（见 `../backend/nginx-companion.conf.example` 里的 `location /chat/`）。改静态文件**即时生效、无需重启**。

对外地址：`https://你的域名/chat/` → 在手机浏览器打开 → 分享菜单「添加到主屏幕」。

---

## 2. 配置（改一处就够）

打开 `index.html`，顶部 `<script>` 里有**唯一的配置块**：

```js
const CONFIG = {
  APP_NAME:   "Tidal Echo",  // App / 菜单标题（manifest 里也改一下，见下）
  AI_NAME:    "Claude",       // 你 AI 伴侣的显示名（顶栏 / 通话 / 推送 / 旁白）
  HUMAN_NAME: "你",           // 旁白里怎么称呼你（很少露出）
  SINCE:      "2026/01/01",   // 菜单页「在一起多少天」的起点 YYYY/MM/DD（留空 "" 则隐藏计数）
};
```

- `AI_NAME` 建议和后端 `relay.env` 的 `RELAY_AI_NAME` 一致（推送标题由后端发，用的是后端那个名字）。
- 顶栏名字用户还能在「个人信息」页就地改备注，存本机，不影响别人。
- 想换 App 名字：同时改 `manifest.webmanifest` 的 `name` / `short_name`，和 `sw.js` 顶部的 `AI_NAME`（推送兜底标题）。

**路径**：`const API_BASE = "/relay"` —— 同源相对路径，对应后端 `RELAY_PUBLIC_PREFIX`。若你把 API 挂在别的前缀，改这一处即可（别写死域名）。

**头像 / 图标**：`avatar-sea.png` 是默认头像（抽象海面，用户可在 App 里自己换）；`icon-192/512.png`、`apple-touch-icon.png`、`favicon.png` 是占位「潮汐」图标，换成你自己的同名文件即可。

---

## 3. 它和后端的契约（新前端必须对齐这些）

| 方向 / 事件 | 约定 |
|---|---|
| 消息方向 | `from: "human" \| "ai"`（人类 / AI） |
| 戳一戳事件 | `{ type:"reaction", id, reactions:{ai:"❤️"}, by:"ai" }` |
| API 基址 | `/relay/`（后端 `RELAY_PUBLIC_PREFIX`） |
| PWA 静态 | `/chat/`（后端 `RELAY_APP_PATH`，推送点开就回到这里） |
| 历史 / 实时 / 发送 | `GET /app/history` · `SSE /app/stream` · `POST /app/send` |
| 在线心跳 | `POST /app/ping`（前台每 60s） |
| 推送 | 公钥 `GET /app/vapid_public` · 订阅 `POST /app/subscribe` / `/app/unsubscribe` |
| 语音 / 通话 / TTS | `POST /app/voice` · `/app/call` · `/app/tts`（后端没配则自动降级） |
| 鉴权 | 每个请求带 `Authorization: Bearer <RELAY_SECRET>`；SSE 用 `?token=`（浏览器 EventSource 设不了头） |

登录页输入的「连接密钥」= 后端 `RELAY_SECRET`，存在本机 `localStorage`（key 全部以 `companion_` 开头）。

---

## 4. 主题

两套，存 `localStorage.companion_theme`，在「个人信息 → 主题」切换：

- **珍珠**（`light`，默认）—— 暖白珍珠潮汐
- **海港**（`harbor`）—— 中性冷调，适合做基调再改色

要加新主题：在 `<style>` 里照 `:root[data-theme="harbor"]{…}` 复制一段、给 `THEMES`/`WALLS` 加一项、菜单里加个按钮、配一张 `chat-xxx.webp` + `menu-xxx.webp` 壁纸即可。

---

## 5. Service Worker / 缓存（**改前端必读**）

`sw.js` 顶部：

```js
const CACHE = "companion-v1";   // ← 每次改前端都要 bump（v1 → v2 → …）
```

- 策略：导航请求 **network-first**（在线刷新即拿最新 `index.html`），其余同源 GET **cache-first**，`/relay/` 全程不拦截。
- **不 bump 版本号，已装到主屏的用户会一直停在旧壳**（预缓存的旧 `index.html` 不更新）。bump → SW 重装 → 重新 precache → activate 清旧 cache。
- 验证：在线刷新一次即为新版（SW 后台换代）；偶尔需刷第二次让 SW 完全切过去。

---

## 6. 锁屏推送（可选）

后端配好 VAPID 后（见 `../backend/DEPLOY.md` §4），在「个人信息 → 锁屏通知」开启：

- iOS 只在「添加到主屏」的 standalone PWA 里能收推送，普通 Safari 标签页不行（App 内已有提示）。
- 逻辑：只有 PWA 不在前台时，AI 的回复才推一条锁屏通知；前台开着就不打扰。

---

## 7. 占位 / 空壳说明（开源版有意留白）

为匹配「核心聊天」版后端，这些是**占位假按键**，点了只给个 toast 或显示示例数据，不连后端——保留是为了让你照着接自己的功能：

- 菜单页：Movie / Memory / Tides / Room
- 设置页：模型选择 / effort / forge 阈值 / reset / swap / status（显示示例数字）
- **相册** `album.html`：完整的相册 UI 空壳，文件头注明了要自己实现的 `/relay/app/album/*` 端点；没接后端时显示密钥门 / 空态。

核心聊天（文字 / 图片 / 语音 / 通话 / 戳一戳 / 推送）是真接后端的。

---

## 8. 安全

- 别把填好的密钥、你的域名、真实头像/图标提交进公开仓库。
- `companion_secret` 存在用户浏览器本机；它等于后端 `RELAY_SECRET`，泄露=别人能读你们全部对话。这是单用户模型，一把钥匙代表「就你和你的 AI」。
