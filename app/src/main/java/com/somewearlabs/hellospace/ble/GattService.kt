package com.somewearlabs.hellospace.ble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import com.somewearlabs.hellospace.R
import com.somewearlabs.somewearcore.api.Log
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.BleServerManager
import no.nordicsemi.android.ble.observer.ServerObserver
import java.util.*

/**
 * Advertises a Bluetooth LE GATT service and takes care of its requests. The service
 * runs as a foreground service, which is generally required so that it can run even
 * while the containing app has no UI. It is also possible to have the service
 * started up as part of the OS boot sequence using code similar to the following:
 *
 * <pre>
 *     class OsNotificationReceiver : BroadcastReceiver() {
 *          override fun onReceive(context: Context?, intent: Intent?) {
 *              when (intent?.action) {
 *                  // Start our Gatt service as a result of the system booting up
 *                  Intent.ACTION_BOOT_COMPLETED -> {
 *                     context?.startForegroundService(Intent(context, GattService::class.java))
 *                  }
 *              }
 *          }
 *      }
 * </pre>
 */
class GattService : Service() {
    private object ServiceProfile {
        val serviceUUID = UUID.fromString("BEFDFF20-C979-11E1-9B21-0800200C9A66")
        val summaryDataUUID = UUID.fromString("BEFDFF60-C979-11E1-9B21-0800200C9A66")
        val updatePeriodUUID= UUID.fromString("BEFDFFA0-C979-11E1-9B21-0800200C9A66")
        val genericAccessUUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        val peripheralPreferredUUID = UUID.fromString("00002a04-0000-1000-8000-00805f9b34fb")
        val updatePeriodBytes = byteArrayOf(3.toByte(), 0)  // 3 seconds
    }

    private var serverManager: ServerManager? = null

    private lateinit var bluetoothObserver: BroadcastReceiver

    private var bleAdvertiseCallback: BleAdvertiser.Callback? = null

