package com.theftguard.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Optional bridge to Shizuku. When Shizuku is running and the user has granted
 * permission, the alarm can turn mobile data, Wi-Fi and location on - actions a
 * normal app is not allowed to perform. Everything here is best-effort: if
 * Shizuku is absent or the binder is down, the calls quietly do nothing.
 */
object ShizukuManager {

    const val REQUEST_CODE = 4242
    private const val TAG = "ShizukuManager"

    private var service: IUserService? = null
    private var binding = false
    private var runWhenConnected = false

    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        isAvailable() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            if (isAvailable() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) {
            Log.w(TAG, "requestPermission failed", e)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            service = if (binder != null && binder.pingBinder()) {
                IUserService.Stub.asInterface(binder)
            } else {
                null
            }
            if (runWhenConnected) {
                runWhenConnected = false
                turnConnectivityOn()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private fun bind(context: Context) {
        if (service != null || binding || !hasPermission()) return
        binding = true
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, UserService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("shizuku")
                .version(2)
            Shizuku.bindUserService(args, connection)
        } catch (e: Throwable) {
            binding = false
            Log.w(TAG, "bindUserService failed", e)
        }
    }

    /** Turn on mobile data, Wi-Fi and location. Called when the alarm starts. */
    fun enableConnectivity(context: Context) {
        if (!hasPermission()) return
        if (service != null) {
            turnConnectivityOn()
        } else {
            runWhenConnected = true
            bind(context.applicationContext)
        }
    }

    private fun turnConnectivityOn() {
        val svc = service ?: return
        runCatching { svc.enableMobileData() }.onFailure { Log.w(TAG, "enableMobileData failed", it) }
        runCatching { svc.enableWifi() }.onFailure { Log.w(TAG, "enableWifi failed", it) }
        runCatching { svc.enableLocation() }.onFailure { Log.w(TAG, "enableLocation failed", it) }
    }
}
