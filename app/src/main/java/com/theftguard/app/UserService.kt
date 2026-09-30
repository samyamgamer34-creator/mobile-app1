package com.theftguard.app

import android.util.Log
import kotlin.system.exitProcess

/**
 * Runs inside the Shizuku-spawned process (ADB/shell uid). It exposes only three
 * fixed actions - enable mobile data, Wi-Fi and location - each of which runs a
 * hardcoded command. It deliberately does NOT accept an arbitrary command string,
 * so it cannot be used as a general remote shell. Shizuku instantiates this via
 * its no-argument constructor.
 */
class UserService : IUserService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exit() {
        destroy()
    }

    override fun enableMobileData(): Boolean = run("svc", "data", "enable")

    override fun enableWifi(): Boolean = run("svc", "wifi", "enable")

    // location_mode 3 = high accuracy (GPS + network).
    override fun enableLocation(): Boolean = run("settings", "put", "secure", "location_mode", "3")

    /**
     * Runs one fixed command (argument vector, not a shell string) and reports
     * whether it exited cleanly.
     */
    private fun run(vararg command: String): Boolean = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.inputStream.readBytes() // drain so the process doesn't block
        process.waitFor() == 0
    } catch (e: Exception) {
        Log.w("UserService", "command failed: ${command.joinToString(" ")}", e)
        false
    }
}
