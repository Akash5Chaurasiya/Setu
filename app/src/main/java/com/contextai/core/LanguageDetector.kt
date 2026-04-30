package com.contextai.core

object LanguageDetector {

    data class DetectionResult(
        val languageCode: String,
        val languageName: String,
        val nativeName: String,
        val confidence: Float,
        val isHinglish: Boolean = false
    )

    private val HINDI_MARKERS = setOf(
        "है", "हैं", "का", "की", "के", "को", "में", "से", "पर", "और",
        "यह", "वह", "मैं", "हम", "तुम", "आप", "कि", "जो", "था", "थी",
        "होगा", "करना", "लिए", "साथ", "बाद", "पहले", "अब", "यहाँ", "वहाँ"
    )

    private val HINGLISH_MARKERS = setOf(
        "hai", "hain", "nahi", "nahin", "kya", "yaar", "bhai", "dost",
        "acha", "accha", "theek", "thik", "kal", "aaj", "abhi", "phir",
        "karo", "karna", "karke", "mera", "meri", "tera", "teri", "apna",
        "matlab", "samajh", "bata", "dekh", "suno", "bol", "lol", "haha",
        "bilkul", "zarur", "jarur", "bahut", "bohot", "thoda", "jaldi",
        "waise", "vaise", "lekin", "magar", "isliye", "kyunki", "woh", "yeh"
    )

    private val TAMIL_MARKERS = setOf(
        "என்ன", "இல்லை", "ஆம்", "நான்", "நீங்கள்", "அவர்", "இது", "அது"
    )

    private val TELUGU_MARKERS = setOf(
        "అవును", "కాదు", "నేను", "మీరు", "ఏమిటి", "అది", "ఇది"
    )

    private val KANNADA_MARKERS = setOf(
        "ಹೌದು", "ಇಲ್ಲ", "ನಾನು", "ನೀವು", "ಏನು", "ಅದು", "ಇದು"
    )

    private val BENGALI_MARKERS = setOf(
        "হ্যাঁ", "না", "আমি", "আপনি", "কি", "এটা", "সেটা", "তুমি"
    )

    private val MARATHI_MARKERS = setOf(
        "आहे", "नाही", "मी", "तुम्ही", "काय", "हे", "ते", "आणि", "होय"
    )

    fun detect(text: String): DetectionResult? {
        if (text.isBlank()) return null

        val total = text.count { !it.isWhitespace() }
        if (total == 0) return null

        var devanagari = 0
        var tamil = 0
        var telugu = 0
        var kannada = 0
        var bengali = 0
        var arabic = 0
        var chinese = 0
        var japanese = 0
        var korean = 0

        for (c in text) {
            when {
                c in '\u0900'..'\u097F' -> devanagari++
                c in '\u0B80'..'\u0BFF' -> tamil++
                c in '\u0C00'..'\u0C7F' -> telugu++
                c in '\u0C80'..'\u0CFF' -> kannada++
                c in '\u0980'..'\u09FF' -> bengali++
                c in '\u0600'..'\u06FF' -> arabic++
                c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' -> chinese++
                c in '\u3040'..'\u30FF' -> japanese++
                c in '\uAC00'..'\uD7AF' -> korean++
            }
        }

        val threshold = 0.08f

        if (devanagari.toFloat() / total > threshold) {
            val isMarathi = MARATHI_MARKERS.count { it in text } > HINDI_MARKERS.count { it in text }
            return if (isMarathi) {
                DetectionResult("mr", "Marathi", "मराठी", devanagari.toFloat() / total)
            } else {
                DetectionResult("hi", "Hindi", "हिंदी", devanagari.toFloat() / total)
            }
        }

        if (tamil.toFloat() / total > threshold)
            return DetectionResult("ta", "Tamil", "தமிழ்", tamil.toFloat() / total)
        if (telugu.toFloat() / total > threshold)
            return DetectionResult("te", "Telugu", "తెలుగు", telugu.toFloat() / total)
        if (kannada.toFloat() / total > threshold)
            return DetectionResult("kn", "Kannada", "ಕನ್ನಡ", kannada.toFloat() / total)
        if (bengali.toFloat() / total > threshold)
            return DetectionResult("bn", "Bengali", "বাংলা", bengali.toFloat() / total)
        if (arabic.toFloat() / total > threshold)
            return DetectionResult("ar", "Arabic", "العربية", arabic.toFloat() / total)
        if (chinese.toFloat() / total > threshold)
            return DetectionResult("zh", "Chinese", "中文", chinese.toFloat() / total)
        if (japanese.toFloat() / total > threshold)
            return DetectionResult("ja", "Japanese", "日本語", japanese.toFloat() / total)
        if (korean.toFloat() / total > threshold)
            return DetectionResult("ko", "Korean", "한국어", korean.toFloat() / total)

        // Hinglish: Latin-script text with Hindi lexical markers
        val lowerWords = text.lowercase().split(Regex("[\\s,!?.]+")).filter { it.length >= 2 }.toSet()
        val hinglishHits = lowerWords.count { it in HINGLISH_MARKERS }
        if (hinglishHits >= 2 || (hinglishHits >= 1 && lowerWords.size <= 6)) {
            return DetectionResult("hi", "Hindi", "हिंदी", hinglishHits.toFloat() / lowerWords.size.coerceAtLeast(1), isHinglish = true)
        }

        return null
    }

    fun getLanguageDisplayName(code: String): String = when (code) {
        "hi" -> "हिंदी (Hindi)"
        "mr" -> "मराठी (Marathi)"
        "ta" -> "தமிழ் (Tamil)"
        "te" -> "తెలుగు (Telugu)"
        "kn" -> "ಕನ್ನಡ (Kannada)"
        "bn" -> "বাংলা (Bengali)"
        "ar" -> "العربية (Arabic)"
        "zh" -> "中文 (Chinese)"
        "ja" -> "日本語 (Japanese)"
        "ko" -> "한국어 (Korean)"
        else -> code.uppercase()
    }
}
