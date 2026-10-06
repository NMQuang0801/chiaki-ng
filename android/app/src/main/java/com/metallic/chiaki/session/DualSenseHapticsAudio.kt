// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.content.Context
import android.media.*
import android.util.Log

private const val TAG = "DualSenseHapticsAudio"
private const val HAPTICS_SAMPLE_RATE = 3000
private const val OUTPUT_SAMPLE_RATE = 48000
private const val UPSAMPLE_FACTOR = OUTPUT_SAMPLE_RATE / HAPTICS_SAMPLE_RATE
private const val OUTPUT_CHANNELS = 4
private const val BUFFER_MS = 50

/**
 * Plays the console's haptics on the actuators of a USB DualSense, like the controller does on a PS5:
 * through its USB audio device, as channels 3 and 4 of a 4 channel 48 kHz stream.
 */
class DualSenseHapticsAudio private constructor(val device: AudioDeviceInfo, private val track: AudioTrack)
{
	companion object
	{
		private fun isDualSenseAudio(device: AudioDeviceInfo): Boolean
		{
			if(device.type != AudioDeviceInfo.TYPE_USB_DEVICE && device.type != AudioDeviceInfo.TYPE_USB_HEADSET)
				return false
			val name = device.productName?.toString() ?: return false
			if(!name.contains("DualSense", ignoreCase = true) && !name.contains("Wireless Controller", ignoreCase = true))
				return false
			val counts = device.channelCounts
			val masks = device.channelIndexMasks
			return (counts.isEmpty() && masks.isEmpty()) ||
					counts.any { it >= OUTPUT_CHANNELS } ||
					masks.any { Integer.bitCount(it) >= OUTPUT_CHANNELS }
		}

		fun findDevice(context: Context): AudioDeviceInfo? =
			(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
				.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
				.firstOrNull { isDualSenseAudio(it) }

		fun open(context: Context): DualSenseHapticsAudio?
		{
			val device = findDevice(context) ?: return null
			return try
			{
				val format = AudioFormat.Builder()
					.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
					.setSampleRate(OUTPUT_SAMPLE_RATE)
					.setChannelIndexMask((1 shl OUTPUT_CHANNELS) - 1)
					.build()
				val minBufferSize = AudioTrack.getMinBufferSize(OUTPUT_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_QUAD, AudioFormat.ENCODING_PCM_16BIT)
				val bufferSize = maxOf(minBufferSize, OUTPUT_SAMPLE_RATE * OUTPUT_CHANNELS * 2 * BUFFER_MS / 1000)
				val track = AudioTrack.Builder()
					.setAudioAttributes(AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_GAME)
						.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
						.build())
					.setAudioFormat(format)
					.setBufferSizeInBytes(bufferSize)
					.setTransferMode(AudioTrack.MODE_STREAM)
					.build()
				if(track.state != AudioTrack.STATE_INITIALIZED || !track.setPreferredDevice(device))
				{
					track.release()
					Log.w(TAG, "Could not open a 4 channel stream to ${device.productName}")
					return null
				}
				track.play()
				DualSenseHapticsAudio(device, track)
			}
			catch(e: Exception)
			{
				Log.w(TAG, "Could not open haptics audio", e)
				null
			}
		}
	}

	private val lock = Any()
	private var closed = false
	private var lastLeft = 0
	private var lastRight = 0
	private var output = ShortArray(0)

	/** frame: 16-bit little endian stereo PCM at 3 kHz. May be called from any thread. */
	fun write(frame: ByteArray): Unit = synchronized(lock) {
		if(closed)
			return
		val samples = frame.size / 4
		val outputSize = samples * UPSAMPLE_FACTOR * OUTPUT_CHANNELS
		if(output.size != outputSize)
			output = ShortArray(outputSize)
		var o = 0
		for(i in 0 until samples)
		{
			val left = ((frame[i * 4 + 1].toInt() shl 8) or (frame[i * 4].toInt() and 0xff)).toShort().toInt()
			val right = ((frame[i * 4 + 3].toInt() shl 8) or (frame[i * 4 + 2].toInt() and 0xff)).toShort().toInt()
			for(k in 1..UPSAMPLE_FACTOR)
			{
				output[o++] = 0
				output[o++] = 0
				output[o++] = (lastLeft + (left - lastLeft) * k / UPSAMPLE_FACTOR).toShort()
				output[o++] = (lastRight + (right - lastRight) * k / UPSAMPLE_FACTOR).toShort()
			}
			lastLeft = left
			lastRight = right
		}
		// Never block the network thread, dropping haptics on overflow is fine.
		track.write(output, 0, outputSize, AudioTrack.WRITE_NON_BLOCKING)
		Unit
	}

	fun close(): Unit = synchronized(lock) {
		if(closed)
			return
		closed = true
		try
		{
			track.stop()
		}
		catch(e: IllegalStateException) {}
		track.release()
	}
}
