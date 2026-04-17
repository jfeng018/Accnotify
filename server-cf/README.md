# Accnotify - Cloudflare Workers 服务端

基于 Cloudflare Workers + D1 的 Accnotify 服务端，可替代自托管的 Go 服务器。完全运行在 Cloudflare 边缘网络，无需购买 VPS。

## 功能特性

- REST API 推送通知
- Bark 兼容的简单推送（`GET /push/:key/:title/:body`）
- 基于 Durable Objects 的 WebSocket 实时推送
- 端对端加密（RSA-OAEP + AES-256-GCM，使用 Web Crypto API）
- Webhook 支持：GitHub、GitLab、Docker Hub、Gitea、通用
- D1（基于 SQLite）持久化存储
- CORS 跨域支持

## 前提条件

- [Node.js](https://nodejs.org/) >= 18
- [Wrangler CLI](https://developers.cloudflare.com/workers/wrangler/)（Cloudflare 官方命令行工具）
- 一个 Cloudflare 账号（免费即可，Workers 和 D1 均有免费额度）

## 部署步骤

### 第一步：安装依赖

```bash
cd server-cf
npm install
```

同时全局安装 Wrangler（如果尚未安装）：

```bash
npm install -g wrangler
```

### 第二步：登录 Cloudflare

```bash
wrangler login
```

执行后会自动打开浏览器，授权 Wrangler 访问你的 Cloudflare 账号。

### 第三步：创建 D1 数据库

```bash
wrangler d1 create accnotify-db
```

命令执行后会输出类似以下内容：

```
✅ Successfully created DB 'accnotify-db' in region APAC
Created your new D1 database.

[[d1_databases]]
binding = "DB"
database_name = "accnotify-db"
database_id = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
```

将输出的 `database_id` 填入 `wrangler.toml` 中：

```toml
[[d1_databases]]
binding = "DB"
database_name = "accnotify-db"
database_id = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"  # 替换为你的真实 ID
```

### 第四步：初始化数据库表结构

```bash
# 初始化远端生产数据库
npm run db:init

# 初始化本地开发数据库（本地测试时使用）
npm run db:init:local
```

### 第五步：部署

```bash
# 本地开发调试（访问 http://localhost:8787）
npm run dev

# 部署到 Cloudflare 生产环境
npm run deploy
```

部署成功后会输出你的 Worker 地址，例如：

```
https://accnotify.your-subdomain.workers.dev
```

将此地址填入 Android 客户端的「服务器地址」设置中即可。

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/health` | 健康检查 |
| `POST` | `/register` | 注册新设备 |
| `POST` | `/push/:device_key` | 推送通知（JSON 或表单） |
| `GET` | `/push/:device_key/:title/:body` | 简单推送（Bark 兼容） |
| `POST` | `/webhook/:device_key` | 通用 Webhook |
| `POST` | `/webhook/:device_key/github` | GitHub Webhook |
| `POST` | `/webhook/:device_key/gitlab` | GitLab Webhook |
| `POST` | `/webhook/:device_key/docker` | Docker Hub Webhook |
| `POST` | `/webhook/:device_key/gitea` | Gitea Webhook |
| `GET` | `/ws?device_key=...` | WebSocket 连接 |
| `GET` | `/messages/:device_key` | 获取设备消息列表 |
| `DELETE` | `/messages/:device_key/:message_id` | 删除指定消息 |

## 推送通知请求体（POST /push/:device_key）

```json
{
  "title": "通知标题",
  "body": "通知正文内容",
  "image": "https://example.com/image.png",
  "group": "分组名称",
  "icon": "图标名称",
  "url": "https://example.com",
  "sound": "default",
  "badge": 1
}
```

> `image` 字段也支持 Base64 编码的图片数据（`data:image/png;base64,...`）。

## 设备注册（POST /register）

请求体：

```json
{
  "name": "我的手机",
  "public_key": "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----"
}
```

响应：

```json
{
  "code": 200,
  "message": "ok",
  "data": { "device_key": "生成的设备密钥" }
}
```

## WebSocket

连接地址：`wss://accnotify.your-subdomain.workers.dev/ws?device_key=YOUR_DEVICE_KEY`

服务端推送的消息格式：

```json
{
  "type": "message",
  "data": {
    "message_id": "...",
    "title": "...",
    "body": "..."
  }
}
```

客户端发送以下内容保持连接活跃：

```json
{"type": "ping"}
```

## 端对端加密

在 `wrangler.toml` 中设置 `ENABLE_E2E = "true"` 可启用服务端加密。启用后，如果设备注册时提供了公钥，服务端会在存储前使用 RSA-OAEP + AES-256-GCM 加密 `title` 和 `body` 字段。

## 环境变量

在 `wrangler.toml` 的 `[vars]` 段中可配置：

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `ENABLE_E2E` | `"false"` | 是否启用服务端端对端加密 |

## 常见问题

**Q: 免费额度够用吗？**  
Workers 每天有 10 万次免费请求，D1 每天有 500 万次读、10 万次写的免费额度，个人使用完全够用。

**Q: 部署后 Android 客户端填什么地址？**  
填写 `https://accnotify.your-subdomain.workers.dev`，不带末尾斜杠。

**Q: 如何查看日志？**  
```bash
wrangler tail
```
实时查看 Worker 的请求日志和错误输出。

**Q: 如何更新部署？**  
修改代码后重新执行 `npm run deploy` 即可，Cloudflare 会自动热更新，不存在停机时间。
