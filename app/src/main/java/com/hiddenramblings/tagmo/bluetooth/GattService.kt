/*
 * ====================================================================
 * Copyright (C) 2022 AbandonedCart @ TagMo
 * Flask Copyright (C) 2022 withgallantry @ BluupLabs
 * ====================================================================
 */

package com.hiddenramblings.tagmo.bluetooth

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import androidx.annotation.RequiresApi
import com.hiddenramblings.tagmo.amiibo.Amiibo
import com.hiddenramblings.tagmo.bluetooth.Nordic.isUUID
import com.hiddenramblings.tagmo.eightbit.io.Debug
import com.hiddenramblings.tagmo.eightbit.os.Version
import com.hiddenramblings.tagmo.nfctech.NfcByte
import com.hiddenramblings.tagmo.nfctech.TagArray
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.ArrayList
import java.util.Objects
import java.util.UUID

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
@SuppressLint("MissingPermission")
class GattService : Service() {
    private var listener: BluetoothGattListener? = null
    private var mBluetoothManager: BluetoothManager? = null
    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var mBluetoothDeviceAddress: String? = null
    private var mBluetoothGatt: BluetoothGatt? = null
    private var mCharacteristicRX: BluetoothGattCharacteristic? = null
    private var mCharacteristicTX: BluetoothGattCharacteristic? = null

    private var maxTransmissionUnit = 53
    private val chunkTimeout = 25L

    private var activeSlot = 0
    private var slotsCount = 0

    private var nameCompat: String? = null
    private var tailCompat: String? = null
    private var wipeDeviceCount = 0
    private val listCount = 10

    private var response = StringBuilder()
    private var rangeIndex = 0

    private var puckArray = ArrayList<ByteArray>(slotsCount)
    private var readResponse = byteArrayOf()
    private var tempInfoData = byteArrayOf()

    @Suppress("unused")
    private enum class PUCK(bytes: Int) {
        TEST(0x00),
        INFO(0x01),
        READ(0x02),
        WRITE(0x03),
        SAVE(0x04),
        FWRITE(0x05),
        MOVE(0xFD),
        UART(0xFE),
        NFC(0xFF);

        // RESTART
        val bytes: Byte

        init { this.bytes = bytes.toByte() }
    }

    fun setListener(listener: BluetoothGattListener?) {
        this.listener = listener
    }
    private val commandCallbacks = ArrayList<Runnable>()
    private val gattHandler = Handler(Looper.getMainLooper())

    var serviceType = Nordic.DEVICE.GATT

    interface BluetoothGattListener {
        fun onBluupServicesDiscovered()
        fun onBluupActiveChanged(jsonObject: JSONObject?)
        fun onBluupStatusChanged(jsonObject: JSONObject?)
        fun onBluupListRetrieved(jsonArray: JSONArray)
        fun onBluupRangeRetrieved(jsonArray: JSONArray)
        fun onBluupFilesDownload(dataString: String)
        fun onBluupProcessFinish()
        fun onBluupConnectionLost()

        fun onPixlServicesDiscovered()
        fun onPixlActiveChanged(jsonObject: JSONObject?)
        fun onPixlStatusChanged(jsonObject: JSONObject?)
        fun onPixlDataReceived(result: String?)
        fun onPixlFilesDownload(dataString: String)
        fun onPixlProcessFinish()
        fun onPixlConnectionLost()

        fun onPuckServicesDiscovered()
        fun onPuckActiveChanged(slot: Int)
        fun onPuckDeviceProfile(slotCount: Int)
        fun onPuckListRetrieved(slotData: ArrayList<ByteArray>, active: Int)
        fun onPuckFilesDownload(tagData: ByteArray)
        fun onPuckProcessFinish()
        fun onPuckConnectionLost()
    }

