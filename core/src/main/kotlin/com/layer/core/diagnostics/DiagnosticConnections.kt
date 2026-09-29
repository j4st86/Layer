package com.layer.core.diagnostics

/**
 * Connections worth keeping in a diagnostic report. Everything else would
 * push the screen-off history out of the buffer.
 *
 * Port 5222 is Telegram's own session. Port 5228 and mtalk are FCM. Play
 * Services and microG should not appear at all once they are outside the TUN;
 * a line for them means the exclude did not hold.
 */
object DiagnosticConnections {
    fun keep(destination: String, domain: String, packages: Collection<String>): Boolean {
        val port = destination.substringAfterLast(':', missingDelimiterValue = "")
        if (port == "5222" || port == "5228") return true
        if (domain.contains("mtalk.google.com", ignoreCase = true)) return true
        return packages.any { pkg ->
            pkg == "com.google.android.gms" ||
                pkg == "com.google.android.gsf" ||
                (pkg.contains(".android.gms") && !pkg.startsWith("com.google.android.gms"))
        }
    }
}
