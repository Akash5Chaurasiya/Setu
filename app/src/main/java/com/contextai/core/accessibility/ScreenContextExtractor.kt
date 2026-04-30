package com.contextai.core.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.contextai.domain.model.ContextType
import com.contextai.domain.model.ScreenContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenContextExtractor @Inject constructor() {

    companion object {
        private const val MAX_NODE_DEPTH = 50
        @Volatile var isCaptureEnabled: Boolean = false
    }

    private val emailRegex = Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")
    private val urlRegex = Regex("https?://[\\w\\-]+(\\.[\\w\\-]+)+([\\w\\-._~:/?#\\[\\]@!$&'()*+,;=]*)?")
    private val priceRegex = Regex("(?:Rs\\.?|₹|INR|USD|\\$)\\s*[\\d,]+(?:\\.\\d{1,2})?")

    private val jobKeywords = setOf(
        "experience", "skills", "requirements", "responsibilities", "salary",
        "apply", "job", "role", "position", "candidate", "resume", "cv",
        "qualifications", "engineer", "developer", "manager", "analyst",
        "full.?time", "part.?time", "remote", "hybrid", "on.?site"
    )
    private val foodKeywords = setOf(
        "cart", "order", "restaurant", "menu", "delivery", "items",
        "quantity", "add to cart", "checkout", "food", "cuisine", "dish",
        "calories", "ingredients", "zomato", "swiggy", "doordash"
    )
    private val travelKeywords = setOf(
        "flight", "hotel", "booking", "check.?in", "check.?out", "depart",
        "arrive", "itinerary", "trip", "travel", "destination", "passport",
        "ticket", "boarding", "luggage", "makemytrip", "airbnb", "expedia"
    )

    /**
     * Extracts structured context from the accessibility node tree.
     * Traverses the entire window hierarchy collecting visible text and UI labels.
     */
    fun extract(
        rootNode: AccessibilityNodeInfo?,
        packageName: String,
        appName: String,
        activityTitle: String
    ): ScreenContext {
        if (rootNode == null) {
            return buildEmptyContext(packageName, appName, activityTitle)
        }

        val textCollector = mutableListOf<String>()
        val clickableLabels = mutableListOf<String>()
        collectNodeText(rootNode, textCollector, clickableLabels)

        val rawText = textCollector.joinToString("\n").trim()
        val emails = emailRegex.findAll(rawText)
            .map { it.value.trim().lowercase() }
            .distinct()
            .filter { !it.contains("example.com") && !it.contains("test.com") }
            .toList()
        val urls = urlRegex.findAll(rawText).map { it.value }.distinct().take(5).toList()
        val contextType = detectContextType(packageName, rawText)
        val entities = extractEntities(rawText, contextType)

        return ScreenContext(
            appPackage = packageName,
            appName = appName,
            activityTitle = activityTitle,
            rawText = rawText,
            detectedEmails = emails,
            detectedUrls = urls,
            detectedType = contextType,
            keyEntities = entities
        )
    }

    private fun collectNodeText(
        root: AccessibilityNodeInfo,
        texts: MutableList<String>,
        clickableLabels: MutableList<String>
    ) {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (depth > MAX_NODE_DEPTH) continue
            try {
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                if (!text.isNullOrBlank() && !isNoise(text) && seen.add(text)) {
                    texts.add(text)
                    if (node.isClickable) clickableLabels.add(text)
                }
                if (!desc.isNullOrBlank() && desc != text && !isNoise(desc) && seen.add(desc)) {
                    texts.add(desc)
                }
                for (i in 0 until node.childCount) {
                    runCatching { node.getChild(i) }.getOrNull()?.let {
                        queue.add(it to depth + 1)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Error collecting node text at depth $depth")
            }
        }
    }

    private fun isNoise(text: String): Boolean =
        text.length < 3 || text.all { it.isDigit() || it == ',' || it == '.' || it == ' ' }

    private fun detectContextType(packageName: String, text: String): ContextType {
        val lowerText = text.lowercase()
        val lowerPkg = packageName.lowercase()

        val foodApps = setOf("zomato", "swiggy", "doordash", "ubereats", "grubhub", "foodpanda")
        val travelApps = setOf("makemytrip", "goibibo", "airbnb", "expedia", "booking", "cleartrip")
        val jobApps = setOf("linkedin", "naukri", "indeed", "glassdoor", "monster", "shine")
        val emailApps = setOf("gmail", "outlook", "yahoo.mail", "protonmail")

        if (foodApps.any { lowerPkg.contains(it) }) return ContextType.FOOD_ORDER
        if (travelApps.any { lowerPkg.contains(it) }) return ContextType.TRAVEL
        if (jobApps.any { lowerPkg.contains(it) }) return ContextType.JOB_POST
        if (emailApps.any { lowerPkg.contains(it) }) return ContextType.EMAIL

        val foodScore = foodKeywords.count { lowerText.contains(it.toRegex()) }
        val travelScore = travelKeywords.count { lowerText.contains(it.toRegex()) }
        val jobScore = jobKeywords.count { lowerText.contains(it.toRegex()) }

        return when {
            jobScore >= 3 -> ContextType.JOB_POST
            foodScore >= 3 -> ContextType.FOOD_ORDER
            travelScore >= 3 -> ContextType.TRAVEL
            else -> ContextType.GENERIC
        }
    }

    private fun extractEntities(text: String, type: ContextType): Map<String, String> {
        val entities = mutableMapOf<String, String>()

        when (type) {
            ContextType.JOB_POST -> {
                extractJobEntities(text, entities)
            }
            ContextType.FOOD_ORDER -> {
                extractFoodEntities(text, entities)
            }
            ContextType.TRAVEL -> {
                extractTravelEntities(text, entities)
            }
            else -> {
                val prices = priceRegex.findAll(text).map { it.value }.take(3).toList()
                if (prices.isNotEmpty()) entities["prices"] = prices.joinToString(", ")
            }
        }

        return entities
    }

    private fun extractJobEntities(text: String, entities: MutableMap<String, String>) {
        val companyPatterns = listOf(
            Regex("(?:at|@|with|company:?)\\s+([A-Z][A-Za-z0-9\\s&.]+?)(?:\\s|,|\\.|\$)", RegexOption.MULTILINE),
            Regex("([A-Z][A-Za-z0-9\\s&.]+?)\\s+is hiring", RegexOption.MULTILINE)
        )
        companyPatterns.forEach { pattern ->
            val match = pattern.find(text)
            if (match != null && entities["company"] == null) {
                entities["company"] = match.groupValues[1].trim()
            }
        }

        val rolePatterns = listOf(
            Regex("(?:role|position|title|hiring for):?\\s+([A-Za-z0-9\\s/\\-]+?)(?:\\n|,|\\.)", RegexOption.IGNORE_CASE),
            Regex("(Senior|Junior|Lead|Principal|Staff)?\\s*(Software|Frontend|Backend|Full.?Stack|Mobile|Android|iOS|Data|ML|AI)\\s+(Engineer|Developer|Scientist|Analyst)", RegexOption.IGNORE_CASE)
        )
        rolePatterns.forEach { pattern ->
            val match = pattern.find(text)
            if (match != null && entities["role"] == null) {
                entities["role"] = match.value.trim()
            }
        }

        val salaryMatch = Regex("(?:salary|ctc|compensation|package):?\\s*([^\\n.]+)", RegexOption.IGNORE_CASE).find(text)
        if (salaryMatch != null) entities["salary"] = salaryMatch.groupValues[1].trim().take(50)

        val expMatch = Regex("(\\d+)\\s*[-–]\\s*(\\d+)\\s*years?\\s+(?:of\\s+)?experience", RegexOption.IGNORE_CASE).find(text)
            ?: Regex("(\\d+)\\+?\\s*years?\\s+(?:of\\s+)?experience", RegexOption.IGNORE_CASE).find(text)
        if (expMatch != null) entities["experience"] = expMatch.value.trim()
    }

    private fun extractFoodEntities(text: String, entities: MutableMap<String, String>) {
        val totalMatch = Regex("(?:total|grand total|bill|amount):?\\s*(?:Rs\\.?|₹)?\\s*([\\d,]+(?:\\.\\d{1,2})?)", RegexOption.IGNORE_CASE).find(text)
        if (totalMatch != null) entities["total"] = "₹${totalMatch.groupValues[1]}"

        val itemCount = Regex("(\\d+)\\s+items?", RegexOption.IGNORE_CASE).find(text)
        if (itemCount != null) entities["items"] = itemCount.groupValues[1]

        val restaurant = Regex("(?:from|at|restaurant):?\\s+([A-Z][A-Za-z0-9\\s&']+?)(?:\\n|,|\\.)", RegexOption.MULTILINE).find(text)
        if (restaurant != null) entities["restaurant"] = restaurant.groupValues[1].trim()
    }

    private fun extractTravelEntities(text: String, entities: MutableMap<String, String>) {
        val datePattern = Regex("(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2},?\\s*\\d{0,4}|\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}", RegexOption.IGNORE_CASE)
        val dates = datePattern.findAll(text).map { it.value }.take(2).toList()
        if (dates.isNotEmpty()) entities["dates"] = dates.joinToString(" to ")

        val flightMatch = Regex("[A-Z]{2}\\s*\\d{3,4}").find(text)
        if (flightMatch != null) entities["flight"] = flightMatch.value

        val priceMatch = priceRegex.find(text)
        if (priceMatch != null) entities["price"] = priceMatch.value
    }

    private fun buildEmptyContext(packageName: String, appName: String, activityTitle: String) = ScreenContext(
        appPackage = packageName,
        appName = appName,
        activityTitle = activityTitle,
        rawText = "",
        detectedEmails = emptyList(),
        detectedUrls = emptyList(),
        detectedType = ContextType.GENERIC,
        keyEntities = emptyMap()
    )
}
