package com.example.stalker2ai

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.Charset
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var btnBluetoothSettings: Button
    private lateinit var btnWifiSettings: Button
    private lateinit var btnMobileSettings: Button
    private lateinit var btnClearList: Button
    private lateinit var btnSettings: ImageButton
    private lateinit var tvDetectorStatus: TextView
    private lateinit var tvServerStatus: TextView
    private lateinit var tvAuthStatus: TextView
    private lateinit var rvFiles: RecyclerView
    private lateinit var filesAdapter: FilesAdapter
    
    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var bleDataPrefs: SharedPreferences
    private var isSoundEnabled: Boolean = true
    private var toneGenerator: ToneGenerator? = null

    private var bleService: BleService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleService.LocalBinder
            bleService = binder.getService()
            isBound = true
            bleService?.setSoundEnabled(isSoundEnabled)
            forceFullReset()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            bleService = null
        }
    }

    private val bleUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.stalker2ai.BLE_STATE_CHANGED",
                BluetoothAdapter.ACTION_STATE_CHANGED,
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    if (intent.action == "com.example.stalker2ai.BLE_STATE_CHANGED") {
                        updateDetectorStatus()
                    } else {
                        forceFullReset()
                    }
                    updateBluetoothUI()
                    updateWifiUI()
                    updateMobileUI()
                }
                BleService.ACTION_FILE_SAVED -> {
                    updateFilesList()
                }
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            runOnUiThread { updateMobileUI() }
        }
        override fun onLost(network: android.net.Network) {
            runOnUiThread { updateMobileUI() }
        }
        override fun onCapabilitiesChanged(network: android.net.Network, networkCapabilities: android.net.NetworkCapabilities) {
            runOnUiThread { updateMobileUI() }
        }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                updateFilesList()
            }
        }
    }

    private val detectorNames = listOf("Detector", "Stalker", "ESP32", "UART", "C3", "STABLE")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences("StalkerSettings", MODE_PRIVATE)
        bleDataPrefs = getSharedPreferences("BleDeviceData", MODE_PRIVATE)
        isSoundEnabled = sharedPreferences.getBoolean("isSoundEnabled", true)
        
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
        } catch (_: Exception) {
        }

        btnBluetoothSettings = findViewById(R.id.btnBluetoothSettings)
        btnWifiSettings = findViewById(R.id.btnWifiSettings)
        btnMobileSettings = findViewById(R.id.btnMobileSettings)
        btnClearList = findViewById(R.id.btnClearList)
        btnSettings = findViewById(R.id.btnSettings)
        tvDetectorStatus = findViewById(R.id.tvDetectorStatus)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        tvAuthStatus = findViewById(R.id.tvAuthStatus)
        rvFiles = findViewById(R.id.rvFiles)

        setupRecyclerView()
        refreshBluetoothAdapter()

        val serviceIntent = Intent(this, BleService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
        } catch (_: Exception) {
        }

        val filter = android.content.IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction("com.example.stalker2ai.BLE_STATE_CHANGED")
            addAction(BleService.ACTION_FILE_SAVED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        
        try {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(bleUpdateReceiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(bleUpdateReceiver, filter)
            }
        } catch (_: Exception) {}

        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        btnSettings.setOnClickListener { playSound(); showSettingsDialog() }
        btnBluetoothSettings.setOnClickListener { playSound(); startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        btnWifiSettings.setOnClickListener { playSound(); startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        btnMobileSettings.setOnClickListener { playSound(); startActivity(Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)) }
        btnClearList.setOnClickListener { playSound(); showClearListDialog() }

        tvDetectorStatus.setOnClickListener {
            playSound()
            forceFullReset()
            showBluetoothDiagnostic()
        }

        updateBluetoothUI(); updateWifiUI(); updateMobileUI()
        checkPermissions()
        updateFilesList()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun setupRecyclerView() {
        filesAdapter = FilesAdapter(
            emptyList(),
            onDescribeClick = { file ->
                playSound()
                showFileDescriptionDialog(file)
            },
            onSendClick = { file ->
                playSound()
                handleSendFile(file)
            }
        )
        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = filesAdapter
    }

    private fun handleSendFile(file: File) {
        val isServerConnected = tvServerStatus.currentTextColor == ContextCompat.getColor(this, R.color.status_connected)
        
        if (!isServerConnected) {
            Toast.makeText(this, "Нет связи с сервером. Выполните подключение", Toast.LENGTH_LONG).show()
        } else {
            filesAdapter.setSending(file, true)
            
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val jsonString = FileInputStream(file).use { it.bufferedReader().readText() }
                    val jsonObject = JSONObject(jsonString)
                    jsonObject.put("is_sent", true)
                    
                    FileOutputStream(file).use { 
                        it.write(jsonObject.toString(4).toByteArray(Charset.forName("UTF-8"))) 
                    }
                    
                    Toast.makeText(this, "Файл успешно отправлен", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Ошибка отправки: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    filesAdapter.setSending(file, false)
                    updateFilesList()
                }
            }, 2000)
        }
    }

    private fun showClearListDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_dialog_title)
            .setMessage(R.string.clear_dialog_message)
            .setPositiveButton("Да") { _, _ ->
                clearProcessedFiles()
            }
            .setNeutralButton(R.string.btn_test_delete) { _, _ ->
                forceDeleteAllFiles()
            }
            .setNegativeButton("Нет", null)
            .show()
    }

    private fun updateFilesList() {
        val stalkerFolder = File(Environment.getExternalStorageDirectory(), "Stalker2Ai")
        if (stalkerFolder.exists() && stalkerFolder.isDirectory) {
            val files = stalkerFolder.listFiles()?.filter { it.isFile && it.extension.lowercase() == "json" } ?: emptyList()
            runOnUiThread {
                filesAdapter.updateFiles(files.sortedByDescending { it.name })
            }
        } else {
            runOnUiThread {
                filesAdapter.updateFiles(emptyList())
            }
        }
    }

    private fun showFileDescriptionDialog(file: File) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_file_description, findViewById(android.R.id.content), false)
        val tvFileName = dialogView.findViewById<TextView>(R.id.tvFileName)
        val etFindingName = dialogView.findViewById<EditText>(R.id.etFindingName)
        val spinnerUseful = dialogView.findViewById<Spinner>(R.id.spinnerUseful)
        val spinnerLocation = dialogView.findViewById<Spinner>(R.id.spinnerLocation)
        val spinnerWater = dialogView.findViewById<Spinner>(R.id.spinnerWater)
        val spinnerSoil = dialogView.findViewById<Spinner>(R.id.spinnerSoil)

        val layoutLocation = dialogView.findViewById<LinearLayout>(R.id.layoutLocation)
        val layoutWater = dialogView.findViewById<LinearLayout>(R.id.layoutWater)
        val layoutSoil = dialogView.findViewById<LinearLayout>(R.id.layoutSoil)
        val layoutUseful = dialogView.findViewById<LinearLayout>(R.id.layoutUseful)

        layoutLocation.setOnClickListener { spinnerLocation.performClick() }
        layoutWater.setOnClickListener { spinnerWater.performClick() }
        layoutSoil.setOnClickListener { spinnerSoil.performClick() }
        layoutUseful.setOnClickListener { spinnerUseful.performClick() }

        val soilOptions = resources.getStringArray(R.array.soil_options)
        val soilAdapter = ArrayAdapter(this, R.layout.spinner_item_right, soilOptions)
        soilAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSoil.adapter = soilAdapter

        tvFileName.text = file.name

        var initialFindingInfo = ""
        var initialUsefulRu = ""
        var initialLocationRu = ""
        var initialWaterRu = ""
        var initialSoilRu = ""

        try {
            val jsonString = FileInputStream(file).use { stream -> stream.bufferedReader().readText() }
            val jsonObject = JSONObject(jsonString)
            
            initialFindingInfo = jsonObject.optString("finding_info", "")
            etFindingName.setText(initialFindingInfo)

            initialUsefulRu = when(jsonObject.optString("is_useful", "")) {
                "Yes" -> "Да"
                "No" -> "Нет"
                else -> ""
            }
            initialLocationRu = when(jsonObject.optString("search_location", "")) {
                "field" -> "поле"
                "trash" -> "мусорка"
                "beach" -> "пляж"
                else -> ""
            }
            initialWaterRu = when(jsonObject.optString("search_water", "")) {
                "none" -> "нет"
                "fresh" -> "пресная"
                "salt" -> "соленая"
                else -> ""
            }
            initialSoilRu = when(jsonObject.optString("search_soil", "")) {
                "chernozem" -> "Чернозём"
                "sandstone" -> "Песчаник"
                "sandy_loam" -> "Супесь"
                "clay" -> "Глинозём"
                "loam" -> "Суглинок"
                "peat" -> "Торфяник"
                "lime_soil" -> "Известковая почва"
                else -> ""
            }
            
            val usefulOptions = resources.getStringArray(R.array.useful_options)
            spinnerUseful.setSelection(usefulOptions.indexOf(initialUsefulRu).coerceAtLeast(0))
            
            val locationOptions = resources.getStringArray(R.array.location_options)
            spinnerLocation.setSelection(locationOptions.indexOf(initialLocationRu).coerceAtLeast(0))
            
            val waterOptions = resources.getStringArray(R.array.water_options)
            spinnerWater.setSelection(waterOptions.indexOf(initialWaterRu).coerceAtLeast(0))

            spinnerSoil.setSelection(soilOptions.indexOf(initialSoilRu).coerceAtLeast(0))
        } catch (_: Exception) {}

        val alertDialog = AlertDialog.Builder(this)
            .setTitle(R.string.finding_dialog_title)
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                val findingInfo = etFindingName.text.toString().trim()
                val usefulRu = spinnerUseful.selectedItem?.toString() ?: ""
                val locationRu = spinnerLocation.selectedItem?.toString() ?: ""
                val waterRu = spinnerWater.selectedItem?.toString() ?: ""
                val soilRu = spinnerSoil.selectedItem?.toString() ?: ""
                
                val usefulEn = when(usefulRu) { "Да" -> "Yes"; "Нет" -> "No"; else -> "" }
                val locationEn = when(locationRu) { "поле" -> "field"; "мусорка" -> "trash"; "пляж" -> "beach"; else -> "" }
                val waterEn = when(waterRu) { "нет" -> "none"; "пресная" -> "fresh"; "соленая" -> "salt"; else -> "" }
                val soilEn = when(soilRu) {
                    "Чернозём" -> "chernozem"
                    "Песчаник" -> "sandstone"
                    "Супесь" -> "sandy_loam"
                    "Глинозём" -> "clay"
                    "Суглинок" -> "loam"
                    "Торфяник" -> "peat"
                    "Известковая почва" -> "lime_soil"
                    else -> ""
                }
                
                updateJsonFileWithProgress(file, findingInfo, usefulEn, locationEn, waterEn, soilEn)
            }
            .setNegativeButton("Отмена", null)
            .create()

        alertDialog.show()
        val btnSave = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
        btnSave.isEnabled = false

        val validate = {
            val currentFindingInfo = etFindingName.text.toString().trim()
            val currentUsefulRu = spinnerUseful.selectedItem?.toString() ?: ""
            val currentLocationRu = spinnerLocation.selectedItem?.toString() ?: ""
            val currentWaterRu = spinnerUseful.selectedItem?.toString() ?: ""
            val currentSoilRu = spinnerSoil.selectedItem?.toString() ?: ""

            val isChanged = currentFindingInfo != initialFindingInfo ||
                            currentUsefulRu != initialUsefulRu || 
                            currentLocationRu != initialLocationRu || 
                            currentWaterRu != initialWaterRu ||
                            currentSoilRu != initialSoilRu
            
            val isAllFilled = currentFindingInfo.isNotEmpty() &&
                              currentUsefulRu.trim().isNotEmpty() && 
                              currentLocationRu.trim().isNotEmpty() && 
                              currentWaterRu.trim().isNotEmpty() &&
                              currentSoilRu.trim().isNotEmpty()
            
            btnSave.isEnabled = isChanged && isAllFilled
        }

        etFindingName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { validate() }
        })

        val itemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) { validate() }
            override fun onNothingSelected(p0: AdapterView<*>?) { validate() }
        }

        spinnerUseful.onItemSelectedListener = itemSelectedListener
        spinnerLocation.onItemSelectedListener = itemSelectedListener
        spinnerWater.onItemSelectedListener = itemSelectedListener
        spinnerSoil.onItemSelectedListener = itemSelectedListener
    }

    private fun updateJsonFileWithProgress(file: File, findingInfo: String, useful: String, location: String, water: String, soil: String) {
        filesAdapter.setSaving(file, true)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val jsonString = FileInputStream(file).use { it.bufferedReader().readText() }
                val jsonObject = if (jsonString.trim().isEmpty()) JSONObject() else JSONObject(jsonString)
                
                jsonObject.put("finding_info", findingInfo)
                jsonObject.put("is_useful", useful)
                jsonObject.put("search_location", location)
                jsonObject.put("search_water", water)
                jsonObject.put("search_soil", soil)
                
                FileOutputStream(file).use { it.write(jsonObject.toString(4).toByteArray(Charset.forName("UTF-8"))) }
                showGratitudeDialog()
            } catch (_: Exception) {
            } finally {
                filesAdapter.setSaving(file, false)
                updateFilesList()
            }
        }, 1200)
    }

    private fun showGratitudeDialog() {
        val phrases = resources.getStringArray(R.array.gratitude_phrases)
        val randomPhrase = phrases[Random.nextInt(phrases.size)]
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_gratitude, null)
        dialogView.findViewById<TextView>(R.id.tvGratitudePhrase).text = randomPhrase

        val alertDialog = AlertDialog.Builder(this).setView(dialogView).setCancelable(true).create()
        dialogView.findViewById<View>(R.id.rootGratitude).setOnClickListener { alertDialog.dismiss() }
        alertDialog.show()
        alertDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun refreshBluetoothAdapter() {
        val manager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = manager?.adapter
    }

    @SuppressLint("MissingPermission")
    private fun forceFullReset() {
        refreshBluetoothAdapter()
        val adapter = bluetoothAdapter
        if (adapter?.isEnabled != true) return

        val manager = getSystemService(BluetoothManager::class.java) ?: return
        val devices = mutableSetOf<BluetoothDevice>()
        adapter.bondedDevices?.let { devices.addAll(it) }
        
        val profileList = mutableListOf(
            BluetoothProfile.GATT,
            BluetoothProfile.GATT_SERVER,
            BluetoothProfile.HEADSET,
            BluetoothProfile.A2DP
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            profileList.add(BluetoothProfile.HID_DEVICE)
        }

        profileList.forEach { p ->
            try { 
                manager.getConnectedDevices(p)?.let { devices.addAll(it) } 
            } catch (_: Exception) {
            }
        }

        devices.forEach { device ->
            val name = device.name ?: ""
            if (detectorNames.any { name.contains(it, ignoreCase = true) }) {
                bleService?.connectDevice(device)
            }
        }
        updateDetectorStatus()
    }

    @SuppressLint("MissingPermission")
    private fun updateDetectorStatus() {
        val isEnabled = bluetoothAdapter?.isEnabled == true
        if (!isEnabled) { 
            setDetectorUI(false)
        } else {
            val connectedDevices = bleService?.getConnectedDevices() ?: emptyList()
            val isConnected = connectedDevices.any { dev ->
                val name = dev.name ?: ""
                detectorNames.any { name.contains(it, ignoreCase = true) }
            }
            setDetectorUI(isConnected)
        }
        updateBluetoothUI()
    }

    @SuppressLint("MissingPermission")
    private fun showBluetoothDiagnostic() {
        val info = StringBuilder()
        val connectedDevices = bleService?.getConnectedDevices() ?: emptyList()
        
        val activeDetector = connectedDevices.find { dev ->
            val name = dev.name ?: ""
            detectorNames.any { name.contains(it, ignoreCase = true) }
        }

        if (activeDetector == null) {
            info.append("Подключенный детектор не найден.")
        } else {
            val name = activeDetector.name ?: "Без имени"
            info.append("Имя: ").append(name).append("\n")
            info.append("Адрес: ").append(activeDetector.address).append("\n")
            info.append("Связь: ЕСТЬ\n\n")
            
            val storedUuids = bleDataPrefs.getString("uuids_${activeDetector.address}", null)
            if (storedUuids != null) {
                info.append("Список UUID детектора:\n").append(storedUuids)
            } else {
                info.append("UUID считываются... Подождите 2 секунды.")
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.ble_diagnostic_title)
            .setMessage(info.toString().trim())
            .setCancelable(false)
            .setPositiveButton("Понятно") { _, _ -> updateDetectorStatus() }
            .show()
    }

    private fun checkPermissions() {
        val p = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION, 
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p.add(Manifest.permission.BLUETOOTH_CONNECT); p.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            p.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val toRequest = p.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 101)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showManageStorageDialog()
            }
        }
    }

    private fun clearProcessedFiles() {
        val stalkerFolder = File(Environment.getExternalStorageDirectory(), "Stalker2Ai")
        if (stalkerFolder.exists() && stalkerFolder.isDirectory) {
            val files = stalkerFolder.listFiles()?.filter { it.isFile && it.extension.lowercase() == "json" } ?: emptyList()
            var deletedCount = 0
            files.forEach { file ->
                if (isFilledAndSent(file)) {
                    if (file.delete()) {
                        deletedCount++
                    }
                }
            }
            if (deletedCount > 0) {
                Toast.makeText(this, "Удалено файлов: $deletedCount", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Нет файлов со статусом 'Заполнено' и 'Отправлено'", Toast.LENGTH_SHORT).show()
            }
            updateFilesList()
        }
    }

    private fun forceDeleteAllFiles() {
        val stalkerFolder = File(Environment.getExternalStorageDirectory(), "Stalker2Ai")
        if (stalkerFolder.exists() && stalkerFolder.isDirectory) {
            val files = stalkerFolder.listFiles()?.filter { it.isFile && it.extension.lowercase() == "json" } ?: emptyList()
            var deletedCount = 0
            files.forEach { file ->
                if (file.delete()) {
                    deletedCount++
                }
            }
            Toast.makeText(this, "Тестовое удаление: удалено $deletedCount файлов", Toast.LENGTH_SHORT).show()
            updateFilesList()
        }
    }

    private fun isFilledAndSent(file: File): Boolean {
        return try {
            val jsonString = FileInputStream(file).use { it.bufferedReader().readText() }
            val jsonObject = JSONObject(jsonString)
            
            val info = jsonObject.optString("finding_info", "")
            val isUseful = jsonObject.optString("is_useful", "")
            val location = jsonObject.optString("search_location", "")
            val water = jsonObject.optString("search_water", "")
            val soil = jsonObject.optString("search_soil", "")
            
            val isFilled = info.isNotEmpty() && isUseful.isNotEmpty() && 
                           location.isNotEmpty() && water.isNotEmpty() && soil.isNotEmpty()
            
            val isSent = jsonObject.optBoolean("is_sent", false)
            
            isFilled && isSent
        } catch (_: Exception) {
            false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    @SuppressLint("InlinedApi")
    private fun showManageStorageDialog() {
        AlertDialog.Builder(this)
            .setTitle("Доступ к памяти")
            .setMessage("Для сохранения файлов данных необходимо разрешить доступ к управлению всеми файлами в настройках.")
            .setCancelable(false)
            .setPositiveButton("В настройки") { _: DialogInterface, _: Int ->
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = "package:$packageName".toUri()
                    manageStorageLauncher.launch(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    manageStorageLauncher.launch(intent)
                }
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun setDetectorUI(isConnected: Boolean) {
        tvDetectorStatus.text = getText(if (isConnected) R.string.status_detector_on else R.string.status_detector_off)
        tvDetectorStatus.setTextColor(ContextCompat.getColor(this, if (isConnected) R.color.status_connected else R.color.status_disconnected))
        tvDetectorStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (isConnected) R.drawable.ic_bluetooth_on else R.drawable.ic_bluetooth_off, 0)
    }

    private fun updateBluetoothUI() {
        val adapter = bluetoothAdapter
        setIconStatus(btnBluetoothSettings, adapter?.isEnabled == true)
    }
    
    private fun updateWifiUI() {
        val wifiManager = getSystemService(WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        setIconStatus(btnWifiSettings, wifiManager?.isWifiEnabled == true)
    }

    private fun updateMobileUI() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val isMobile = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
        setIconStatus(btnMobileSettings, isMobile)
    }

    private fun setIconStatus(button: Button, isEnabled: Boolean) {
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (isEnabled) R.drawable.ic_bluetooth_on else R.drawable.ic_bluetooth_off, 0)
    }

    private fun playSound() {
        if (isSoundEnabled) {
            try {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                }
            } catch (_: Exception) { }
        }
    }

    private fun showSettingsDialog() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, findViewById(android.R.id.content), false)
        dialog.setContentView(view)
        val tvOn = view.findViewById<TextView>(R.id.tvSoundOn); val tvOff = view.findViewById<TextView>(R.id.tvSoundOff)
        val btnAboutAppSetting = view.findViewById<LinearLayout>(R.id.btnAboutApp)
        val btnOpenFolder = view.findViewById<LinearLayout>(R.id.btnOpenFolder)
        
        updateToggleUI(tvOn, tvOff)
        tvOn.setOnClickListener { isSoundEnabled = true; saveSoundSetting(true); bleService?.setSoundEnabled(true); updateToggleUI(tvOn, tvOff); playSound() }
        tvOff.setOnClickListener { isSoundEnabled = false; saveSoundSetting(false); bleService?.setSoundEnabled(false); updateToggleUI(tvOn, tvOff) }
        btnAboutAppSetting.setOnClickListener { playSound(); showAboutDialog(); dialog.dismiss() }
        btnOpenFolder.setOnClickListener { playSound(); openStalkerFolder(); dialog.dismiss() }
        dialog.show()
    }

    private fun openStalkerFolder() {
        val folder = File(Environment.getExternalStorageDirectory(), "Stalker2Ai")
        if (!folder.exists()) {
            folder.mkdirs()
        }
        
        val uri = "content://com.android.externalstorage.documents/document/primary%3AStalker2Ai".toUri()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "vnd.android.document/directory")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val genericIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivity(Intent.createChooser(genericIntent, "Открыть папку"))
            } catch (_: Exception) {
                Toast.makeText(this, "Менеджер файлов не найден", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this).setTitle(R.string.dialog_about_title).setMessage(R.string.dialog_about_message)
            .setPositiveButton(R.string.dialog_positive_button) { d: DialogInterface, _: Int -> playSound(); d.dismiss() }.show()
    }

    private fun updateToggleUI(tvOn: TextView, tvOff: TextView) {
        if (isSoundEnabled) {
            tvOn.setBackgroundResource(R.drawable.bg_toggle_selected); tvOn.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            tvOff.setBackgroundResource(R.drawable.bg_toggle_unselected); tvOff.setTextColor(ContextCompat.getColor(this, android.R.color.black))
        } else {
            tvOn.setBackgroundResource(R.drawable.bg_toggle_unselected); tvOn.setTextColor(ContextCompat.getColor(this, android.R.color.black))
            tvOff.setBackgroundResource(R.drawable.bg_toggle_selected); tvOff.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        }
    }

    private fun saveSoundSetting(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("isSoundEnabled", enabled) }
    }

    private fun clearDataNotifications() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.cancel(2) // ID уведомления данных
        bleDataPrefs.edit { putInt("unread_data_count", 0) }
    }

    override fun onResume() {
        super.onResume()
        clearDataNotifications()
        forceFullReset()
        updateBluetoothUI()
        updateWifiUI()
        updateMobileUI()
        updateFilesList()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        try { unregisterReceiver(bleUpdateReceiver) } catch (_: Exception) { }
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.unregisterNetworkCallback(networkCallback)
        toneGenerator?.release()
        toneGenerator = null
    }
}
