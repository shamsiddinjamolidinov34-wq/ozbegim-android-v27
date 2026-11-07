package uz.ekranoquvchi.mvp.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 bitli PCM (little-endian), kanallar ketma-ket (interleaved). */
internal class Pcm(val sampleRate: Int, val channels: Int, val data: ByteArray) {
    val frameBytes: Int
        get() = channels * 2
}

/**
 * Asosiy dvigatel synthesizeToFile bilan yozgan WAV faylni o'qiydi. Tizim (FileSynthesisCallback) doim 44 baytli
 * oddiy sarlavha yozadi, lekin boshqa tuzilishlar (qo'shimcha bo'laklar, 8/24/32 bit, float) ham qo'llab-quvvatlanadi:
 * hammasi 16 bitli PCM'ga o'tkaziladi, chunki SynthesisCallback'ga shu format beriladi.
 */
internal object WavReader {
    private const val MAX_FILE_BYTES = 24L * 1024L * 1024L
    private const val HEADER_MIN = 44L

    fun read(file: File): Pcm? {
        return try {
            if (!file.exists()) return null
            val len = file.length()
            if (len < HEADER_MIN || len > MAX_FILE_BYTES) return null
            parse(file.readBytes())
        } catch (_: Throwable) {
            null
        }
    }

    fun parse(b: ByteArray): Pcm? {
        if (b.size < 12) return null
        if (!tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var code = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var haveFmt = false
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= b.size) {
            val size = bb.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            if (tag(b, pos, "fmt ")) {
                if (size < 16L || body + 16 > b.size) return null
                code = bb.getShort(body).toInt() and 0xFFFF
                channels = bb.getShort(body + 2).toInt() and 0xFFFF
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt() and 0xFFFF
                if (code == 0xFFFE && size >= 26L && body + 26 <= b.size) {
                    code = bb.getShort(body + 24).toInt() and 0xFFFF
                }
                haveFmt = true
            } else if (tag(b, pos, "data")) {
                dataStart = body
                val remaining = (b.size - body).toLong()
                dataLen = (if (size > remaining) remaining else size).toInt()
                break
            }
            val next = body.toLong() + size + (size and 1L)
            if (next > b.size.toLong()) break
            pos = next.toInt()
        }
        if (!haveFmt || dataStart < 0) return null
        if (rate <= 0 || channels < 1 || channels > 2) return null
        val bytesPerSample = bits / 8
        if (bytesPerSample <= 0) return null
        val frame = channels * bytesPerSample
        val usable = dataLen - dataLen % frame
        val out = to16(b, dataStart, usable, code, bits) ?: return null
        return Pcm(rate, channels, out)
    }

    private fun tag(b: ByteArray, at: Int, s: String): Boolean {
        if (at + 4 > b.size) return false
        for (i in 0 until 4) if (b[at + i].toInt() != s[i].code) return false
        return true
    }

    private fun to16(b: ByteArray, start: Int, len: Int, code: Int, bits: Int): ByteArray? {
        if (len <= 0) return ByteArray(0)
        if (code == 1 && bits == 16) return b.copyOfRange(start, start + len)
        if (code == 1 && bits == 8) {
            val out = ByteArray(len * 2)
            for (i in 0 until len) {
                val v = ((b[start + i].toInt() and 0xFF) - 128) shl 8
                out[i * 2] = (v and 0xFF).toByte()
                out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            return out
        }
        if (code == 1 && bits == 24) {
            val n = len / 3
            val out = ByteArray(n * 2)
            for (i in 0 until n) {
                out[i * 2] = b[start + i * 3 + 1]
                out[i * 2 + 1] = b[start + i * 3 + 2]
            }
            return out
        }
        if (code == 1 && bits == 32) {
            val n = len / 4
            val out = ByteArray(n * 2)
            for (i in 0 until n) {
                out[i * 2] = b[start + i * 4 + 2]
                out[i * 2 + 1] = b[start + i * 4 + 3]
            }
            return out
        }
        if (code == 3 && bits == 32) {
            val n = len / 4
            val out = ByteArray(n * 2)
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) {
                val f = bb.getFloat(start + i * 4)
                var s = (f * 32767f).toInt()
                if (s > 32767) s = 32767
                if (s < -32768) s = -32768
                out[i * 2] = (s and 0xFF).toByte()
                out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            return out
        }
        return null
    }
}

/**
 * SynthesisCallback.start() bitta formatni qabul qiladi (bir marta). Bir so'rovning bo'laklari boshqa chastota yoki
 * kanal sonida kelib qolsa, birinchi bo'lak formatiga keltiriladi (kanal soni va chiziqli interpolyatsiya bilan chastota).
 */
internal object PcmConvert {
    fun to(pcm: Pcm, rate: Int, channels: Int): Pcm {
        if (pcm.sampleRate == rate && pcm.channels == channels) return pcm
        var samples = toShorts(pcm.data)
        var ch = pcm.channels
        if (ch != channels) {
            samples = if (ch == 2 && channels == 1) downmix(samples) else upmix(samples)
            ch = channels
        }
        if (pcm.sampleRate != rate) samples = resample(samples, ch, pcm.sampleRate, rate)
        return Pcm(rate, ch, toBytes(samples))
    }