    private fun getCharacteristicValue(characteristic: BluetoothGattCharacteristic, data: ByteArray?) {
        if (data?.isNotEmpty() == true) {
            Debug.warn(this.javaClass,
                    "${Nordic.getLogTag(characteristic.uuid)} ${TagArray.bytesToHex(data)}"
            )
            when (serviceType) {
                Nordic.DEVICE.PIXL, Nordic.DEVICE.LOOP, Nordic.DEVICE.LINK -> {
                    if (characteristic.uuid.isUUID(GattRX)) {
                        listener?.onPixlDataReceived(TagArray.bytesToHex(data))
                    }
                }
                Nordic.DEVICE.PUCK -> {
                    if (characteristic.uuid.isUUID(GattRX)) {
                        when {
                            TagArray.bytesToString(data).endsWith("DTM_PUCK_FAST") -> {
                                sendCommand(byteArrayOf(PUCK.INFO.bytes), null)
                            }
                            tempInfoData.isNotEmpty() -> {
                                val sliceData = tempInfoData.plus(data)
                                puckArray.add(sliceData[1].toInt(), sliceData.copyOfRange(2, sliceData.size))
                                tempInfoData = byteArrayOf()
                                if (puckArray.size == slotsCount) {
                                    listener?.onPuckListRetrieved(puckArray, activeSlot)
                                } else{
                                    val nextSlot = sliceData[1].toInt() + 1
                                    sendCommand(byteArrayOf(PUCK.INFO.bytes, nextSlot.toByte()), null)
                                }
                            }
                            data[0] == PUCK.INFO.bytes -> {
                                if (data.size == 3) {
                                    activeSlot = data[1].toInt()
                                    slotsCount = data[2].toInt()
                                    listener?.onPuckDeviceProfile(slotsCount)
                                } else {
                                    tempInfoData = data
                                }
                            }
                            data[0] == PUCK.READ.bytes -> {
                                if (data[2].toInt() + (data[3].toInt() * 4) >= 143) {
                                    readResponse = readResponse.plus(data.copyOfRange(4, data.size))
                                    listener?.onPuckFilesDownload(readResponse)
                                    readResponse = byteArrayOf()
                                } else {
                                    readResponse = readResponse.plus(data.copyOfRange(4, data.size))
                                }
                            }
                            data[0] == PUCK.WRITE.bytes -> {

                            }
                            data[0] == PUCK.SAVE.bytes -> {
                                sendCommand(byteArrayOf(PUCK.NFC.bytes), null)
                            }
                            data[0] == PUCK.NFC.bytes -> {
                                listener?.onPuckProcessFinish()
                                deviceAmiibo
                            }
                        }
                        if (commandCallbacks.size > 0) {
                            commandCallbacks[0].run()
                            commandCallbacks.removeAt(0)
                        }
                    }
                }
                else -> {

                }
            }
        }
    }

    private fun getCharacteristicValue(characteristic: BluetoothGattCharacteristic, output: String?) {
        if (!output.isNullOrEmpty()) {
            Debug.warn(this.javaClass, "${Nordic.getLogTag(characteristic.uuid)} $output")
            if (characteristic.uuid.isUUID(GattRX)) {
                if (output.contains(">tag.")) {
                    response = StringBuilder()
                    response.append(output.split(">".toRegex()).toTypedArray()[1])
                } else if (output.startsWith("tag.")
                        || output.startsWith("{") || response.isNotEmpty()) {
                    response.append(output)
                }
                val formatted =
                        if (response.isNotEmpty()) response.toString().trim { it <= ' ' }.replace(
                                Objects.requireNonNull(System.getProperty("line.separator")).toRegex(), ""
                        ) else ""
                if (isJSONValid(formatted) || formatted.endsWith(">")
                        || formatted.lastIndexOf("undefined") == 0 || formatted.lastIndexOf("\n") == 0
                ) {
                    if (commandCallbacks.size > 0) {
                        commandCallbacks[0].run()
                        commandCallbacks.removeAt(0)
                    }
                }
                when {
                    formatted.startsWith("tag.get()") || formatted.startsWith("tag.setTag") -> {
                        if (formatted.endsWith(">")) {
                            if (formatted.contains("Uncaught no such element")
                                    && null != nameCompat && null != tailCompat
                            ) {
                                response = StringBuilder()
                                fixSlotDetails(nameCompat, tailCompat)
                                nameCompat = null
                                tailCompat = null
                                return
                            }
                            try {
                                val getAmiibo = formatted.substring(
                                        formatted.indexOf("{"),
                                        formatted.lastIndexOf("}") + 1
                                )
                                try {
                                    listener?.onBluupActiveChanged(JSONObject(getAmiibo))
                                } catch (e: JSONException) {
                                    Debug.warn(e)
                                    listener?.onBluupActiveChanged(null)
                                }
                            } catch (ex: StringIndexOutOfBoundsException) {
                                Debug.warn(ex)
                                listener?.onBluupActiveChanged(null)
                            }
                            response = StringBuilder()
                            nameCompat = null
                            tailCompat = null
                        }
                    }
                    formatted.startsWith("tag.getList") -> {
                        if (formatted.endsWith(">") || formatted.endsWith("\n")) {
                            val getList = formatted.substring(
                                    formatted.indexOf("["),
                                    formatted.lastIndexOf("]") + 1
                            )
                            try {
                                var escapedList = getList
                                        .replace("/", "\\/")
                                        .replace("'", "\\'")
                                        .replace("-", "\\-")
                                when {
                                    getList.contains("...") -> {
                                        if (rangeIndex > 0) {
                                            rangeIndex = 0
                                            escapedList = escapedList.replace(" ...", "")
                                            listener?.onBluupListRetrieved(JSONArray(escapedList))
                                        } else {
                                            rangeIndex += 1
                                            listener?.onBluupListRetrieved(JSONArray())
                                            getDeviceAmiiboRange(0)
                                        }
                                    }
                                    rangeIndex > 0 -> {
                                        val jsonArray = JSONArray(escapedList)
                                        if (jsonArray.length() > 0) {
                                            listener?.onBluupRangeRetrieved(jsonArray)
                                            getDeviceAmiiboRange(rangeIndex * listCount)
                                            rangeIndex += 1
                                        } else {
                                            rangeIndex = 0
                                            activeAmiibo
                                        }
                                    }
                                    else -> {
                                        listener?.onBluupListRetrieved(JSONArray(escapedList))
                                    }
                                }
                            } catch (e: JSONException) {
                                Debug.warn(e)
                            }
                            response = StringBuilder()
                            if (rangeIndex == 0) listener?.onBluupProcessFinish()
                        }
                    }
                    formatted.startsWith("tag.remove") -> {
                        if (formatted.endsWith(">") || formatted.endsWith("\n")) {
                            if (wipeDeviceCount > 0) {
                                wipeDeviceCount -= 1
                                delayedTagCharacteristic("remove(tag.get().name)")
                            } else {
                                listener?.onBluupStatusChanged(null)
                            }
                            response = StringBuilder()
                        }
                    }
                    formatted.startsWith("tag.download") -> {
                        if (formatted.endsWith(">") || formatted.endsWith("\n")) {
                            listener?.let {
                                for (dataString in formatted.split(
                                        "new Uint8Array".toRegex()).toTypedArray()
                                ) {
                                    if (dataString.startsWith("tag.download")
                                            && dataString.endsWith("=")
                                    ) continue
                                    it.onBluupFilesDownload(dataString.substring(
                                            1, dataString.lastIndexOf(")")
                                    ))
                                }
                            }
                            response = StringBuilder()
                        }
                    }
                    formatted.startsWith("tag.createBlank()") -> {
                        if (formatted.endsWith(">") || formatted.endsWith("\n")) {
                            response = StringBuilder()
                            listener?.onBluupStatusChanged(null)
                        }
                    }
                    formatted.endsWith("}") -> {
                        if (formatted.startsWith("tag.saveUploadedTag")) {
                            response = StringBuilder()
                        } else {
                            listener?.let {
                                try {
                                    val jsonObject = JSONObject(response.toString())
                                    val event = jsonObject.getString("event")
                                    if (event == "button") it.onBluupActiveChanged(jsonObject)
                                    if (event == "delete") it.onBluupStatusChanged(jsonObject)
                                } catch (e: JSONException) {
                                    if (e.message?.contains("tag.setTag") == true)
                                        activeAmiibo
                                    else Debug.warn(e)
                                }
                            }
                        }
                        response = StringBuilder()
                    }
                    formatted.endsWith(">") -> {
                        response = StringBuilder()
                    }
                }
            }
        }
    }

