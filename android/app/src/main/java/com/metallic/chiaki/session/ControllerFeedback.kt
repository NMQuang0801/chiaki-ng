// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.content.Context
import android.graphics.Color
import android.hardware.lights.Light
import android.hardware.lights.LightState
import android.hardware.lights.LightsManager
import android.hardware.lights.LightsRequest
import android.os.*
import android.view.InputDevice
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.*

private const val RUMBLE_DURATION_MS = 5000L
private const val HAPTIC_RUMBLE_TIMEOUT_MS = 150L

/**
 * Routes rumble, adaptive trigger and light events from the console to, in order of preference:
 * a DualSense driven over USB, gamepads exposing vibrators and lights (Android 12+), or the phone's vibrator.
 * Must be used from the main thread, except for onEvent.
 */
class ControllerFeedback(private val context: Context, private val preferences: Preferences)
{
	private val handler = Handler(Looper.getMainLooper())
	private val rumbleEnabled = preferences.rumbleEnabled

	var usbController: DualSenseUsb? = null
		set(value)
		{
			field = value
			value?.let { applyStateTo(it) }
		}

	private var rumbleLeft: UByte = 0U
	private var rumbleRight: UByte = 0U
	private var hapticStrength: UByte = 0U
	private var appliedRumble: Pair<UByte, UByte> = 0.toUByte() to 0.toUByte()
	private var triggerEffects: TriggerEffectsEvent? = null
	private var ledColor: LedColorEvent? = null
	private var playerIndex: PlayerIndexEvent? = null
	private var intensity: DualSenseIntensityEvent? = null

	private val gamepads = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GamepadFeedback() else null
	private val phoneVibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

	private val hapticTimeout = Runnable {
		hapticStrength = 0U
		updateRumble()
	}

	/** Written to directly from the native thread, set from the main thread */
	@Volatile var hapticsAudio: DualSenseHapticsAudio? = null

	/** May be called from any thread */
	fun onEvent(event: Event)
	{
		if(event is HapticsFrameEvent)
		{
			hapticsAudio?.write(event.frame)
			return
		}
		handler.post { handleEvent(event) }
	}

	private fun handleEvent(event: Event)
	{
		when(event)
		{
			is RumbleEvent -> {
				rumbleLeft = event.left
				rumbleRight = event.right
				updateRumble()
			}
			is HapticRumbleEvent -> {
				// Haptics only arrive while something is playing, so stop on our own once they dry up.
				hapticStrength = event.strength
				handler.removeCallbacks(hapticTimeout)
				handler.postDelayed(hapticTimeout, HAPTIC_RUMBLE_TIMEOUT_MS)
				updateRumble()
			}
			is TriggerEffectsEvent -> {
				triggerEffects = event
				usbController?.setTriggerEffects(event.typeLeft, event.left, event.typeRight, event.right)
			}
			is LedColorEvent -> {
				ledColor = event
				usbController?.setLedColor(event.red, event.green, event.blue) ?: gamepads?.setLights(ledColor, playerIndex)
			}
			is PlayerIndexEvent -> {
				playerIndex = event
				usbController?.setPlayerIndex(event.index) ?: gamepads?.setLights(ledColor, playerIndex)
			}
			is DualSenseIntensityEvent -> {
				intensity = event
				usbController?.setIntensity(event.value)
			}
			else -> {}
		}
	}

	private fun applyStateTo(controller: DualSenseUsb)
	{
		intensity?.let { controller.setIntensity(it.value) }
		triggerEffects?.let { controller.setTriggerEffects(it.typeLeft, it.left, it.typeRight, it.right) }
		ledColor?.let { controller.setLedColor(it.red, it.green, it.blue) }
		playerIndex?.let { controller.setPlayerIndex(it.index) }
		appliedRumble = 0.toUByte() to 0.toUByte()
		cancelPhoneAndGamepadRumble()
		updateRumble()
	}

	private fun updateRumble()
	{
		if(!rumbleEnabled)
			return
		val rumble = maxOf(rumbleLeft, hapticStrength) to maxOf(rumbleRight, hapticStrength)
		if(rumble == appliedRumble)
			return
		appliedRumble = rumble
		val (left, right) = rumble

		val usbController = usbController
		if(usbController != null)
		{
			usbController.setRumble(left, right)
			return
		}
		if(gamepads?.rumble(left, right) == true)
			return
		phoneRumble(left, right)
	}

	private fun phoneRumble(left: UByte, right: UByte)
	{
		val amplitude = minOf(255, (left.toInt() + right.toInt()) / 2)
		phoneVibrator.cancel()
		if(amplitude == 0)
			return
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			phoneVibrator.vibrate(VibrationEffect.createOneShot(1000, amplitude))
		else
		{
			@Suppress("DEPRECATION")
			phoneVibrator.vibrate(1000)
		}
	}

	private fun cancelPhoneAndGamepadRumble()
	{
		phoneVibrator.cancel()
		gamepads?.rumble(0U, 0U)
	}

	fun close()
	{
		handler.removeCallbacksAndMessages(null)
		cancelPhoneAndGamepadRumble()
		gamepads?.close()
	}

	/** Vibrators and lights of gamepads are only exposed since Android 12 */
	private class GamepadFeedback
	{
		private val lightsSessions = mutableMapOf<Int, LightsManager.LightsSession>()

		private fun gamepads(): List<InputDevice> =
			InputDevice.getDeviceIds()
				.asList()
				.mapNotNull { InputDevice.getDevice(it) }
				.filter { !it.isVirtual && it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD }

		/** @return whether any gamepad can rumble */
		fun rumble(left: UByte, right: UByte): Boolean
		{
			if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
				return false
			var handled = false
			for(device in gamepads())
			{
				val vibratorManager = device.vibratorManager
				val ids = vibratorManager.vibratorIds
				if(ids.isEmpty())
					continue
				handled = true
				if(left.toInt() == 0 && right.toInt() == 0)
				{
					vibratorManager.cancel()
					continue
				}
				val combined = CombinedVibration.startParallel()
				ids.forEachIndexed { i, id ->
					val amplitude = when
					{
						ids.size == 1 -> maxOf(left, right)
						i == 0 -> left
						i == 1 -> right
						else -> maxOf(left, right)
					}.toInt()
					if(amplitude > 0)
						combined.addVibrator(id, VibrationEffect.createOneShot(RUMBLE_DURATION_MS, amplitude))
				}
				vibratorManager.vibrate(combined.combine())
			}
			return handled
		}

		fun setLights(color: LedColorEvent?, playerIndex: PlayerIndexEvent?)
		{
			if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
				return
			for(device in gamepads())
			{
				val lightsManager = device.lightsManager
				val lights = lightsManager.lights
				if(lights.isEmpty())
					continue
				val request = LightsRequest.Builder()
				var any = false
				for(light in lights)
				{
					val state = when
					{
						light.type == Light.LIGHT_TYPE_PLAYER_ID && playerIndex != null ->
							LightState.Builder().setPlayerId(playerIndex.index.toInt()).build()
						light.hasRgbControl() && color != null ->
							LightState.Builder().setColor(Color.rgb(color.red.toInt(), color.green.toInt(), color.blue.toInt())).build()
						else -> null
					} ?: continue
					request.addLight(light, state)
					any = true
				}
				if(!any)
					continue
				lightsSessions.getOrPut(device.id) { lightsManager.openSession() }.requestLights(request.build())
			}
		}

		fun close()
		{
			if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
				return
			lightsSessions.values.forEach { it.close() }
			lightsSessions.clear()
		}
	}
}
