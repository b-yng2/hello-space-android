package com.somewearlabs.hellospace.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import no.nordicsemi.android.ble.observer.ServerObserver

class BlePeripheral(
        val context: Context
): ServerObserver {
    private val serverManager = PeripheralServerManager(context)

    override fun onServerReady() {
    }

    override fun onDeviceConnectedToServer(device: BluetoothDevice) {
    }

    override fun onDeviceDisconnectedFromServer(device: BluetoothDevice) {
    }

}