    fun getCharacteristicValue(characteristic: BluetoothGattCharacteristic) {
        when (serviceType) {
            Nordic.DEVICE.BLUUP, Nordic.DEVICE.FLASK, Nordic.DEVICE.SLIDE -> {
                @Suppress("DEPRECATION")
                getCharacteristicValue(characteristic, characteristic.getStringValue(0x0))
            }
            Nordic.DEVICE.PIXL, Nordic.DEVICE.LOOP, Nordic.DEVICE.LINK -> {
                @Suppress("DEPRECATION")
                getCharacteristicValue(characteristic, characteristic.value)
            }
            Nordic.DEVICE.PUCK -> {
                @Suppress("DEPRECATION")
                getCharacteristicValue(characteristic, characteristic.value)
            }
            else -> {

            }
        }
        getCharacteristicValue(characteristic, characteristic.value)
    }

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private val mGattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mBluetoothGatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                when (serviceType) {
                    Nordic.DEVICE.BLUUP, Nordic.DEVICE.FLASK, Nordic.DEVICE.SLIDE -> {
                        listener?.onBluupConnectionLost()
                    }
                    Nordic.DEVICE.PIXL, Nordic.DEVICE.LOOP, Nordic.DEVICE.LINK -> {
                        listener?.onPixlConnectionLost()
                    }
                    Nordic.DEVICE.PUCK -> {
                        listener?.onPuckConnectionLost()
                    }
                    else -> {

                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (Version.isLollipop) {
                    gatt.requestMtu(512) // Maximum: 517
                } else {
                    when (serviceType) {
                        Nordic.DEVICE.BLUUP, Nordic.DEVICE.FLASK, Nordic.DEVICE.SLIDE -> {
                            listener?.onBluupServicesDiscovered()
                        }
                        Nordic.DEVICE.PIXL, Nordic.DEVICE.LOOP, Nordic.DEVICE.LINK -> {
                            listener?.onPixlServicesDiscovered()
                        }
                        Nordic.DEVICE.PUCK -> {
                            listener?.onPuckServicesDiscovered()
                        }
                        else -> {

                        }
                    }
                }
            } else {
                Debug.warn(this.javaClass, "onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicRead(
                gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS)
                getCharacteristicValue(characteristic, value)
        }


        @Deprecated("Deprecated in Java", ReplaceWith("if (status == BluetoothGatt.GATT_SUCCESS) getCharacteristicValue(characteristic)", "android.bluetooth.BluetoothGatt"))
        override fun onCharacteristicRead(
                gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) getCharacteristicValue(characteristic)
        }

