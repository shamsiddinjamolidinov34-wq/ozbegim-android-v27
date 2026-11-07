package uz.ekranoquvchi.mvp.content

/**
 * Uzun matnni gap bo'laklariga ajratadi: birinchi bo'lak tez eshitilsin (monografiya 9.2, 9.8).
 *
 * Har bir bo'lak dvigatelga alohida beriladi, dvigatel esa har bo'lak oxirida ohangni pasaytirib, to'xtaydi (urg'u beradi).
 * Shu sababli bo'lak iloji boricha gap oxirida tugaydi. Gap o'rtasida bo'lishga to'g'ri kelsa (gap juda uzun), eng yaqin vergul,
 * nuqtali vergul, ikki nuqta yoki tireda, bunday belgi topilmasa so'z chegarasida bo'linadi: so'z o'rtasida hech qachon.
 */
object TextChunker {
    /** Bo'lak shu belgi bilan tugagan so'zdan keyin yopiladi (bo'lak yumshoq chegaraga yetgan bo'lsa). */
    private const val SENTENCE_END = ".!?\u2026;:"

    /** Bo'lak to'lib ketsa, shu belgilardan keyin kesiladi. */
    private const val SOFT_BREAK = ",;:\u2014\u2013"

    fun split(text: String, soft: Int = 160, hard: Int = 260): List<String> {
        if (text.length <= hard) return listOf(text)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (word in text.split(' ')) {
            if (sb.isNotEmpty() && sb.length + 1 + word.length > hard) {
                flushForced(sb, out, soft / 2)
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(word)
            if (sb.length >= soft && word.isNotEmpty() && SENTENCE_END.indexOf(word.last()) >= 0) {
                out.add(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /**
     * Bo'lak to'lib ketdi: oxirgi vergul/ikki nuqta/tiredan keyin kesiladi (bo'lakda kamida minLen belgi qoladi),
     * qolgan qismi keyingi bo'lakning boshiga o'tadi. Bunday joy bo'lmasa, bo'lak to'liq yopiladi.
     */
    private fun flushForced(sb: StringBuilder, out: MutableList<String>, minLen: Int) {
        var cut = -1
        var i = sb.length - 1
        while (i > minLen) {
            if (sb[i] == ' ' && SOFT_BREAK.indexOf(sb[i - 1]) >= 0) {
                cut = i
                break
            }
            i--
        }
        if (cut < 0) {
            out.add(sb.toString())
            sb.setLength(0)
            return
        }
        val head = sb.substring(0, cut)
        val tail = sb.substring(cut + 1)
        out.add(head)
        sb.setLength(0)
        sb.append(tail)
    }
}
