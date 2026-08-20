package com.floatwm.launcher.core

import java.io.DataOutputStream

/**
 * Minimal `su` shell wrapper. Deliberately not using a library like libsu --
 * this is one-shot, best-effort, run-once-per-app-launch usage, not a
 * persistent interactive root shell, so the extra dependency isn't worth it
 * for what this needs. If you later want retry/streaming/persistent-shell
 * behavior, swap this out for topjohnwu's libsu; nothing else in this file
 * needs to change to do that.
 */
object RootShell {

    /** Blocking. Call from a background thread/dispatcher, never the main thread. */
    fun isRootAvailable(): Boolean = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        process.waitFor() == 0
    } catch (e: Exception) {
        false
    }

    /**
     * Runs [commands] in a single `su` session, in order. Returns true only
     * if the su process itself exited 0 -- this does NOT check the exit
     * code of each individual command, so a typo'd command that fails
     * silently inside the shell won't be caught here. Good enough for the
     * two fixed, hardcoded `settings put` calls this app actually uses;
     * don't reuse this for anything where a partial failure matters.
     */
    fun runAsRoot(vararg commands: String): Boolean = try {
        val process = Runtime.getRuntime().exec(arrayOf("su"))
        DataOutputStream(process.outputStream).use { stdin ->
            commands.forEach { cmd -> stdin.writeBytes("$cmd\n") }
            stdin.writeBytes("exit\n")
            stdin.flush()
        }
        process.waitFor() == 0
    } catch (e: Exception) {
        false
    }
}