        override fun onCharacteristicWrite(
                gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            Debug.warn(this.javaClass,
                    Nordic.getLogTag(characteristic.uuid) + " onCharacteristicWrite " + status
            )
        }

        override fun onCharacteristicChanged(
                gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            when (serviceType) {
                Nordic.DEVICE.BLUUP, Nordic.DEVICE.FLASK, Nordic.DEVICE.SLIDE -> {
                    getCharacteristicValue(characteristic, value.decodeToString())
                }
                Nordic.DEVICE.PIXL, Nordic.DEVICE.LOOP, Nordic.DEVICE.LINK -> {
                    getCharacteristicValue(characteristic, value)
                }
                Nordic.DEVICE.PUCK -> {
                    getCharacteristicValue(characteristic, value)
                }
                else -> {

                }
            }
        }

        @Deprecated("Deprecated in Java", ReplaceWith(
                "onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray)"
        ))
        override fun onCharacteristicChanged(
                gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            getCharacteristicValue(characteristic)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Debug.verbose(this.javaClass, "onMtuChange complete: $mtu")
                maxTransmissionUnit = mtu - 3
            } else {
                Debug.warn(this.javaClass, "onMtuChange received: $status")
            }
            when (serviceType) {
                Nordic.DEVICE.BLUUP, Nordic.DEVICE.FLASK, Nordic.DEVICE.SLIDE -> {
                    listener?.onBluupServicesDiscovered()
                }
                Nordic.DEVICE.PIXL, Nordic.DEVICE.LOOP, Nordic.DEVICE.LINK -> {
                    listener?.onPixlServicesDiscovered()
                }
                Nordic.DEVICE.PUCK -> {
                    listener?.onPuckServicesDiscovered()
                }
                else -> {

                }
            }
        }

        override fun onReliableWriteCompleted(gatt: BluetoothGatt?, status: Int) {
            super.onReliableWriteCompleted(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS)
                mCharacteristicRX?.let { getCharacteristicValue(it) }
        }

    }

    inner class LocalBinder : Binder() {
        val service: GattService
            get() = this@GattService
    }

    override fun onBind(intent: Intent): IBinder {
        return mBinder
    }

    override fun onUnbind(intent: Intent): Boolean {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        if (mBluetoothGatt == null) return super.onUnbind(intent)
        mBluetoothGatt!!.close()
        mBluetoothGatt = null
        return super.onUnbind(intent)
    }

    private val mBinder: IBinder = LocalBinder()

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    fun initialize(): Boolean {
        // For API level 18 and above, get a reference to BluetoothAdapter through BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            if (mBluetoothManager == null) return false
        }
        mBluetoothAdapter = mBluetoothManager?.adapter
        return mBluetoothAdapter != null
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     * @return Return true if the connection is initiated successfully. The connection result
     * is reported asynchronously through the
     * `BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)`
     * callback.
     */
    fun connect(address: String?): Boolean {
        if (mBluetoothAdapter == null || address == null) return false

        // Previously connected device.  Try to reconnect.
        if (address == mBluetoothDeviceAddress) mBluetoothGatt?.let { return it.connect() }
        val device = mBluetoothAdapter?.getRemoteDevice(address) ?: return false
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback)
        mBluetoothDeviceAddress = address
        return true
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * `BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)`
     * callback.
     */
    fun disconnect() {
        if (mBluetoothAdapter == null) return
        mBluetoothGatt?.disconnect()
    }

    private fun setResponseDescriptors(characteristic: BluetoothGattCharacteristic) {
        try {
            val descriptorTX = characteristic.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            )
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Version.isTiramisu) {
                mBluetoothGatt?.writeDescriptor(descriptorTX, value)
            } else @Suppress("DEPRECATION") {
                descriptorTX.value = value
                mBluetoothGatt?.writeDescriptor(descriptorTX)
            }
        } catch (ignored: Exception) { }
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled        If true, enable notification.
     */
    @Suppress("SameParameterValue")
    private fun setCharacteristicNotification(
            characteristic: BluetoothGattCharacteristic, enabled: Boolean
    ) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) return
        mBluetoothGatt?.setCharacteristicNotification(characteristic, enabled)
        setResponseDescriptors(characteristic)
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after `BluetoothGatt#discoverServices()` completes successfully.
     *
     * @return A `List` of supported services.
     */
    private val supportedGattServices: List<BluetoothGattService>?
        get() = mBluetoothGatt?.services

    @Throws(UnsupportedOperationException::class)
    fun setPuckServicesUUID()  {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            throw UnsupportedOperationException()
        }
        val services = supportedGattServices
        if (services.isNullOrEmpty()) throw UnsupportedOperationException()
        for (customService in services) {
            when (customService.uuid) {
                Nordic.NUS -> {
                    legacyInterface = false
                    break
                }
                Nordic.LegacyNUS -> {
                    legacyInterface = true
                    break
                }
                else -> {
                    continue
                }
            }
        }
        setCharacteristicRX()
    }

    private fun getCharacteristicRX(mCustomService: BluetoothGattService): BluetoothGattCharacteristic {
        var mReadCharacteristic = mCustomService.getCharacteristic(GattRX)
        if (mBluetoothGatt?.readCharacteristic(mReadCharacteristic) != true) run breaking@{
            mCustomService.characteristics.forEach {
                val customUUID = it.uuid
                /*get the read characteristic from the service*/
                if (customUUID.isUUID(GattRX)) {
                    Debug.verbose(this.javaClass, "GattReadCharacteristic: $customUUID")
                    mReadCharacteristic = mCustomService.getCharacteristic(customUUID)
                    return@breaking
                }
            }
        }
        return mReadCharacteristic
    }

    @Throws(UnsupportedOperationException::class)
    fun setCharacteristicRX() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            throw UnsupportedOperationException()
        }
        val mCustomService = mBluetoothGatt!!.getService(GattNUS)
        if (null == mCustomService) {
            val services = supportedGattServices
            if (services.isNullOrEmpty()) throw UnsupportedOperationException()
            for (service in services) {
                Debug.verbose(this.javaClass, "GattReadService: ${service.uuid}")
                mCharacteristicRX = getCharacteristicRX(service)
                break
            }
        } else {
            mCharacteristicRX = getCharacteristicRX(mCustomService)
        }
        mCharacteristicRX?.let { setCharacteristicNotification(it, true) }
    }

    private fun getCharacteristicTX(mCustomService: BluetoothGattService): BluetoothGattCharacteristic {
        var mWriteCharacteristic = mCustomService.getCharacteristic(GattTX)
        if (!mCustomService.characteristics.contains(mWriteCharacteristic)) {
            for (characteristic in mCustomService.characteristics) {
                val customUUID = characteristic.uuid
                if (customUUID.isUUID(GattTX)) {
                    Debug.verbose(this.javaClass, "GattWriteCharacteristic: $customUUID")
                    mWriteCharacteristic = mCustomService.getCharacteristic(customUUID)
                    break
                }

            }
        }
        return mWriteCharacteristic
    }

    @Throws(UnsupportedOperationException::class)
    fun setCharacteristicTX() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            throw UnsupportedOperationException()
        }
        val mCustomService = mBluetoothGatt!!.getService(GattNUS)
        if (null == mCustomService) {
            val services = supportedGattServices
            if (services.isNullOrEmpty()) throw UnsupportedOperationException()
            for (customService in services) {
                Debug.verbose(this.javaClass, "GattWriteService: ${customService.uuid}")
                mCharacteristicTX = getCharacteristicTX(customService)
            }
        } else {
            mCharacteristicTX = getCharacteristicTX(mCustomService)
        }
        mCharacteristicTX?.let { setCharacteristicNotification(it, true) }
    }

    private fun reliableWriteCharacteristic(value: ByteArray) {
        mBluetoothGatt!!.beginReliableWrite()
        mCharacteristicTX!!.writeType =
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (Version.isTiramisu) {
            mBluetoothGatt!!.writeCharacteristic(
                    mCharacteristicTX!!, value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } else @Suppress("DEPRECATION") {
            mCharacteristicTX!!.value = value
            mBluetoothGatt!!.writeCharacteristic(mCharacteristicTX)
        }
        mBluetoothGatt!!.executeReliableWrite()
    }

    private fun delayedWriteCharacteristic(value: ByteArray) {
        val chunks = GattArray.byteToPortions(value, maxTransmissionUnit)
        val commandQueue = commandCallbacks.size + chunks.size
        gattHandler.postDelayed({
            var i = 0
            while (i < chunks.size) {
                val chunk = chunks[i]
                if (null == mCharacteristicTX) continue
                gattHandler.postDelayed({
                    mCharacteristicTX!!.writeType =
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    if (Version.isTiramisu) {
                        mBluetoothGatt!!.writeCharacteristic(
                                mCharacteristicTX!!, chunk,
                                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        )
                    } else @Suppress("DEPRECATION") {
                        mCharacteristicTX!!.value = chunk
                        mBluetoothGatt!!.writeCharacteristic(mCharacteristicTX)
                    }
                }, (i + 1) * chunkTimeout)
                i += 1
            }
        }, commandQueue * chunkTimeout)
    }

    private fun queueByteCharacteristic(value: ByteArray) {
        if (null == mCharacteristicTX) {
            try {
                setCharacteristicTX()
            } catch (e: UnsupportedOperationException) {
                Debug.warn(e)
            }
        }
        commandCallbacks.add(Runnable { delayedWriteCharacteristic(value) })
        if (commandCallbacks.size == 1) {
            commandCallbacks[0].run()
            commandCallbacks.removeAt(0)
        }
    }

    private fun queueByteCharacteristic(value: ByteArray, index: Int) {
        if (null == mCharacteristicTX) {
            try {
                setCharacteristicTX()
            } catch (e: UnsupportedOperationException) {
                Debug.warn(e)
            }
        }
        commandCallbacks.add(index, Runnable { delayedWriteCharacteristic(value) })
        if (commandCallbacks.size == 1) {
            commandCallbacks[0].run()
            commandCallbacks.removeAt(0)
        }
    }

    private fun delayedByteCharacteric(value: ByteArray) {
        queueByteCharacteristic(value)
    }

    private fun sendCommand(params: ByteArray, data: ByteArray?) {
        delayedByteCharacteric(data?.let { params.plus(data) } ?: params)
    }

    private fun delayedWriteCharacteristic(value: String) {
        val chunks = GattArray.stringToPortions(value, maxTransmissionUnit)
        val commandQueue = commandCallbacks.size + chunks.size
        gattHandler.postDelayed({
            chunks.forEachIndexed { i, chunk ->
                gattHandler.postDelayed({
                    try {
                        mCharacteristicTX!!.writeType =
                                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        if (Version.isTiramisu) {
                            mBluetoothGatt!!.writeCharacteristic(
                                    mCharacteristicTX!!, chunk.encodeToByteArray(),
                                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            )
                        } else @Suppress("DEPRECATION") {
                            mCharacteristicTX!!.value = chunk.encodeToByteArray()
                            mBluetoothGatt!!.writeCharacteristic(mCharacteristicTX)
                        }
                    } catch (ex: NullPointerException) {
                        listener?.onBluupServicesDiscovered()
                    }
                }, (i + 1) * chunkTimeout)
            }
        }, commandQueue * chunkTimeout)
    }

    private fun queueTagCharacteristic(value: String) {
        if (null == mCharacteristicTX) {
            try {
                setCharacteristicTX()
            } catch (e: UnsupportedOperationException) {
                Debug.warn(e)
            }
        }
        commandCallbacks.add(Runnable { delayedWriteCharacteristic("tag.$value\n") })
        if (commandCallbacks.size == 1) {
            commandCallbacks[0].run()
            commandCallbacks.removeAt(0)
        }
    }

    private fun delayedTagCharacteristic(value: String) {
        queueTagCharacteristic(value)
    }

    private fun promptTagCharacteristic(value: String) {
        if (null == mCharacteristicTX) {
            try {
                setCharacteristicTX()
            } catch (e: UnsupportedOperationException) {
                Debug.warn(e)
            }
        }
        commandCallbacks.add(0, Runnable { delayedWriteCharacteristic("tag.$value\n") })
        if (commandCallbacks.size == 1) {
            commandCallbacks[0].run()
            commandCallbacks.removeAt(0)
        }
    }

    private fun queueScreenCharacteristic(value: String) {
        if (null == mCharacteristicTX) {
            try {
                setCharacteristicTX()
            } catch (e: UnsupportedOperationException) {
                Debug.warn(e)
            }
        }
        commandCallbacks.add(Runnable { delayedWriteCharacteristic("screen.$value\n") })
        if (commandCallbacks.size == 1) {
            commandCallbacks[0].run()
            commandCallbacks.removeAt(0)
        }
    }

    private fun delayedScreenCharacteristic(value: String) {
        queueScreenCharacteristic(value)
    }

    val deviceDetails: Unit
        get() {
            if (!legacyInterface) {
                sendCommand(byteArrayOf(
                        0x66, 0x61, 0x73, 0x74, 0x4D, 0x6F, 0x64, 0x65, 0x28, 0x29, 0x0A
                ), null)
            }
        }

    val deviceAmiibo: Unit
        get() {
            when (serviceType) {
                Nordic.DEVICE.BLUUP, Nordic.DEVICE.FLASK, Nordic.DEVICE.SLIDE -> {
                    delayedTagCharacteristic("getList()")
                }
                Nordic.DEVICE.LOOP -> {
                    reliableWriteCharacteristic(byteArrayOf(
                            0x02, 0x01, 0x89.toByte(), 0x88.toByte(), 0x03
                    ))
                }
                Nordic.DEVICE.LINK -> {
                    delayedByteCharacteric(byteArrayOf(
                            0x00, 0x00, 0x10, 0x02, 0x33,
                            0x53, 0x34, 0xAB.toByte(), 0x1F, 0xE8.toByte(),
                            0xC2.toByte(), 0x6D, 0xE5.toByte(), 0x35, 0x27,
                            0x4B, 0x52, 0xE0.toByte(), 0x1F, 0x26
                    ))
                }
                Nordic.DEVICE.PUCK -> {
                    puckArray = ArrayList<ByteArray>(slotsCount)
                    sendCommand(byteArrayOf(PUCK.INFO.bytes, 0.toByte()), null)
                }
                else ->{

                }
            }
        }

    val activeAmiibo: Unit
        get() {
            delayedTagCharacteristic("get()")
        }

    private fun processLoopUpload(input: ByteArray): List<ByteArray> {
        val output = mutableListOf<ByteArray>()
        var start = 0
        while (start < input.size) {
            val chunkSize = 128.coerceAtMost(input.size - start)
            val chunk = input.sliceArray(start until start + chunkSize)
            val newData = ByteArray(5 + chunk.size + 2)
            newData[0] = 0x02.toByte()
            newData[1] = (chunk.size + 3).toByte()
            newData[2] = 0x87.toByte()
            newData[3] = if (chunk.size < 128) 1 else 0
            newData[4] = output.size.toByte()
            chunk.copyInto(newData, 5)
            val xorValue = xorByteArray(newData.sliceArray(1 until newData.size - 2))
            newData[newData.size - 2] = xorValue
            newData[newData.size - 1] = 0x03.toByte()
            output.add(newData)
            start += chunkSize
        }
        return output
    }

    private fun processLinkUpload(inputArray: ByteArray): List<ByteArray> {
        val writeCommands = mutableListOf<ByteArray>()

        // Ensure the working array is exactly 540 bytes
        val workingArray = ByteArray(540)
        inputArray.copyInto(workingArray, startIndex = 0, endIndex = 540)

        // Add initial byte arrays to the output
        writeCommands.add(byteArrayOf(0xA0.toByte(), 0xB0.toByte()))
        writeCommands.add(byteArrayOf(
                0xAC.toByte(), 0xAC.toByte(), 0x00.toByte(), 0x04.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x02.toByte(), 0x1C.toByte())
        )
        writeCommands.add(byteArrayOf(0xAB.toByte(), 0xAB.toByte(), 0x02.toByte(), 0x1C.toByte()))

        // Loop through the input array and slice 20 bytes at a time
        for (i in workingArray.indices step 20) {
            val slice = workingArray.sliceArray(i until i + 20)
            val iteration = (i / 20) + 1

            // Create temporary ByteArray with required values
            val tempArray = byteArrayOf(
                    0xDD.toByte(), 0xAA.toByte(), 0x00.toByte(), 0x14.toByte(),
                    *slice,
                    0x00.toByte(),
                    iteration.toByte()
            )

            // Add temporary array to the output
            writeCommands.add(tempArray)
        }

        // Add final byte arrays to the output
        writeCommands.add(byteArrayOf(0xBC.toByte(), 0xBC.toByte()))
        writeCommands.add(byteArrayOf(0xCC.toByte(), 0xDD.toByte()))

        return writeCommands
    }

    fun uploadAmiiboData(tagData: ByteArray) {
        when (serviceType) {
            Nordic.DEVICE.LOOP -> {
                tagData[536] = 0x80.toByte()
                tagData[537] = 0x80.toByte()
                processLoopUpload(tagData).forEach {
                    commandCallbacks.add(Runnable {
                        delayedByteCharacteric(it)
                    })
                }
            }
            Nordic.DEVICE.LINK -> {
                processLinkUpload(tagData).forEach {
                    commandCallbacks.add(Runnable {
                        delayedByteCharacteric(it)
                    })
                }
            }
            else -> {

            }
        }
    }

    fun uploadSlotAmiibo(tagData: ByteArray, slot: Int) {
        val pages = TagArray.bytesToPages(tagData)
        TagArray.bytesToPages(tagData).forEachIndexed { index, bytes ->
            sendCommand(byteArrayOf(
                    PUCK.WRITE.bytes, slot.toByte(), (index * NfcByte.PAGE_SIZE).toByte(), 0x01
            ), bytes)
        }
        sendCommand(byteArrayOf(PUCK.SAVE.bytes, slot.toByte()), null)
    }

    fun uploadAmiiboFile(tagData: ByteArray, amiibo: Amiibo, index: Int, complete: Boolean) {
        delayedTagCharacteristic("startTagUpload(${tagData.size})")
        val parameters: ArrayList<String> = arrayListOf()
        for (chunk in GattArray.byteToPortions(tagData, 128)) {
            val byteString = Base64.encodeToString(
                    chunk, Base64.NO_PADDING or Base64.NO_CLOSE or Base64.NO_WRAP
            )
            parameters.add("tagUploadChunk(\"$byteString\")")
        }
        amiibo.name?.let { name ->
            val nameUnicode = GattArray.stringToUnicode(name)
            val nameIndexed = if (index > 0) "$index.$nameUnicode" else nameUnicode
            val amiiboName = truncateUnicode(nameIndexed, amiibo.bluupTail.length)
            parameters.add("saveUploadedTag(\"$amiiboName|${amiibo.bluupTail}|0\")")
        }
        if (complete) {
            parameters.add("uploadsComplete()")
            parameters.add("getList()")
        }
        parameters.forEach {
            commandCallbacks.add(Runnable {
                delayedWriteCharacteristic("tag.$it\n")
            })
        }
    }

    fun setActiveAmiibo(amiiboName: String?, tail: String?) {
        amiiboName?.let { name ->
            if (name.startsWith("New Tag")) {
                delayedTagCharacteristic("setTag(\"$name|$tail|0\")")
                return
            }
            tail?.let {
                nameCompat = truncateUnicode(GattArray.stringToUnicode(name), it.length)
                tailCompat = it
                delayedTagCharacteristic("setTag(\"$nameCompat|$it|0\")")
            }
        }
    }

    fun setActiveSlot(slot: Int) {
        sendCommand(byteArrayOf(PUCK.NFC.bytes, slot.toByte()), null)
        activeSlot = slot
        listener?.onPuckActiveChanged(activeSlot)
    }

    fun deleteAmiibo(amiiboName: String?, tail: String?) {
        amiiboName?.let { name ->
            if (name.startsWith("New Tag")) {
                // delayedTagCharacteristic("remove(\"$name||$tail\")")
                delayedTagCharacteristic("remove(\"$name|$tail|0\")")
                return
            }
            tail?.let {
                nameCompat = truncateUnicode(GattArray.stringToUnicode(name), it.length)
                tailCompat = it
                delayedTagCharacteristic("remove(\"$nameCompat|$it|0\")")
            }
        }
    }

    fun downloadAmiibo(fileName: String?, tail: String?) {
        tail?.let {
            fileName?.let { file ->
                val amiiboName = truncateUnicode(GattArray.stringToUnicode(file), it.length)
                delayedTagCharacteristic("download(\"$amiiboName|$it|0\")")
            }
        }
    }

    @Suppress("unused")
    fun downloadSlotData(slot: Int) {
        for (i in 0..35) {
            sendCommand(byteArrayOf(PUCK.READ.bytes, slot.toByte(), (i * 4).toByte(), 0x04), null)
        }
        sendCommand(byteArrayOf(PUCK.READ.bytes, slot.toByte(), 0x8C.toByte(), 0x03), null)

    }

    private fun getDeviceAmiiboRange(index: Int) {
        delayedTagCharacteristic("getList($index,$listCount)") // 5 ... 5
    }

    fun createBlankTag() {
        delayedTagCharacteristic("createBlank()")
    }

    fun clearStorage(count: Int) {
        wipeDeviceCount = count - 1
        delayedTagCharacteristic("remove(tag.get().name)")
    }

    fun setFlaskFace(stacked: Boolean) {
        delayedScreenCharacteristic("setFace(" + (if (stacked) 1 else 0) + ")")
    }

    private fun fixSlotDetails(amiiboName: String?, tail: String?) {
        tail?.let {
            amiiboName?.let { amiibo ->
                val fixedName = truncateUnicode(GattArray.stringToUnicode(amiibo), it.length)
                promptTagCharacteristic(
                        "rename(\"$fixedName|$it\",\"$fixedName|$it|0\" )"
                )
                deviceAmiibo
            }
        }
    }

    private fun isJSONValid(test: String): Boolean {
        if (test.startsWith("tag.") && test.endsWith(")")) return false
        try {
            JSONObject(test)
        } catch (ex: JSONException) {
            try {
                JSONArray(test)
            } catch (jex: JSONException) {
                return false
            }
        }
        return true
    }

    private fun truncateUnicode(unicodeName: String, tailSize: Int) : String {
        return unicodeName.run {
            val nameLength = length + tailSize + 3 // |tail|#
            if (nameLength > 28) substring(0, length - (nameLength - 28)) else this
        }
    }

    private fun xorByteArray(byteArray: ByteArray): Byte {
        if (byteArray.isEmpty()) {
            throw IllegalArgumentException("Empty collection can't be reduced.")
        }
        var result = byteArray[0]
        for (i in 1 until byteArray.size) {
            result = (result.toInt() xor byteArray[i].toInt()).toByte()
        }
        return (result.toInt() and 0xff).toByte()
    }

    companion object {
        private var legacyInterface = false
        val GattNUS: UUID = if (legacyInterface) Nordic.LegacyNUS else Nordic.NUS
        val GattTX: UUID = if (legacyInterface) Nordic.LegacyTX else Nordic.TX
        val GattRX: UUID = if (legacyInterface) Nordic.LegacyRX else Nordic.RX
    }
}