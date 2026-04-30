package com.contextai.presentation.consent

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.contextai.core.DesignTokens
import com.contextai.core.consent.ConsentManager
import com.contextai.core.dpToPx
import com.contextai.data.preferences.SecurePreferencesManager
import com.contextai.presentation.MainActivity
import com.contextai.presentation.onboarding.OnboardingActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class ConsentActivity : AppCompatActivity() {

    @Inject lateinit var consentManager: ConsentManager
    @Inject lateinit var prefsManager: SecurePreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.WHITE))

        lifecycleScope.launch {
            if (consentManager.hasValidConsent()) {
                proceedForward()
                return@launch
            }
            setContentView(buildConsentView())
        }
    }

    private fun buildConsentView(): View {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(this@ConsentActivity), 48.dpToPx(this@ConsentActivity),
                24.dpToPx(this@ConsentActivity), 32.dpToPx(this@ConsentActivity))
            setBackgroundColor(Color.WHITE)
        }

        val headline = TextView(this).apply {
            text = "Before you start"
            textSize = DesignTokens.textXL
            setTextColor(DesignTokens.textPrimary)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, 0, 0, 6.dpToPx(this@ConsentActivity))
        }

        val sub = TextView(this).apply {
            text = "Please read this. It takes about 60 seconds."
            textSize = DesignTokens.textMD
            setTextColor(DesignTokens.textSecondary)
            setPadding(0, 0, 0, 24.dpToPx(this@ConsentActivity))
        }

        val cards = buildDataPracticeCards()
        val policySection = buildExpandablePolicy()
        val consentRow = buildCheckboxRow(
            "I have read and agree to the above. I understand my screen content " +
            "will be sent to Anthropic's Claude API when I use the assistant."
        )
        val ageRow = buildCheckboxRow("I confirm I am 18 years of age or older.")
        val acceptButton = buildAcceptButton(consentRow, ageRow)

        val declineLink = TextView(this).apply {
            text = "Decline and exit"
            textSize = DesignTokens.textSM
            setTextColor(DesignTokens.textTertiary)
            gravity = Gravity.CENTER
            setPadding(0, 16.dpToPx(this@ConsentActivity), 0, 0)
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { finishAffinity() }
        }

        listOf(headline, sub, cards, policySection, consentRow, ageRow, acceptButton, declineLink)
            .forEach { container.addView(it) }

        scroll.addView(container)
        return scroll
    }

    private fun buildDataPracticeCards(): LinearLayout {
        data class Practice(val icon: String, val title: String, val body: String,
                            val safe: Boolean, val link: String? = null)

        val practices = listOf(
            Practice("👁", "Screen reading — on demand only",
                "We read your screen ONLY when you tap the bubble. Never in the background.",
                true),
            Practice("🚫", "Sensitive apps are always blocked",
                "Banking, payment, Aadhaar, and health apps are permanently blocked. " +
                "The AI never sees them.", true),
            Practice("✂", "Sensitive data filtered automatically",
                "OTPs, card numbers, PAN, and Aadhaar are removed before anything leaves your phone.",
                true),
            Practice("☁", "AI processing via Anthropic's Claude",
                "Your questions and screen context are sent to Anthropic (anthropic.com) to " +
                "generate answers. Anthropic's privacy policy applies.",
                false, "https://www.anthropic.com/privacy"),
            Practice("📱", "Everything else stays on your device",
                "Conversation history, your profile, and settings are stored only on this phone. " +
                "No servers, no accounts, no cloud sync.", true)
        )

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 8.dpToPx(this@ConsentActivity))
        }

        practices.forEach { p ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply {
                    setColor(if (p.safe) Color.argb(15, 34, 197, 94) else Color.argb(12, 108, 99, 255))
                    cornerRadius = DesignTokens.radiusMD.dpToPx(this@ConsentActivity).toFloat()
                    setStroke(1.dpToPx(this@ConsentActivity),
                        if (p.safe) Color.argb(40, 34, 197, 94) else Color.argb(30, 108, 99, 255))
                }
                setPadding(14.dpToPx(this@ConsentActivity), 12.dpToPx(this@ConsentActivity),
                    14.dpToPx(this@ConsentActivity), 12.dpToPx(this@ConsentActivity))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8.dpToPx(this@ConsentActivity) }
            }

            val iconView = TextView(this).apply {
                text = p.icon
                textSize = 18f
                setPadding(0, 0, 12.dpToPx(this@ConsentActivity), 0)
                gravity = Gravity.TOP
            }

            val textBlock = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            textBlock.addView(TextView(this).apply {
                text = p.title
                textSize = DesignTokens.textSM
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(DesignTokens.textPrimary)
            })
            textBlock.addView(TextView(this).apply {
                text = p.body
                textSize = DesignTokens.textSM
                setTextColor(DesignTokens.textSecondary)
                setLineSpacing(0f, 1.4f)
                setPadding(0, 3.dpToPx(this@ConsentActivity), 0, 0)
            })
            if (p.link != null) {
                val linkUrl = p.link
                textBlock.addView(TextView(this).apply {
                    text = "Read Anthropic's privacy policy →"
                    textSize = DesignTokens.textXS
                    setTextColor(DesignTokens.brandPrimary)
                    setPadding(0, 4.dpToPx(this@ConsentActivity), 0, 0)
                    paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                    setOnClickListener {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl)))
                    }
                })
            }

            card.addView(iconView)
            card.addView(textBlock)
            container.addView(card)
        }

        return container
    }

    private fun buildExpandablePolicy(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4.dpToPx(this@ConsentActivity), 0, 12.dpToPx(this@ConsentActivity))
        }

        val toggle = TextView(this).apply {
            text = "Read full privacy policy ▼"
            textSize = DesignTokens.textSM
            setTextColor(DesignTokens.brandPrimary)
            setPadding(0, 6.dpToPx(this@ConsentActivity), 0, 6.dpToPx(this@ConsentActivity))
        }

        val policyText = TextView(this).apply {
            visibility = View.GONE
            textSize = DesignTokens.textXS
            setTextColor(DesignTokens.textSecondary)
            setLineSpacing(0f, 1.5f)
            setPadding(0, 8.dpToPx(this@ConsentActivity), 0, 4.dpToPx(this@ConsentActivity))
            text = buildPolicyText()
        }

        toggle.setOnClickListener {
            if (policyText.visibility == View.GONE) {
                policyText.visibility = View.VISIBLE
                toggle.text = "Hide privacy policy ▲"
            } else {
                policyText.visibility = View.GONE
                toggle.text = "Read full privacy policy ▼"
            }
        }

        container.addView(toggle)
        container.addView(policyText)
        return container
    }

    private fun buildPolicyText(): String {
        val date = SimpleDateFormat("dd MMMM yyyy", Locale.ENGLISH).format(Date())
        return """
CONTEXTAI PRIVACY POLICY
Last updated: $date

1. WHAT THIS APP DOES
ContextAI is an Android overlay assistant that reads on-screen content when you tap the floating bubble, sends relevant context to an AI model (Anthropic Claude), and displays the response.

2. DATA WE COLLECT
a) Screen content: Only visible text from the current screen, captured only when you tap the bubble. Never collected passively.
b) Conversation history: Stored locally on your device only.
c) Your profile: Name, role, skills you optionally enter. Stored locally only, encrypted on device.
d) App usage stats: Action counts for the daily digest. Stored locally only.

3. DATA WE DO NOT COLLECT
- Passwords or fields marked as password type
- OTPs, PINs, CVV numbers (auto-redacted before any transmission)
- Aadhaar numbers, PAN numbers (auto-redacted)
- Credit or debit card numbers (auto-redacted)
- Content from banking, payment, or password manager apps (permanently blocked)
- Microphone audio outside of your explicit hold-to-speak gesture
- Location data, contact lists, photos

4. THIRD PARTY SERVICES
This app uses Anthropic's Claude API. When you send a query, your question and relevant screen context are sent to Anthropic's servers.
Anthropic's privacy policy: https://www.anthropic.com/privacy

5. DATA STORAGE
All data except AI queries is stored exclusively on your device using Android's encrypted storage. We operate no servers and maintain no user database.

6. YOUR RIGHTS (DPDP Act 2023 / GDPR)
- Access: View all stored data in Settings
- Deletion: Settings > Data > Delete everything
- Withdrawal: Uninstall the app at any time to remove all local data

7. CHILDREN
This app is not intended for users under 18 years of age.

8. CHANGES TO THIS POLICY
If we make material changes, you will be asked to re-accept before continuing to use the app.
        """.trimIndent()
    }

    private fun buildCheckboxRow(label: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12.dpToPx(this@ConsentActivity), 0, 4.dpToPx(this@ConsentActivity))
        }

        val checkbox = CheckBox(this).apply {
            id = View.generateViewId()
            buttonTintList = android.content.res.ColorStateList.valueOf(DesignTokens.brandPrimary)
        }

        val labelView = TextView(this).apply {
            text = label
            textSize = DesignTokens.textSM
            setTextColor(DesignTokens.textPrimary)
            setLineSpacing(0f, 1.4f)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = 10.dpToPx(this@ConsentActivity) }
            setOnClickListener { checkbox.isChecked = !checkbox.isChecked }
        }

        row.addView(checkbox)
        row.addView(labelView)
        row.tag = checkbox
        return row
    }

    private fun buildAcceptButton(consentRow: LinearLayout, ageRow: LinearLayout): Button {
        val button = Button(this).apply {
            text = "I agree — continue"
            textSize = DesignTokens.textMD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(DesignTokens.brandPrimary)
                cornerRadius = DesignTokens.radiusPill.dpToPx(this@ConsentActivity).toFloat()
            }
            isEnabled = false
            alpha = 0.4f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                52.dpToPx(this@ConsentActivity)
            ).apply { topMargin = 16.dpToPx(this@ConsentActivity) }
        }

        val consentBox = consentRow.tag as CheckBox
        val ageBox = ageRow.tag as CheckBox

        val listener = android.widget.CompoundButton.OnCheckedChangeListener { _, _ ->
            val both = consentBox.isChecked && ageBox.isChecked
            button.isEnabled = both
            button.animate().alpha(if (both) 1f else 0.4f).setDuration(200).start()
        }
        consentBox.setOnCheckedChangeListener(listener)
        ageBox.setOnCheckedChangeListener(listener)

        button.setOnClickListener {
            lifecycleScope.launch {
                consentManager.recordConsent()
                proceedForward()
            }
        }

        return button
    }

    private fun proceedForward() {
        val dest = if (!prefsManager.isOnboardingComplete()) OnboardingActivity::class.java
                   else MainActivity::class.java
        startActivity(Intent(this, dest))
        finish()
    }
}
