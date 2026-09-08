package com.voicespreader.remote

import org.json.JSONObject

data class PairingInfo(
    val host: String,
    val port: Int,
    val session: String,
    val secret: String,
) {
    companion object {
        fun fromQrPayload(payload: String): PairingInfo? {
            val parts = payload.trim().uppercase().split(':')
            if (parts.size != 5 || parts[0] != "VSP1") return null
            val port = parts[2].toIntOrNull() ?: return null
            if (!isValid(parts[1], port, parts[3], parts[4])) {
                return null
            }
            return PairingInfo(parts[1], port, parts[3], parts[4])
        }

        fun fromDiscoveryJson(json: String): PairingInfo? = runCatching {
            val value = JSONObject(json)
            if (value.getInt("protocol") != 1) return null
            val host = value.getString("host")
            val port = value.getInt("port")
            val session = value.getString("session").uppercase()
            val secret = value.getString("secret").uppercase()
            if (!isValid(host, port, session, secret)) return null
            PairingInfo(host, port, session, secret)
        }.getOrNull()

        private fun isValid(host: String, port: Int, session: String, secret: String): Boolean {
            val hex = Regex("^[0-9A-F]{32}$")
            return host.isNotBlank()
                && port in 1..65535
                && hex.matches(session)
                && hex.matches(secret)
        }
    }
}
