package com.somewearlabs.hellospace.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import no.nordicsemi.android.ble.BleServerManager
import java.util.*


class PeripheralServerManager internal constructor(context: Context) : BleServerManager(context) {
    private val serviceUUID = UUID.fromString("BEFDFF20-C979-11E1-9B21-0800200C9A66")
    private val charUUID = UUID.fromString("BEFDFF60-C979-11E1-9B21-0800200C9A66")

    private val updatePeriodUUID = UUID.fromString("BEFDFFA0-C979-11E1-9B21-0800200C9A66")

    val emitFrequency = 3 // in seconds

    override fun initializeServer(): List<BluetoothGattService> {
        // In this example there's only one service, so singleton list is created.
        return listOf(
                service(serviceUUID,
                        characteristic(
                                charUUID,
                                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                                BluetoothGattCharacteristic.PERMISSION_READ,  // permissions
                                cccd(), updatePeriodDescriptor() // descriptors
                        ))
        )
    }

    private fun updatePeriodDescriptor(): BluetoothGattDescriptor {
        return descriptor(updatePeriodUUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                byteArrayOf(emitFrequency.toByte(), 0)   // 3 seconds
        )
    }
}
