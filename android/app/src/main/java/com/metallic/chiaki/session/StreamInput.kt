package com.metallic.chiaki.session

import android.content.Context
import android.hardware.*
import android.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.ControllerState

private const val SONY_VENDOR_ID = 0x054C

class StreamInput(val context: Context, val preferences: Preferences)
{
	var controllerStateChangedCallback: ((ControllerState) -> Unit)? = null

	val controllerState: ControllerState get()
	{
		val controllerState = sensorControllerState or keyControllerState or motionControllerState

		val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
		@Suppress("DEPRECATION")
		when(windowManager.defaultDisplay.rotation)
		{
			Surface.ROTATION_90 -> {
				controllerState.accelX *= -1.0f
				controllerState.accelZ *= -1.0f
				controllerState.gyroX *= -1.0f
				controllerState.gyroZ *= -1.0f
				controllerState.orientX *= -1.0f
				controllerState.orientZ *= -1.0f
			}
			else -> {}
		}

		// prioritize motion controller's l2 and r2 over key
		// (some controllers send only key, others both but key earlier than full press)
		if(motionControllerState.l2State > 0U)
			controllerState.l2State = motionControllerState.l2State
		if(motionControllerState.r2State > 0U)
			controllerState.r2State = motionControllerState.r2State

		return controllerState or touchControllerState
	}

	private val sensorControllerState = ControllerState() // from Motion Sensors
	private val keyControllerState = ControllerState() // from KeyEvents
	private val motionControllerState = ControllerState() // from MotionEvents
	var touchControllerState = ControllerState()
		set(value)
		{
			field = value
			controllerStateUpdated()
		}

	private val swapCrossMoon = preferences.swapCrossMoon
	private val controllerLayout = preferences.controllerLayout
	private val rawPlayStationLayoutByDevice = mutableMapOf<Int, Boolean>()

	private fun usesRawPlayStationLayout(device: InputDevice?): Boolean = when(controllerLayout)
	{
		Preferences.ControllerLayout.STANDARD -> false
		Preferences.ControllerLayout.PLAYSTATION_RAW -> true
		Preferences.ControllerLayout.AUTO ->
			device != null && rawPlayStationLayoutByDevice.getOrPut(device.id) { detectRawPlayStationLayout(device) }
	}

	/**
	 * Without a vendor key layout (e.g. DualSense before Android 12), Android exposes a Sony controller's
	 * HID buttons in report order: Square = BUTTON_A, Cross = BUTTON_B, Circle = BUTTON_C, ...
	 * A properly mapped controller never reports BUTTON_C or BUTTON_Z.
	 */
	private fun detectRawPlayStationLayout(device: InputDevice): Boolean
	{
		if(device.vendorId != SONY_VENDOR_ID)
			return false
		val hasKeys = device.hasKeys(KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_Z)
		return hasKeys[0] && hasKeys[1]
	}

	private fun standardButtonMask(keyCode: Int): UInt? = when(keyCode)
	{
		KeyEvent.KEYCODE_BUTTON_A -> if(swapCrossMoon) ControllerState.BUTTON_MOON else ControllerState.BUTTON_CROSS
		KeyEvent.KEYCODE_BUTTON_B -> if(swapCrossMoon) ControllerState.BUTTON_CROSS else ControllerState.BUTTON_MOON
		KeyEvent.KEYCODE_BUTTON_X -> if(swapCrossMoon) ControllerState.BUTTON_PYRAMID else ControllerState.BUTTON_BOX
		KeyEvent.KEYCODE_BUTTON_Y -> if(swapCrossMoon) ControllerState.BUTTON_BOX else ControllerState.BUTTON_PYRAMID
		KeyEvent.KEYCODE_BUTTON_L1 -> ControllerState.BUTTON_L1
		KeyEvent.KEYCODE_BUTTON_R1 -> ControllerState.BUTTON_R1
		KeyEvent.KEYCODE_BUTTON_THUMBL -> ControllerState.BUTTON_L3
		KeyEvent.KEYCODE_BUTTON_THUMBR -> ControllerState.BUTTON_R3
		KeyEvent.KEYCODE_BUTTON_SELECT -> ControllerState.BUTTON_SHARE
		KeyEvent.KEYCODE_BUTTON_START -> ControllerState.BUTTON_OPTIONS
		KeyEvent.KEYCODE_BUTTON_C -> ControllerState.BUTTON_PS
		KeyEvent.KEYCODE_BUTTON_MODE -> ControllerState.BUTTON_PS
		else -> null
	}

