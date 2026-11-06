package com.app.featuregate.console

import android.app.Activity
import android.app.featuregate.FeatureGateManager
import android.app.featuregate.FeatureRule
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import java.time.LocalTime

class MainActivity : Activity() {
    companion object {
        private const val TAG = "FeatureGateConsole"
    }

    // Created on first use; null if the service isn't available on this build
    private val manager: FeatureGateManager? by lazy {
        getSystemService(FeatureGateManager::class.java)
    }

    private lateinit var statusText: TextView
    private lateinit var rulesText: TextView
    private lateinit var eventsText: TextView
    private lateinit var featureInput: EditText
    private lateinit var percentInput: EditText
    private lateinit var chargingCheck: CheckBox

    // SAM conversion: a lambda becomes a FeatureGateManager.Listener.
    // Runs on the main thread because we register it with mainExecutor.
    private val listener = FeatureGateManager.Listener { featureId, allowed ->
        appendEvent("$featureId -> ${if (allowed) "ALLOWED" else "BLOCKED"}")
        refreshRules()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status)
        rulesText = findViewById(R.id.rules)
        eventsText = findViewById(R.id.events)
        featureInput = findViewById(R.id.feature_input)
        percentInput = findViewById(R.id.percent_input)
        chargingCheck = findViewById(R.id.charging_check)

        findViewById<Button>(R.id.check_button).setOnClickListener { onCheckClicked() }
        findViewById<Button>(R.id.set_button).setOnClickListener { onSetRuleClicked() }
        findViewById<Button>(R.id.remove_button).setOnClickListener { onRemoveClicked() }
        findViewById<Button>(R.id.camera_button).setOnClickListener { onOpenCameraClicked() }

        statusText.text = manager?.let { mgr ->
            "Connected. Supported =" + mgr.supportedFeatures.joinToString(", ")
        } ?: "Service not available"
    }

    override fun onStart() {
        super.onStart()
        manager?.registerListener(mainExecutor, listener) // callbacks on the main thread
        Log.i(TAG, "Registered listener")
        refreshRules()
    }

    override fun onStop() {
        manager?.unregisterListener(listener) // avoid leaking our stub in system_server
        Log.i(TAG, "Unregistered listener")
        super.onStop()
    }

    private fun onCheckClicked() {
        val id = featureId() ?: return
        val mgr = manager ?: return showNoService()
        statusText.text = safeCall { "$id: ${if (mgr.isFeatureAllowed(id)) "ALLOWED" else "BLOCKED"}" }
    }

    private fun onSetRuleClicked() {
        val id = featureId() ?: return
        val percent = percentInput.text.toString().toIntOrNull()
        if (percent == null) {
            statusText.text = "Enter a battery percent"
            return
        }
        val mgr = manager ?: return showNoService()
        statusText.text = safeCall {
            mgr.setRule(FeatureRule(id, percent, chargingCheck.isChecked))
            "Rule set for $id"
        }
        refreshRules()
    }

    private fun onRemoveClicked() {
        val id = featureId() ?: return
        val mgr = manager ?: return showNoService()
        statusText.text = safeCall {
            mgr.removeRule(id)
            "Rule removed for $id"
        }
        refreshRules()
    }

    private fun refreshRules() {
        val mgr = manager ?: return
        val rules = mgr.getRules()
        rulesText.text = if (rules.isEmpty()) {
            "No rules"
        } else {
            rules.joinToString("\n") { r ->
                val state = if (mgr.isFeatureAllowed(r.featureId)) "ALLOWED" else "BLOCKED"
                "${r.featureId}: min ${r.minBatteryPercent}%, charging=${r.allowWhenCharging} -> $state"
            }
        }
    }

    private fun featureId(): String? {
        val id = featureInput.text.toString().trim()
        if (id.isEmpty()) {
            statusText.text = "Enter a feature id"
            return null
        }
        return id
    }

    private fun appendEvent(message: String) {
        val time = LocalTime.now().withNano(0)
        eventsText.text = "$time: $message\n${eventsText.text}" // newest first
    }

    private fun showNoService() {
        statusText.text = "Service not available"
    }

    private fun onOpenCameraClicked() {
        val cameraManager = getSystemService(CameraManager::class.java);
        val id = cameraManager.cameraIdList.firstOrNull() ?: run {
            statusText.text = "No camera found"
            return;
        }
        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    statusText.text = "Camera $id OPENED"
                    camera.close()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    val disabled = error == CameraDevice.StateCallback.ERROR_CAMERA_DISABLED
                    statusText.text = "Camera error $error" + if (disabled) " (DISABLED)" else ""
                    camera.close()
                }
            }, null)
        } catch (e: CameraAccessException) {
            val disabled = e.reason == CameraAccessException.CAMERA_DISABLED
            statusText.text = "CameraAccessException reason=${e.reason}" + if (disabled) " (DISABLED)" else ""
        } catch (e: SecurityException) {
            statusText.text = "No CAMERA permission: ${e.message}"
        }
    }
}

/** Runs a service call and turns the service's exceptions into readable text. */
private inline fun safeCall(action: () -> String): String = try {
    action()
} catch (e: SecurityException) {
    "Not allowed: ${e.message}"
} catch (e: IllegalArgumentException) {
    "Invalid: ${e.message}"
}