    override fun onCreate() {
        super.onCreate()
        log.debug("onCreate: will create GattService")

        // Setup as a foreground service

        val notificationChannel = NotificationChannel(
                GattService::class.java.simpleName,
                "Bluetooth Peripheral",
                NotificationManager.IMPORTANCE_DEFAULT
        )
        val notificationService =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationService.createNotificationChannel(notificationChannel)

        val notification = NotificationCompat.Builder(this, GattService::class.java.simpleName)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Not a Zephyr")
                .setContentText("Beep boop")
                .setAutoCancel(true)

        startForeground(1, notification.build())

        // Observe OS state changes in BLE

        bluetoothObserver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val bluetoothState = intent.getIntExtra(
                                BluetoothAdapter.EXTRA_STATE,
                                -1
                        )
                        when (bluetoothState) {
                            BluetoothAdapter.STATE_ON -> enableBleServices()
                            BluetoothAdapter.STATE_OFF -> disableBleServices()
                        }
                    }
                }
            }
        }
        registerReceiver(bluetoothObserver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        // Startup BLE if we have it

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bluetoothManager.adapter?.isEnabled == true) enableBleServices()
    }

    override fun onDestroy() {
        super.onDestroy()
        disableBleServices()
    }

    private fun enableBleServices() {
        serverManager = ServerManager(this)
        serverManager!!.open()

        bleAdvertiseCallback = BleAdvertiser.Callback()

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter.bluetoothLeAdvertiser?.startAdvertising(
                BleAdvertiser.settings(),
                BleAdvertiser.advertiseData(),
                bleAdvertiseCallback!!
        )
    }

    private fun disableBleServices() {
        if (bleAdvertiseCallback != null) {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothManager.adapter.bluetoothLeAdvertiser?.stopAdvertising(bleAdvertiseCallback!!)
            bleAdvertiseCallback = null
        }

        serverManager?.close()
        serverManager = null
    }

    override fun onBind(intent: Intent?): IBinder {
        return DataPlane()
    }

    /**
     * Functionality available to clients
     */
    inner class DataPlane : Binder() {
        /**
         * Change the value of the GATT characteristic that we're publishing
         */
        fun setCharacteristicValue(bytes: ByteArray) {
            serverManager?.setCharacteristicValue(bytes)
        }
    }

    /*
     * Manages the entire GATT service, declaring the services and characteristics on offer
     */
    private class ServerManager(val context: Context) : BleServerManager(context), ServerObserver {

        private val summaryDataChar = characteristic(
                ServiceProfile.summaryDataUUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ,  // permissions
                cccd(), updatePeriodDescriptor() // descriptors
        )

        private val peripheralPreferredChar = characteristic(
                ServiceProfile.peripheralPreferredUUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        )

        private val serverConnections = mutableMapOf<String, ServerConnection>()

        fun setCharacteristicValue(bytes: ByteArray) {
            log.debug("setCharacteristicValue: did update char; bytes=${bytes.contentToString()}")
            summaryDataChar.value = bytes
            serverConnections.values.forEach { serverConnection ->
                serverConnection.sendNotificationForMyGattCharacteristic(bytes)
            }
        }

        override fun log(priority: Int, message: String) {
            if (priority < android.util.Log.DEBUG) return
            log.debug("gattLog: $message")
        }

        override fun initializeServer(): List<BluetoothGattService> {
            setServerObserver(this)
            return listOf(
                    service(ServiceProfile.serviceUUID, summaryDataChar),
                    // Doesn't seem to work. System probably overrides
//                    service(ServiceProfile.genericAccessUUID, peripheralPreferredChar)
            )
        }

        override fun onServerReady() {
            log.debug("Gatt server ready")
        }

        override fun onDeviceConnectedToServer(device: BluetoothDevice) {
            log.debug("onDeviceConnectedToServer: did connect; address=${device.address}")

            val serverConnection = ServerConnection()
            serverConnection.useServer(this@ServerManager)
            serverConnection.connect(device).enqueue()

            serverConnections[device.address] = serverConnection
        }

        override fun onDeviceDisconnectedFromServer(device: BluetoothDevice) {
            log.debug("Device disconnected ${device.address}")
            val serverConnection = serverConnections[device.address]
            if (serverConnection != null) {
                serverConnection.close()
                serverConnections.remove(device.address)
            }
        }

        private fun updatePeriodDescriptor(): BluetoothGattDescriptor {
            return descriptor(ServiceProfile.updatePeriodUUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                    ServiceProfile.updatePeriodBytes
            )
        }

        /*
         * Manages the state of an individual server connection (there can be many of these)
         */
        inner class ServerConnection : BleManager(context) {

            private var gattCallback: GattCallback? = null

            fun sendNotificationForMyGattCharacteristic(value: ByteArray) {
                gattCallback?.sendNotificationForMyGattCharacteristic(value)
            }

            override fun getGattCallback(): BleManagerGattCallback {
                gattCallback = GattCallback(summaryDataChar)
                return gattCallback!!
            }

            private inner class GattCallback(val myGattCharacteristic: BluetoothGattCharacteristic) : BleManagerGattCallback() {

                fun sendNotificationForMyGattCharacteristic(value: ByteArray) {
                    sendNotification(myGattCharacteristic, value).enqueue()
                }

                // There are no services that we need from the connecting device, but
                // if there were, we could specify them here.
                override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
                    return true
                }

                override fun onDeviceDisconnected() {
                }
            }
        }
    }

    object BleAdvertiser {
        class Callback : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                log.debug("LE Advertise Started.")
            }

            override fun onStartFailure(errorCode: Int) {
                log.warn("LE Advertise Failed. $errorCode")
            }
        }

        fun settings(): AdvertiseSettings {
            return AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setConnectable(true)
                    .setTimeout(0)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .build()
        }

        fun advertiseData(): AdvertiseData {
            return AdvertiseData.Builder()
                    .setIncludeDeviceName(false) // Including it will blow the length
                    .setIncludeTxPowerLevel(false)
                    .addServiceUuid(ParcelUuid(ServiceProfile.serviceUUID))
                    .build()
        }
    }

    companion object: Log()
}