    private fun toShorts(data: ByteArray): ShortArray {
        val n = data.size / 2
        val s = ShortArray(n)
        for (i in 0 until n) {
            s[i] = ((data[i * 2].toInt() and 0xFF) or (data[i * 2 + 1].toInt() shl 8)).toShort()
        }
        return s
    }

    private fun toBytes(s: ShortArray): ByteArray {
        val out = ByteArray(s.size * 2)
        for (i in s.indices) {
            val v = s[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun downmix(s: ShortArray): ShortArray {
        val n = s.size / 2
        val out = ShortArray(n)
        for (i in 0 until n) out[i] = ((s[i * 2].toInt() + s[i * 2 + 1].toInt()) / 2).toShort()
        return out
    }

    private fun upmix(s: ShortArray): ShortArray {
        val out = ShortArray(s.size * 2)
        for (i in s.indices) {
            out[i * 2] = s[i]
            out[i * 2 + 1] = s[i]
        }
        return out
    }

    private fun resample(s: ShortArray, ch: Int, from: Int, to: Int): ShortArray {
        val frames = s.size / ch
        if (frames == 0 || from <= 0 || to <= 0) return s
        var outFrames = ((frames.toLong() * to.toLong()) / from.toLong()).toInt()
        if (outFrames < 1) outFrames = 1
        val out = ShortArray(outFrames * ch)
        val ratio = from.toDouble() / to.toDouble()
        for (i in 0 until outFrames) {
            val pos = i * ratio
            var i0 = pos.toInt()
            if (i0 > frames - 1) i0 = frames - 1
            var i1 = i0 + 1
            if (i1 > frames - 1) i1 = frames - 1
            val frac = pos - i0
            for (c in 0 until ch) {
                val a = s[i0 * ch + c].toInt()
                val b = s[i1 * ch + c].toInt()
                out[i * ch + c] = (a + (b - a) * frac).toInt().toShort()
            }
        }
        return out
    }
}

/**
 * Ravon o'qish ("Urg'u berib o'qish" o'chiq): har bir bo'lakning boshi va oxiridagi jimlikni kesadi.
 *
 * Dvigatel har bo'lak oxiriga jimlik qo'shadi va ohangni pasaytiradi; bo'laklar ketma-ket ulanganda shu jimlik
 * gaplar orasida sezilarli to'xtalish bo'ladi. Bu yerda tovush kuchi eng baland qiymatning taxminan 1,5 foizidan
 * (minus 36 dB) past bo'lgan chekka qismlar olib tashlanadi; so'zning yumshoq oxiri (sh, s, f) kesilmasligi va
 * chertish bo'lmasligi uchun har tomonda 20 millisekund qoldiriladi. Jim yoki juda qisqa bo'lak o'zgarmaydi.
 */
internal object PcmEdge {
    private const val KEEP_MS = 20
    private const val MIN_PEAK = 64
    private const val MIN_THRESHOLD = 40

    fun trim(pcm: Pcm): Pcm {
        val ch = pcm.channels
        if (ch < 1 || pcm.sampleRate <= 0) return pcm
        val data = pcm.data
        val frame = pcm.frameBytes
        val frames = data.size / frame
        if (frames < 2) return pcm

        var peak = 0
        val total = frames * ch
        for (k in 0 until total) {
            val a = abs16(data, k)
            if (a > peak) peak = a
        }
        if (peak < MIN_PEAK) return pcm
        var thr = peak / 64
        if (thr < MIN_THRESHOLD) thr = MIN_THRESHOLD

        var first = -1
        var last = -1
        for (f in 0 until frames) {
            var loud = false
            for (c in 0 until ch) {
                if (abs16(data, f * ch + c) > thr) {
                    loud = true
                    break
                }
            }
            if (loud) {
                if (first < 0) first = f
                last = f
            }
        }
        if (first < 0) return pcm

        val keep = (pcm.sampleRate.toLong() * KEEP_MS / 1000L).toInt()
        var from = first - keep
        if (from < 0) from = 0
        var to = last + keep + 1
        if (to > frames) to = frames
        if (from == 0 && to == frames) return pcm
        return Pcm(pcm.sampleRate, ch, data.copyOfRange(from * frame, to * frame))
    }

    /** idx-chi 16 bitli (little-endian) namunaning moduli. */
    private fun abs16(d: ByteArray, idx: Int): Int {
        val lo = d[idx * 2].toInt() and 0xFF
        val hi = d[idx * 2 + 1].toInt()
        val v = (hi shl 8) or lo
        return if (v < 0) -v else v
    }
}
