package com.trah.accnotify.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.trah.accnotify.AccnotifyApp
import com.trah.accnotify.R
import com.trah.accnotify.databinding.ActivityTutorialBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class TutorialActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTutorialBinding
    private var isGetRequest = false

    private val examples = listOf(
        "选择示例模板...",
        "GET 请求示例",
        "POST 简单消息",
        "POST 带标题消息",
        "POST 多行消息",
        "Webhook 通用",
        "Webhook GitHub",
        "POST 图片推送 (Base64)"
    )

    // Map to track which examples are GET requests
    private val getRequestExamples = setOf("GET 请求示例")
    // Map to track which examples are webhook requests
    private val webhookExamples = mapOf(
        "Webhook 通用" to "",
        "Webhook GitHub" to "/github"
    )

    private fun getExampleMessage(exampleName: String): String {
        val pushUrl = getPushUrl()
        return when (exampleName) {
            "GET 请求示例" -> "${pushUrl}/测试标题/这是一条测试消息"
            "POST 简单消息" -> """{
  "body": "这是一条简单的推送消息"
}"""
            "POST 带标题消息" -> """{
  "title": "服务器告警",
  "body": "CPU 使用率已达 95%"
}"""
            "POST 多行消息" -> """{
  "title": "任务完成",
  "body": "备份任务已完成\n耗时: 5分钟\n大小: 1.2GB"
}"""
            "Webhook 通用" -> """{
  "title": "部署通知",
  "body": "生产环境部署完成\n版本: v2.1.0\n状态: 成功"
}"""
            "Webhook GitHub" -> """{
  "ref": "refs/heads/main",
  "commits": [
    {
      "message": "fix: resolve crash bug",
      "author": { "name": "developer" }
    }
  ],
  "repository": {
    "name": "accnotify",
    "full_name": "user/accnotify",
    "html_url": "https://github.com/user/accnotify"
  },
  "pusher": { "name": "developer" },
  "head_commit": {
    "message": "fix: resolve crash bug",
    "author": { "name": "developer" }
  },
  "compare": "https://github.com/user/accnotify/compare/abc...def"
}"""
            "POST 图片推送 (Base64)" -> run {
                val base64Image = getAppLogoBase64()
                """{
  "title": "图片推送测试",
  "body": "这是一条带图片的推送\n图片为应用 Logo",
  "image": "$base64Image"
}"""
            }
            else -> ""
        }
    }

    /**
     * 将 App logo 转换为 Base64 字符串
     */
    private fun getAppLogoBase64(): String {
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground) ?: return ""
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, 128, 128)
        drawable.draw(canvas)
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme color overlay
        val themeOverlay = when (AccnotifyApp.getInstance().keyManager.themeColor) {
            "blue" -> R.style.ThemeOverlay_Blue
            "green" -> R.style.ThemeOverlay_Green
            "red" -> R.style.ThemeOverlay_Red
            "pink" -> R.style.ThemeOverlay_Pink
            "white" -> R.style.ThemeOverlay_White
            "black" -> R.style.ThemeOverlay_Black
            else -> R.style.ThemeOverlay_Blue
        }
        theme.applyStyle(themeOverlay, true)
        
        super.onCreate(savedInstanceState)
        binding = ActivityTutorialBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupInstructions()
    }


    private fun setupUI() {
        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Spinner setup
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, examples)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerExamples.adapter = adapter

        binding.spinnerExamples.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    val selectedExample = examples[position]
                    isGetRequest = getRequestExamples.contains(selectedExample)
                    val isWebhook = webhookExamples.containsKey(selectedExample)
                    val message = getExampleMessage(selectedExample)
                    binding.etMessage.setText(message)

                    // Update button text based on request type
                    binding.btnSendTest.text = when {
                        isGetRequest -> "发送 GET 请求"
                        isWebhook -> "发送 Webhook"
                        else -> "发送 POST 请求"
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Send test button
        binding.btnSendTest.setOnClickListener {
            sendTestPush()
        }
    }


    private fun getPushUrl(): String {
        val keyManager = AccnotifyApp.getInstance().keyManager
        val serverUrl = keyManager.serverUrl
        val deviceKey = keyManager.getDeviceKey() ?: "YOUR_DEVICE_KEY"
        return "$serverUrl/push/$deviceKey"
    }

    private fun getWebhookUrl(): String {
        val keyManager = AccnotifyApp.getInstance().keyManager
        val serverUrl = keyManager.serverUrl
        val deviceKey = keyManager.getDeviceKey() ?: "YOUR_DEVICE_KEY"
        return "$serverUrl/webhook/$deviceKey"
    }

    private fun getCurrentWebhookSuffix(): String {
        val selected = binding.spinnerExamples.selectedItem?.toString() ?: ""
        return webhookExamples[selected] ?: ""
    }

    private fun setupInstructions() {
        val pushUrl = getPushUrl()
        val webhookUrl = getWebhookUrl()
        
        val markdown = """
## 什么是 Accnotify

Accnotify 是一款基于 **WebSocket** 的安卓推送工具。通过简单的 HTTP 请求，即可向手机发送实时通知。

---

## 快速开始

1. 复制首页的 **Push URL**
2. 向该地址发送 HTTP 请求
3. 手机即时收到推送通知

---

## 请求参数

- `title` - 通知标题（可选）
- `body` - 通知内容（**必填**）
- `image` - 图片数据，支持 URL 或 Base64（可选）

---

## 请求方式

**GET 请求（不加密，仅测试用）**
```
${pushUrl}/标题/内容
```

**POST 请求（RSA 加密）**
```
POST ${pushUrl}
Content-Type: application/json

{"title": "标题", "body": "内容"}
```

**带图片的推送**
```json
{
  "title": "图片通知",
  "body": "正文",
  "image": "<Base64 编码的图片数据>"
}
```

---

## Webhook 推送

支持多个平台的 Webhook，服务端自动解析并格式化为可读通知：

| 平台 | 地址 |
|------|------|
| 通用 | `${webhookUrl}` |
| GitHub | `${webhookUrl}/github` |
| GitLab | `${webhookUrl}/gitlab` |
| Docker Hub | `${webhookUrl}/docker` |
| Gitea | `${webhookUrl}/gitea` |

**GitHub Webhook 配置：**  
在 GitHub 仓库 → Settings → Webhooks → Add webhook  
- Payload URL: `${webhookUrl}/github`  
- Content type: `application/json`  
- 事件：选择 push 或其他需要的事件

---

## 安全说明

- **POST 请求**：RSA 端到端加密
- **GET 请求**：明文传输，仅用于测试
- Push Key 是唯一凭证，请妥善保管
- 重置密钥后，旧链接立即失效

---

## 应用场景

- 服务器监控告警
- CI/CD 构建通知（GitHub / GitLab Webhook）
- 自动化脚本通知
- IoT 设备状态推送
- 定时任务提醒
        """.trimIndent()

        
        val markwon = io.noties.markwon.Markwon.create(this)
        markwon.setMarkdown(binding.tvInstructions, markdown)
    }


    private fun sendTestPush() {
        val message = binding.etMessage.text.toString().trim()
        if (message.isEmpty()) {
            Toast.makeText(this, "请输入消息内容", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedExample = binding.spinnerExamples.selectedItem?.toString() ?: ""
        val isWebhook = webhookExamples.containsKey(selectedExample)

        binding.btnSendTest.isEnabled = false
        binding.btnSendTest.text = "发送中..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val responseCode: Int
                
                if (isGetRequest) {
                    // GET request - message is the full URL
                    val url = URL(message)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    responseCode = connection.responseCode
                    connection.disconnect()
                } else if (isWebhook) {
                    // Webhook request
                    val suffix = getCurrentWebhookSuffix()
                    val url = URL(getWebhookUrl() + suffix)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    // Add event header for specific webhook types
                    if (suffix == "/github") {
                        connection.setRequestProperty("X-GitHub-Event", "push")
                    } else if (suffix == "/gitea") {
                        connection.setRequestProperty("X-Gitea-Event", "push")
                    }
                    connection.doOutput = true
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    connection.outputStream.use { os ->
                        os.write(message.toByteArray(Charsets.UTF_8))
                    }
                    responseCode = connection.responseCode
                    connection.disconnect()
                } else {
                    // POST request - message is JSON body
                    val url = URL(getPushUrl())
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    connection.doOutput = true
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000

                    connection.outputStream.use { os ->
                        os.write(message.toByteArray(Charsets.UTF_8))
                    }
                    responseCode = connection.responseCode
                    connection.disconnect()
                }

                withContext(Dispatchers.Main) {
                    binding.btnSendTest.isEnabled = true
                    binding.btnSendTest.text = when {
                        isGetRequest -> "发送 GET 请求"
                        isWebhook -> "发送 Webhook"
                        else -> "发送 POST 请求"
                    }

                    if (responseCode in 200..299) {
                        Toast.makeText(this@TutorialActivity, "推送成功！请查看通知", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@TutorialActivity, "推送失败: HTTP $responseCode", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnSendTest.isEnabled = true
                    binding.btnSendTest.text = when {
                        isGetRequest -> "发送 GET 请求"
                        isWebhook -> "发送 Webhook"
                        else -> "发送 POST 请求"
                    }
                    Toast.makeText(this@TutorialActivity, "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
