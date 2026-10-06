// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.metallic.chiaki.lib.ControllerState
import kotlin.concurrent.thread

private const val TAG = "DualSenseUsb"
private const val DUALSENSE_VENDOR_ID = 0x054C
private val DUALSENSE_PRODUCT_IDS = setOf(0x0CE6 /* DualSense */, 0x0DF2 /* DualSense Edge */)
private const val ACTION_USB_PERMISSION = "com.metallic.chiaki.USB_PERMISSION"

private const val INPUT_REPORT_ID = 0x01
private const val INPUT_REPORT_MIN_SIZE = 41
private const val OUTPUT_REPORT_ID = 0x02
private const val OUTPUT_REPORT_SIZE = 48
private const val TOUCHPAD_NATIVE_HEIGHT = 1080
private const val TRIGGER_EFFECT_OFF = 0x05
private const val TRANSFER_TIMEOUT_MS = 100
private val PLAYER_LEDS = byteArrayOf(0x04, 0x0A, 0x15, 0x1B, 0x1F)

/**
 * A DualSense connected by cable, driven directly through the USB HID interface.
 * Android has no API for adaptive triggers, so this is the only way to get them without root.
 * While open, the kernel driver is detached and Android stops reporting the controller's input,
 * so the input reports are parsed here instead.
 */
