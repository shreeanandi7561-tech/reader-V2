package com.reader.app.data.local

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Room TypeConverters for non-primitive fields we persist.
 *
 * [FloatArray] is encoded as little-endian IEEE-754 bytes so on-disk size is
 * exactly `4 * length` and we don't pay a JSON tax for embeddings.
 */
class Converters {

    @TypeConverter
    fun fromFloatArray(value: FloatArray?): ByteArray? {
        if (value == null) return null
        val buf = ByteBuffer.allocate(value.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in value) buf.putFloat(f)
        return buf.array()
    }

    @TypeConverter
    fun toFloatArray(value: ByteArray?): FloatArray? {
        if (value == null) return null
        val buf = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(value.size / 4)
        for (i in out.indices) out[i] = buf.float
        return out
    }
}