	private fun rawPlayStationButtonMask(keyCode: Int): UInt? = when(keyCode)
	{
		KeyEvent.KEYCODE_BUTTON_A -> ControllerState.BUTTON_BOX
		KeyEvent.KEYCODE_BUTTON_B -> ControllerState.BUTTON_CROSS
		KeyEvent.KEYCODE_BUTTON_C -> ControllerState.BUTTON_MOON
		KeyEvent.KEYCODE_BUTTON_X -> ControllerState.BUTTON_PYRAMID
		KeyEvent.KEYCODE_BUTTON_Y -> ControllerState.BUTTON_L1
		KeyEvent.KEYCODE_BUTTON_Z -> ControllerState.BUTTON_R1
		KeyEvent.KEYCODE_BUTTON_L2 -> ControllerState.BUTTON_SHARE
		KeyEvent.KEYCODE_BUTTON_R2 -> ControllerState.BUTTON_OPTIONS
		KeyEvent.KEYCODE_BUTTON_SELECT -> ControllerState.BUTTON_L3
		KeyEvent.KEYCODE_BUTTON_START -> ControllerState.BUTTON_R3
		KeyEvent.KEYCODE_BUTTON_MODE -> ControllerState.BUTTON_PS
		KeyEvent.KEYCODE_BUTTON_THUMBL -> ControllerState.BUTTON_TOUCHPAD
		else -> null
	}

	private val sensorEventListener = object: SensorEventListener {
		override fun onSensorChanged(event: SensorEvent)
		{
			when(event.sensor.type)
			{
				Sensor.TYPE_ACCELEROMETER -> {
					sensorControllerState.accelX = event.values[1] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelY = event.values[2] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelZ = event.values[0] / SensorManager.GRAVITY_EARTH
				}
				Sensor.TYPE_GYROSCOPE -> {
					sensorControllerState.gyroX = event.values[1]
					sensorControllerState.gyroY = event.values[2]
					sensorControllerState.gyroZ = event.values[0]
				}
				Sensor.TYPE_ROTATION_VECTOR -> {
					val q = floatArrayOf(0f, 0f, 0f, 0f)
					SensorManager.getQuaternionFromVector(q, event.values)
					sensorControllerState.orientX = q[2]
					sensorControllerState.orientY = q[3]
					sensorControllerState.orientZ = q[1]
					sensorControllerState.orientW = q[0]
				}
				else -> return
			}
			controllerStateUpdated()
		}

		override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
	}

	private val motionLifecycleObserver = object: LifecycleObserver {
		@OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
		fun onResume()
		{
			val samplingPeriodUs = 4000
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			listOfNotNull(
				sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
				sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
				sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
			).forEach {
				sensorManager.registerListener(sensorEventListener, it, samplingPeriodUs)
			}
		}

		@OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
		fun onPause()
		{
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			sensorManager.unregisterListener(sensorEventListener)
		}
	}

	fun observe(lifecycleOwner: LifecycleOwner)
	{
		if(preferences.motionEnabled)
			lifecycleOwner.lifecycle.addObserver(motionLifecycleObserver)
	}

	private fun controllerStateUpdated()
	{
		controllerStateChangedCallback?.let { it(controllerState) }
	}

	fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		//Log.i("StreamSession", "key event $event")
		if(event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)
			return false

		val raw = usesRawPlayStationLayout(event.device)
		val pressed = event.action == KeyEvent.ACTION_DOWN
		val (l2Key, r2Key) =
			if(raw) KeyEvent.KEYCODE_BUTTON_L1 to KeyEvent.KEYCODE_BUTTON_R1
			else KeyEvent.KEYCODE_BUTTON_L2 to KeyEvent.KEYCODE_BUTTON_R2
		when(event.keyCode)
		{
			l2Key -> {
				keyControllerState.l2State = if(pressed) UByte.MAX_VALUE else 0U
				controllerStateUpdated()
				return true
			}
			r2Key -> {
				keyControllerState.r2State = if(pressed) UByte.MAX_VALUE else 0U
				controllerStateUpdated()
				return true
			}
		}

		// dpad is handled by MotionEvents
		val buttonMask = (if(raw) rawPlayStationButtonMask(event.keyCode) else standardButtonMask(event.keyCode))
			?: return false

		keyControllerState.buttons = keyControllerState.buttons.run {
			when(event.action)
			{
				KeyEvent.ACTION_DOWN -> this or buttonMask
				KeyEvent.ACTION_UP -> this and buttonMask.inv()
				else -> this
			}
		}

		controllerStateUpdated()
		return true
	}

	fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		if(event.source and InputDevice.SOURCE_CLASS_JOYSTICK != InputDevice.SOURCE_CLASS_JOYSTICK)
			return false
		fun Float.signedAxis() = (this * Short.MAX_VALUE).toInt().toShort()
		fun Float.unsignedAxis() = (this * UByte.MAX_VALUE.toFloat()).toUInt().toUByte()
		fun triggerAxis(axis: Int): Float
		{
			val value = event.getAxisValue(axis)
			val range = event.device?.getMotionRange(axis, event.source) ?: return value.coerceIn(0f, 1f)
			if(range.range <= 0f)
				return value.coerceIn(0f, 1f)
			return ((value - range.min) / range.range).coerceIn(0f, 1f)
		}
		val raw = usesRawPlayStationLayout(event.device)
		motionControllerState.leftX = event.getAxisValue(MotionEvent.AXIS_X).signedAxis()
		motionControllerState.leftY = event.getAxisValue(MotionEvent.AXIS_Y).signedAxis()
		motionControllerState.rightX = event.getAxisValue(MotionEvent.AXIS_Z).signedAxis()
		motionControllerState.rightY = event.getAxisValue(MotionEvent.AXIS_RZ).signedAxis()
		motionControllerState.l2State = triggerAxis(if(raw) MotionEvent.AXIS_RX else MotionEvent.AXIS_LTRIGGER).unsignedAxis()
		motionControllerState.r2State = triggerAxis(if(raw) MotionEvent.AXIS_RY else MotionEvent.AXIS_RTRIGGER).unsignedAxis()
		motionControllerState.buttons = motionControllerState.buttons.let {
			val dpadX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
			val dpadY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
			val dpadButtons =
				(if(dpadX > 0.5f) ControllerState.BUTTON_DPAD_RIGHT else 0U) or
						(if(dpadX < -0.5f) ControllerState.BUTTON_DPAD_LEFT else 0U) or
						(if(dpadY > 0.5f) ControllerState.BUTTON_DPAD_DOWN else 0U) or
						(if(dpadY < -0.5f) ControllerState.BUTTON_DPAD_UP else 0U)
			it and (ControllerState.BUTTON_DPAD_RIGHT or
					ControllerState.BUTTON_DPAD_LEFT or
					ControllerState.BUTTON_DPAD_DOWN or
					ControllerState.BUTTON_DPAD_UP).inv() or
					dpadButtons
		}
		//Log.i("StreamSession", "motionEvent => $motionControllerState")
		controllerStateUpdated()
		return true
	}
}