package com.layer.core.config

import com.layer.core.i18n.copy
import com.layer.core.model.VlessServerConfig

class DuplicateConnectionException(
    message: String = MESSAGE,
) : IllegalArgumentException(message) {
    companion object {
        val MESSAGE: String
            get() = copy("This connection already exists", "Такое соединение уже есть")
    }
}

object ConnectionIdentity {
    fun subscriptionKey(url: String): String = url.trim().trimEnd('/')

    fun matches(
        existingUuid: String,
        existingConfig: VlessServerConfig,
        incoming: ParsedVlessLink,
    ): Boolean {
        if (!existingUuid.equals(incoming.uuid, ignoreCase = true)) return false
        val server = incoming.server ?: return true
        return existingConfig.address.equals(server.address, ignoreCase = true) &&
            existingConfig.port == server.port &&
            existingConfig.security.equals(server.security, ignoreCase = true) &&
            existingConfig.publicKey == server.publicKey &&
            existingConfig.shortId.equals(server.shortId, ignoreCase = true)
    }
}