class DualSenseUsb private constructor(
	val device: UsbDevice,
	private val connection: UsbDeviceConnection,
	private val usbInterface: UsbInterface,
	private val inEndpoint: UsbEndpoint,
	private val outEndpoint: UsbEndpoint?,
	private val onState: (ControllerState) -> Unit)
{
	companion object
	{
		fun isDualSense(device: UsbDevice) =
			device.vendorId == DUALSENSE_VENDOR_ID && device.productId in DUALSENSE_PRODUCT_IDS

		fun find(usbManager: UsbManager): UsbDevice? =
			usbManager.deviceList.values.firstOrNull { isDualSense(it) }

		/** callback is invoked on the main thread */
		fun requestPermission(context: Context, device: UsbDevice, callback: (granted: Boolean) -> Unit)
		{
			val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
			if(usbManager.hasPermission(device))
			{
				callback(true)
				return
			}
			val appContext = context.applicationContext
			val receiver = object: BroadcastReceiver()
			{
				override fun onReceive(context: Context, intent: Intent)
				{
					appContext.unregisterReceiver(this)
					callback(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) || usbManager.hasPermission(device))
				}
			}
			ContextCompat.registerReceiver(appContext, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
			val flags = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
			val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
			usbManager.requestPermission(device, PendingIntent.getBroadcast(appContext, 0, intent, flags))
		}

		/** onState is invoked on a background thread whenever the controller's input changes */
		fun open(usbManager: UsbManager, device: UsbDevice, onState: (ControllerState) -> Unit): DualSenseUsb?
		{
			val usbInterface = (0 until device.interfaceCount)
				.map { device.getInterface(it) }
				.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID } ?: return null
			val endpoints = (0 until usbInterface.endpointCount).map { usbInterface.getEndpoint(it) }
			val inEndpoint = endpoints.firstOrNull {
				it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT
			} ?: return null
			val outEndpoint = endpoints.firstOrNull {
				it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_INT
			}
			val connection = usbManager.openDevice(device) ?: return null
			if(!connection.claimInterface(usbInterface, true))
			{
				Log.e(TAG, "Failed to claim the HID interface")
				connection.close()
				return null
			}
			return DualSenseUsb(device, connection, usbInterface, inEndpoint, outEndpoint, onState).also { it.start() }
		}
	}

	@Volatile private var running = true
	private var readThread: Thread? = null

	private val outputLock = Any()
	private var rumbleLeft: Byte = 0
	private var rumbleRight: Byte = 0
	private val leftTrigger = ByteArray(11).also { it[0] = TRIGGER_EFFECT_OFF.toByte() }
	private val rightTrigger = ByteArray(11).also { it[0] = TRIGGER_EFFECT_OFF.toByte() }
	private var intensity: Byte = 0
	private var playerLeds: Byte = PLAYER_LEDS[0]
	private val ledColor = byteArrayOf(0x00, 0x00, 0x40)

	private fun start()
	{
		// Stop the firmware's own light bar animation so the colors set below are visible
		val reset = ByteArray(OUTPUT_REPORT_SIZE)
		reset[0] = OUTPUT_REPORT_ID.toByte()
		reset[39] = 0x02
		reset[42] = 0x02
		write(reset)
		sendOutput(triggers = true)
		readThread = thread(name = TAG) { readLoop() }
	}

	fun close()
	{
		running = false
		synchronized(outputLock) {
			rumbleLeft = 0
			rumbleRight = 0
			leftTrigger.fill(0)
			leftTrigger[0] = TRIGGER_EFFECT_OFF.toByte()
			rightTrigger.fill(0)
			rightTrigger[0] = TRIGGER_EFFECT_OFF.toByte()
			sendOutput(triggers = true)
		}
		readThread?.join(TRANSFER_TIMEOUT_MS * 3L)
		connection.releaseInterface(usbInterface)
		connection.close()
	}

	fun setRumble(left: UByte, right: UByte) = synchronized(outputLock) {
		rumbleLeft = left.toByte()
		rumbleRight = right.toByte()
		sendOutput()
	}

	fun setTriggerEffects(typeLeft: UByte, left: ByteArray, typeRight: UByte, right: ByteArray) = synchronized(outputLock) {
		leftTrigger[0] = typeLeft.toByte()
		left.copyInto(leftTrigger, 1, 0, minOf(left.size, 10))
		rightTrigger[0] = typeRight.toByte()
		right.copyInto(rightTrigger, 1, 0, minOf(right.size, 10))
		sendOutput(triggers = true)
	}

	fun setLedColor(red: UByte, green: UByte, blue: UByte) = synchronized(outputLock) {
		ledColor[0] = red.toByte()
		ledColor[1] = green.toByte()
		ledColor[2] = blue.toByte()
		sendOutput()
	}

	fun setPlayerIndex(index: UByte) = synchronized(outputLock) {
		playerLeds = PLAYER_LEDS[index.toInt() % PLAYER_LEDS.size]
		sendOutput()
	}

	fun setIntensity(value: UByte) = synchronized(outputLock) {
		intensity = value.toByte()
		sendOutput(triggers = true)
	}

	/** Same layout as SDL's DS5EffectsState_t, prefixed by the report id */
	private fun sendOutput(triggers: Boolean = false)
	{
		val report = ByteArray(OUTPUT_REPORT_SIZE)
		report[0] = OUTPUT_REPORT_ID.toByte()
		// rumble emulation, no audio haptics, and the trigger effects if they changed
		report[1] = (0x01 or 0x02 or (if(triggers) 0x04 or 0x08 else 0)).toByte()
		// light bar, player LEDs, motor power reduction
		report[2] = (0x04 or 0x10 or 0x40).toByte()
		report[3] = rumbleRight
		report[4] = rumbleLeft
		rightTrigger.copyInto(report, 11)
		leftTrigger.copyInto(report, 22)
		report[37] = intensity
		// improved rumble emulation on firmware 2.24 and newer
		report[39] = 0x04
		report[44] = playerLeds
		ledColor.copyInto(report, 45)
		write(report)
	}

	private fun write(report: ByteArray)
	{
		val written = if(outEndpoint != null)
			connection.bulkTransfer(outEndpoint, report, report.size, TRANSFER_TIMEOUT_MS)
		else
			connection.controlTransfer(0x21, 0x09, 0x0200 or OUTPUT_REPORT_ID, usbInterface.id, report, report.size, TRANSFER_TIMEOUT_MS)
		if(written < 0)
			Log.w(TAG, "Failed to send output report")
	}

	private fun readLoop()
	{
		val buf = ByteArray(maxOf(inEndpoint.maxPacketSize, 64))
		var lastState: ControllerState? = null
		while(running)
		{
			val read = connection.bulkTransfer(inEndpoint, buf, buf.size, TRANSFER_TIMEOUT_MS * 2)
			if(read < 0)
			{
				// timeout or unplugged, the detach broadcast will close us
				Thread.sleep(10)
				continue
			}
			if(read < INPUT_REPORT_MIN_SIZE || buf[0].toInt() != INPUT_REPORT_ID)
				continue
			val state = parseInputReport(buf)
			if(state != lastState)
			{
				lastState = state
				onState(state)
			}
		}
	}

	private fun parseInputReport(report: ByteArray): ControllerState
	{
		fun u(i: Int) = report[i].toInt() and 0xff
		fun axis(i: Int) = (((u(i) shl 8) or u(i)) - 0x8000).toShort()
		fun bit(byte: Int, mask: Int, button: UInt) = if(byte and mask != 0) button else 0U

		val state = ControllerState()
		state.leftX = axis(1)
		state.leftY = axis(2)
		state.rightX = axis(3)
		state.rightY = axis(4)
		state.l2State = u(5).toUByte()
		state.r2State = u(6).toUByte()

		val b0 = u(8)
		val b1 = u(9)
		val b2 = u(10)
		val dpad = when(b0 and 0x0f)
		{
			0 -> ControllerState.BUTTON_DPAD_UP
			1 -> ControllerState.BUTTON_DPAD_UP or ControllerState.BUTTON_DPAD_RIGHT
			2 -> ControllerState.BUTTON_DPAD_RIGHT
			3 -> ControllerState.BUTTON_DPAD_DOWN or ControllerState.BUTTON_DPAD_RIGHT
			4 -> ControllerState.BUTTON_DPAD_DOWN
			5 -> ControllerState.BUTTON_DPAD_DOWN or ControllerState.BUTTON_DPAD_LEFT
			6 -> ControllerState.BUTTON_DPAD_LEFT
			7 -> ControllerState.BUTTON_DPAD_UP or ControllerState.BUTTON_DPAD_LEFT
			else -> 0U
		}
		state.buttons = dpad or
				bit(b0, 0x10, ControllerState.BUTTON_BOX) or
				bit(b0, 0x20, ControllerState.BUTTON_CROSS) or
				bit(b0, 0x40, ControllerState.BUTTON_MOON) or
				bit(b0, 0x80, ControllerState.BUTTON_PYRAMID) or
				bit(b1, 0x01, ControllerState.BUTTON_L1) or
				bit(b1, 0x02, ControllerState.BUTTON_R1) or
				bit(b1, 0x10, ControllerState.BUTTON_SHARE) or
				bit(b1, 0x20, ControllerState.BUTTON_OPTIONS) or
				bit(b1, 0x40, ControllerState.BUTTON_L3) or
				bit(b1, 0x80, ControllerState.BUTTON_R3) or
				bit(b2, 0x01, ControllerState.BUTTON_PS) or
				bit(b2, 0x02, ControllerState.BUTTON_TOUCHPAD)

		val touchpadWidth = ControllerState.TOUCHPAD_WIDTH.toInt()
		val touchpadHeight = ControllerState.TOUCHPAD_HEIGHT.toInt()
		for(i in 0 until 2)
		{
			val offset = 33 + i * 4
			val contact = u(offset)
			if(contact and 0x80 != 0)
				continue
			val x = u(offset + 1) or ((u(offset + 2) and 0x0f) shl 8)
			val y = (u(offset + 2) shr 4) or (u(offset + 3) shl 4)
			state.touches[i].id = (contact and 0x7f).toByte()
			state.touches[i].x = x.coerceIn(0, touchpadWidth - 1).toUShort()
			state.touches[i].y = (y * touchpadHeight / TOUCHPAD_NATIVE_HEIGHT).coerceIn(0, touchpadHeight - 1).toUShort()
		}
		return state
	}
}
