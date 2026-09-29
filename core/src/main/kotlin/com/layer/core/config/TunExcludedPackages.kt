package com.layer.core.config

/**
 * Packages that must not enter the TUN. This is the system FCM socket and the
 * microG process that delivers pushes for patched Morphe apps. The Morphe
 * apps themselves stay in the tunnel.
 *
 * A route rule inside sing-box is not enough: the packet has already entered
 * the TUN, so the relay freezes with the process in Doze.
 */
object TunExcludedPackages {
    const val PLAY_SERVICES = "com.google.android.gms"
    const val SERVICES_FRAMEWORK = "com.google.android.gsf"

    fun resolve(ownPackageName: String, installedPackages: Collection<String>): List<String> {
        val installed = installedPackages.toSet()
        return buildList {
            if (ownPackageName.isNotBlank()) add(ownPackageName)
            if (PLAY_SERVICES in installed) add(PLAY_SERVICES)
            if (SERVICES_FRAMEWORK in installed) add(SERVICES_FRAMEWORK)
            installed.filter(::isMicroGPushPackage).forEach { add(it) }
        }.distinct()
    }

    /**
     * microG GmsCore only. Play Services and its siblings
     * (`com.google.android.gms.supervision`, location history) stay out of this
     * list: they are not the Morphe push process, and YouTube must stay in the tunnel.
     */
    fun isMicroGPushPackage(packageName: String): Boolean {
        return packageName.contains(".android.gms") &&
            !packageName.startsWith("com.google.android.gms")
    }
}
