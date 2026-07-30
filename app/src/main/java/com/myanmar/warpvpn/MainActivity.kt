package com.myanmar.warpvpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.wireguard.android.backend.GoBackend
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ConfigModel(
    val id: String,
    val name: String,
    val content: String,
    val endpoint: String,
    var isSelected: Boolean = false
)

class MainActivity : AppCompatActivity() {

    private val VPS_API_URL = "http://104.207.93.17:8000"

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var btnMenu: ImageView
    private lateinit var btnConnectCard: MaterialCardView
    private lateinit var cardServer: MaterialCardView
    private lateinit var tvServerName: TextView
    private lateinit var imgPower: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var cardLogs: MaterialCardView

    private lateinit var btnClearLogs: ImageView
    private lateinit var btnCopyLogs: ImageView

    private lateinit var cardEngineCf: MaterialCardView
    private lateinit var rbEngineCf: RadioButton
    private lateinit var cardEngineCustom: MaterialCardView
    private lateinit var rbEngineCustom: RadioButton

    private lateinit var switchDarkMode: SwitchMaterial
    private lateinit var switchLogs: SwitchMaterial
    private lateinit var switchPing: SwitchMaterial
    private lateinit var rgDns: RadioGroup
    private lateinit var rbDnsDefault: RadioButton
    private lateinit var rbDnsCloudflare: RadioButton
    private lateinit var rbDnsGoogle: RadioButton
    private lateinit var btnRestoreDefaults: MaterialButton
    private lateinit var tvTelegram: TextView

    private var tvDeviceId: TextView? = null
    private var btnCopyDeviceId: ImageView? = null
    private var myDeviceId: String = ""

    private var isConnected = false
    private var pingJob: Job? = null
    private var pendingConfigStr: String? = null

