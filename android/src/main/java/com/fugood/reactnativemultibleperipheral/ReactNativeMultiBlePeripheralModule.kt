package com.fugood.reactnativemultibleperipheral

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.provider.SyncStateContract
import android.util.Log
import android.util.Base64
import android.os.ParcelUuid
import androidx.core.content.ContextCompat

import java.util.UUID
import java.util.Arrays

import kotlin.collections.MutableSet
import kotlin.collections.MutableMap
import kotlin.collections.LinkedHashMap

@ReactModule(name = ReactNativeMultiBlePeripheralModule.NAME)
class ReactNativeMultiBlePeripheralModule(reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String {
    return NAME
  }

  private var bluetoothManager: BluetoothManager? = null
  private var bluetoothAdapter: BluetoothAdapter? = null
  private var bluetoothLeAdvertisers: MutableMap<Int, BluetoothLeAdvertiser> = LinkedHashMap()
  private var bluetoothGattServers: MutableMap<Int, BluetoothGattServer> = LinkedHashMap()
  private var registeredDevices: MutableMap<Int, MutableSet<BluetoothDevice>> = LinkedHashMap()
  private var services: MutableMap<Int, MutableMap<String, BluetoothGattService>> = LinkedHashMap()
  private var advertiseCallbacks: MutableMap<Int, AdvertiseCallback> = LinkedHashMap()

  init {
    bluetoothManager = ContextCompat.getSystemService(
      reactContext,
      BluetoothManager::class.java
    )
    bluetoothAdapter = bluetoothManager?.adapter
  }

  fun sendEvent(name: String, params: WritableMap) {
    val context = getReactApplicationContext()
    if (!context.hasActiveReactInstance()) {
      Log.w(NAME, "dropped $name event: no active React instance")
      return
    }
    context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
      .emit(name, params)
  }

  @ReactMethod
  fun setDeviceName(name: String, promise: Promise) {
    if (bluetoothAdapter == null) {
      promise.reject("error", "Not support bluetooth")
      return
    }
    bluetoothAdapter!!.name = name
    promise.resolve(null)
  }

  @ReactMethod
  fun createPeripheral(id: Int, promise: Promise) {
    val bluetoothAdapter = bluetoothAdapter
    val bluetoothManager = bluetoothManager
    if (bluetoothAdapter == null || bluetoothManager == null) {
      promise.reject("error", "Not support bluetooth")
      return
    }
    if (!bluetoothAdapter.isMultipleAdvertisementSupported() && bluetoothLeAdvertisers.isNotEmpty()) {
      promise.reject("error", "Not support multiple advertisement")
      return
    }
    val bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
    if (bluetoothLeAdvertiser == null) {
      promise.reject("error", "Not support bluetoothLeAdvertiser")
      return
    }
    bluetoothLeAdvertisers[id] = bluetoothLeAdvertiser

    services[id] = LinkedHashMap()

    promise.resolve(null)
  }

  @ReactMethod
  fun checkState(id: Int, promise: Promise) {
    val bluetoothAdapter = bluetoothAdapter
    if (bluetoothAdapter == null) {
      promise.reject("error", "Not support bluetooth")
      return
    }
    var stateString = when (bluetoothAdapter.state) {
      BluetoothAdapter.STATE_ON -> "on"
      BluetoothAdapter.STATE_OFF -> "off"
      BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
      BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
      else -> "unknown"
    }
    promise.resolve(stateString)
  }

  @ReactMethod
  fun addService(
    id: Int,
    uuid: String,
    primary: Boolean,
    promise: Promise
  ) {
    val serviceUUID = UUID.fromString(uuid)
    val service = BluetoothGattService(
      serviceUUID,
      if (primary) BluetoothGattService.SERVICE_TYPE_PRIMARY else BluetoothGattService.SERVICE_TYPE_SECONDARY
    )
    services[id]?.put(uuid, service)
    promise.resolve(null)
  }

  @ReactMethod
  fun addCharacteristic(
    id: Int,
    serviceUUID: String,
    characteristicUUID: String,
    properties: Int,
    permissions: Int,
    promise: Promise
  ) {
    if (services[id] == null) {
      promise.reject("error", "Not found peripheral")
      return
    }
    val service = services[id]?.get(serviceUUID)
    if (service == null) {
      promise.reject("error", "Not found service")
      return
    }
    val characteristic = BluetoothGattCharacteristic(
      UUID.fromString(characteristicUUID),
      properties,
      permissions
    )
    if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
      val descriptor = BluetoothGattDescriptor(
        Constants.CLIENT_CONFIG,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
      )
      characteristic.addDescriptor(descriptor)
    }
    service.addCharacteristic(characteristic)
    promise.resolve(null)
  }

  @ReactMethod
  fun updateValue(
    id: Int,
    serviceUUID: String,
    characteristicUUID: String,
    value: String,
    promise: Promise
  ) {
    val bluetoothLeAdvertiser = bluetoothLeAdvertisers[id]
    if (bluetoothLeAdvertiser == null) {
      promise.reject("error", "Not found bluetoothLeAdvertiser")
      return
    }
    val service = services[id]?.get(serviceUUID)
    if (service == null) {
      promise.reject("error", "Not found service")
      return
    }
    val characteristic = service.getCharacteristic(UUID.fromString(characteristicUUID))
    if (characteristic == null) {
      promise.reject("error", "Not found characteristic")
      return
    }
    characteristic.value = Base64.decode(value, Base64.DEFAULT)
    promise.resolve(null)
  }

  @ReactMethod
  fun sendNotification(
    id: Int,
    serviceUUID: String,
    characteristicUUID: String,
    value: String,
    confirm: Boolean,
    promise: Promise
  ) {
    val bluetoothGattServer = bluetoothGattServers[id]
    if (bluetoothGattServer == null) {
      promise.reject("error", "Have not started advertising")
      return
    }
    val registeredDevices = registeredDevices[id]
    if (registeredDevices == null) {
      promise.reject("error", "Not found registeredDevices")
      return
    }
    val service = bluetoothGattServer.getService(UUID.fromString(serviceUUID))
    if (service == null) {
      promise.reject("error", "Not found service")
      return
    }
    val characteristic = service.getCharacteristic(UUID.fromString(characteristicUUID))
    if (characteristic == null) {
      promise.reject("error", "Not found characteristic")
      return
    }
    var value = Base64.decode(value, Base64.DEFAULT)
    characteristic.value = value
    if (registeredDevices.isEmpty()) {
      Log.w(NAME, "[server $id] notification on ${characteristic.uuid} has no subscribers; nobody receives it")
    }
    for (device in registeredDevices) {
      val sent = bluetoothGattServer.notifyCharacteristicChanged(
        device,
        characteristic,
        confirm
      )
      Log.i(NAME, "[server $id] notify $device on ${characteristic.uuid}: ${if (sent) "queued" else "FAILED"}")
    }
    promise.resolve(null)
  }

  @ReactMethod
  fun startAdvertising(
    id: Int,
    advServices: ReadableMap?,
    options: ReadableMap?,
    promise: Promise
  ) {
    val bluetoothManager = bluetoothManager
    if (bluetoothManager == null) {
      promise.reject("error", "Not support bluetooth")
      return
    }
    val bluetoothLeAdvertiser = bluetoothLeAdvertisers[id]
    if (bluetoothLeAdvertiser == null) {
      promise.reject("error", "Not found bluetoothLeAdvertiser")
      return
    }
    if (advertiseCallbacks[id] != null) {
      promise.reject("error", "Already advertising")
      return
    }

    // A GATT server left open by an earlier startAdvertising (a stop → start
    // or a ride switch) would keep hosting the same service next to the new
    // one, and a passenger could discover and write to the stale copy. Close
    // it first so there is only ever one live server per peripheral id.
    closeGattServer(id, "startAdvertising")

    registeredDevices[id] = mutableSetOf<BluetoothDevice>()

    // The callbacks answer requests through the server that received them.
    // Looking the server up by id instead (as before) answered on whichever
    // server was newest, so a request that reached an older server was never
    // answered and the passenger's GATT operation hung until it timed out.
    var server: BluetoothGattServer? = null

    val bluetoothGattServer = bluetoothManager.openGattServer(
      getReactApplicationContext(),
      object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
          super.onConnectionStateChange(device, status, newState)
          if (newState == BluetoothProfile.STATE_CONNECTED) {
            Log.i(NAME, "[server $id] CONNECTED: $device")
          } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            Log.i(NAME, "[server $id] DISCONNECTED: $device")
            registeredDevices[id]?.remove(device)
          }
        }

        override fun onCharacteristicReadRequest(
          device: BluetoothDevice,
          requestId: Int,
          offset: Int,
          characteristic: BluetoothGattCharacteristic
        ) {
          super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
          val gattServer = server ?: return
          if (offset > characteristic.value.size) {
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
          } else {
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.value)
          }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
          super.onNotificationSent(device, status)
        }

        override fun onCharacteristicWriteRequest(
          device: BluetoothDevice,
          requestId: Int,
          characteristic: BluetoothGattCharacteristic,
          preparedWrite: Boolean,
          responseNeeded: Boolean,
          offset: Int,
          value: ByteArray
        ) {
          super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
          val gattServer = server ?: return
          Log.i(NAME, "[server $id] write from $device to ${characteristic.uuid} (${value.size} bytes)")
          val params = Arguments.createMap()
          params.putString("id", id.toString())
          params.putString("device", device.address)
          params.putString("service", characteristic.service.uuid.toString())
          params.putString("characteristic", characteristic.uuid.toString())
          params.putInt("offset", offset)
          params.putString("value", Base64.encodeToString(value, Base64.NO_WRAP))
          sendEvent("onWrite", params)
          if (responseNeeded) {
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
          }
        }

        override fun onDescriptorReadRequest(
          device: BluetoothDevice,
          requestId: Int,
          offset: Int,
          descriptor: BluetoothGattDescriptor
        ) {
          super.onDescriptorReadRequest(device, requestId, offset, descriptor)
          val gattServer = server ?: return
          if (Constants.CLIENT_CONFIG == descriptor.uuid) {
            val subscribed = registeredDevices[id]?.contains(device) == true
            gattServer.sendResponse(
              device,
              requestId,
              BluetoothGatt.GATT_SUCCESS,
              0,
              if (subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
              else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            )
          } else {
            Log.w(NAME, "[server $id] unknown descriptor read request: ${descriptor.uuid}")
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
          }
        }

        override fun onDescriptorWriteRequest(
          device: BluetoothDevice,
          requestId: Int,
          descriptor: BluetoothGattDescriptor,
          preparedWrite: Boolean,
          responseNeeded: Boolean,
          offset: Int,
          value: ByteArray
        ) {
          super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
          val gattServer = server ?: return
          if (Constants.CLIENT_CONFIG == descriptor.uuid) {
            // Always answer a subscription request, even while not advertising.
            // A passenger's CCCD write that gets no response blocks every
            // later GATT operation on its connection, including the payment
            // write, until the stack's timeout.
            val subscribers = registeredDevices[id]
            if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
              subscribers?.add(device)
              Log.i(NAME, "[server $id] subscribed: $device (${subscribers?.size ?: 0} subscriber(s))")
            } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
              subscribers?.remove(device)
              Log.i(NAME, "[server $id] unsubscribed: $device")
            }
            if (subscribers == null) {
              Log.w(NAME, "[server $id] subscription from $device while not advertising; answered anyway")
            }
            if (responseNeeded) {
              gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
          } else {
            Log.w(NAME, "[server $id] unknown descriptor write request: ${descriptor.uuid}")
            if (responseNeeded) {
              gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
          }
        }

      }
    )
    if (bluetoothGattServer == null) {
      registeredDevices.remove(id)
      promise.reject("error", "Could not open GATT server (is Bluetooth on?)")
      return
    }
    server = bluetoothGattServer
    bluetoothGattServers[id] = bluetoothGattServer
    Log.i(NAME, "[server $id] GATT server opened")

    for (service in services[id]!!.values) {
      bluetoothGattServer.addService(service)
    }

    val connectable = Utils.get<Boolean>(options, "connectable", true)
    val includeDeviceName = Utils.get<Boolean>(options, "includeDeviceName", true)
    val includeTxPower = Utils.get<Boolean>(options, "includeTxPower", false)
    val advertiseMode = Utils.get<Int>(options, "mode", AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
    val txPowerLevel = Utils.get<Int>(options, "txPowerLevel", AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
    val manufacturerId = Utils.getInt(options, "manufacturerId")
    val manufacturerData = Utils.getString(options, "manufacturerData")

    val advertiseSettings = AdvertiseSettings.Builder()
      .setAdvertiseMode(advertiseMode)
      .setTxPowerLevel(txPowerLevel)
      .setConnectable(connectable)
      .setTimeout(0)
      .build()

    val advertiseDataBuilder = AdvertiseData.Builder()
      .setIncludeDeviceName(includeDeviceName)
      .setIncludeTxPowerLevel(includeTxPower)
    if (manufacturerId != null && manufacturerData != null) {
      advertiseDataBuilder.addManufacturerData(
        manufacturerId,
        Base64.decode(manufacturerData, Base64.DEFAULT)
      )
    }
    if (advServices != null) {
      for (service in advServices.entryIterator) {
        val uuid = UUID.fromString(service.key)
        if (service.value is String) {
          val data = Base64.decode(service.value as String, Base64.DEFAULT)
          advertiseDataBuilder.addServiceData(ParcelUuid(uuid), data)
        }
        advertiseDataBuilder.addServiceUuid(ParcelUuid(uuid))
      }
    }

    if (advertiseCallbacks[id] == null) {
      advertiseCallbacks[id] = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
          super.onStartSuccess(settingsInEffect)
          promise.resolve(null)
        }
        override fun onStartFailure(errorCode: Int) {
          super.onStartFailure(errorCode)
          promise.reject("error", "Error on advertising, code $errorCode")
        }
      }
    }

    val advertiseData = advertiseDataBuilder.build()
    bluetoothLeAdvertiser.startAdvertising(advertiseSettings, advertiseData, advertiseCallbacks[id])
  }

  @ReactMethod
  fun stopAdvertising(
    id: Int,
    promise: Promise
  ) {
    val bluetoothLeAdvertiser = bluetoothLeAdvertisers[id]
    if (bluetoothLeAdvertiser == null) {
      promise.reject("error", "Not found bluetoothLeAdvertiser")
      return
    }
    if (advertiseCallbacks[id] == null) {
      promise.reject("error", "Had not start advertising")
      return
    }
    bluetoothLeAdvertiser.stopAdvertising(advertiseCallbacks[id])
    // Clearing the callback is what lets the next startAdvertising run (North BUG #005).
    advertiseCallbacks.remove(id)
    registeredDevices.remove(id)
    // Close the GATT server with the advertisement. Left open, it kept
    // accepting connections with no subscriber list, so subscription requests
    // went unanswered, and the next startAdvertising opened a second server
    // beside it (North BUG #011).
    closeGattServer(id, "stopAdvertising")
    Log.i(NAME, "[server $id] advertising stopped")
    promise.resolve(null)
  }

  @ReactMethod
  fun destroyPeripheral(
    id: Int,
    promise: Promise
  ) {
    val callback = advertiseCallbacks[id]
    val bluetoothLeAdvertiser = bluetoothLeAdvertisers[id]
    if (callback != null && bluetoothLeAdvertiser != null) {
      bluetoothLeAdvertiser.stopAdvertising(callback)
    }
    closeGattServer(id, "destroyPeripheral")
    bluetoothLeAdvertisers.remove(id)
    advertiseCallbacks.remove(id)
    registeredDevices.remove(id)
    promise.resolve(null)
  }

  /**
   * Close and forget the GATT server for this peripheral id. Safe to call
   * when there is none. Never throws: it runs on teardown paths.
   */
  private fun closeGattServer(id: Int, reason: String) {
    val server = bluetoothGattServers.remove(id) ?: return
    try {
      server.clearServices()
      server.close()
      Log.i(NAME, "[server $id] GATT server closed ($reason)")
    } catch (e: Exception) {
      Log.w(NAME, "[server $id] closing GATT server failed ($reason)", e)
    }
  }

  /**
   * React Native calls this when it throws this module instance away: a JS
   * reload (Metro "r", dev-menu Reload, red-box recovery) or the React
   * instance shutting down. JS cleanup (useEffect returns, BLEFacade.destroy)
   * does NOT run on a reload, so without this the old advertiser and GATT
   * server stayed registered with the Bluetooth stack for the life of the
   * process. The reloaded app then opened a second server beside it, and a
   * passenger that reached the stale one had its payment delivered to the
   * dead JS instance: no ack, and a timeout (North BUG #011).
   */
  override fun invalidate() {
    Log.i(NAME, "invalidate: releasing ${advertiseCallbacks.size} advertiser(s) and ${bluetoothGattServers.size} GATT server(s)")
    for ((id, callback) in advertiseCallbacks) {
      try {
        bluetoothLeAdvertisers[id]?.stopAdvertising(callback)
      } catch (e: Exception) {
        Log.w(NAME, "[server $id] stopAdvertising failed during invalidate", e)
      }
    }
    for (id in bluetoothGattServers.keys.toList()) {
      closeGattServer(id, "invalidate")
    }
    advertiseCallbacks.clear()
    registeredDevices.clear()
    bluetoothLeAdvertisers.clear()
    services.clear()
    super.invalidate()
  }

  companion object {
    const val NAME = "ReactNativeMultiBlePeripheral"
  }
}
