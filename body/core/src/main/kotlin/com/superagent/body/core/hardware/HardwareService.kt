package com.superagent.body.core.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.superagent.common.HeadsetResult
import com.superagent.common.SayResult
import com.superagent.common.SensorResult

class HardwareService(private val context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val lastSensorValues = mutableMapOf<Int, FloatArray>()

    fun audioRoute(target: String): SayResult {
        when (target) {
            "earpiece" -> audio.mode = AudioManager.MODE_IN_COMMUNICATION
            "speaker" -> audio.mode = AudioManager.MODE_NORMAL
            "headset" -> {
                if (!headset().connected) throw IllegalStateException("耳机未插入")
                audio.mode = AudioManager.MODE_NORMAL
            }
            "auto" -> audio.mode = AudioManager.MODE_NORMAL
        }
        return SayResult(target)
    }

    fun vibrate(pattern: List<Int>, repeat: Int = -1) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (vibrator == null) return
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern.map { it.toLong() }.toLongArray(), repeat))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern.map { it.toLong() }.toLongArray(), repeat)
        }
    }

    fun sensor(type: String): SensorResult {
        val sensorType = when (type) {
            "proximity" -> Sensor.TYPE_PROXIMITY
            "light" -> Sensor.TYPE_LIGHT
            "step" -> Sensor.TYPE_STEP_COUNTER
            "motion" -> Sensor.TYPE_ACCELEROMETER
            else -> throw IllegalArgumentException("未知传感器: $type")
        }
        val sensor = sensorManager.getDefaultSensor(sensorType)
            ?: return SensorResult(type, 0.0, System.currentTimeMillis())
        val values = lastSensorValues[sensorType]
        if (values == null) {
            sensorManager.registerListener(onceListener(sensorType), sensor, SensorManager.SENSOR_DELAY_NORMAL)
            return SensorResult(type, 0.0, System.currentTimeMillis())
        }
        return SensorResult(type, values.firstOrNull()?.toDouble() ?: 0.0, System.currentTimeMillis())
    }

    private fun onceListener(sensorType: Int): SensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            lastSensorValues[sensorType] = event.values.clone()
            sensorManager.unregisterListener(this)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun headset(): HeadsetResult {
        if (Build.VERSION.SDK_INT >= 31) {
            val wired = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }
            val bt = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            return HeadsetResult(wired || bt, when { wired -> "wired"; bt -> "bluetooth"; else -> "none" })
        }
        @Suppress("DEPRECATION")
        return HeadsetResult(audio.isWiredHeadsetOn, "wired")
    }
}