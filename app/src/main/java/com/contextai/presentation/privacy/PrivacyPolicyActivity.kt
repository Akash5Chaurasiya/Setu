package com.contextai.presentation.privacy

import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.contextai.R

class PrivacyPolicyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this)
        setContentView(webView)

        supportActionBar?.title = "Privacy Policy"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        webView.loadDataWithBaseURL(null, PRIVACY_HTML, "text/html", "UTF-8", null)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private val PRIVACY_HTML = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
              body { font-family: sans-serif; padding: 16px; line-height: 1.6; color: #333; }
              h1 { font-size: 20px; } h2 { font-size: 16px; margin-top: 24px; }
              p, li { font-size: 14px; }
            </style>
            </head>
            <body>
            <h1>ContextAI Privacy Policy</h1>
            <p><em>Last updated: April 2026</em></p>

            <h2>What we read</h2>
            <p>ContextAI reads visible on-screen text <strong>only when you tap the floating bubble</strong>. It does not run continuously, log your activity in the background, or capture audio, photos, or location data.</p>

            <h2>Sensitive apps — automatic blocking</h2>
            <p>The following categories of apps are <strong>blocked by default</strong> — ContextAI will not read their screen content under any circumstances:</p>
            <ul>
              <li>Banking apps (SBI, ICICI, Axis, HDFC, Kotak, etc.)</li>
              <li>Payment apps (Paytm, PhonePe, Google Pay)</li>
              <li>Password managers (LastPass, 1Password, Bitwarden, Dashlane)</li>
              <li>Medical apps (Practo, mfine, Apollo 247)</li>
              <li>Government identity apps (mAadhaar, DigiLocker)</li>
              <li>VPN apps</li>
            </ul>

            <h2>What we redact before sending to AI</h2>
            <p>Even for non-blocked apps, the following patterns are automatically removed from the screen text before it is sent to the AI:</p>
            <ul>
              <li>OTPs and numeric codes (4–8 digits)</li>
              <li>Card numbers (13–19 digits)</li>
              <li>Aadhaar numbers (12-digit groups)</li>
              <li>PAN card numbers</li>
              <li>CVV codes</li>
              <li>UPI IDs</li>
              <li>IFSC codes</li>
            </ul>

            <h2>What is sent to the AI</h2>
            <p>When you submit a query, ContextAI sends to the selected AI provider:</p>
            <ul>
              <li>Your question text (with any sensitive patterns redacted)</li>
              <li>Sanitised screen text from the current app</li>
              <li>Your profile name and role (if you have set them)</li>
              <li>App name and detected context type</li>
            </ul>
            <p>Nothing else is sent. Your data is subject to the privacy policy of the AI provider you choose (Anthropic, OpenAI, Google, or Groq).</p>

            <h2>Data stored on your device</h2>
            <p>Conversation history is stored locally in an encrypted database on your device. It is never uploaded to our servers. You can delete all history at any time from Settings → Privacy → Data on your device.</p>
            <p>API keys are stored using Android Keystore-backed AES-256-GCM encryption, additionally obfuscated with a device-specific salt.</p>

            <h2>No analytics, no ads</h2>
            <p>ContextAI collects no analytics, crash data, or usage telemetry. There are no advertisements.</p>

            <h2>Contact &amp; Data requests</h2>
            <p>To request deletion of any locally stored data, go to <strong>Settings → Privacy → Data on your device → Delete all data</strong>. Because we operate no servers, there is no cloud data to delete.</p>
            <p>For any other questions about this policy, uninstall the app — all local data is removed automatically.</p>
            </body>
            </html>
        """.trimIndent()
    }
}
