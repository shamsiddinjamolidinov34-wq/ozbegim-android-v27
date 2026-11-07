package uz.ekranoquvchi.mvp.content

/**
 * Ravon o'qish ("Urg'u berib o'qish" o'chiq, Tafsilotlar): matn to'xtalishlarsiz o'qilishi uchun tayyorlanadi.
 *
 * Nutq dvigatellari vergul, nuqta, ikki nuqta, nuqtali vergul, undov, so'roq, ko'p nuqta, qavs va qo'shtirnoq oldida to'xtaydi
 * hamda gap oxirida ohangni pasaytiradi (urg'u beradi). Shu belgilar so'z chetidan olib tashlanadi, shunda dvigatel butun
 * matnni bir nafasda, ravon o'qiydi. Qoidalar:
 *  - faqat so'zning boshi va oxiridagi belgilar olinadi; so'z ichidagisiga tegilmaydi: "3.14", "10:30", "1,5", "a@b.uz",
 *    "o'n", "qizil-yashil" o'zgarmaydi (raqam va sana naqshlari buzilmaydi);
 *  - yakka turgan tire, uzun tire va markerlar ("-", "—", "•") olib tashlanadi;
 *  - apostrof (o', g', ma'no) va "+", "%", "#", "@", "$" kabi o'qiladigan belgilarga tegilmaydi;
 *  - qator ajratgichlar va ketma-ket probellar bitta probelga aylanadi;
 *  - natija bo'sh bo'lib qolsa (matn faqat tinish belgisidan iborat), asl matn qaytariladi: hech narsa jim qolmasin.
 *
 * Tarmoqsiz, Android'siz: oddiy JVM'da sinaladi.
 */
object FluentText {
    /** So'z chetida turganda to'xtash va ohang tushishini keltiradigan belgilar. */
    private const val EDGE = ".,;:!?\u2026\u00AB\u00BB\u201C\u201D\u201E\"()[]{}\u3002\uFF0C\uFF1B\uFF1A\uFF01\uFF1F"

    /** Yakka turganda (atrofida probel) tashlab yuboriladigan belgilar: tire variantlari va markerlar. */
    private const val STANDALONE = "-\u2010\u2011\u2012\u2013\u2014\u2015\u2212\u2022\u00B7\u25CF\u25AA\u25E6"

    fun flatten(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n) break
            var j = i
            while (j < n && !text[j].isWhitespace()) j++
            var start = i
            var end = j
            while (start < end && EDGE.indexOf(text[start]) >= 0) start++
            while (end > start && EDGE.indexOf(text[end - 1]) >= 0) end--
            if (end > start && !isStandalone(text, start, end)) {
                if (out.isNotEmpty()) out.append(' ')
                out.append(text, start, end)
            }
            i = j
        }
        return if (out.isBlank()) text else out.toString()
    }

    /** Qism faqat tire va marker belgilaridan iboratmi (so'zlar orasidagi ajratgich: "Bosh sahifa - Sozlamalar"). */
    private fun isStandalone(s: String, start: Int, end: Int): Boolean {
        for (k in start until end) if (STANDALONE.indexOf(s[k]) < 0) return false
        return true
    }
}
