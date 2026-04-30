package com.contextai.core.security

object SensitiveAppFilter {

    private val BLOCKED_PACKAGES = setOf(
        // Banking
        "com.sbi.lotusintouch",
        "com.csam.icici.bank.imobile",
        "com.axis.mobile",
        "com.hdfcbank.hdfcmobilebank",
        "com.kotak.mobile2",
        "com.msf.kbank.mobile",
        "com.idbi.mpassbook",
        "com.pnb.mbanking",
        "com.unionbank.ubimobile",
        "com.bankofbaroda.mconnect",
        "com.canara.bank.mobilebanking",
        // Payments
        "net.one97.paytm",
        "com.phonepe.app",
        "com.google.android.apps.nbu.paisa.user",
        "in.amazon.mShop.android.shopping",
        "com.freecharge.android",
        // Password managers
        "com.lastpass.lpandroid",
        "com.onepassword.android",
        "com.dashlane",
        "com.bitwarden.mobile",
        "com.keeper.android",
        "com.agilebits.onepassword",
        // Medical / Health
        "com.practo.fabric",
        "co.mfine.android",
        "com.apollo247.patient",
        "com.medlife.app",
        // Identity / Government
        "in.uidai.maadhaarplus",
        "com.digilocker.android",
        "com.nsdl.mobileapp",
        // VPN / Security
        "com.nordvpn.android",
        "com.expressvpn.vpn",
        "com.protonvpn.android"
    )

    private val OTP_PATTERN = Regex("""\b\d{4,8}\b""")
    private val CARD_NUMBER_PATTERN = Regex("""\b(?:\d[ \-]?){13,19}\b""")
    private val AADHAAR_PATTERN = Regex("""\b\d{4}\s\d{4}\s\d{4}\b""")
    private val PAN_PATTERN = Regex("""\b[A-Z]{5}\d{4}[A-Z]\b""")
    private val CVV_PATTERN = Regex("""\bCVV[:\s]*\d{3,4}\b""", RegexOption.IGNORE_CASE)
    private val IFSC_PATTERN = Regex("""\b[A-Z]{4}0[A-Z0-9]{6}\b""")
    private val UPI_PATTERN = Regex("""[\w.\-]+@[\w]+""")

    fun isBlocked(packageName: String): Boolean =
        BLOCKED_PACKAGES.any { packageName == it || packageName.startsWith("$it.") }

    fun blockedCount(): Int = BLOCKED_PACKAGES.size

    fun sanitizeText(rawText: String): String {
        var result = rawText
        result = AADHAAR_PATTERN.replace(result, "XXXX XXXX XXXX")
        result = CARD_NUMBER_PATTERN.replace(result) { mr ->
            val stripped = mr.value.replace(Regex("[\\s\\-]"), "")
            if (stripped.length in 13..19) "XXXX-XXXX-XXXX-${stripped.takeLast(4)}"
            else mr.value
        }
        result = PAN_PATTERN.replace(result, "XXXXX9999X")
        result = CVV_PATTERN.replace(result, "CVV: XXX")
        result = IFSC_PATTERN.replace(result, "XXXXX000000")
        result = UPI_PATTERN.replace(result) { mr ->
            if (mr.value.contains('@')) "xxxx@upi" else mr.value
        }
        result = OTP_PATTERN.replace(result) { mr ->
            val v = mr.value
            if (v.length in 4..8 && v.all { it.isDigit() }) "X".repeat(v.length) else v
        }
        return result
    }
}
