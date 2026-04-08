package com.example.stalker2ai

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class BleService : Service() {

    companion object {
        private const val TAG = "BleService"
        private const val CHANNEL_ID = "ble_silent_channel"
        private const val DATA_CHANNEL_ID = "ble_data_v4" // Новый ID для сброса настроек канала
        private const val NOTIFICATION_ID = 1
        private const val DATA_NOTIFICATION_ID = 2
        const val ACTION_FILE_SAVED = "com.example.stalker2ai.FILE_SAVED"
    }

    private val binder = LocalBinder()
    private val activeGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val connectingDevices = ConcurrentHashMap.newKeySet<String>()
    private val clientConfigDescriptorUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    
    private var isSoundEnabled = true
    private lateinit var bleDataPrefs: SharedPreferences
    private var toneGenerator: ToneGenerator? = null

    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        bleDataPrefs = getSharedPreferences("BleDeviceData", MODE_PRIVATE)
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating ToneGenerator", e)
        }
        createNotificationChannels()
        
        // Обязательно вызываем startForeground сразу при запуске сервиса
        startForegroundInternal()

        // Через короткое время проверяем, нужно ли оставлять уведомление
        Handler(Looper.getMainLooper()).postDelayed({
            updateForegroundState()
        }, 2000)
    }

    private fun updateForegroundState() {
        Handler(Looper.getMainLooper()).post {
            if (activeGatts.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                startForegroundInternal()
            }
        }
    }

    private fun startForegroundInternal() {
        val notification = createSilentNotification()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun createNotificationChannels() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return

            // Канал для фоновой службы
            val silentChannel = NotificationChannel(
                CHANNEL_ID, 
                "Stalker 2 Background Service", 
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
            }
            manager.createNotificationChannel(silentChannel)

            // Канал для уведомлений о данных:
            val dataChannel = NotificationChannel(
                DATA_CHANNEL_ID,
                "Входящие данные",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setShowBadge(true)
                enableLights(true)
                lightColor = android.graphics.Color.RED
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(dataChannel)
        }
    }

    private fun createSilentNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun showDataNotification(deviceName: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        
        val notificationCount = bleDataPrefs.getInt("unread_data_count", 0) + 1
        bleDataPrefs.edit { putInt("unread_data_count", notificationCount) }

        val notification = NotificationCompat.Builder(this, DATA_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Новые данные ($notificationCount)")
            .setContentText("От устройства: $deviceName")
            .setAutoCancel(true)
            .setNumber(notificationCount)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(Notification.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(DATA_NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    fun connectDevice(device: BluetoothDevice) {
        val addr = device.address
        if (activeGatts.containsKey(addr) || connectingDevices.contains(addr)) return
        
        connectingDevices.add(addr)
        device.connectGatt(this, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val addr = gatt.device.address
            connectingDevices.remove(addr)
            
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                activeGatts[addr] = gatt
                gatt.requestMtu(32)
                sendBroadcast(Intent("com.example.stalker2ai.BLE_STATE_CHANGED"))
                updateForegroundState()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
                activeGatts.remove(addr)
                sendBroadcast(Intent("com.example.stalker2ai.BLE_STATE_CHANGED"))
                updateForegroundState()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) gatt?.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                saveDeviceUuids(gatt)
                enableNotifications(gatt)
            }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { processIncomingData(gatt.device, characteristic.uuid.toString(), it) }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            processIncomingData(gatt.device, characteristic.uuid.toString(), value)
        }
    }

    private fun processIncomingData(device: BluetoothDevice, uuid: String, data: ByteArray) {
        if (data.isNotEmpty()) saveDataAsJson(device, uuid, data)
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt) {
        gatt.services?.forEach { service ->
            service.characteristics?.forEach { characteristic ->
                if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    characteristic.getDescriptor(clientConfigDescriptorUuid)?.let { descriptor ->
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun saveDataAsJson(device: BluetoothDevice, charUuid: String, data: ByteArray) {
        try {
            if (isSoundEnabled) playSound()
            
            // Используем общую папку Stalker2Ai в корне памяти, как в MainActivity
            @Suppress("DEPRECATION")
            val rootFolder = File(Environment.getExternalStorageDirectory(), "Stalker2Ai")
            if (!rootFolder.exists()) rootFolder.mkdirs()
            
            val now = System.currentTimeMillis()
            val fileIdx = bleDataPrefs.getInt("file_counter", 0) + 1
            bleDataPrefs.edit { putInt("file_counter", fileIdx) }
            val numberPrefix = String.format(Locale.US, "%04d", fileIdx)
            val dateStr = SimpleDateFormat("yy.MM.dd_HH.mm", Locale.getDefault()).format(Date(now))
            val charPool : List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')
            val randomString = (1..2)
                .map { Random.nextInt(0, charPool.size).let { charPool[it] } }
                .joinToString("")
            val fileName = "${numberPrefix}_${dateStr}_$randomString.json"
            val jsonObject = JSONObject().apply {
                put("device_name", device.name ?: "Unknown Device")
                put("device_address", device.address)
                put("characteristic_uuid", charUuid)
                put("timestamp", now)
                put("data_string", String(data, Charsets.UTF_8))
            }
            File(rootFolder, fileName).writeText(jsonObject.toString(4))
            
            sendBroadcast(Intent(ACTION_FILE_SAVED))
            showDataNotification(device.name ?: "Unknown Device")

        } catch (e: Exception) {
            Log.e(TAG, "Error saving JSON data", e)
        }
    }

    private fun playSound() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 500)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing sound", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun saveDeviceUuids(gatt: BluetoothGatt) {
        val sb = StringBuilder()
        gatt.services?.forEach { service ->
            val sUuid = service.uuid.toString()
            if (!isSystemUuid(sUuid)) {
                sb.append("S:").append(sUuid).append(" (").append(getGattName(sUuid)).append(")\n")
                service.characteristics?.forEach { char ->
                    val cUuid = char.uuid.toString()
                    if (!isSystemUuid(cUuid)) sb.append(" C:").append(cUuid).append(" (").append(getGattName(cUuid)).append(")\n")
                }
            }
        }
        bleDataPrefs.edit {
            putString("uuids_${gatt.device.address}", sb.toString().trim())
        }
    }

    private fun getGattName(uuid: String): String {
        val u = uuid.lowercase(Locale.US)
        return when {
            u.contains("6e400001") -> "Nordic UART Service"
            u.contains("6e400002") -> "UART RX (Запись)"
            u.contains("6e400003") -> "UART TX (Чтение)"
            u.contains("ffe1") -> "UART Data Port"
            else -> "Data"
        }
    }

    private fun isSystemUuid(uuid: String): Boolean {
        val u = uuid.lowercase(Locale.US)
        return u.contains("180f") || u.contains("180a") || u.contains("1800") || u.contains("1801")
    }

    fun getConnectedDevices(): List<BluetoothDevice> = activeGatts.values.map { it.device }
    fun setSoundEnabled(enabled: Boolean) { isSoundEnabled = enabled }

    @SuppressLint("MissingPermission")
    fun disconnectAll() {
        activeGatts.values.forEach { it.disconnect(); it.close() }
        activeGatts.clear()
        connectingDevices.clear()
        sendBroadcast(Intent("com.example.stalker2ai.BLE_STATE_CHANGED"))
        updateForegroundState()
    }

    override fun onDestroy() {
        disconnectAll()
        toneGenerator?.release()
        toneGenerator = null
        super.onDestroy()
    }
}
