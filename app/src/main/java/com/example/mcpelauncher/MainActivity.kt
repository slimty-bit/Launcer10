package com.example.mcpelauncher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val MANIFEST_URL = "https://githubusercontent.com"
    private val INSTALL_ACTION = "com.example.mcpelauncher.INSTALL_COMPLETE"

    data class RemoteVersion(
        val version: String,
        val title: String,
        val apkUrl: String,
        val arch: String
    )

    private lateinit var tvDeviceArch: TextView
    private lateinit var tvVersionInfo: TextView
    private lateinit var tvDownloadProgress: TextView
    private lateinit var progressBarDownload: ProgressBar
    private lateinit var spinnerInstalled: Spinner
    private lateinit var spinnerRemoteVersions: Spinner
    private lateinit var btnUninstallSelected: Button
    private lateinit var btnDownloadAndInstall: Button
    private lateinit var btnClearCache: Button
    private lateinit var btnPlay: Button

    private val installedPackages = mutableListOf<Pair<String, String>>()
    private val remoteVersions = mutableListOf<RemoteVersion>()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isDownloading = false

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (confirmIntent != null) startActivity(confirmIntent)
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    Toast.makeText(context, "Версия успешно установлена!", Toast.LENGTH_SHORT).show()
                    scanInstalledMinecraftClients()
                }
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Отменено или конфликт версий"
                    Toast.makeText(context, "Установка не завершена: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLauncherUI())

        val filter = IntentFilter(INSTALL_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(installReceiver, filter)
        }

        checkDeviceArchitecture()
        loadRemoteVersionList()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        scanInstalledMinecraftClients()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(installReceiver)
    }

    private fun buildLauncherUI(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }

        val title = TextView(this).apply {
            text = "MCPE LAUNCHER"
            setTextColor(Color.WHITE)
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(title)

        tvDeviceArch = TextView(this).apply {
            text = "CPU: проверка..."
            setTextColor(Color.parseColor("#888888"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(20))
        }
        root.addView(tvDeviceArch)

        root.addView(makeSectionLabel("УСТАНОВЛЕНО НА ТЕЛЕФОНЕ:"))
        spinnerInstalled = makeStyledSpinner()
        root.addView(spinnerInstalled)

        btnUninstallSelected = makeStyledButton("Удалить выбранную версию (для отката)", "#222222", "#FF6B6B", 42)
        root.addView(btnUninstallSelected)

        val catalogLabel = makeSectionLabel("СКАЧАТЬ ВЕРСИЮ (1.12 — 1.21+):")
        catalogLabel.setPadding(0, dp(18), 0, dp(6))
        root.addView(catalogLabel)

        spinnerRemoteVersions = makeStyledSpinner()
        root.addView(spinnerRemoteVersions)

        tvVersionInfo = TextView(this).apply {
            text = "Загрузка списка версий..."
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(12))
        }
        root.addView(tvVersionInfo)

        progressBarDownload = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14))
        }
        root.addView(progressBarDownload)

        tvDownloadProgress = TextView(this).apply {
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 12f
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(10))
        }
        root.addView(tvDownloadProgress)

        btnDownloadAndInstall = makeStyledButton("Скачать и установить", "#2E5B88", "#FFFFFF", 52)
        root.addView(btnDownloadAndInstall)

        btnClearCache = makeStyledButton("Очистить скачанные APK", "#1E1E1E", "#AAAAAA", 40)
        root.addView(btnClearCache)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(spacer)

        btnPlay = makeStyledButton("ИГРАТЬ", "#3C8527", "#FFFFFF", 66).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(btnPlay)

        return root
    }

    private fun setupListeners() {
        spinnerRemoteVersions.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateSelectedVersionUi()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnDownloadAndInstall.setOnClickListener {
            if (!isDownloading && remoteVersions.isNotEmpty()) {
                checkInstallPermissionAndStart()
            }
        }

        btnClearCache.setOnClickListener {
            val dir = File(filesDir, "versions")
            if (dir.exists()) {
                dir.deleteRecursively()
                Toast.makeText(this, "Кэш очищен", Toast.LENGTH_SHORT).show()
                updateSelectedVersionUi()
            }
        }

        btnUninstallSelected.setOnClickListener {
            if (installedPackages.isNotEmpty()) {
                val pkg = installedPackages[spinnerInstalled.selectedItemPosition].second
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
            }
        }

        btnPlay.setOnClickListener {
            if (installedPackages.isNotEmpty()) {
                val pkg = installedPackages[spinnerInstalled.selectedItemPosition].second
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) startActivity(intent)
            }
        }
    }

    private fun checkDeviceArchitecture() {
        val abis = Build.SUPPORTED_ABIS.joinToString(", ")
        val has32Bit = Build.SUPPORTED_32_BIT_ABIS.isNotEmpty()
        tvDeviceArch.text = if (has32Bit) "CPU: $abis (32-bit и 64-bit)" else "CPU: $abis (Только 64-bit)"
    }

    private fun loadRemoteVersionList() {
        executor.execute {
            try {
                val conn = URL(MANIFEST_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val jsonArray = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })

                val loaded = mutableListOf<RemoteVersion>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    loaded.add(
                        RemoteVersion(
                            version = obj.getString("version"),
                            title = obj.getString("title"),
                            apkUrl = obj.getString("url"),
                            arch = obj.optString("arch", "Universal")
                        )
                    )
                }
                mainHandler.post {
                    remoteVersions.clear()
                    remoteVersions.addAll(loaded)
                    private fun scanInstalledMinecraftClients() {
installedPackages.clear()
val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
packageManager.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
} else {
@Suppress("DEPRECATION")
packageManager.queryIntentActivities(mainIntent, 0)
}
for (info in apps) {
val pkg = info.activityInfo.packageName
if (pkg.contains("minecraftpe", ignoreCase = true) || pkg.contains("mojang", ignoreCase = true)) {
val pInfo = packageManager.getPackageInfo(pkg, 0)
installedPackages.add(Pair("Minecraft ${pInfo.versionName} ($pkg)", pkg))
}
}
if (installedPackages.isEmpty()) {
spinnerInstalled.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Нет установленных версий"))
btnPlay.isEnabled = false
btnPlay.alpha = 0.5f
btnUninstallSelected.visibility = View.GONE
} else {
spinnerInstalled.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, installedPackages.map { it.first })
btnPlay.isEnabled = true
btnPlay.alpha = 1.0f
btnUninstallSelected.visibility = View.VISIBLE
}
}
private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
private fun makeSectionLabel(textStr: String): TextView = TextView(this).apply {
text = textStr
setTextColor(Color.parseColor("#AAAAAA"))
textSize = 12f
setTypeface(null, Typeface.BOLD)
setPadding(0, 0, 0, dp(6))
}
private fun makeStyledSpinner(): Spinner = Spinner(this).apply {
background = GradientDrawable().apply {
setColor(Color.parseColor("#252525"))
cornerRadius = dp(8).toFloat()
}
setPadding(dp(10), 0, dp(10), 0)
layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
bottomMargin = dp(8)
}
}
private fun makeStyledButton(label: String, bgColor: String, textColor: String, heightDp: Int): Button = Button(this).apply {
text = label
setTextColor(Color.parseColor(textColor))
isAllCaps = false
background = GradientDrawable().apply {
setColor(Color.parseColor(bgColor))
cornerRadius = dp(10).toFloat()
}
layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)).apply {
bottomMargin = dp(8)
}
}
                }
