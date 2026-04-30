package com.contextai.presentation.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.contextai.ContextAIApplication
import com.contextai.R
import com.contextai.core.HapticEngine
import com.contextai.core.overlay.FloatingBubbleService
import com.contextai.core.security.AccessibilityGate
import com.contextai.core.security.MicrophoneGate
import com.contextai.core.security.SensitiveAppFilter
import com.contextai.databinding.FragmentSettingsBinding
import com.contextai.domain.model.AiProvider
import com.contextai.presentation.privacy.PrivacyPolicyActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()

    @Inject lateinit var accessibilityGate: AccessibilityGate
    @Inject lateinit var microphoneGate: MicrophoneGate

    private var updatingUi = false
    private val gateStatusHandler = Handler(Looper.getMainLooper())
    private val gateStatusRunnable = object : Runnable {
        override fun run() {
            updateGateMicStatus()
            if (accessibilityGate.isOpen() || microphoneGate.isOpen()) {
                gateStatusHandler.postDelayed(this, 1000)
            }
        }
    }

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { extractFileText(uri) }
            if (text.isNotBlank()) {
                binding.etResumeText.setText(text)
                showSnackbar("Imported — review then tap Save Context")
            } else {
                showSnackbar("Text extraction failed. Save as .txt and re-import, or paste below.")
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupAppVersion()
        setupClickListeners()
        observeUiState()
        observeEvents()
    }

    private fun setupAppVersion() {
        runCatching {
            val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            binding.tvAppVersion.text = "v${info.versionName}"
        }
    }

    private fun setupClickListeners() {
        binding.switchService.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) {
                val ctx = requireContext()
                if (checked) {
                    ctx.startForegroundService(Intent(ctx, FloatingBubbleService::class.java))
                } else {
                    ctx.stopService(Intent(ctx, FloatingBubbleService::class.java))
                }
                updateServiceStatusLabel(checked)
            }
        }

        binding.switchAnthropic.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi && checked) viewModel.setActiveProvider(AiProvider.ANTHROPIC)
        }
        binding.switchOpenai.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi && checked) viewModel.setActiveProvider(AiProvider.OPENAI)
        }
        binding.switchGemini.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi && checked) viewModel.setActiveProvider(AiProvider.GEMINI)
        }
        binding.switchGroq.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi && checked) viewModel.setActiveProvider(AiProvider.GROQ)
        }

        binding.btnSaveAnthropicKey.setOnClickListener {
            viewModel.saveApiKey(AiProvider.ANTHROPIC, binding.etAnthropicKey.text?.toString() ?: "")
            binding.etAnthropicKey.setText("")
        }
        binding.btnSaveOpenaiKey.setOnClickListener {
            viewModel.saveApiKey(AiProvider.OPENAI, binding.etOpenaiKey.text?.toString() ?: "")
            binding.etOpenaiKey.setText("")
        }
        binding.btnSaveGeminiKey.setOnClickListener {
            viewModel.saveApiKey(AiProvider.GEMINI, binding.etGeminiKey.text?.toString() ?: "")
            binding.etGeminiKey.setText("")
        }
        binding.btnSaveGroqKey.setOnClickListener {
            viewModel.saveApiKey(AiProvider.GROQ, binding.etGroqKey.text?.toString() ?: "")
            binding.etGroqKey.setText("")
        }

        binding.btnSaveProfile.setOnClickListener {
            viewModel.saveUserProfile(
                name = binding.etName.text?.toString() ?: "",
                role = binding.etRole.text?.toString() ?: "",
                skills = binding.etSkills.text?.toString() ?: "",
                experience = binding.etExperience.text?.toString() ?: ""
            )
        }

        binding.switchLockScreen.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) viewModel.setShowBubbleOnLockScreen(checked)
        }

        binding.switchAutoDetect.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) viewModel.setAutoDetectContext(checked)
        }

        binding.switchHaptic.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) {
                viewModel.setHapticEnabled(checked)
                HapticEngine.setEnabled(checked)
            }
        }

        binding.btnImportPdf.setOnClickListener {
            pdfPicker.launch(arrayOf("application/pdf", "text/plain"))
        }

        binding.btnSaveResume.setOnClickListener {
            val text = binding.etResumeText.text?.toString() ?: ""
            viewModel.saveResumeText(text)
            showSnackbar("Context saved — AI will use this going forward")
        }

        binding.btnClearHistory.setOnClickListener { confirmClearHistory() }

        // Privacy section
        binding.switchSaveHistory.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) viewModel.setSaveHistory(checked)
        }
        binding.btnDeleteAllData.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete all data?")
                .setMessage("This will permanently delete all conversation history stored on this device.")
                .setPositiveButton("Delete") { _, _ ->
                    viewModel.deleteAllHistory()
                    showSnackbar("All data deleted")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.rowPrivacyWhatWeRead.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("What we read")
                .setMessage("ContextAI reads the visible text on your screen only when you tap the floating bubble. It does NOT run continuously or log your activity.\n\nSensitive apps (banking, health, password managers) are automatically blocked from reading.")
                .setPositiveButton("OK", null)
                .show()
        }
        binding.rowPrivacyWhatWeSend.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("What we send to AI")
                .setMessage("We send to the AI:\n• Your question\n• Visible screen text (OTPs, card numbers, Aadhaar, PAN are automatically redacted before sending)\n• Your profile name and role (if set)\n• App name and context type\n\nWe never send passwords, audio, photos, or your location.")
                .setPositiveButton("OK", null)
                .show()
        }
        binding.rowPrivacyBlockedApps.setOnClickListener {
            val blockedList = listOf(
                "SBI YONO, ICICI iMobile, Axis Mobile",
                "HDFC MobileBanking, Kotak Mobile",
                "Paytm, PhonePe, Google Pay",
                "LastPass, 1Password, Dashlane, Bitwarden",
                "Practo, mfine, Apollo 247",
                "mAadhaar, DigiLocker",
                "NordVPN, ExpressVPN, ProtonVPN"
            ).joinToString("\n• ", prefix = "• ")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Blocked apps (${SensitiveAppFilter.blockedCount()})")
                .setMessage("ContextAI will not read screen content from:\n\n$blockedList\n\nThis list is built-in and cannot be changed.")
                .setPositiveButton("OK", null)
                .show()
        }
        binding.rowPrivacyPolicy.setOnClickListener {
            startActivity(Intent(requireContext(), PrivacyPolicyActivity::class.java))
        }

        binding.btnReportBug.setOnClickListener {
            val logs = (requireActivity().application as ContextAIApplication).getRecentLogs()
            val scrubbed = logs.map { line ->
                line
                    .replace(Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"), "[email]")
                    .replace(Regex("\\b\\d{10,}\\b"), "[number]")
                    .replace(Regex("Bearer [\\w\\-\\.]+"), "Bearer [redacted]")
                    .replace(Regex("sk-[\\w\\-]+"), "[api-key]")
            }
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ContextAI Logs", scrubbed.joinToString("\n")))
            showSnackbar(getString(R.string.logs_copied))
        }

        binding.btnGrantOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        binding.btnGrantAccessibility.setOnClickListener {
            showSnackbar("In Accessibility Settings, find ContextAI and toggle it")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updatingUi = true

                    binding.switchAnthropic.isChecked = state.selectedProvider == AiProvider.ANTHROPIC
                    binding.switchOpenai.isChecked = state.selectedProvider == AiProvider.OPENAI
                    binding.switchGemini.isChecked = state.selectedProvider == AiProvider.GEMINI
                    binding.switchGroq.isChecked = state.selectedProvider == AiProvider.GROQ

                    updateKeyStatus(binding.tvAnthropicKeyStatus, state.anthropicHasKey)
                    updateKeyStatus(binding.tvOpenaiKeyStatus, state.openaiHasKey)
                    updateKeyStatus(binding.tvGeminiKeyStatus, state.geminiHasKey)
                    updateKeyStatus(binding.tvGroqKeyStatus, state.groqHasKey)

                    binding.etName.setText(state.userProfile.name)
                    binding.etRole.setText(state.userProfile.currentRole)
                    binding.etSkills.setText(state.userProfile.skills)
                    binding.etExperience.setText(state.userProfile.experience)

                    binding.switchLockScreen.isChecked = state.showBubbleOnLockScreen
                    binding.switchAutoDetect.isChecked = state.autoDetectContext
                    binding.switchHaptic.isChecked = state.hapticEnabled
                    if (binding.etResumeText.text.isNullOrEmpty() && state.resumeText.isNotBlank()) {
                        binding.etResumeText.setText(state.resumeText)
                    }

                    updatePermissionStatus(
                        accessibilityEnabled = state.accessibilityEnabled,
                        overlayEnabled = android.provider.Settings.canDrawOverlays(requireContext())
                    )

                    binding.tvHistoryCount.text = getString(R.string.history_count, state.historyCount)
                    binding.tvSessionTokens.text = "${state.sessionTokensUsed} / 2000 tokens"
                    binding.tokenProgressBar.progress = state.sessionTokensUsed.coerceAtMost(2000)

                    val serviceRunning = state.accessibilityEnabled
                    binding.switchService.isChecked = serviceRunning
                    updateServiceStatusLabel(serviceRunning)

                    binding.switchSaveHistory.isChecked = state.saveHistory

                    // Privacy status dots
                    val overlayGranted = android.provider.Settings.canDrawOverlays(requireContext())
                    updatePrivacyDot(binding.vPrivacyDotOverlay, overlayGranted)
                    updatePrivacyDot(binding.vPrivacyDotAccessibility, state.accessibilityEnabled)
                    updatePrivacyDot(binding.vPrivacyDotNetwork, true)

                    updatingUi = false
                }
            }
        }
    }

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is SettingsEvent.ApiKeySaved ->
                            showSnackbar(getString(R.string.api_key_saved))
                        is SettingsEvent.ProfileSaved ->
                            showSnackbar(getString(R.string.profile_saved))
                        is SettingsEvent.HistoryCleared ->
                            showSnackbar(getString(R.string.history_cleared))
                        is SettingsEvent.Error ->
                            showSnackbar(event.message)
                    }
                }
            }
        }
    }

    private fun updateServiceStatusLabel(active: Boolean) {
        binding.tvServiceStatus.text = if (active) "Bubble active" else "Bubble inactive"
        val dotBg = if (active) {
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(resources.getColor(R.color.status_green, null))
            }
        } else {
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(resources.getColor(R.color.status_red, null))
            }
        }
        binding.vServiceStatusDot.background = dotBg
    }

    private fun updateKeyStatus(textView: android.widget.TextView, hasKey: Boolean) {
        textView.text = if (hasKey) {
            getString(R.string.api_key_set)
        } else {
            getString(R.string.api_key_not_configured)
        }
        textView.setTextColor(
            resources.getColor(
                if (hasKey) R.color.status_green else R.color.status_red,
                null
            )
        )
    }

    private fun updatePrivacyDot(dot: View, active: Boolean) {
        val greenColor = resources.getColor(R.color.status_green, null)
        val redColor = resources.getColor(R.color.status_red, null)
        dot.setBackgroundColor(if (active) greenColor else redColor)
    }

    private fun updatePermissionStatus(accessibilityEnabled: Boolean, overlayEnabled: Boolean) {
        val greenColor = resources.getColor(R.color.status_green, null)
        val redColor = resources.getColor(R.color.status_red, null)

        binding.tvOverlayStatus.text = if (overlayEnabled) getString(R.string.permission_granted) else getString(R.string.permission_not_granted)
        binding.tvOverlayStatus.setTextColor(if (overlayEnabled) greenColor else redColor)
        binding.btnGrantOverlay.visibility = if (overlayEnabled) View.GONE else View.VISIBLE

        binding.tvAccessibilityStatus.text = if (accessibilityEnabled) getString(R.string.permission_granted) else getString(R.string.permission_not_granted)
        binding.tvAccessibilityStatus.setTextColor(if (accessibilityEnabled) greenColor else redColor)
        binding.btnGrantAccessibility.text = if (accessibilityEnabled) "Manage / Disable" else "Grant"
    }

    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clear_history_title)
            .setMessage(R.string.clear_history_message)
            .setPositiveButton(R.string.clear) { _, _ -> viewModel.clearHistory() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus(
            accessibilityEnabled = viewModel.uiState.value.accessibilityEnabled,
            overlayEnabled = android.provider.Settings.canDrawOverlays(requireContext())
        )
        updateGateMicStatus()
        if (accessibilityGate.isOpen() || microphoneGate.isOpen()) {
            gateStatusHandler.post(gateStatusRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        gateStatusHandler.removeCallbacks(gateStatusRunnable)
    }

    private fun updateGateMicStatus() {
        if (_binding == null) return
        val amberColor = com.contextai.core.DesignTokens.warning
        val greenColor = resources.getColor(R.color.status_green, null)

        // Accessibility dot: amber when gate is actively reading, green when idle/granted
        if (accessibilityGate.isOpen()) {
            val secs = (accessibilityGate.remainingMs() / 1000).toInt()
            binding.vPrivacyDotAccessibility.setBackgroundColor(amberColor)
            binding.tvAccessibilityStatus.text = "Active — reading (${secs}s remaining)"
            binding.tvAccessibilityStatus.setTextColor(amberColor)
            if (!gateStatusHandler.hasMessages(0)) {
                gateStatusHandler.post(gateStatusRunnable)
            }
        } else if (viewModel.uiState.value.accessibilityEnabled) {
            binding.vPrivacyDotAccessibility.setBackgroundColor(greenColor)
            binding.tvAccessibilityStatus.text = getString(R.string.permission_granted)
            binding.tvAccessibilityStatus.setTextColor(greenColor)
        }

        // Mic: show live status in the "What we send" privacy row subtitle if available
        if (microphoneGate.isOpen()) {
            binding.vPrivacyDotNetwork.setBackgroundColor(amberColor)
        } else {
            updatePrivacyDot(binding.vPrivacyDotNetwork, true)
        }
    }

    // Returns extracted text, or "" if extraction fails / quality is too low
    private fun extractFileText(uri: Uri): String {
        val mime = requireContext().contentResolver.getType(uri) ?: ""
        // Plain text — always reliable
        if (mime == "text/plain") {
            return runCatching {
                requireContext().contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?.take(4000) ?: ""
            }.getOrDefault("")
        }
        // PDF — multi-strategy extraction
        return runCatching {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val raw = String(bytes, Charsets.ISO_8859_1)
                val sb = StringBuilder()

                // Strategy 1: decompress FlateDecode streams then extract text operators
                // Most modern PDFs (Word/Docs exports) compress content with zlib/deflate
                val streamRx = Regex("""stream\r?\n(.*?)\r?\nendstream""", setOf(RegexOption.DOT_MATCHES_ALL))
                for (m in streamRx.findAll(raw)) {
                    val chunk = m.groupValues[1].toByteArray(Charsets.ISO_8859_1)
                    val decompressed = tryInflate(chunk) ?: continue
                    extractPdfTextOps(String(decompressed, Charsets.ISO_8859_1), sb)
                }

                // Strategy 2: uncompressed PDF (older Word, LibreOffice, simple exporters)
                if (sb.length < 30) {
                    extractPdfTextOps(raw, sb)
                }

                val result = sb.toString().replace(Regex("\\s{2,}"), " ").trim()

                // Quality gate — reject if text looks like binary/metadata garbage
                if (isReadableText(result)) result.take(4000) else ""
            } ?: ""
        }.getOrDefault("")
    }

    private fun tryInflate(data: ByteArray): ByteArray? {
        // Try standard zlib header (bytes 0x78 0x9C / 0x78 0x01 / 0x78 0xDA)
        if (data.size > 2 && data[0] == 0x78.toByte()) {
            runCatching {
                java.util.zip.InflaterInputStream(data.inputStream()).readBytes()
            }.onSuccess { return it }
        }
        // Try raw deflate (no header)
        return runCatching {
            java.util.zip.InflaterInputStream(
                data.inputStream(), java.util.zip.Inflater(true)
            ).readBytes()
        }.getOrNull()
    }

    /**
     * Parse PDF text operators inside a BT/ET block.
     *
     * Rules:
     *  - Consecutive Tj/TJ calls with NO intervening position operator → concatenate (same word)
     *  - Td / TD / T* / Tm between text calls → insert word/line boundary
     *  - A space char inside a Tj token is respected as-is
     */
    private fun extractPdfTextOps(content: String, sb: StringBuilder) {
        val btEt = Regex("""BT\s(.*?)\sET""", setOf(RegexOption.DOT_MATCHES_ALL))

        // Matches: (string)Op  |  [array]TJ  |  positioning operator token
        val itemRx = Regex(
            """\(([^)\\]*(?:\\.[^)\\]*)*)\)\s*(?:Tj|TJ|'|")""" +
            """|\[(.*?)\]\s*TJ""" +
            """|\b(Td|TD|T\*|Tm)\b""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        for (block in btEt.findAll(content)) {
            val lineBuf = StringBuilder()
            var pendingSep = false  // true after a positioning operator

            for (m in itemRx.findAll(block.groupValues[1])) {
                when {
                    // ── Simple string: (text) Tj ──
                    m.groupValues[1].isNotEmpty() -> {
                        val t = decodePdfString(m.groupValues[1])
                        if (pendingSep && lineBuf.isNotEmpty()) {
                            // Decide space vs newline: if lineBuf ends mid-word add space,
                            // a space was already in the trailing char → skip
                            if (lineBuf.last() != ' ' && lineBuf.last() != '\n') {
                                lineBuf.append(' ')
                            }
                            pendingSep = false
                        }
                        lineBuf.append(t)
                    }
                    // ── Array: [(text) kern (text)]TJ ──
                    m.groupValues[2].isNotEmpty() -> {
                        val parts = Regex("""\(([^)\\]*(?:\\.[^)\\]*)*)\)""")
                            .findAll(m.groupValues[2])
                        for (p in parts) {
                            val t = decodePdfString(p.groupValues[1])
                            if (pendingSep && lineBuf.isNotEmpty() && lineBuf.last() != ' ') {
                                lineBuf.append(' ')
                                pendingSep = false
                            }
                            lineBuf.append(t)
                        }
                    }
                    // ── Positioning operator → word / line boundary ──
                    m.groupValues[3].isNotEmpty() -> {
                        pendingSep = true
                    }
                }
            }

            val line = lineBuf.toString().trim()
            if (line.isNotBlank() && line.any { it.isLetter() }) {
                sb.appendLine(line)
            }
        }
    }

    private fun decodePdfString(raw: String): String =
        raw.replace("\\(", "(")
            .replace("\\)", ")")
            .replace("\\n", "\n")
            .replace("\\r", "")
            .replace("\\t", " ")

    // Rejects PDF metadata garbage: requires >45% letter chars and at least 40 total chars
    private fun isReadableText(text: String): Boolean {
        if (text.length < 40) return false
        val letters = text.count { it.isLetter() }
        return letters.toFloat() / text.length > 0.45f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
