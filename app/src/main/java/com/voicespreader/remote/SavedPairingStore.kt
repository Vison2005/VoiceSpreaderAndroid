package com.voicespreader.remote

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class SavedPairing(
    val name: String,
    val pairing: PairingInfo,
    val lastConnectedAt: Long,
)

/** 在应用私有存储中保存已确认成功的电脑配对信息，IP 变化时仍可通过 session 定位。 */
class SavedPairingStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun list(): List<SavedPairing> = runCatching {
        val raw = preferences.getString(KEY_DEVICES, null) ?: return emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val pairing = PairingInfo(
                    host = item.optString("host"),
                    port = item.optInt("port"),
                    session = item.optString("session").uppercase(),
                    secret = item.optString("secret").uppercase(),
                )
                if (pairing.host.isBlank()
                    || pairing.port !in 1..65535
                    || pairing.session.length != 32
                    || pairing.secret.length != 32
                    || !pairing.session.all { it in HEX_DIGITS }
                    || !pairing.secret.all { it in HEX_DIGITS }
                ) {
                    continue
                }
                add(
                    SavedPairing(
                        name = item.optString("name").ifBlank { defaultName(pairing) },
                        pairing = pairing,
                        lastConnectedAt = item.optLong("lastConnectedAt", 0L),
                    ),
                )
            }
        }.sortedByDescending { it.lastConnectedAt }
    }.getOrDefault(emptyList())

    @Synchronized
    fun remember(pairing: PairingInfo, name: String? = null) {
        val previous = list().firstOrNull {
            it.pairing.session.equals(pairing.session, ignoreCase = true)
        }
        val updated = buildList {
            add(
                SavedPairing(
                    name = name?.ifBlank { null }
                        ?: previous?.name
                        ?: defaultName(pairing),
                    pairing = pairing,
                    lastConnectedAt = System.currentTimeMillis(),
                ),
            )
            addAll(
                list().filterNot {
                    it.pairing.session.equals(pairing.session, ignoreCase = true)
                },
            )
        }.take(MAX_SAVED_DEVICES)
        persist(updated)
    }

    @Synchronized
    fun findBySession(session: String): PairingInfo? = list()
        .firstOrNull { it.pairing.session.equals(session, ignoreCase = true) }
        ?.pairing

    @Synchronized
    fun forget(session: String) {
        persist(
            list().filterNot {
                it.pairing.session.equals(session, ignoreCase = true)
            },
        )
    }

    private fun persist(devices: List<SavedPairing>) {
        val array = JSONArray()
        devices.forEach { saved ->
            array.put(
                JSONObject()
                    .put("name", saved.name)
                    .put("host", saved.pairing.host)
                    .put("port", saved.pairing.port)
                    .put("session", saved.pairing.session)
                    .put("secret", saved.pairing.secret)
                    .put("lastConnectedAt", saved.lastConnectedAt),
            )
        }
        preferences.edit { putString(KEY_DEVICES, array.toString()) }
    }

    private fun defaultName(pairing: PairingInfo): String = "Windows · ${pairing.host}"

    private companion object {
        const val PREFERENCES_NAME = "saved_pairings"
        const val KEY_DEVICES = "devices"
        const val MAX_SAVED_DEVICES = 8
        const val HEX_DIGITS = "0123456789ABCDEF"
    }
}
