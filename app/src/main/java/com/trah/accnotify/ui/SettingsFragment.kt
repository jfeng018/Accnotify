package com.trah.accnotify.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.trah.accnotify.AccnotifyApp
import com.trah.accnotify.BuildConfig
import com.trah.accnotify.R
import com.trah.accnotify.databinding.FragmentSettingsBinding
import com.trah.accnotify.databinding.ItemServerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val app by lazy { AccnotifyApp.getInstance() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupServerList()
        setupActions()
        setupDisplayModeSelector()
        setupThemeColorPicker()
        setupForegroundNotificationSwitch()
        setupVersionInfo()
    }

    private fun setupVersionInfo() {
        // Set current version
        binding.tvVersion.text = "当前版本 ${BuildConfig.VERSION_NAME}"

        // Check for update click
        binding.btnCheckUpdate.setOnClickListener {
            checkForUpdate()
        }
    }

    private fun checkForUpdate() {
        binding.btnCheckUpdate.isEnabled = false
        val originalText = binding.tvVersion.text
        binding.tvVersion.text = "正在检查更新..."

        lifecycleScope.launch {
            try {
                val latestVersion = fetchLatestVersion()
                val currentVersion = BuildConfig.VERSION_NAME

                withContext(Dispatchers.Main) {
                    binding.btnCheckUpdate.isEnabled = true

                    if (latestVersion != null) {
                        if (compareVersions(latestVersion, currentVersion) > 0) {
                            // New version available
                            binding.tvVersion.text = "发现新版本 $latestVersion"
                            showUpdateDialog(latestVersion)
                        } else {
                            // Up to date
                            binding.tvVersion.text = "已是最新版本 ($currentVersion)"
                            showCleanDialog(
                                title = "检查更新",
                                message = "当前已是最新版本 ($currentVersion)",
                                positiveText = "好的",
                                negativeText = null
                            )
                        }
                    } else {
                        // Failed to check
                        binding.tvVersion.text = originalText
                        showCleanDialog(
                            title = "检查更新",
                            message = "检查更新失败，请稍后再试",
                            positiveText = "好的",
                            negativeText = null
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnCheckUpdate.isEnabled = true
                    binding.tvVersion.text = originalText
                    showCleanDialog(
                        title = "检查更新",
                        message = "检查更新失败: ${e.message}",
                        positiveText = "好的",
                        negativeText = null
                    )
                }
            }
        }
    }

    private suspend fun fetchLatestVersion(): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/trah01/Accnotify/releases/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                // Parse tag_name from JSON response
                val tagNameRegex = """"tag_name"\s*:\s*"([^"]+)"""".toRegex()
                val match = tagNameRegex.find(response)
                match?.groupValues?.get(1)?.removePrefix("v")
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun compareVersions(version1: String, version2: String): Int {
        val v1 = version1.split(".").map { it.toIntOrNull() ?: 0 }
        val v2 = version2.split(".").map { it.toIntOrNull() ?: 0 }

        val maxLength = maxOf(v1.size, v2.size)
        for (i in 0 until maxLength) {
            val num1 = v1.getOrElse(i) { 0 }
            val num2 = v2.getOrElse(i) { 0 }
            if (num1 > num2) return 1
            if (num1 < num2) return -1
        }
        return 0
    }

    private fun showUpdateDialog(latestVersion: String) {
        showCleanDialog(
            title = "发现新版本",
            message = "当前版本: ${BuildConfig.VERSION_NAME}\n最新版本: $latestVersion\n\n是否前往 GitHub 下载？",
            positiveText = "前往下载",
            onPositive = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/trah01/Accnotify/releases"))
                startActivity(intent)
            }
        )
    }

    private fun setupServerList() {
        binding.serverListContainer.removeAllViews()
        val keyManager = app.keyManager
        val servers = keyManager.getServers()
        val currentUrl = keyManager.serverUrl

        servers.forEach { url ->
            val itemBinding = ItemServerBinding.inflate(layoutInflater, binding.serverListContainer, false)
            itemBinding.tvServerUrl.text = url

            if (url == currentUrl) {
                itemBinding.ivSelected.visibility = View.VISIBLE
                itemBinding.ivSelected.setImageResource(R.drawable.ic_fluent_globe)
                itemBinding.tvServerUrl.setTextColor(ContextCompat.getColor(requireContext(), R.color.clean_primary))
                itemBinding.tvServerUrl.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                itemBinding.ivSelected.visibility = View.INVISIBLE
                itemBinding.tvServerUrl.setTextColor(ContextCompat.getColor(requireContext(), R.color.clean_text_primary))
                itemBinding.tvServerUrl.setTypeface(null, android.graphics.Typeface.NORMAL)
            }

            // Click to select
            itemBinding.root.setOnClickListener {
                if (!isAdded) return@setOnClickListener
                keyManager.serverUrl = url
                Toast.makeText(context, "已切换服务器", Toast.LENGTH_SHORT).show()
                setupServerList() // Refresh UI
                // Restart WebSocket service to connect to new server (delay to let UI settle)
                view?.postDelayed({ restartWebSocketService() }, 300)
            }

            // Edit
            itemBinding.btnEdit.setOnClickListener {
                showEditServerDialog(url)
            }

            // Delete
            itemBinding.btnDelete.setOnClickListener {
                showCleanDialog(
                    title = "删除服务器",
                    message = "确定要删除 $url 吗？",
                    positiveText = "删除",
                    onPositive = {
                        keyManager.removeServer(url)
                        setupServerList()
                    }
                )
            }

            binding.serverListContainer.addView(itemBinding.root)
        }
    }

    private fun showEditServerDialog(currentUrl: String) {
        val et = EditText(requireContext())
        et.setText(currentUrl)
        et.setSelection(currentUrl.length)
        et.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_clean_input)
        et.setPadding(32, 32, 32, 32)

        showCleanDialog(
            title = "编辑服务器地址",
            message = "请输入新的服务器地址：",
            positiveText = "保存",
            customView = et,
            onPositive = {
                val newUrl = et.text.toString().trim().trimEnd('/')
                if (newUrl.isNotEmpty() && newUrl != currentUrl) {
                    val keyManager = app.keyManager
                    // Remove old URL and add new one
                    keyManager.removeServer(currentUrl)
                    keyManager.addServer(newUrl)
                    // If the edited URL was the current one, switch to new URL
                    if (keyManager.serverUrl == currentUrl) {
                        keyManager.serverUrl = newUrl
                    }
                    setupServerList()
                    Toast.makeText(context, "服务器地址已更新", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun setupActions() {
        binding.btnAddServer.setOnClickListener {
            val et = EditText(requireContext())
            et.setHint("https://xx.com")
            et.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_clean_input)
            et.setPadding(32, 32, 32, 32)

            showCleanDialog(
                title = "添加服务器",
                message = "请输入服务器地址：",
                positiveText = "添加",
                customView = et,
                onPositive = {
                    val newUrl = et.text.toString().trim().trimEnd('/')
                    if (newUrl.isNotEmpty()) {
                        app.keyManager.addServer(newUrl)
                        setupServerList()
                    }
                }
            )
        }

        binding.linkGithub.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/trah01/Accnotify"))
            startActivity(intent)
        }

        binding.btnResetEncryption.setOnClickListener {
            showCleanDialog(
                title = "重置加密密钥",
                message = "这将生成全新的 E2E 公私钥对。\n\n旧消息将无法解密。重置后必须点击首页的\"同步服务器\"。",
                positiveText = "重置",
                onPositive = {
                    app.keyManager.regenerateAllKeys()
                    Toast.makeText(context, "密钥已重置，请务必同步服务器", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun setupForegroundNotificationSwitch() {
        val keyManager = app.keyManager
        binding.switchForegroundNotification.isChecked = keyManager.showForegroundNotification
        binding.switchForegroundNotification.setOnCheckedChangeListener { _, isChecked ->
            keyManager.showForegroundNotification = isChecked
            // Restart service so it picks up the new setting immediately
            view?.postDelayed({ restartWebSocketService() }, 200)
        }
    }

    private fun setupDisplayModeSelector() {
        val currentMode = app.keyManager.themeMode

        // 先清除 listener，防止设置 isChecked 时触发回调
        binding.themeModeGroup.setOnCheckedChangeListener(null)

        when (currentMode) {
            "light" -> binding.radioLightMode.isChecked = true
            "dark" -> binding.radioDarkMode.isChecked = true
            else -> binding.radioFollowSystem.isChecked = true
        }

        binding.themeModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.radioLightMode -> "light"
                R.id.radioDarkMode -> "dark"
                else -> "system"
            }
            if (newMode != app.keyManager.themeMode) {
                app.keyManager.themeMode = newMode
                val mode = when (newMode) {
                    "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                // 清除 listener 防止重建过程中再次触发
                binding.themeModeGroup.setOnCheckedChangeListener(null)
                // post 到下一帧，避免 OPPO/OnePlus ViewMirrorManager 在当前布局过程中冲突
                view?.post {
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
                }
            }
        }
    }

    private fun setupThemeColorPicker() {
        val container = binding.root.findViewById<android.widget.LinearLayout>(R.id.themeColorContainer)
        container.removeAllViews()
        val keyManager = app.keyManager
        val currentTheme = keyManager.themeColor

        // Color definitions: key -> (display color hex, name)
        val colors = listOf(
            "blue" to Pair(0xFF005FB8.toInt(), "蓝色"),
            "green" to Pair(0xFF107C10.toInt(), "绿色"),
            "red" to Pair(0xFFC42B1C.toInt(), "红色"),
            "pink" to Pair(0xFFBF0077.toInt(), "粉色"),
            "white" to Pair(0xFF9E9E9E.toInt(), "浅色"),
            "black" to Pair(0xFF1F2937.toInt(), "深色")
        )

        val density = resources.displayMetrics.density
        val circleSize = (40 * density).toInt()
        val margin = (8 * density).toInt()

        for ((key, pair) in colors) {
            val (color, name) = pair
            val outerLayout = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = margin
                    marginEnd = margin
                }
            }

            // Color circle
            val circleView = View(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(circleSize, circleSize)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(color)
                    if (key == currentTheme) {
                        setStroke((3 * density).toInt(), color)
                    }
                }
            }

            // Checkmark overlay for selected
            val frameLayout = FrameLayout(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(circleSize, circleSize)
            }
            frameLayout.addView(circleView)

            if (key == currentTheme) {
                val checkMark = TextView(requireContext()).apply {
                    text = "✓"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                frameLayout.addView(checkMark)
            }

            // Label
            val label = TextView(requireContext()).apply {
                text = name
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(resources.getColor(R.color.clean_text_secondary, null))
                gravity = android.view.Gravity.CENTER
                setPadding(0, (4 * density).toInt(), 0, 0)
            }

            outerLayout.addView(frameLayout)
            outerLayout.addView(label)

            outerLayout.setOnClickListener {
                if (key != currentTheme) {
                    keyManager.themeColor = key
                    // Recreate activity to apply new theme
                    activity?.recreate()
                    return@setOnClickListener // 主题色切换后立即 return，防止 recreate 后 fragment 操作
                }
            }

            container.addView(outerLayout)
        }
    }

    private fun showCleanDialog(
        title: String,
        message: String,
        positiveText: String = "确定",
        negativeText: String? = "取消",
        customView: View? = null,
        onPositive: (() -> Unit)? = null
    ) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_clean, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val tvMessage = dialogView.findViewById<TextView>(R.id.dialogMessage)
        val btnPositive = dialogView.findViewById<TextView>(R.id.btnPositive)
        val btnNegative = dialogView.findViewById<TextView>(R.id.btnNegative)
        val customContainer = dialogView.findViewById<FrameLayout>(R.id.dialogCustomContainer)

        tvTitle.text = title
        tvMessage.text = message
        btnPositive.text = positiveText

        if (negativeText != null) {
            btnNegative.text = negativeText
            btnNegative.visibility = View.VISIBLE
        } else {
            btnNegative.visibility = View.GONE
        }

        if (customView != null) {
            customContainer.addView(customView)
            customContainer.visibility = View.VISIBLE
        }

        val alertDialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        alertDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnPositive.setOnClickListener {
            onPositive?.invoke()
            alertDialog.dismiss()
        }

        btnNegative.setOnClickListener {
            alertDialog.dismiss()
        }

        alertDialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun restartWebSocketService() {
        val ctx = context ?: return
        if (!isAdded) return
        try {
            val intent = android.content.Intent(ctx, com.trah.accnotify.service.WebSocketService::class.java)
            ctx.stopService(intent)
            com.trah.accnotify.service.WebSocketService.start(ctx)
        } catch (e: Exception) {
            android.util.Log.e("SettingsFragment", "Failed to restart WebSocket service", e)
        }
    }
}

