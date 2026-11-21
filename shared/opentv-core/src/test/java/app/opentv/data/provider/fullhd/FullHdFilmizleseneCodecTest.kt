package app.opentv.data.provider.fullhd

import com.google.common.truth.Truth.assertThat
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Test

class FullHdFilmizleseneCodecTest {
    @Test
    fun scx_liveFixture_decodesRapidVidUrl() {
        val token = "nUE0pUZ6Yl9lLKOcMUMcMP5ipzpiqatiqwS4ZGV0Amt0ZTL="

        assertThat(FullHdFilmizleseneCodec.decodeScxItem(token))
            .isEqualTo("https://rapidvid.org/vx/v1x1247840f")
    }

    @Test
    fun extractScxUrls_ignoresNonUrlTokens() {
        val html = """
            <script>
              var scx = {
                "atom": {
                  "tt": "QXRvbQ==",
                  "sx": {
                    "t": ["nUE0pUZ6Yl9lLKOcMUMcMP5ipzpiqatiqwS4ZGV0Amt0ZTL="]
                  }
                }
              };
            </script>
        """.trimIndent()

        assertThat(FullHdFilmizleseneCodec.extractScxUrls(html))
            .containsExactly("https://rapidvid.org/vx/v1x1247840f")
    }

    @Test
    fun rapidVidP8_roundTripsPayload() {
        val payload = """
            {"cm":"https://cdn.example/video.m3u8","ct":[{"file":"https://cdn.example/tr.vtt","label":"Türkçe"}]}
        """.trimIndent()
        val token = encodeRapidVid(payload)
        val html = "<script>window._p8='$token';</script>"

        assertThat(FullHdFilmizleseneCodec.extractRapidVidPayloadJson(html))
            .isEqualTo(payload)
    }

    private fun encodeRapidVid(plain: String): String {
        val innerBase64 = Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))
        val key = "K9L"
        val shifted = buildString(innerBase64.length) {
            innerBase64.forEachIndexed { index, char ->
                val offset = key[index % key.length].code % 5 + 1
                append((char.code + offset).toChar())
            }
        }
        val outer = Base64.getEncoder().encodeToString(
            shifted.toByteArray(StandardCharsets.ISO_8859_1),
        )
        return outer.reversed()
    }
}
