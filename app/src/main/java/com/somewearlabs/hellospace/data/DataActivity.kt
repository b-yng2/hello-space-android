package com.somewearlabs.hellospace.data

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.somewearlabs.hellospace.R.id
import com.somewearlabs.hellospace.R.layout
import com.somewearlabs.hellospace.ble.GattService
import com.somewearlabs.hellospace.data.model.UserItem
import com.somewearlabs.somewearcore.api.*
import com.somewearlabs.somewearcore.api.DeviceConnectionState.Connected
import com.somewearlabs.somewearui.api.SomewearUI
import com.somewearlabs.uicomponent.extension.onClick
import io.reactivex.Observable.interval
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import java.util.*
import java.util.concurrent.TimeUnit

class DataActivity : AppCompatActivity() {
    private val device: SomewearDevice = SomewearDevice.instance
    private val disposable = CompositeDisposable()
    private val recyclerViewAdapter = SimpleRecyclerViewAdapter()
    private var gattServiceConn: GattServiceConn? = null
    private val dataGenerator = interval(5, TimeUnit.SECONDS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layout.activity_data)

        // Configure buttons
        val waypointIntentButton = findViewById<Button>(id.waypointIntentButton)
        val sendMessageButton = findViewById<Button>(id.sendMessageButton)
        val sendDataButton = findViewById<Button>(id.sendDataButton)
        waypointIntentButton.setOnClickListener { v: View? -> sendWaypointToSomewear() }
        sendMessageButton.setOnClickListener { v: View? -> sendMessage() }
//        sendDataButton.setOnClickListener { v: View? -> sendData() }

        // Gatt server
        val gattCharacteristicValue = findViewById<EditText>(id.editTextGattCharacteristicValue)
        val emitCharValueButton = findViewById<Button>(id.emitCharValueButton)
        emitCharValueButton.onClick {
            val input = gattCharacteristicValue.text?.toString()
            if (input.isNullOrEmpty()) return@onClick

            val result = input.hexToByteArray()
            if (result == null) {
                Toast.makeText(this, "Invalid hex string", Toast.LENGTH_SHORT).show()
                return@onClick
            }

            gattServiceConn?.binding?.setCharacteristicValue(result)
        }

        // Configure status bar view
//        val statusBarView = findViewById<SomewearStatusBarView>(id.statusBarView)
//        statusBarView.setPresenter(this)

        // Handle firmware updates
        SomewearUI.instance.configureFirmwareUpdateHandling(this)

        // Configure UserItem list
        val recyclerView = findViewById<RecyclerView>(id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = recyclerViewAdapter
        recyclerView.addItemDecoration(DividerItemDecoration(recyclerView.context, DividerItemDecoration.VERTICAL))
        disposable.addAll(
                dataGenerator.observeOn(AndroidSchedulers.mainThread()).subscribe { _ ->
                    if (gattServiceConn != null) {
                        Log.d("DataActivity", "will send fake data")
                        gattServiceConn?.binding?.setCharacteristicValue(createZephyrData())
                    }
                },
                device.firmwareUpdateStatus
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe { status: FirmwareUpdateStatus -> Log.d("DataActivity", "firmwareUpdateStatus=$status") },  // Observe connectivity changes
                device.connectionState
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe { connectionState: DeviceConnectionState ->
                            // Hide buttons when not connected
                            val isVisible = connectionState == Connected
                            val visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
                            sendMessageButton.visibility = visibility
                            sendDataButton.visibility = visibility
                        },  // Observe any updates from the device
        )

        // Startup our Bluetooth GATT service explicitly so it continues to run even if
        // this activity is not in focus
        startForegroundService(Intent(this, GattService::class.java))
    }

    override fun onStart() {
        super.onStart()

        val latestGattServiceConn = GattServiceConn()
        if (bindService(Intent(this, GattService::class.java), latestGattServiceConn, 0)) {
            gattServiceConn = latestGattServiceConn
        }
    }

    override fun onStop() {
        super.onStop()

        if (gattServiceConn != null) {
            unbindService(gattServiceConn!!)
            gattServiceConn = null
        }
    }

    override fun onDestroy() {
        // Unregister any callbacks/observers
        disposable.dispose()
        super.onDestroy()

        // We only want the service around for as long as our app is being run on the device
        stopService(Intent(this, GattService::class.java))
    }

