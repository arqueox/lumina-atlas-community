package dev.lumina.atlas.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * DataSource que corrige o `level_idc` do SPS H.264 à passagem dos bytes.
 *
 * Alguns canais IPTV (ex.: SIC Notícias, CMTV) declaram Level 4.0 mas usam mais
 * reference frames do que esse nível permite a 1080p ("number of reference frames
 * exceeds max"). Os descodificadores de hardware rejeitam esses streams na
 * inicialização (ERROR_CODE_DECODER_INIT_FAILED), e o fallback por software fica
 * em câmara lenta. Ao reescrever o level para 5.1 (0x33) — que permite um DPB
 * muito maior — o descodificador de hardware aceita e reproduz normalmente.
 */
@UnstableApi
class SpsLevelPatchingDataSource(private val upstream: DataSource) : DataSource {

    private val chunk = 64 * 1024
    private var buf = ByteArray(0)
    private var pos = 0
    private var carry = ByteArray(0)
    private var eos = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        buf = ByteArray(0); pos = 0; carry = ByteArray(0); eos = false
        return upstream.open(dataSpec)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() = upstream.close()

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (pos >= buf.size && !fill()) return C.RESULT_END_OF_INPUT
        val n = minOf(length, buf.size - pos)
        System.arraycopy(buf, pos, target, offset, n)
        pos += n
        return n
    }

    private fun fill(): Boolean {
        while (true) {
            if (eos) {
                if (carry.isEmpty()) return false
                buf = carry; carry = ByteArray(0); pos = 0
                patch(buf, buf.size)
                return true
            }
            val tmp = ByteArray(chunk)
            val r = upstream.read(tmp, 0, chunk)
            if (r == C.RESULT_END_OF_INPUT) { eos = true; continue }
            if (r <= 0) continue
            val combined = ByteArray(carry.size + r)
            System.arraycopy(carry, 0, combined, 0, carry.size)
            System.arraycopy(tmp, 0, combined, carry.size, r)
            // Deixa uma pequena cauda por processar, para o caso de um SPS ficar a
            // meio da fronteira entre leituras (precisamos de até 7 bytes à frente).
            val keep = 8
            val patchLen = maxOf(0, combined.size - keep)
            if (patchLen == 0) { carry = combined; continue }
            patch(combined, patchLen)
            buf = combined.copyOfRange(0, patchLen)
            carry = combined.copyOfRange(patchLen, combined.size)
            pos = 0
            return true
        }
    }

    /** Procura start codes (00 00 01) seguidos de NAL SPS (tipo 7) e corrige o level. */
    private fun patch(a: ByteArray, len: Int) {
        var i = 0
        while (i + 6 < len) {
            if (a[i].toInt() == 0 && a[i + 1].toInt() == 0 && a[i + 2].toInt() == 1) {
                val nalType = a[i + 3].toInt() and 0x1F
                if (nalType == 7) {
                    val profile = a[i + 4].toInt() and 0xFF
                    // Valida que é mesmo um SPS (perfil H.264 conhecido) para não tocar
                    // em falsos positivos (ex.: início de PES com stream_id parecido).
                    if (profile in KNOWN_PROFILES) {
                        val cur = a[i + 6].toInt() and 0xFF
                        if (cur < TARGET_LEVEL) a[i + 6] = TARGET_LEVEL.toByte()
                    }
                }
                i += 3
            } else {
                i++
            }
        }
    }

    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            SpsLevelPatchingDataSource(upstreamFactory.createDataSource())
    }

    private companion object {
        const val TARGET_LEVEL = 0x33 // Level 5.1
        val KNOWN_PROFILES = setOf(66, 77, 88, 100, 110, 122, 244, 44, 83, 86, 118, 128)
    }
}