    private val backend by lazy { GoBackend(applicationContext) }
    private val tunnel = WgTunnel()
    private val notificationHelper by lazy { NotificationHelper(this) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            appendLog("Notification Permission Granted!")
        } else {
            appendLog("Notification Permission Denied!")
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            appendLog("VPN Permission Granted!")
            connectVpnWithPendingConfig()
        } else {
            appendLog("VPN Permission Denied!")
            resetUi()
            Toast.makeText(this, "VPN Permission is required!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("DARK_MODE", true)

        if (isDark) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    showExitDialog()
                }
            }
        })

        drawerLayout = findViewById(R.id.drawerLayout)
        btnMenu = findViewById(R.id.btnMenu)
        btnConnectCard = findViewById(R.id.btnConnectCard)
        cardServer = findViewById(R.id.cardServer)
        tvServerName = findViewById(R.id.tvServerName)
        imgPower = findViewById(R.id.imgPower)
        tvStatus = findViewById(R.id.tvStatus)
        tvLogs = findViewById(R.id.tvLogs)
        cardLogs = findViewById(R.id.cardLogs)

        btnClearLogs = findViewById(R.id.btnClearLogs)
        btnCopyLogs = findViewById(R.id.btnCopyLogs)

        cardEngineCf = findViewById(R.id.cardEngineCf)
        rbEngineCf = findViewById(R.id.rbEngineCf)
        cardEngineCustom = findViewById(R.id.cardEngineCustom)
        rbEngineCustom = findViewById(R.id.rbEngineCustom)

        switchDarkMode = findViewById(R.id.switchDarkMode)
        switchLogs = findViewById(R.id.switchLogs)
        switchPing = findViewById(R.id.switchPing)

        rgDns = findViewById(R.id.rgDns)
        rbDnsDefault = findViewById(R.id.rbDnsDefault)
        rbDnsCloudflare = findViewById(R.id.rbDnsCloudflare)
        rbDnsGoogle = findViewById(R.id.rbDnsGoogle)

        btnRestoreDefaults = findViewById(R.id.btnRestoreDefaults)
        tvTelegram = findViewById(R.id.tvTelegram)

        myDeviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        tvDeviceId = findViewById(R.id.tvDeviceId)
        btnCopyDeviceId = findViewById(R.id.btnCopyDeviceId)

        tvDeviceId?.text = myDeviceId

        switchDarkMode.isChecked = isDark
        switchLogs.isChecked = prefs.getBoolean("SHOW_LOGS", true)
        switchPing.isChecked = prefs.getBoolean("AUTO_PING", true)

        val savedEngine = prefs.getString("WARP_ENGINE", "CF_DIRECT")
        setEngineSelectionUI(savedEngine == "CF_DIRECT")

        setupListeners()
        
        when (prefs.getString("DNS_SETTING", "DEFAULT")) {
            "CLOUDFLARE" -> rbDnsCloudflare.isChecked = true
            "GOOGLE" -> rbDnsGoogle.isChecked = true
            else -> rbDnsDefault.isChecked = true
        }

        cardLogs.visibility = if (switchLogs.isChecked) View.VISIBLE else View.GONE
        updateActiveServerName()
        
        appendLog("SN Tulip Vpn App Started")
        appendLog("Device ID: $myDeviceId")
        appendLog("Ready to connect...")
        
        checkNotificationPermission()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    
    private fun setupListeners() {
        btnMenu.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }

        btnConnectCard.setOnClickListener {
            if (!isConnected) prepareAndConnectVpn() else disconnectVpn()
        }

        btnCopyDeviceId?.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Device ID", myDeviceId)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Device ID Copied!", Toast.LENGTH_SHORT).show()
        }

        cardServer.setOnClickListener { showSelectLocationBottomSheet() }

        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("DARK_MODE", isChecked).apply()
            AppCompatDelegate.setDefaultNightMode(if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }

        switchLogs.setOnCheckedChangeListener { _, isChecked ->
            val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("SHOW_LOGS", isChecked).apply()
            cardLogs.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        switchPing.setOnCheckedChangeListener { _, isChecked ->
            val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("AUTO_PING", isChecked).apply()
            if (isConnected) startPingManager()
        }

        rgDns.setOnCheckedChangeListener { _, checkedId ->
            val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
            val dnsType = when (checkedId) {
                R.id.rbDnsCloudflare -> "CLOUDFLARE"
                R.id.rbDnsGoogle -> "GOOGLE"
                else -> "DEFAULT"
            }
            prefs.edit().putString("DNS_SETTING", dnsType).apply()
            appendLog("DNS Mode set to: $dnsType")
        }

        btnClearLogs.setOnClickListener {
            tvLogs.text = "Logs cleared.\n"
            Toast.makeText(this, "Logs Cleared", Toast.LENGTH_SHORT).show()
        }

        btnCopyLogs.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Connection Logs", tvLogs.text.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs Copied to Clipboard!", Toast.LENGTH_SHORT).show()
        }

        cardEngineCf.setOnClickListener {
            getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE).edit().putString("WARP_ENGINE", "CF_DIRECT").apply()
            setEngineSelectionUI(true)
            appendLog("Engine set to Cloudflare Direct API")
        }

        cardEngineCustom.setOnClickListener {
            getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE).edit().putString("WARP_ENGINE", "CUSTOM_API").apply()
            setEngineSelectionUI(false)
            appendLog("Engine set to Custom Backup API")
        }

        btnRestoreDefaults.setOnClickListener { showRestoreDefaultsDialog() }
        tvTelegram.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/premium_channel_404"))) }
    }

    private fun showRestoreDefaultsDialog() {
        val dialog = AlertDialog.Builder(this, R.style.DarkCustomDialog)
            .setTitle("Restore Defaults")
            .setMessage("Are you sure you want to reset all settings, configs, and preferences to default?")
            .setPositiveButton("OK") { d, _ ->
                getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE).edit().clear().apply()
                switchDarkMode.isChecked = true
                switchLogs.isChecked = true
                switchPing.isChecked = true
                rbDnsDefault.isChecked = true
                setEngineSelectionUI(true)
                updateActiveServerName()
                appendLog("Restored all settings and configs to default.")
                Toast.makeText(this, "All settings restored to defaults!", Toast.LENGTH_SHORT).show()
                drawerLayout.closeDrawer(GravityCompat.START)
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                d.dismiss()
            }
            .setNegativeButton("CANCEL") { d, _ -> d.dismiss() }
            .create()
        dialog.show()
    }
    
    private fun showExitDialog() {
        val dialog = AlertDialog.Builder(this, R.style.DarkCustomDialog)
            .setTitle("Exit WARP TUNNEL?")
            .setMessage("Choose whether to minimize to background or exit the app completely.")
            .setPositiveButton("EXIT") { _, _ ->
                if (isConnected) disconnectVpn()
                finishAffinity()
            }
            .setNeutralButton("MINIMIZE") { d, _ -> d.dismiss(); moveTaskToBack(true) }
            .setNegativeButton("CANCEL") { d, _ -> d.dismiss() }
            .create()
        dialog.show()
    }

    private fun setEngineSelectionUI(isCfDirect: Boolean) {
        if (isCfDirect) {
            rbEngineCf.isChecked = true; rbEngineCustom.isChecked = false
            cardEngineCf.strokeColor = Color.parseColor("#38BDF8"); cardEngineCf.strokeWidth = 4
            cardEngineCustom.strokeColor = Color.parseColor("#334155"); cardEngineCustom.strokeWidth = 2
        } else {
            rbEngineCf.isChecked = false; rbEngineCustom.isChecked = true
            cardEngineCf.strokeColor = Color.parseColor("#334155"); cardEngineCf.strokeWidth = 2
            cardEngineCustom.strokeColor = Color.parseColor("#38BDF8"); cardEngineCustom.strokeWidth = 4
        }
    }

    private fun updateActiveServerName() {
        val selected = getSelectedConfig()
        tvServerName.text = if (selected != null) "${selected.name} [${selected.endpoint}]" else "WARP Auto Clean IP [Auto]"
    }

    private fun showSelectLocationBottomSheet() {
        val bottomSheet = BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_select_location, null)
        bottomSheet.setContentView(dialogView)
        val btnAddConfig = dialogView.findViewById<MaterialCardView>(R.id.btnAddConfig)
        val tvEmptyState = dialogView.findViewById<TextView>(R.id.tvEmptyState)
        val rvConfigs = dialogView.findViewById<RecyclerView>(R.id.rvConfigs)
        rvConfigs.layoutManager = LinearLayoutManager(this)

        fun refreshList() {
            val configList = getAllConfigs()
            if (configList.isEmpty()) {
                tvEmptyState.visibility = View.VISIBLE; rvConfigs.visibility = View.GONE
            } else {
                tvEmptyState.visibility = View.GONE; rvConfigs.visibility = View.VISIBLE
                rvConfigs.adapter = ConfigAdapter(configList, { selectedConfig ->
                    setSelectedConfig(selectedConfig.id)
                    updateActiveServerName()
                    bottomSheet.dismiss()
                }, { deleteConfig ->
                    if (isConnected) Toast.makeText(this, "Please disconnect VPN first!", Toast.LENGTH_SHORT).show()
                    else {
                        deleteConfigById(deleteConfig.id)
                        appendLog("Deleted config: ${deleteConfig.name}")
                        refreshList()
                        updateActiveServerName()
                    }
                })
            }
        }
        refreshList()
        btnAddConfig.setOnClickListener { bottomSheet.dismiss(); showImportConfigDialog() }
        bottomSheet.show()
    }

    private fun showImportConfigDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_server, null)
        val etConfigInput = dialogView.findViewById<EditText>(R.id.etConfigInput)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)
        val btnImport = dialogView.findViewById<MaterialButton>(R.id.btnImport)
        val dialog = AlertDialog.Builder(this, R.style.DarkCustomDialog).setView(dialogView).create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnImport.setOnClickListener {
            val inputText = etConfigInput.text.toString().trim()
            if (inputText.isNotEmpty()) {
                try {
                    var parsedConfig = inputText
                    if (inputText.startsWith("wireguard://", ignoreCase = true)) parsedConfig = parseWireGuardUri(inputText)
                    else Config.parse(ByteArrayInputStream(parsedConfig.toByteArray()))

                    val newId = System.currentTimeMillis().toString()
                    val name = "Imported Server #${getAllConfigs().size + 1}"
                    val endpoint = extractEndpoint(parsedConfig)
                    saveNewConfig(ConfigModel(newId, name, parsedConfig, endpoint, true))
                    appendLog("Config Imported Successfully!")
                    Toast.makeText(this, "Config Imported Successfully!", Toast.LENGTH_SHORT).show()
                    updateActiveServerName()
                    dialog.dismiss()
                } catch (e: Exception) {
                    Toast.makeText(this, "Invalid Config Format: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else Toast.makeText(this, "Please paste valid Config!", Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }

    private fun parseWireGuardUri(uriString: String): String {
        val cleanUri = uriString.replace("wireguard://", "")
        val atIndex = cleanUri.indexOf('@')
        val privateKey = cleanUri.substring(0, atIndex)
        val rest = cleanUri.substring(atIndex + 1)
        val questionIndex = rest.indexOf('?')
        val endpointPart = rest.substring(0, questionIndex)
        val queryPart = rest.substring(questionIndex + 1)
        val endpointParts = endpointPart.split(':')
        val params = mutableMapOf<String, String>()
        queryPart.split('&').forEach { param ->
            val parts = param.split('=')
            if (parts.size == 2) params[parts[0]] = URLDecoder.decode(parts[1], "UTF-8")
        }
        return buildRawConfig(URLDecoder.decode(privateKey, "UTF-8"), "${endpointParts[0]}:${endpointParts[1]}", params["address"]!!, params["publickey"]!!, params["mtu"] ?: "1280")
    }

    private fun buildRawConfig(privateKey: String, endpoint: String, address: String, publicKey: String, mtu: String): String {
        val formattedAddress = if (address.contains(",") && !address.contains(", ")) address.replace(",", ", ") else address
        return "[Interface]\nPrivateKey = $privateKey\nAddress = $formattedAddress\nDNS = 1.1.1.1, 1.0.0.1\nMTU = $mtu\n\n[Peer]\nPublicKey = $publicKey\nEndpoint = $endpoint\nAllowedIPs = 0.0.0.0/0, ::/0"
    }

    private fun extractEndpoint(configStr: String): String {
        val match = Regex("Endpoint\\s*=\\s*(\\S+)").find(configStr)
        return match?.groupValues?.get(1) ?: "162.159.192.1:500"
    }

    private fun applyCustomDnsToConfig(rawConfig: String): String {
        val prefs = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE)
        val targetDns = when (prefs.getString("DNS_SETTING", "DEFAULT")) {
            "CLOUDFLARE" -> "1.1.1.1, 1.0.0.1"
            "GOOGLE" -> "8.8.8.8, 8.8.4.4"
            else -> return rawConfig
        }
        return if (rawConfig.contains("DNS =", ignoreCase = true)) rawConfig.replace(Regex("DNS\\s*=\\s*[^\\n]+", RegexOption.IGNORE_CASE), "DNS = $targetDns")
        else rawConfig.replace("[Interface]", "[Interface]\nDNS = $targetDns")
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            tvLogs.append("[$timestamp] $message\n")
            tvLogs.post {
                val scrollAmount = tvLogs.layout?.getLineTop(tvLogs.lineCount) ?: 0
                tvLogs.scrollTo(0, maxOf(0, scrollAmount - tvLogs.height))
            }
        }
    }

    private suspend fun checkDeviceAccess(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$VPS_API_URL/check_device/$deviceId")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                val jsonObject = JSONObject(response)
                if (jsonObject.optString("status") == "active") return@withContext true
                else {
                    val msg = jsonObject.optString("message", "Access Denied!")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Access Denied: $msg", Toast.LENGTH_LONG).show()
                        appendLog("❌ API Check Failed: $msg")
                    }
                    return@withContext false
                }
            } else {
                withContext(Dispatchers.Main) { appendLog("❌ API Server Error: Code ${connection.responseCode}") }
                return@withContext false
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                appendLog("❌ Network Error: Could not reach API server. Check VPS IP/Internet.")
                Toast.makeText(this@MainActivity, "Network Error to VPS!", Toast.LENGTH_SHORT).show()
            }
            return@withContext false
        }
    }
    
    private fun prepareAndConnectVpn() {
        tvStatus.text = "VERIFYING..."
        btnConnectCard.setStrokeColor(Color.parseColor("#F59E0B"))
        appendLog("Verifying Device ID with Server...")

        lifecycleScope.launch(Dispatchers.IO) {
            val isAuthorized = checkDeviceAccess(myDeviceId)
            if (!isAuthorized) {
                withContext(Dispatchers.Main) { resetUi() }
                return@launch
            }

            withContext(Dispatchers.Main) {
                tvStatus.text = "CONNECTING..."
                appendLog("✅ Device Authorized. Preparing VPN connection...")
            }

            try {
                var selectedModel = getSelectedConfig()
                var configStr: String

                if (selectedModel == null) {
                    val engineMode = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE).getString("WARP_ENGINE", "CF_DIRECT") ?: "CF_DIRECT"
                    val wgcf = WgcfManager()
                    configStr = try { wgcf.registerAndGetConfig(engineMode) } catch (e: Exception) { wgcf.registerAndGetConfig(if (engineMode == "CF_DIRECT") "CUSTOM_API" else "CF_DIRECT") }
                    saveNewConfig(ConfigModel("warp_${System.currentTimeMillis()}", "WARP Auto Clean IP", configStr, extractEndpoint(configStr), true))
                } else {
                    configStr = selectedModel.content
                }

                configStr = applyCustomDnsToConfig(configStr)
                pendingConfigStr = configStr
                
                withContext(Dispatchers.Main) {
                    val intent = VpnService.prepare(this@MainActivity)
                    if (intent != null) vpnPermissionLauncher.launch(intent)
                    else connectVpnWithPendingConfig()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("Error: ${e.message}")
                    resetUi()
                }
            }
        }
    }

    private fun connectVpnWithPendingConfig() {
        val configStr = pendingConfigStr ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val wgConfig = Config.parse(ByteArrayInputStream(configStr.toByteArray()))
                backend.setState(tunnel, com.wireguard.android.backend.Tunnel.State.UP, wgConfig)
                withContext(Dispatchers.Main) {
                    isConnected = true
                    tvStatus.text = "CONNECTED"
                    tvStatus.setTextColor(Color.parseColor("#4ADE80"))
                    btnConnectCard.setStrokeColor(Color.parseColor("#4ADE80"))
                    imgPower.setColorFilter(Color.parseColor("#4ADE80"))
                    Toast.makeText(this@MainActivity, "Connected Successfully!", Toast.LENGTH_SHORT).show()
                    appendLog("✅ Connected!")
                    startPingManager()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("Connection Error: ${e.message}"); resetUi() }
            }
        }
    }

    private fun disconnectVpn() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                stopPingManager()
                backend.setState(tunnel, com.wireguard.android.backend.Tunnel.State.DOWN, null)
                withContext(Dispatchers.Main) { appendLog("Disconnected."); resetUi() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { resetUi() } }
        }
    }

    private fun startPingManager() {
        stopPingManager()
        pingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && isConnected) {
                runSinglePing()
                delay(30000)
            }
        }
    }

    private fun stopPingManager() { pingJob?.cancel(); pingJob = null }

    private suspend fun runSinglePing() = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            val reachable = InetAddress.getByName("1.1.1.1").isReachable(3000)
            val pingTime = System.currentTimeMillis() - startTime
            if (reachable) {
                appendLog("🏓 Ping: $pingTime ms")
                withContext(Dispatchers.Main) { notificationHelper.updateNotification("$pingTime ms") }
            }
        } catch (e: Exception) {}
    }

    private fun resetUi() {
        stopPingManager()
        isConnected = false
        tvStatus.text = "TAP TO CONNECT"
        tvStatus.setTextColor(Color.parseColor("#94A3B8"))
        btnConnectCard.setStrokeColor(Color.parseColor("#334155"))
        imgPower.setColorFilter(Color.parseColor("#94A3B8"))
        notificationHelper.cancelNotification()
    }

    private fun getAllConfigs(): List<ConfigModel> {
        val jsonStr = getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE).getString("CONFIG_LIST_JSON", "[]")
        val list = mutableListOf<ConfigModel>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(ConfigModel(obj.getString("id"), obj.getString("name"), obj.getString("content"), obj.getString("endpoint"), obj.optBoolean("isSelected", false)))
            }
        } catch (e: Exception) {}
        if (list.isNotEmpty() && list.none { it.isSelected }) list[0].isSelected = true
        return list
    }

    private fun getSelectedConfig(): ConfigModel? = getAllConfigs().let { it.find { m -> m.isSelected } ?: it.firstOrNull() }
    private fun setSelectedConfig(id: String) { val list = getAllConfigs(); list.forEach { it.isSelected = (it.id == id) }; saveConfigList(list) }
    private fun saveNewConfig(model: ConfigModel) { val list = getAllConfigs().toMutableList(); list.forEach { it.isSelected = false }; model.isSelected = true; list.add(0, model); saveConfigList(list) }
    private fun deleteConfigById(id: String) { val list = getAllConfigs().filter { it.id != id }; if (list.isNotEmpty() && list.none { it.isSelected }) list[0].isSelected = true; saveConfigList(list) }
    private fun saveConfigList(list: List<ConfigModel>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("content", it.content).put("endpoint", it.endpoint).put("isSelected", it.isSelected)) }
        getSharedPreferences("WARP_VPN_PREFS", Context.MODE_PRIVATE).edit().putString("CONFIG_LIST_JSON", array.toString()).apply()
    }

    class ConfigAdapter(private val list: List<ConfigModel>, private val onItemClick: (ConfigModel) -> Unit, private val onDeleteClick: (ConfigModel) -> Unit) : RecyclerView.Adapter<ConfigAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvName)
            val tvEndpoint: TextView = view.findViewById(R.id.tvEndpoint)
            val btnDelete: ImageView = view.findViewById(R.id.btnDelete)
            val imgCheck: ImageView = view.findViewById(R.id.imgCheck)
            val cardItem: MaterialCardView = view.findViewById(R.id.cardItem)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_config, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.tvName.text = item.name; holder.tvEndpoint.text = item.endpoint
            holder.imgCheck.visibility = if (item.isSelected) View.VISIBLE else View.INVISIBLE
            holder.cardItem.strokeColor = Color.parseColor(if (item.isSelected) "#22C55E" else "#334155")
            holder.cardItem.strokeWidth = if (item.isSelected) 4 else 2
            holder.cardItem.setOnClickListener { onItemClick(item) }
            holder.btnDelete.setOnClickListener { onDeleteClick(item) }
        }
        override fun getItemCount() = list.size
    }

    class WgTunnel : com.wireguard.android.backend.Tunnel {
        override fun getName() = "WARPTunnel"
        override fun onStateChange(newState: com.wireguard.android.backend.Tunnel.State) {}
    }
}