    private fun sendMessage() {
//        // Craft a message
//        val content = "Hello from space!"
//        val email = EmailAddress.build("someweardev@gmail.com")
//        val message = MessagePayload.build(content, email)
//
//        // Send the message via satellite
//        device.send(message)
//
//        // show outbound payloads as soon as we hand them off to be sent.
//        userItemSource!!.createOrUpdateUserItem(message)
    }

    private fun createZephyrData(): ByteArray {
        val bytes = ByteArray(20)
        bytes[0] = 1        // version
        bytes[1] = 0        // status msb
        bytes[2] = 0        // status lsb
        bytes[3] = 98       // heart rate
        bytes[4] = 22       // breathing rate msb
        bytes[5] = 0        // breathing rate lsb
        bytes[6] = 20       // device temp msb
        bytes[7] = 0        // device temp msb
        bytes[8] = 0        // posture msb
        bytes[9] = 0        // posture lsb
        bytes[10] = 0       // activity msb
        bytes[11] = 0       // activity lsb
        bytes[12] = 0       // heart rate variability msb
        bytes[13] = 0       // heart rate variability lsb
        bytes[14] = 88      // battery
        bytes[15] = 99      // heart rate confidence
        bytes[16] = 98      // breathing rate confidence
        bytes[17] = 0       // heat stress level
        bytes[18] = 0       // physiological strain index
        bytes[19] = -40      // core temperature

        return bytes
    }

    private fun sendWaypointToSomewear() {
        val latitude = 34.035251
        val longitude = -118.481247
        val timestamp = Date().time
        val name = "Test Waypoint"
        val notes = "Here are some optional notes"
        val intent = Intent("com.somewearlabs.action.SEND")
        //        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.type = "application/vnd.somewear.waypoint"
        intent.putExtra("latitude", latitude)
        intent.putExtra("longitude", longitude)
        intent.putExtra("timestamp", timestamp)
        intent.putExtra("name", name)
        intent.putExtra("notes", notes)
        val activities = packageManager.queryIntentActivities(intent, 0)
        if (activities.isEmpty()) return
        startActivity(intent)
    }

//    private fun sendData() {
//        /*
//         * Want to send data that doesn't fit into one of our prebuilt types? You can send any
//         * arbitrary byte array over satellite using DataPayload.
//         *
//         * Note: You can actually create a LocationPayload, this is just a simple example using a
//         *       protobuf to send structured data over the wire.
//         */
//        val latitude = 37.7796649f
//        val longitude = -122.4039177f
//        val timestamp = Date()
//        val proto = Location.newBuilder()
//                .setLatitude(latitude)
//                .setLongitude(longitude)
//                .setTimestamp(timestamp.time / 1000)
//                .build()
//        val data = DataPayload.build(proto.toByteArray())
//
//        // Send the message via satellite
//        device.send(data)
//
//        // show outbound payloads as soon as we hand them off to be sent.
//        userItemSource!!.createOrUpdateUserItem(data)
//    }

    private fun userItemsDidUpdate(items: List<UserItem>) {
        // prepare model for presentation
        val viewModels: MutableList<String> = ArrayList()
        for (p in items) {
            viewModels.add(p.toString())
        }

        // update the view
        recyclerViewAdapter.setItems(viewModels)
        recyclerViewAdapter.notifyDataSetChanged()
    }
}

private class GattServiceConn : ServiceConnection {
    var binding: GattService.DataPlane? = null

    override fun onServiceDisconnected(name: ComponentName?) {
        Log.d("GattServiceConn", "onServiceDisconnected: $name")
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        Log.d("GattServiceConn", "onServiceConnected: $name")
        if (service is GattService.DataPlane) {
            binding = service
        }
    }
}

fun String.hexToByteArray(): ByteArray? {
    val len = this.length
    if (len == 0 || len % 2 != 0) return null

    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        val d1 = Character.digit(this[i], 16)
        val d2 = Character.digit(this[i + 1], 16)
        if (d1 == -1 || d2 == -1) return null

        data[i / 2] = ((d1 shl 4) + d2).toByte()
        i += 2
    }
    return data
}
