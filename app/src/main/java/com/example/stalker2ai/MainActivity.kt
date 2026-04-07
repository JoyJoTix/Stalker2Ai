package com.example.stalker2ai

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

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

    private val fileSavedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BleService.ACTION_FILE_SAVED) {
                updateFilesList()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences("Stalker2AiPrefs", MODE_PRIVATE)
        bleDataPrefs = getSharedPreferences("BleDataPrefs", MODE_PRIVATE)
        isSoundEnabled = sharedPreferences.getBoolean("sound_enabled", true)

        initViews()
        setupBluetooth()
        updateFilesList()

        val intent = Intent(this, BleService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)

        val filter = IntentFilter(BleService.ACTION_FILE_SAVED)
        ContextCompat.registerReceiver(this, fileSavedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            forceFullReset()
        }

        setupAuthStatus()
        
        val btnSettings = findViewById<ImageButton>(R.id.btnSettings)
        val btnBluetoothSettings = findViewById<Button>(R.id.btnBluetoothSettings)
        val btnWifiSettings = findViewById<Button>(R.id.btnWifiSettings)
        val btnMobileSettings = findViewById<Button>(R.id.btnMobileSettings)
        val btnClearList = findViewById<Button>(R.id.btnClearList)

        btnSettings.setOnClickListener { playSound(); showSettingsDialog() }
        btnBluetoothSettings.setOnClickListener { playSound(); startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        btnWifiSettings.setOnClickListener { playSound(); startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        btnMobileSettings.setOnClickListener { playSound(); startActivity(Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)) }
        btnClearList.setOnClickListener { playSound(); showClearListDialog() }

        val etAuthKey = findViewById<EditText>(R.id.etAuthKey)
        etAuthKey.setText(sharedPreferences.getString("auth_key", ""))
        etAuthKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val key = s.toString()
                sharedPreferences.edit { putString("auth_key", key) }
                if (key.length == 32) {
                    playSound()
                    setupAuthStatus()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        if (savedInstanceState == null) {
            checkPermissions()
        }

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error creating ToneGenerator", e)
        }
    }

    private fun initViews() {
        tvDetectorStatus = findViewById(R.id.tvDetectorStatus)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        tvAuthStatus = findViewById(R.id.tvAuthStatus)
        rvFiles = findViewById(R.id.rvFiles)
        rvFiles.layoutManager = LinearLayoutManager(this)
        filesAdapter = FilesAdapter(emptyList(), { file ->
            showFileOptions(file)
        }, { file ->
            sendFileToServer(file)
        })
        rvFiles.adapter = filesAdapter
    }

    private fun setupBluetooth() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 101)
        }
    }

    private fun updateFilesList() {
        val directory = File(getExternalFilesDir(null), "stalker_data")
        if (!directory.exists()) directory.mkdirs()
        val files = directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
        filesAdapter.updateFiles(files)
    }

    private fun showFileOptions(file: File) {
        val options = arrayOf(getString(R.string.file_option_send), getString(R.string.file_option_delete))
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendFileToServer(file)
                    1 -> {
                        file.delete()
                        updateFilesList()
                        Toast.makeText(this, R.string.file_deleted, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun sendFileToServer(file: File) {
        val authKey = sharedPreferences.getString("auth_key", "") ?: ""
        if (authKey.isEmpty()) {
            Toast.makeText(this, R.string.error_no_auth_key, Toast.LENGTH_SHORT).show()
            return
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("key", authKey)
            .addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
            .build()

        val request = Request.Builder()
            .url("https://s2.skif.biz.ua/stalker/api.php")
            .post(requestBody)
            .build()

        tvServerStatus.text = getString(R.string.status_sending)
        tvServerStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connecting))

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvServerStatus.text = getString(R.string.status_error)
                    tvServerStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_disconnected))
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                runOnUiThread {
                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val json = JSONObject(responseBody)
                            if (json.optString("status") == "success") {
                                tvServerStatus.text = getString(R.string.status_ok)
                                tvServerStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_connected))
                                file.delete()
                                updateFilesList()
                                Toast.makeText(this@MainActivity, R.string.file_sent_success, Toast.LENGTH_SHORT).show()
                            } else {
                                tvServerStatus.text = getString(R.string.status_error)
                                tvServerStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_disconnected))
                                Toast.makeText(this@MainActivity, "Server: ${json.optString("message")}", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            tvServerStatus.text = getString(R.string.status_error)
                            tvServerStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_disconnected))
                            Toast.makeText(this@MainActivity, "JSON error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        tvServerStatus.text = getString(R.string.status_error)
                        tvServerStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_disconnected))
                        Toast.makeText(this@MainActivity, "Response error: ${response.code}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun setupAuthStatus() {
        val authKey = sharedPreferences.getString("auth_key", "") ?: ""
        if (authKey.length == 32) {
            tvAuthStatus.text = getString(R.string.status_ok)
            tvAuthStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
        } else {
            tvAuthStatus.text = getString(R.string.status_no_key)
            tvAuthStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
        }
    }

    private fun forceFullReset() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        
        if (adapter == null || !adapter.isEnabled) {
            tvDetectorStatus.text = getString(R.string.status_bluetooth_off)
            tvDetectorStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
            return
        }

        tvDetectorStatus.text = getString(R.string.status_resetting)
        tvDetectorStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connecting))
        
        bleService?.forceReset()
        
        Handler(Looper.getMainLooper()).postDelayed({
            updateDetectorStatus()
        }, 2000)
    }

    private fun updateDetectorStatus() {
        if (bleService?.isConnected() == true) {
            tvDetectorStatus.text = getString(R.string.status_connected)
            tvDetectorStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
        } else {
            tvDetectorStatus.text = getString(R.string.status_searching)
            tvDetectorStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connecting))
        }
    }

    private fun playSound() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (isSoundEnabled && am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            try { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100) } catch (_: Exception) { }
        }
    }

    private fun showSettingsDialog() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, findViewById(android.R.id.content), false)
        dialog.setContentView(view)
        val tvOn = view.findViewById<TextView>(R.id.tvSoundOn); val tvOff = view.findViewById<TextView>(R.id.tvSoundOff)
        val btnAboutAppSetting = view.findViewById<LinearLayout>(R.id.btnAboutApp)
        val btnOpenFolder = view.findViewById<LinearLayout>(R.id.btnOpenFolder)
        
        if (isSoundEnabled) {
            tvOn.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
            tvOff.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        } else {
            tvOn.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            tvOff.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
        }

        tvOn.setOnClickListener { isSoundEnabled = true; saveSoundSetting(true); bleService?.setSoundEnabled(true); dialog.dismiss() }
        tvOff.setOnClickListener { isSoundEnabled = false; saveSoundSetting(false); bleService?.setSoundEnabled(false); dialog.dismiss() }
        btnAboutAppSetting.setOnClickListener { playSound(); showAboutDialog() }
        btnOpenFolder.setOnClickListener { playSound(); openStalkerFolder() }
        dialog.show()
    }

    private fun saveSoundSetting(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("sound_enabled", enabled) }
        if (enabled) playSound()
    }

    private fun openStalkerFolder() {
        val folder = File(getExternalFilesDir(null), "stalker_data")
        if (!folder.exists()) folder.mkdirs()
        Toast.makeText(this, folder.absolutePath, Toast.LENGTH_LONG).show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about_app_title)
            .setMessage(R.string.about_app_message)
            .setPositiveButton(R.string.dialog_positive_button) { d: DialogInterface, _: Int -> playSound(); d.dismiss() }
            .show()
    }

    private fun showClearListDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_list_title)
            .setMessage(R.string.clear_list_message)
            .setPositiveButton(R.string.dialog_positive_button) { _: DialogInterface, _: Int ->
                playSound()
                val directory = File(getExternalFilesDir(null), "stalker_data")
                directory.listFiles()?.forEach { it.delete() }
                updateFilesList()
            }
            .setNegativeButton(R.string.dialog_negative_button) { d: DialogInterface, _: Int -> playSound(); d.dismiss() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(fileSavedReceiver) } catch (_: Exception) {}
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        toneGenerator?.release()
        toneGenerator = null
    }
}
