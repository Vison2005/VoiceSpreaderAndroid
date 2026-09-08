package com.voicespreader.remote

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.roundToInt

/** protocol 3 的公共常量、JSON 和二进制帧编解码。 */
object ProtocolV3 {
    const val VERSION = 3
    const val MAX_CONTROL_FRAME_BYTES = 256 * 1024
    const val MAX_FILE_FRAME_BYTES = 1024 * 1024
    const val FILE_CHUNK_BYTES = 256 * 1024

    const val TYPE_INPUT_POINTER = 30
    const val TYPE_INPUT_BUTTON = 31
    const val TYPE_INPUT_SCROLL = 32
    const val TYPE_SHORTCUT_EXECUTE = 33
    const val TYPE_SHORTCUT_CATALOG = 34
    const val TYPE_FILE_OFFER = 40
    const val TYPE_FILE_ACCEPT = 41
    const val TYPE_FILE_CHUNK = 42
    const val TYPE_FILE_COMPLETE = 43
    const val TYPE_FILE_CANCEL = 44
    const val TYPE_SCAN_REQUEST = 50
    const val TYPE_SCAN_RESULT = 51
    const val TYPE_SCAN_ACK = 52
    const val TYPE_SCAN_CANCEL = 53
    const val TYPE_CAPTURE_REQUEST = 60
    const val TYPE_CAPTURE_RESULT = 61

    const val CHANNEL_CONTROL = "control"
    const val CHANNEL_FILE = "file"

    const val CAP_AUDIO = "audio"
    const val CAP_TOUCHPAD = "input.touchpad"
    const val CAP_SHORTCUT = "input.shortcut"
    const val CAP_APP_LAUNCH = "input.app"
    const val CAP_FILE_SEND = "file.send"
    const val CAP_FILE_RECEIVE = "file.receive"
    const val CAP_CAMERA_CAPTURE = "camera.capture"
    const val CAP_SCAN_CAMERA = "scan.camera"
    const val CAP_SCAN_GALLERY = "scan.gallery"

    enum class PointerAction(val value: Int) {
        DOWN(0),
        MOVE(1),
        UP(2),
        CANCEL(3),
    }

    data class PointerPayload(
        val sequence: Int,
        val timestampMicros: Long,
        val action: PointerAction,
        val pointerId: Int,
        val deltaX: Float,
        val deltaY: Float,
    )

    data class ButtonPayload(
        val sequence: Int,
        val timestampMicros: Long,
        val button: Int,
        val down: Boolean,
    )

    data class ScrollPayload(
        val sequence: Int,
        val timestampMicros: Long,
        val deltaX: Float,
        val deltaY: Float,
    )

    data class FileItem(
        val itemId: UUID,
        val name: String,
        val mime: String,
        val size: Long,
        val modifiedAt: Long,
    )

    fun newRequestId(): String = UUID.randomUUID().toString()

    fun newTransferId(): String = UUID.randomUUID().toString()

    fun buildHello(
        session: String,
        secret: String,
        deviceId: String,
        deviceName: String,
        capabilities: Collection<String>,
        channel: String = CHANNEL_CONTROL,
        transferId: String? = null,
    ): String {
        val value = JSONObject()
            .put("type", "hello")
            .put("protocol", VERSION)
            .put("session", session)
            .put("secret", secret)
            .put("deviceId", deviceId)
            .put("deviceName", deviceName)
            .put("capabilities", JSONArray(capabilities.toList()))
        if (channel != CHANNEL_CONTROL) value.put("channel", channel)
        if (!transferId.isNullOrBlank()) value.put("transferId", transferId)
        return value.toString() + "\n"
    }

    /** 写入外层长度和 type 字节，payload 不得包含 type。 */
    fun writeFrame(output: DataOutputStream, type: Int, payload: ByteArray, fileChannel: Boolean = false) {
        val maximum = if (fileChannel) MAX_FILE_FRAME_BYTES else MAX_CONTROL_FRAME_BYTES
        require(type in 0..255) { "帧类型超出范围" }
        require(payload.size + 1 in 1..maximum) { "帧长度超出限制" }
        synchronized(output) {
            output.writeInt(payload.size + 1)
            output.writeByte(type)
            output.write(payload)
            output.flush()
        }
    }

    fun encodePointer(value: PointerPayload): ByteArray = ByteBuffer
        .allocate(4 + 8 + 1 + 4 + 4 + 4)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(value.sequence)
        .putLong(value.timestampMicros)
        .put(value.action.value.toByte())
        .putInt(value.pointerId)
        .putInt((value.deltaX * 1000f).roundToInt())
        .putInt((value.deltaY * 1000f).roundToInt())
        .array()

    fun encodeButton(value: ButtonPayload): ByteArray = ByteBuffer
        .allocate(4 + 8 + 1 + 1)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(value.sequence)
        .putLong(value.timestampMicros)
        .put(value.button.toByte())
        .put(if (value.down) 1 else 0)
        .array()

    fun encodeScroll(value: ScrollPayload): ByteArray = ByteBuffer
        .allocate(4 + 8 + 4 + 4)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(value.sequence)
        .putLong(value.timestampMicros)
        .putInt((value.deltaX * 1000f).roundToInt())
        .putInt((value.deltaY * 1000f).roundToInt())
        .array()

    fun uuidBytes(value: UUID): ByteArray = ByteBuffer
        .allocate(16)
        .order(ByteOrder.BIG_ENDIAN)
        .putLong(value.mostSignificantBits)
        .putLong(value.leastSignificantBits)
        .array()

    fun jsonFrame(value: JSONObject): ByteArray = value.toString().toByteArray(Charsets.UTF_8)

    fun offerJson(transferId: String, items: Collection<FileItem>): JSONObject = JSONObject()
        .put("transferId", transferId)
        .put("items", JSONArray(items.map { item ->
            JSONObject()
                .put("itemId", item.itemId.toString())
                .put("name", item.name)
                .put("mime", item.mime)
                .put("size", item.size)
                .put("modifiedAt", item.modifiedAt)
        }))

    fun shortcutJson(
        requestId: String,
        name: String,
        modifiers: Collection<String>,
        key: String,
        repeat: Int = 1,
    ): JSONObject = JSONObject()
        .put("requestId", requestId)
        .put("name", name)
        .put("modifiers", JSONArray(modifiers.toList()))
        .put("key", key)
        .put("repeat", repeat)

    fun appLaunchJson(requestId: String, appId: String): JSONObject = JSONObject()
        .put("requestId", requestId)
        .put("action", "launchApp")
        .put("appId", appId)

    fun scanRequestJson(
        requestId: String,
        mode: String,
        formats: Collection<String> = listOf("QR_CODE"),
        autoOpen: Boolean = false,
    ): JSONObject = JSONObject()
        .put("requestId", requestId)
        .put("mode", mode)
        .put("formats", JSONArray(formats.toList()))
        .put("autoOpen", autoOpen)

    fun captureRequestJson(
        requestId: String,
        mode: String,
    ): JSONObject = JSONObject()
        .put("requestId", requestId)
        .put("mode", mode)

    fun outputFor(socketOutput: java.io.OutputStream): DataOutputStream =
        DataOutputStream(BufferedOutputStream(socketOutput, 32 * 1024))
}
