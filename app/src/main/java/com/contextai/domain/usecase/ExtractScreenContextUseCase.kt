package com.contextai.domain.usecase

import android.view.accessibility.AccessibilityNodeInfo
import com.contextai.core.accessibility.ScreenContextExtractor
import com.contextai.domain.model.ScreenContext
import javax.inject.Inject

/**
 * Extracts and returns a [ScreenContext] from the current accessibility node tree.
 */
class ExtractScreenContextUseCase @Inject constructor(
    private val extractor: ScreenContextExtractor
) {
    operator fun invoke(
        rootNode: AccessibilityNodeInfo?,
        packageName: String,
        appName: String,
        activityTitle: String
    ): ScreenContext = extractor.extract(rootNode, packageName, appName, activityTitle)
}
