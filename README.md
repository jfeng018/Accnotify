# Accnotify

Accnotify 是一款面向 Android 的即时通知推送工具，支持 Go 自托管服务端。它兼容 Bark 风格接口，支持 WebSocket 实时推送、Webhook 整形、图片消息、历史记录和端到端加密。

## 核心亮点

- Bark 风格接口，已有脚本可以快速迁移
- Go 服务端稳定、完整、易自托管
- 支持 GitHub、GitLab、Docker Hub、Gitea、飞书、钉钉、企微和通用 Webhook
- 支持文本、链接、分组、角标、图片消息
- 历史消息支持图片缩略图，详情图可长按保存或分享
- 设置页支持主题色选择，以及浅色、暗夜、跟随系统三种显示模式
- 首页集中展示系统权限入口，便于首次配置

## 服务端部署

适合长期稳定使用，功能最完整。

```bash
cd server
docker-compose up -d
```

或手动构建：

```bash
cd server
go mod download
go build -o accnotify-server
./accnotify-server
```

## Android 客户端快速开始

1. 安装 APK 并打开应用
2. 在设置页填写你的服务器地址
3. 回到首页，点击“注册设备 / 同步连接”
4. 在首页“系统权限”中完成以下配置：
   - 辅助功能（保活）
   - 电池优化白名单
   - 通知权限
5. 复制推送地址或设备密钥，开始发送消息

## 推送测试 

可以使用项目的[test-push.html](https://github.com/trah01/Accnotify/blob/main/test-push.html)进行快速测试，验证服务器和客户端，在网页填入服务器地址和key即可

## 推送示例

### 基本文本推送

```bash
curl -X POST "http://your-server:8080/push/your-device-key" \
  -H "Content-Type: application/json" \
  -d '{"title":"系统通知","body":"服务已经恢复正常"}'
```

### 带换行的推送

```bash
curl -X POST "http://your-server:8080/push/your-device-key" \
  -H "Content-Type: application/json" \
  -d '{"title":"监控告警","body":"CPU 超过 90%\n\n当前负载：12.5\n内存使用：87%"}'
```

### 图片推送

支持 Base64 图片字段，历史消息会显示缩略图，点开详情可查看大图并长按保存或分享。

```json
{
  "title": "图片通知",
  "body": "这是一张测试图片",
  "image": "data:image/png;base64,..."
}
```

### GET 请求（Bark 风格）

```bash
curl "http://your-server:8080/push/your-device-key/标题/内容"
```

也支持只传正文：

```bash
curl "http://your-server:8080/push/your-device-key/只有正文"
```

## Webhook 支持

### 通用 Webhook

```text
https://your-server.com/webhook/your-device-key
```

### 已适配的平台

- GitHub
- GitLab
- Docker Hub
- Gitea
- 飞书 / Lark
- 钉钉
- 企业微信
- 其他 JSON 或纯文本请求会走通用解析

Webhook 消息默认展示整理后的内容，详情页底部可以展开查看原始内容。

## 测试页

项目根目录提供了本地测试页面：

- test-push.html

用途：

- 手动输入服务器地址和设备密钥
- 测试普通文本推送
- 测试 JSON / Webhook 格式
- 测试图片上传与图片消息发送

直接用浏览器打开即可，无需额外构建。

## 最近新增功能

- 服务器切换稳定性优化，降低切换时闪退概率
- Webhook 文本中的换行符会自动还原为真实换行
- 历史消息列表支持图片缩略图
- 图片详情支持长按保存和分享
- 设置页新增主题色切换
- 新增浅色、暗夜、跟随系统显示模式
- 首页整合系统权限入口，首次配置更直观

## API 概览

### 注册设备

```text
POST /register
```

请求体示例：

```json
{
  "device_key": "设备唯一标识",
  "public_key": "RSA 公钥（PEM 格式）",
  "name": "设备名称"
}
```

### 发送推送

```text
POST /push/:device_key
```

字段示例：

```json
{
  "title": "通知标题",
  "body": "通知正文",
  "group": "分组名称",
  "url": "点击跳转链接",
  "badge": 1,
  "image": "data:image/png;base64,..."
}
```

## 项目结构

```text
Accnotify/
├── app/                 Android 客户端
├── server/              Go 服务端
└── test-push.html       本地测试页
```

## 注意事项

- 敏感消息建议使用你自己的 Go 服务端
- 重置加密密钥后，旧消息将无法解密
- device_key 是唯一投递凭证，请妥善保管

## 链接

- GitHub：https://github.com/trah01/Accnotify
- Issues：https://github.com/trah01/Accnotify/issues

