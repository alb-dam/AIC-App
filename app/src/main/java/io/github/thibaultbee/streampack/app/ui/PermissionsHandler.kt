package io.github.thibaultbee.streampack.app.ui

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Self-contained permissions handler using the Activity Result API.
 *
 * Must be instantiated **before** the Activity reaches STARTED state
 * (i.e. as a property initializer or in `onCreate` before `super.onCreate`).
 */
class PermissionsHandler(
    private val activity: ComponentActivity,
    private val permissions: List<String>,
    private val onGranted: () -> Unit,
    private val onRationale: (permissions: List<String>, retry: () -> Unit) -> Unit,
    private val onDenied: (permissions: List<String>) -> Unit
) {

    private val launcher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val denied = results.filterValues { !it }.keys.toList()
            when {
                denied.isEmpty() -> onGranted()
                denied.any { activity.shouldShowRequestPermissionRationale(it) } -> {
                    onRationale(denied) { launchPermissions(denied.toTypedArray()) }
                }
                else -> onDenied(denied)
            }
        }

    private fun launchPermissions(perms: Array<String>) {
        launcher.launch(perms)
    }

    /** Request all permissions, or invoke [onGranted] immediately if already held. */
    fun request() {
        if (hasAll()) {
            onGranted()
        } else {
            val needsRationale = permissions.filter {
                activity.shouldShowRequestPermissionRationale(it)
            }
            if (needsRationale.isNotEmpty()) {
                onRationale(needsRationale) { launchPermissions(needsRationale.toTypedArray()) }
            } else {
                launchPermissions(permissions.toTypedArray())
            }
        }
    }

    private fun hasAll(): Boolean = permissions.all {
        activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
}

