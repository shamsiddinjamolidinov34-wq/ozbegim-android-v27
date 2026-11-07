package uz.ekranoquvchi.mvp.tts

import android.content.Context
import android.os.SystemClock
import uz.ekranoquvchi.mvp.R
import uz.ekranoquvchi.mvp.content.ContentPipeline
import uz.ekranoquvchi.mvp.content.EmojiMode
import uz.ekranoquvchi.mvp.content.FluentText
import uz.ekranoquvchi.mvp.content.SpeechNormalizer
import uz.ekranoquvchi.mvp.content.TextLang
import uz.ekranoquvchi.mvp.diag.Diag
import uz.ekranoquvchi.mvp.diag.ErrorCode
import uz.ekranoquvchi.mvp.diag.swallowed
import uz.ekranoquvchi.mvp.util.Prefs
import java.util.Locale

/**
 * Tizim dvigateli orqali kelgan matnni O'zbegimning o'z qoidalari bo'yicha tayyorlaydi (ekran o'quvchidagi bilan bir xil
 * quvur: ContentPipeline). O'zbekcha so'rovda: uch yozuv (lotin, kirill, yangi lotin), raqamlar, Rim raqamlari,
 * telefon raqamlari, emoji, til ko'prigi. Boshqa tillarda matn deyarli o'zgarishsiz qoladi. Tarmoqqa hech narsa chiqmaydi.
 *
 * Raqamlar haqida (muhim): orqa dvigatel raqamni o'zicha o'qiydi va o'zbekchada xato qiladi ("10" - "on", "90" - "toqson",
 * "105" - "yuzda 5", "1500" - "mingda 500"). Shuning uchun o'zbekcha matnda raqam HECH QACHON dvigatelga xom ketmaydi:
 *  - o'zbekchami yo'qmi, avval Tafsilotlar > Matn tili tanloviga, "Avtomatik" bo'lsa so'ralgan tilga qarab aniqlanadi
 *    (ilgari faqat so'ralgan til hisobga olinardi va tanlov e'tiborsiz qolardi);
 *  - matn qoidalari o'chiq bo'lsa ham, o'zbekcha matndagi raqamlar so'zga aylantiriladi;
 *  - quvur xato bersa, xato jurnalga yoziladi (ilgari jimgina asl matn berilardi) va kamida raqamlar so'zga aylantiriladi.
 */
internal object TtsText {
    @Volatile
    private var lastErrorLogAt = 0L

    /** So'rov o'zbekchami: "uz" doim o'zbekcha, "other" doim boshqa til; "Avtomatik" va "Tizim tili" so'ralgan tilga qarab. */
    fun isUzbek(c: Context, locale: Locale): Boolean = when (Prefs.textLang(c)) {
        TextLang.UZ -> true
        TextLang.OTHER -> false
        else -> TextLang.isUzbekTag(locale.language)
    }

    /** @param rules O'zbegim matn qoidalari yoqiqmi (Tizim dvigateli sozlamalari). O'chiq bo'lsa ham raqamlar so'zga aylanadi. */
    fun prepare(c: Context, text: String, locale: Locale, backendPackage: String?, rules: Boolean = true): String {
        if (text.isEmpty()) return text
        val uz = isUzbek(c, locale)
        if (rules) {
            try {
                return full(c, text, uz, backendPackage)
            } catch (t: Throwable) {
                swallowed("TtsText.prepare", t)
                logFailure("prepare_failed", t)
            }
        }
        return minimal(c, text, uz)
    }

    private fun full(c: Context, text: String, uz: Boolean, backendPackage: String?): String {
        val base = if (uz) {
            Prefs.pipelineOptions(c, Prefs.engineReadsCyrillic(c, backendPackage))
        } else {
            // So'ralgan til o'zbekcha emas: o'zbekcha raqam so'zlari va fan qisqartmalari qo'llanmaydi
            Prefs.otherLangOptions(c)
        }
        val emojiMode = Prefs.emojiMode(c)
        val opts = base.copy(
            emoji = if (uz || emojiMode == EmojiMode.SKIP) emojiMode else EmojiMode.KEEP,
            emojiWord = c.getString(R.string.emoji_word),
        )
        return ContentPipeline.process(text, opts).text
    }

    /**
     * Faqat eng zarur ishlar: ravon rejim yoqiq bo'lsa tinish belgilari olinadi, o'zbekcha matnda raqamlar so'zga aylanadi.
     * Qoidalar o'chirilganda yoki quvur xato berganda ishlatiladi.
     */
    private fun minimal(c: Context, text: String, uz: Boolean): String {
        var t = text
        val fluent = !Prefs.prosody(c)
        if (fluent) {
            try {
                t = FluentText.flatten(t)
            } catch (ex: Throwable) {
                swallowed("TtsText.minimal.flatten", ex)
            }
        }
        if (uz) {
            try {
                t = SpeechNormalizer.sweepDigits(SpeechNormalizer.normalize(t, false, fluent))
            } catch (ex: Throwable) {
                swallowed("TtsText.minimal.numbers", ex)
                logFailure("numbers_failed", ex)
            }
        }
        return t
    }

    /** Xato jurnaliga yozadi (matnsiz, faqat sinf nomi); takrorlanuvchi xato jurnalni to'ldirmasligi uchun 30 soniyada bir marta. */
    private fun logFailure(why: String, t: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastErrorLogAt < 30_000L) return
        lastErrorLogAt = now
        try {
            Diag.log.log(ErrorCode.TTS_SERVICE_ERROR, mapOf("why" to why, "exc" to t.javaClass.simpleName))
        } catch (ex: Throwable) {
            swallowed("TtsText.logFailure", ex)
        }
    }
}
