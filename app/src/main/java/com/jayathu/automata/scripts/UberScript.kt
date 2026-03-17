package com.jayathu.automata.scripts

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.jayathu.automata.engine.ActionExecutor
import com.jayathu.automata.engine.AutomationEngine
import com.jayathu.automata.engine.AutomationStep
import com.jayathu.automata.engine.NodeFinder
import com.jayathu.automata.engine.ScreenReader
import com.jayathu.automata.engine.StepResult
import com.jayathu.automata.engine.UiInspector
import com.jayathu.automata.service.AutomataAccessibilityService

/**
 * Uber automation script — hybrid accessibility + OCR approach.
 *
 * Uber flow:
 *   1. Launch Uber → may show "ride again" prompt → tap Skip
 *   2. Home screen → tap "Where to?" search bar
 *   3. Search screen → starting location already filled, enter destination in "Where to" field
 *      (or tap from history below)
 *   4. Select search result → ride options screen
 *   5. Ride types: Tuk, Moto, Zip
 *   6. Read price (do NOT book)
 */
object UberScript {

    private const val TAG = "UberScript"
    private const val PACKAGE = "com.ubercab"

    // Text labels
    private const val WHERE_TO_TEXT = "Where to?"

    // Uber ride type names
    private const val TUK_TEXT = "Tuk"
    private const val MOTO_TEXT = "Moto"
    private const val ZIP_TEXT = "Zip"

    fun buildSteps(context: Context, destination: String, rideType: String, pickupAddress: String = ""): List<AutomationStep> {
        val steps = mutableListOf(
            verifyAppInstalled(context),
            launchApp(context),
            handleSkipPrompt(),
            tapWhereToField()
        )

        // Set pickup location if provided
        if (pickupAddress.isNotBlank()) {
            steps.add(enterPickupAddress(pickupAddress))
            steps.add(selectPickupSearchResult(pickupAddress))
        }

        steps.addAll(listOf(
            enterDestination(destination),
            selectSearchResult(destination),
            waitForRideOptions(),
            readPrice(mapRideType(rideType))
        ))

        return steps
    }

    fun buildBookingSteps(context: Context, destination: String, rideType: String, pickupAddress: String = ""): List<AutomationStep> {
        // Uber may or may not remember the ride options screen after returnToHome.
        // ensureOnRideOptionsScreen handles both cases: if already on ride options, it skips;
        // if on home screen, it navigates through the full search flow.
        return listOf(
            launchApp(context),
            ensureOnRideOptionsScreen(destination, pickupAddress),
            selectRideType(mapRideType(rideType)),
            tapChooseRide(mapRideType(rideType)),
            handleForMePrompt(),
            tapConfirmPickup()
        )
    }

    /**
     * Quick booking steps — brings Uber back to foreground (still on ride options screen)
     * and taps Choose ride. Used when the app was already navigated during price reading.
     */
    fun buildQuickBookingSteps(context: Context, rideType: String): List<AutomationStep> {
        return listOf(
            resumeApp(context),
            tapChooseRide(mapRideType(rideType)),
            handleForMePrompt(),
            tapConfirmPickup()
        )
    }

    /**
     * Fix OCR-dropped decimal points in prices.
     * Sri Lankan ride prices are typically 100–9999 LKR with 2 decimal places.
     * If OCR drops the dot (e.g. "50861" instead of "508.61"), detect and fix it.
     */
    private fun normalizePrice(price: String): String {
        // Already has a decimal point — trust it
        if (price.contains(".")) return price

        val value = price.toDoubleOrNull() ?: return price

        // If the price looks unreasonably high (>= 10000) and has 5+ digits,
        // it's likely missing a decimal point. Insert one 2 digits from the end.
        if (value >= 10000 && price.length >= 5) {
            val corrected = price.substring(0, price.length - 2) + "." + price.substring(price.length - 2)
            Log.i(TAG, "Price normalization: '$price' → '$corrected' (likely dropped decimal)")
            return corrected
        }

        return price
    }

    private fun mapRideType(rideType: String): String {
        return when (rideType.lowercase()) {
            "bike", "moto" -> MOTO_TEXT
            "tuk" -> TUK_TEXT
            "car", "zip" -> ZIP_TEXT
            else -> rideType
        }
    }

    private fun verifyAppInstalled(context: Context) = AutomationStep(
        name = "Verify Uber installed",
        waitCondition = { true },
        timeoutMs = 2_000,
        action = { _, _ ->
            if (AutomationEngine.isAppInstalled(context, PACKAGE)) {
                StepResult.Success
            } else {
                StepResult.Failure("Uber app is not installed")
            }
        }
    )

    private fun resumeApp(context: Context) = AutomationStep(
        name = "Resume Uber",
        waitCondition = { true },
        timeoutMs = 5_000,
        delayAfterMs = 2000,
        action = { _, _ ->
            if (AutomationEngine.bringToForeground(context, PACKAGE)) {
                Log.i(TAG, "Bringing Uber back to foreground")
                StepResult.Success
            } else {
                StepResult.Failure("Could not resume Uber")
            }
        }
    )

    private fun launchApp(context: Context) = AutomationStep(
        name = "Launch Uber",
        waitCondition = { true },
        timeoutMs = 5_000,
        delayAfterMs = 3000,
        action = { _, _ ->
            if (AutomationEngine.launchApp(context, PACKAGE)) {
                StepResult.Success
            } else {
                StepResult.Failure("Failed to launch Uber")
            }
        }
    )

    /**
     * Handle the "ride again with previous driver" prompt if it appears.
     * Tap "Skip" or similar dismiss button. If no prompt, just proceed.
     */
    private fun handleSkipPrompt() = AutomationStep(
        name = "Handle skip prompt (if any)",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 5_000,
        delayAfterMs = 1500,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            // Check if there's a "Skip" or dismiss button via accessibility
            val skipNode = NodeFinder.findByText(root, "Skip")
                ?: NodeFinder.findByText(root, "Not now")
                ?: NodeFinder.findByText(root, "Dismiss")
                ?: NodeFinder.findByContentDescription(root, "Close")

            if (skipNode != null) {
                Log.i(TAG, "Found skip/dismiss button: ${skipNode.text ?: skipNode.contentDescription}")
                ActionExecutor.click(skipNode)
                return@AutomationStep StepResult.Success
            }

            // OCR fallback
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "Initial screen OCR: ${ocr.fullText.take(300)}")
                val skipBlocks = ScreenReader.findTextBlocks(ocr, "Skip")
                if (skipBlocks.isNotEmpty() && skipBlocks.first().bounds != null) {
                    val b = skipBlocks.first().bounds!!
                    Log.i(TAG, "Tapping 'Skip' via OCR at (${b.centerX()}, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }

                // No skip prompt — check if we're already on the home screen
                if (ocr.fullText.contains("Where to", ignoreCase = true) ||
                    ocr.fullText.contains("Where to?", ignoreCase = true)) {
                    Log.i(TAG, "No skip prompt, already on home screen")
                    return@AutomationStep StepResult.Skip("No prompt to skip")
                }
            }

            // No prompt found, proceed anyway
            Log.i(TAG, "No skip prompt detected, proceeding")
            StepResult.Skip("No skip prompt")
        }
    )

    private fun tapWhereToField() = AutomationStep(
        name = "Tap 'Where to?' field",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 10_000,
        delayAfterMs = 2000,
        maxRetries = 3,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            // Try accessibility first
            val node = NodeFinder.findByText(root, WHERE_TO_TEXT)
                ?: NodeFinder.findByText(root, "Search destination")
                ?: NodeFinder.findByContentDescription(root, WHERE_TO_TEXT)

            if (node != null) {
                Log.i(TAG, "Found 'Where to?' via accessibility, clicking")
                if (ActionExecutor.click(node)) {
                    return@AutomationStep StepResult.Success
                }
            }

            // OCR fallback
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "Home screen OCR: ${ocr.fullText.take(300)}")
                val blocks = ScreenReader.findTextBlocks(ocr, "Where to")
                if (blocks.isNotEmpty() && blocks.first().bounds != null) {
                    val b = blocks.first().bounds!!
                    Log.i(TAG, "Tapping 'Where to?' via OCR at (${b.centerX()}, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }
            }

            StepResult.Retry("Could not find 'Where to?' field")
        }
    )

    /**
     * Enter pickup address in the pickup field (first editable field on search screen).
     * The pickup field usually shows the current location name.
     */
    private fun enterPickupAddress(pickupAddress: String) = AutomationStep(
        name = "Enter pickup address",
        waitCondition = { root ->
            NodeFinder.hasNode(root) { it.isEditable }
        },
        timeoutMs = 10_000,
        delayAfterMs = 2000,
        maxRetries = 3,
        action = { root, _ ->
            // Find ALL editable fields — pickup is the first one (has current location text)
            val allEditFields = NodeFinder.findAllNodesRecursive(root) { it.isEditable }
            Log.i(TAG, "Found ${allEditFields.size} editable field(s) for pickup")
            for ((i, field) in allEditFields.withIndex()) {
                Log.i(TAG, "  Field $i: text='${field.text}' hint='${field.hintText}'")
            }

            // Pickup is the first editable field (has current location or placeholder)
            val pickupField = allEditFields.firstOrNull()
            if (pickupField != null) {
                Log.i(TAG, "Setting pickup on first field, current text='${pickupField.text}'")
                pickupField.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                ActionExecutor.clearText(pickupField)
                if (ActionExecutor.setText(pickupField, pickupAddress)) {
                    Log.i(TAG, "Pickup address set: $pickupAddress")
                    kotlinx.coroutines.delay(1500)
                    return@AutomationStep StepResult.Success
                }
            }

            StepResult.Retry("No editable field found for pickup")
        }
    )

    /**
     * Select pickup search result from the list.
     */
    private fun selectPickupSearchResult(pickupAddress: String) = AutomationStep(
        name = "Select pickup result",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 10_000,
        delayAfterMs = 3000,
        maxRetries = 4,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            val pickupWords = pickupAddress.split(" ").filter { it.length > 2 }

            // Try accessibility — find clickable, NON-editable results
            val clickableResults = NodeFinder.findAllNodesRecursive(root) {
                it.isClickable && !it.isEditable && it.text != null && it.text.toString().length > 3
            }

            val matchingResult = clickableResults.find { node ->
                val text = node.text.toString()
                pickupWords.any { word -> text.contains(word, ignoreCase = true) }
            }

            if (matchingResult != null) {
                Log.i(TAG, "Clicking pickup result via accessibility: ${matchingResult.text}")
                if (ActionExecutor.click(matchingResult)) {
                    return@AutomationStep StepResult.Success
                }
            }

            // OCR fallback
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "Pickup search results OCR: ${ocr.fullText.take(500)}")

                val skipTexts = setOf("Pick-up now", "For me", "Search in a different",
                    "Plan your trip", "Get more results", "Where to")
                val resultCandidates = ocr.blocks
                    .filter { block ->
                        val top = block.bounds?.top ?: 0
                        top in 700..1200 && block.text.length > 3 &&
                        skipTexts.none { block.text.contains(it, ignoreCase = true) }
                    }
                    .sortedBy { it.bounds?.top ?: Int.MAX_VALUE }

                // Match by pickup address words first (prioritize name match over distance)
                for (word in pickupWords) {
                    val match = resultCandidates.find { it.text.contains(word, ignoreCase = true) }
                    if (match?.bounds != null) {
                        val b = match.bounds!!
                        Log.i(TAG, "Tapping pickup result matching '$word': '${match.text}' at (540, ${b.centerY()})")
                        ActionExecutor.tapAtCoordinates(service, ActionExecutor.screenCenterX(service), b.centerY().toFloat())
                        return@AutomationStep StepResult.Success
                    }
                }

                // Fallback: match by distance pattern
                val distanceBlock = resultCandidates.find { block ->
                    block.text.contains(Regex("\\d+\\.?\\d*\\s*mi\\b"))
                }
                if (distanceBlock?.bounds != null) {
                    val b = distanceBlock.bounds!!
                    Log.i(TAG, "Tapping pickup distance line: '${distanceBlock.text}' at (540, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, ActionExecutor.screenCenterX(service), b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }

                // Fallback: first result
                val firstResult = resultCandidates.firstOrNull()
                if (firstResult?.bounds != null) {
                    val b = firstResult.bounds!!
                    Log.i(TAG, "Tapping first pickup result: '${firstResult.text}' at (540, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, ActionExecutor.screenCenterX(service), b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }
            }

            StepResult.Retry("No pickup search results found")
        }
    )

    private fun enterDestination(destination: String) = AutomationStep(
        name = "Enter destination: $destination",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 15_000,
        delayAfterMs = 2000,
        maxRetries = 5,
        action = { root, stepContext ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            val checkOcr = ScreenReader.captureAndRead(service)
            if (checkOcr != null) {
                val hasPrice = checkOcr.fullText.contains("LKR", true) || checkOcr.fullText.contains("Rs", true)
                val hasVehicle = checkOcr.fullText.contains("Moto", true) ||
                        checkOcr.fullText.contains("Tuk", true) || checkOcr.fullText.contains("Zip", true)
                if (hasPrice && hasVehicle) {
                    // We're on ride options screen. This can happen when:
                    // 1. Uber auto-navigated after pickup selection with a cached destination (BAD)
                    // 2. Destination was already entered and Uber navigated here (GOOD)
                    //
                    // If we already typed the destination in a prior retry, trust it.
                    val alreadyTyped = stepContext.collectedData["uber_dest_typed"] == "true"
                    if (alreadyTyped) {
                        Log.i(TAG, "Already on ride options after typing destination — skipping")
                        stepContext.collectedData["destination_verified"] = "true"
                        return@AutomationStep StepResult.Skip("Destination was typed, now on ride options")
                    }

                    // First time seeing ride options — check if destination words are on screen
                    val destWords = destination.split(" ").filter { it.length > 2 }
                    val hasCorrectDest = destWords.any { word ->
                        checkOcr.fullText.contains(word, ignoreCase = true)
                    }
                    if (hasCorrectDest) {
                        Log.i(TAG, "Already on ride options with correct destination — skipping")
                        stepContext.collectedData["destination_verified"] = "true"
                        return@AutomationStep StepResult.Skip("Already on ride options with correct destination")
                    }

                    // Destination not found in text — likely a cached wrong destination.
                    // Only try to fix it once (first retry), then give up and trust it
                    // to avoid infinite retry loops on truncated destination names.
                    val fixAttempted = stepContext.collectedData["uber_dest_fix_attempted"] == "true"
                    if (fixAttempted) {
                        Log.i(TAG, "Already attempted to fix destination, accepting current ride options")
                        stepContext.collectedData["destination_verified"] = "true"
                        return@AutomationStep StepResult.Skip("Accepting ride options after fix attempt")
                    }

                    // First attempt: try to navigate back to search to enter correct destination
                    Log.i(TAG, "On ride options but destination may not match '$destination' — navigating back to search")
                    stepContext.collectedData["uber_dest_fix_attempted"] = "true"

                    val routeBlocks = checkOcr.blocks.filter { block ->
                        val top = block.bounds?.top ?: 0
                        top in 50..350 && block.text.length > 3
                    }
                    val routeBlock = routeBlocks.lastOrNull { it.bounds != null }
                    if (routeBlock?.bounds != null) {
                        val b = routeBlock.bounds!!
                        Log.i(TAG, "Tapping route area to edit destination: '${routeBlock.text}' at (${b.centerX()}, ${b.centerY()})")
                        ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                        kotlinx.coroutines.delay(2000)
                    } else {
                        Log.i(TAG, "Tapping top area to edit route")
                        ActionExecutor.tapAtCoordinates(service, ActionExecutor.screenCenterX(service), ActionExecutor.getScreenSize(service).second * 0.085f)
                        kotlinx.coroutines.delay(2000)
                    }

                    return@AutomationStep StepResult.Retry("Navigated back to search to fix destination")
                }
            }

            // Find ALL editable fields — destination is the empty one
            var allEditFields = NodeFinder.findAllNodesRecursive(root) { it.isEditable }
            Log.i(TAG, "Found ${allEditFields.size} editable field(s)")

            // If no editable fields, try re-tapping "Where to?" to open search screen
            if (allEditFields.isEmpty()) {
                Log.i(TAG, "No editable fields — re-tapping 'Where to?' to open search screen")
                val whereNode = NodeFinder.findByText(root, WHERE_TO_TEXT)
                if (whereNode != null) {
                    ActionExecutor.click(whereNode)
                } else if (checkOcr != null) {
                    val blocks = ScreenReader.findTextBlocks(checkOcr, "Where to")
                    if (blocks.isNotEmpty() && blocks.first().bounds != null) {
                        val b = blocks.first().bounds!!
                        ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                    }
                }
                kotlinx.coroutines.delay(2000)
                val freshRoot = service.getRootNode() ?: root
                allEditFields = NodeFinder.findAllNodesRecursive(freshRoot) { it.isEditable }
                Log.i(TAG, "After re-tap: found ${allEditFields.size} editable field(s)")
            }

            for ((i, field) in allEditFields.withIndex()) {
                Log.i(TAG, "  Field $i: text='${field.text}' hint='${field.hintText}'")
            }

            // Find the destination field: prefer empty/placeholder, or the last one
            val destField = if (allEditFields.size >= 2) {
                val emptyField = allEditFields.find { field ->
                    val text = field.text?.toString() ?: ""
                    text.isEmpty() || text.contains("Where to", ignoreCase = true) ||
                    text.contains("Search", ignoreCase = true) ||
                    text.contains("destination", ignoreCase = true)
                }
                if (emptyField != null) {
                    Log.i(TAG, "Using empty/placeholder field as destination")
                    emptyField
                } else {
                    Log.i(TAG, "Using last editable field as destination")
                    allEditFields.last()
                }
            } else {
                allEditFields.firstOrNull()
            }

            if (destField != null) {
                // Focus the field first
                destField.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                ActionExecutor.clearText(destField)
                if (ActionExecutor.setText(destField, destination)) {
                    Log.i(TAG, "Destination text set: $destination")
                    stepContext.collectedData["uber_dest_typed"] = "true"
                    // Wait for search results
                    kotlinx.coroutines.delay(1500)
                    return@AutomationStep StepResult.Success
                }
            }

            StepResult.Retry("No editable field found for destination")
        }
    )

    private fun selectSearchResult(destination: String) = AutomationStep(
        name = "Select search result",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 10_000,
        delayAfterMs = 3000,
        maxRetries = 4,
        action = { root, stepContext ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            // Check if already on ride options with correct destination
            val checkOcr = ScreenReader.captureAndRead(service)
            if (checkOcr != null) {
                val hasPrice = checkOcr.fullText.contains("LKR", true) || checkOcr.fullText.contains("Rs", true)
                val hasVehicle = checkOcr.fullText.contains("Moto", true) ||
                        checkOcr.fullText.contains("Tuk", true) || checkOcr.fullText.contains("Zip", true)
                if (hasPrice && hasVehicle) {
                    // Skip if destination was verified or typed by enterDestination
                    val verified = stepContext.collectedData["destination_verified"] == "true"
                    val typed = stepContext.collectedData["uber_dest_typed"] == "true"
                    if (verified || typed) {
                        Log.i(TAG, "Already on ride options (verified=$verified, typed=$typed) — skipping")
                        return@AutomationStep StepResult.Skip("Already on ride options")
                    }
                    // enterDestination hasn't run yet — this shouldn't happen normally
                    Log.w(TAG, "On ride options but destination not verified/typed — retrying")
                    return@AutomationStep StepResult.Retry("Destination not yet verified")
                }
            }

            Log.i(TAG, "Looking for search results...")

            val destWords = destination.split(" ").filter { it.length > 2 }

            // Try accessibility — find clickable, NON-editable results matching destination
            // (editable nodes are the search input fields, not results)
            val clickableResults = NodeFinder.findAllNodesRecursive(root) {
                it.isClickable && !it.isEditable && it.text != null && it.text.toString().length > 3
            }

            for (node in clickableResults) {
                Log.i(TAG, "Clickable node: '${node.text}' editable=${node.isEditable}")
            }

            val matchingResult = clickableResults.find { node ->
                val text = node.text.toString()
                destWords.any { word -> text.contains(word, ignoreCase = true) } &&
                // Must not be a search field hint
                !text.equals("Where to?", ignoreCase = true)
            }

            if (matchingResult != null) {
                Log.i(TAG, "Clicking matching result via accessibility: ${matchingResult.text}")
                if (ActionExecutor.click(matchingResult)) {
                    return@AutomationStep StepResult.Success
                }
            }

            // OCR approach — tap the search result by coordinates.
            // The pickup field is at ~Y=548, destination field at ~Y=637.
            // Search RESULTS start below ~Y=700.
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "Search results OCR: ${ocr.fullText.take(500)}")

                // Log all blocks with bounds for debugging
                for (block in ocr.blocks) {
                    if (block.bounds != null) {
                        Log.i(TAG, "OCR block: '${block.text.take(60)}' at Y=${block.bounds!!.top}-${block.bounds!!.bottom}")
                    }
                }

                // Filter to result candidates in Y=700..1200 (below search fields, above keyboard)
                val skipTexts = setOf("Pick-up now", "For me", "Search in a different",
                    "Plan your trip", "Get more results", "Where to")
                val resultCandidates = ocr.blocks
                    .filter { block ->
                        val top = block.bounds?.top ?: 0
                        top in 700..1200 && block.text.length > 3 &&
                        skipTexts.none { block.text.contains(it, ignoreCase = true) }
                    }
                    .sortedBy { it.bounds?.top ?: Int.MAX_VALUE }

                // Strategy 1: Find the distance line (e.g. "6.9 mi 72 Bauddhaloka Mawatha")
                val distanceBlock = resultCandidates.find { block ->
                    block.text.contains(Regex("\\d+\\.?\\d*\\s*mi\\b"))
                }
                if (distanceBlock?.bounds != null) {
                    val b = distanceBlock.bounds!!
                    Log.i(TAG, "Tapping distance/address line via OCR: '${distanceBlock.text}' at (540, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, ActionExecutor.screenCenterX(service), b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }

                // Strategy 2: Match by destination words
                for (word in destWords) {
                    val match = resultCandidates.find { it.text.contains(word, ignoreCase = true) }
                    if (match?.bounds != null) {
                        val b = match.bounds!!
                        Log.i(TAG, "Tapping result matching '$word': '${match.text}' at (540, ${b.centerY()})")
                        ActionExecutor.tapAtCoordinates(service, ActionExecutor.screenCenterX(service), b.centerY().toFloat())
                        return@AutomationStep StepResult.Success
                    }
                }

                // Strategy 3: Tap the FIRST result in the list
                // Handles short/generic destinations like "Home"
                val firstResult = resultCandidates.firstOrNull()
                if (firstResult?.bounds != null) {
                    val b = firstResult.bounds!!
                    Log.i(TAG, "Tapping first result: '${firstResult.text}' at (540, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, ActionExecutor.screenCenterX(service), b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }
            }

            StepResult.Retry("No search results found")
        }
    )

    /**
     * Wait for ride options screen (shows Tuk, Moto, Zip with prices).
     */
    private fun waitForRideOptions() = AutomationStep(
        name = "Wait for ride options",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 20_000,
        delayAfterMs = 1500,
        maxRetries = 6,
        action = { _, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "Checking for ride options: ${ocr.fullText.take(400)}")

                val hasPrice = ocr.fullText.contains("LKR", ignoreCase = true) ||
                        ocr.fullText.contains("Rs", ignoreCase = true)
                val hasVehicle = ocr.fullText.contains("Tuk", ignoreCase = true) ||
                        ocr.fullText.contains("Moto", ignoreCase = true) ||
                        ocr.fullText.contains("Zip", ignoreCase = true)
                val hasChoose = ocr.fullText.contains("Choose", ignoreCase = true)

                if (hasPrice && hasVehicle) {
                    Log.i(TAG, "Ride options detected! hasPrice=$hasPrice hasVehicle=$hasVehicle hasChoose=$hasChoose")
                    return@AutomationStep StepResult.Success
                }

                Log.i(TAG, "Not ride options yet. hasPrice=$hasPrice hasVehicle=$hasVehicle hasChoose=$hasChoose")
            }

            StepResult.Retry("Ride options not visible yet")
        }
    )

    /**
     * Read price from the ride options screen.
     * Uber shows prices per ride type — match by proximity to ride type label.
     */
    private fun readPrice(uberRideType: String) = AutomationStep(
        name = "Read Uber price ($uberRideType)",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 15_000,
        maxRetries = 5,
        delayBeforeMs = 500,
        action = { root, stepContext ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            // Use OCR with proximity matching to get the correct ride type's price.
            // Uber shows multiple ride types (Tuk, Moto, Zip, Minivan) with prices —
            // we need to match the price closest to our target ride type label.
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "Price reading OCR: ${ocr.fullText.take(500)}")

                val pricePattern = Regex("""(?:LKR|[Rr]s\.?)\s*(\d[\d,.]*)""", RegexOption.IGNORE_CASE)
                val allPrices = mutableListOf<Pair<String, Rect?>>()

                for (block in ocr.blocks) {
                    // Fix common OCR misreads in price text before matching
                    val cleanedText = block.text
                        .replace("l", "1")  // lowercase L → 1
                        .replace("O", "0")  // uppercase O → 0 (only in numeric context)
                        .replace("I", "1")  // uppercase I → 1
                    val match = pricePattern.find(cleanedText)
                        ?: pricePattern.find(block.text) // Try original text too
                    if (match != null) {
                        val rawPrice = match.groupValues[1].replace(",", "")
                        val price = normalizePrice(rawPrice)
                        allPrices.add(price to block.bounds)
                        Log.i(TAG, "Found price: LKR $price at ${block.bounds} (raw: '${block.text}', extracted: '$rawPrice')")
                    }
                }

                if (allPrices.isNotEmpty()) {
                    // Match price to ride type by proximity
                    val rideTypeBlocks = ScreenReader.findTextBlocks(ocr, uberRideType)
                    val price = if (rideTypeBlocks.isNotEmpty() && rideTypeBlocks.first().bounds != null && allPrices.size > 1) {
                        val rideBounds = rideTypeBlocks.first().bounds!!
                        Log.i(TAG, "Ride type '$uberRideType' at X=${rideBounds.centerX()}, Y=${rideBounds.centerY()}")
                        // Try X-distance first (horizontal layout), fall back to Y-distance (vertical)
                        allPrices.minByOrNull { (_, bounds) ->
                            if (bounds != null) {
                                // Use combined distance to handle both horizontal and vertical layouts
                                val dx = kotlin.math.abs(bounds.centerX() - rideBounds.centerX())
                                val dy = kotlin.math.abs(bounds.centerY() - rideBounds.centerY())
                                dx + dy
                            } else Int.MAX_VALUE
                        }?.first ?: allPrices.first().first
                    } else {
                        allPrices.first().first
                    }

                    // Extract ETA for the selected ride type
                    val etaPattern = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
                    val rideTypeBounds = rideTypeBlocks.firstOrNull()?.bounds
                    val allEtas = mutableListOf<Pair<Int, Rect?>>()
                    for (block in ocr.blocks) {
                        val etaMatch = etaPattern.find(block.text)
                        if (etaMatch != null) {
                            val minutes = etaMatch.groupValues[1].toIntOrNull()
                            if (minutes != null) {
                                allEtas.add(minutes to block.bounds)
                                Log.i(TAG, "Found ETA: ${minutes} min at ${block.bounds} (raw: '${block.text}')")
                            }
                        }
                    }
                    if (allEtas.isNotEmpty()) {
                        val eta = if (rideTypeBounds != null) {
                            // Match ETA closest to the ride type label
                            allEtas.minByOrNull { (_, bounds) ->
                                if (bounds != null) {
                                    val dx = kotlin.math.abs(bounds.centerX() - rideTypeBounds.centerX())
                                    val dy = kotlin.math.abs(bounds.centerY() - rideTypeBounds.centerY())
                                    dx + dy
                                } else Int.MAX_VALUE
                            }?.first
                        } else {
                            allEtas.first().first
                        }
                        if (eta != null) {
                            stepContext.collectedData["uber_eta"] = eta.toString()
                            Log.i(TAG, "Uber ETA for $uberRideType: $eta min")
                        }
                    }

                    Log.i(TAG, "Uber price for $uberRideType: LKR $price")
                    return@AutomationStep StepResult.SuccessWithData("uber_price", price)
                }

                Log.w(TAG, "No prices found in OCR")
            }

            StepResult.Retry("Could not extract Uber price")
        }
    )

    /**
     * Tap the ride type label (Moto, Tuk, Zip) to select it.
     * Uses OCR coordinate tap (accessibility clicks often don't register on Uber's custom views).
     * Verifies selection by checking that "Choose [type]" button appears.
     */
    private fun selectRideType(uberRideType: String) = AutomationStep(
        name = "Select ride type: $uberRideType",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 15_000,
        delayAfterMs = 2000,
        maxRetries = 5,
        action = { _, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "Ride options screen: ${ocr.fullText.take(400)}")

                // Check if already selected (Choose button shows our type)
                val chooseText = "Choose $uberRideType"
                if (ocr.fullText.contains(chooseText, ignoreCase = true)) {
                    Log.i(TAG, "'$chooseText' already visible — ride type already selected")
                    return@AutomationStep StepResult.Success
                }

                // Find the ride type label via OCR and tap it with coordinates
                // (accessibility clicks don't reliably select ride types in Uber)
                val blocks = ScreenReader.findTextBlocks(ocr, uberRideType)
                if (blocks.isNotEmpty() && blocks.first().bounds != null) {
                    val b = blocks.first().bounds!!
                    Log.i(TAG, "Tapping ride type '$uberRideType' via OCR at (${b.centerX()}, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())

                    // Verify selection worked
                    kotlinx.coroutines.delay(1500)
                    val afterOcr = ScreenReader.captureAndRead(service)
                    if (afterOcr != null && afterOcr.fullText.contains(chooseText, ignoreCase = true)) {
                        Log.i(TAG, "'$chooseText' appeared — selection confirmed")
                        return@AutomationStep StepResult.Success
                    }

                    Log.w(TAG, "Tapped '$uberRideType' but '$chooseText' not visible yet")
                    return@AutomationStep StepResult.Retry("Selection not confirmed")
                }
            }

            StepResult.Retry("Could not find ride type '$uberRideType'")
        }
    )

    /**
     * Tap the "Choose [type]" button (e.g. "Choose Moto") to confirm booking.
     */
    private fun tapChooseRide(uberRideType: String) = AutomationStep(
        name = "Tap Choose $uberRideType",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 10_000,
        delayAfterMs = 2000,
        maxRetries = 3,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            val chooseText = "Choose $uberRideType"

            // Try accessibility first
            val node = NodeFinder.findByText(root, chooseText)
                ?: NodeFinder.findByText(root, "Choose a trip")
            if (node != null) {
                Log.i(TAG, "Tapping '$chooseText' via accessibility")
                if (ActionExecutor.click(node)) {
                    return@AutomationStep StepResult.Success
                }
            }

            // OCR fallback
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                val blocks = ScreenReader.findTextBlocks(ocr, chooseText)
                    .ifEmpty { ScreenReader.findTextBlocks(ocr, "Choose") }
                if (blocks.isNotEmpty() && blocks.first().bounds != null) {
                    val b = blocks.first().bounds!!
                    Log.i(TAG, "Tapping '$chooseText' via OCR at (${b.centerX()}, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }
            }

            StepResult.Retry("Could not find '$chooseText' button")
        }
    )

    /**
     * Ensures we're on the ride options screen. If Uber shows the home screen
     * instead of remembering ride options, navigates through the full search flow.
     */
    private fun ensureOnRideOptionsScreen(destination: String, pickupAddress: String) = AutomationStep(
        name = "Navigate to ride options",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 45_000,
        maxRetries = 2,
        delayAfterMs = 1500,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            // Check if already on ride options screen
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                val hasPrice = ocr.fullText.contains("LKR", true) || ocr.fullText.contains("Rs", true)
                val hasVehicle = ocr.fullText.contains("Moto", true) ||
                        ocr.fullText.contains("Tuk", true) || ocr.fullText.contains("Zip", true)
                if (hasPrice && hasVehicle) {
                    Log.i(TAG, "Already on ride options screen")
                    return@AutomationStep StepResult.Success
                }
                Log.i(TAG, "Not on ride options, navigating from home screen...")
                Log.i(TAG, "Current screen: ${ocr.fullText.take(200)}")
            }

            // Step 1: Tap "Where to?"
            val whereNode = NodeFinder.findByText(root, WHERE_TO_TEXT)
            if (whereNode != null) {
                Log.i(TAG, "Tapping 'Where to?' via accessibility")
                ActionExecutor.click(whereNode)
            } else if (ocr != null) {
                val blocks = ScreenReader.findTextBlocks(ocr, "Where to")
                if (blocks.isNotEmpty() && blocks.first().bounds != null) {
                    val b = blocks.first().bounds!!
                    Log.i(TAG, "Tapping 'Where to?' via OCR")
                    ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                }
            }
            kotlinx.coroutines.delay(2000)

            // Step 2: Enter pickup address if provided
            if (pickupAddress.isNotBlank()) {
                val freshRoot1 = service.getRootNode() ?: root
                val fields1 = NodeFinder.findAllNodesRecursive(freshRoot1) { it.isEditable }
                val pickupField = fields1.firstOrNull()
                if (pickupField != null) {
                    Log.i(TAG, "Entering pickup: $pickupAddress")
                    pickupField.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                    ActionExecutor.clearText(pickupField)
                    ActionExecutor.setText(pickupField, pickupAddress)
                    kotlinx.coroutines.delay(2000)

                    // Select pickup result
                    val pickupOcr = ScreenReader.captureAndRead(service)
                    if (pickupOcr != null) {
                        val pickupWords = pickupAddress.split(" ").filter { it.length > 2 }
                        val skipTexts = setOf("Pick-up now", "For me", "Search in a different", "Plan your trip", "Where to")
                        val candidates = pickupOcr.blocks.filter { block ->
                            val top = block.bounds?.top ?: 0
                            top in 700..1200 && block.text.length > 3 &&
                            skipTexts.none { block.text.contains(it, true) }
                        }.sortedBy { it.bounds?.top ?: Int.MAX_VALUE }

                        val match = pickupWords.firstNotNullOfOrNull { word ->
                            candidates.find { it.text.contains(word, true) }
                        } ?: candidates.firstOrNull()

                        if (match?.bounds != null) {
                            Log.i(TAG, "Selecting pickup result: '${match.text}'")
                            ActionExecutor.tapAtCoordinates(service, ActionExecutor.screenCenterX(service), match.bounds!!.centerY().toFloat())
                            kotlinx.coroutines.delay(2500)

                            // After selecting pickup, Uber may auto-navigate to ride options
                            // with a cached destination. Check and navigate back if needed.
                            val afterPickupOcr = ScreenReader.captureAndRead(service)
                            if (afterPickupOcr != null) {
                                val hasPrice = afterPickupOcr.fullText.contains("LKR", true) || afterPickupOcr.fullText.contains("Rs", true)
                                val hasVehicle = afterPickupOcr.fullText.contains("Moto", true) ||
                                        afterPickupOcr.fullText.contains("Tuk", true) || afterPickupOcr.fullText.contains("Zip", true)
                                if (hasPrice && hasVehicle) {
                                    Log.i(TAG, "Uber auto-navigated to ride options after pickup — tapping route to edit destination")
                                    val routeBlocks = afterPickupOcr.blocks.filter { block ->
                                        val top = block.bounds?.top ?: 0
                                        top in 50..350 && block.text.length > 3
                                    }
                                    val routeBlock = routeBlocks.lastOrNull { it.bounds != null }
                                    if (routeBlock?.bounds != null) {
                                        ActionExecutor.tapAtCoordinates(service, routeBlock.bounds!!.centerX().toFloat(), routeBlock.bounds!!.centerY().toFloat())
                                    } else {
                                        ActionExecutor.tapAtCoordinates(service, ActionExecutor.screenCenterX(service), ActionExecutor.getScreenSize(service).second * 0.085f)
                                    }
                                    kotlinx.coroutines.delay(2000)
                                }
                            }
                        }
                    }
                }
            }

            // Step 3: Enter destination
            val freshRoot2 = service.getRootNode() ?: root
            val fields2 = NodeFinder.findAllNodesRecursive(freshRoot2) { it.isEditable }
            val destField = if (fields2.size >= 2) {
                fields2.find { f ->
                    val t = f.text?.toString() ?: ""
                    t.isEmpty() || t.contains("Where to", true) || t.contains("Search", true)
                } ?: fields2.last()
            } else fields2.firstOrNull()

            if (destField != null) {
                Log.i(TAG, "Entering destination: $destination")
                destField.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                ActionExecutor.clearText(destField)
                ActionExecutor.setText(destField, destination)
                kotlinx.coroutines.delay(2000)
            } else {
                Log.w(TAG, "No editable field found for destination")
                return@AutomationStep StepResult.Retry("No destination field found")
            }

            // Step 4: Select destination result
            val destOcr = ScreenReader.captureAndRead(service)
            if (destOcr != null) {
                val destWords = destination.split(" ").filter { it.length > 2 }
                val skipTexts = setOf("Pick-up now", "For me", "Search in a different", "Plan your trip", "Where to")
                val candidates = destOcr.blocks.filter { block ->
                    val top = block.bounds?.top ?: 0
                    top in 700..1200 && block.text.length > 3 &&
                    skipTexts.none { block.text.contains(it, true) }
                }.sortedBy { it.bounds?.top ?: Int.MAX_VALUE }

                // Try word match, then distance pattern, then first result
                val wordMatch = destWords.firstNotNullOfOrNull { word ->
                    candidates.find { it.text.contains(word, true) }
                }
                val distMatch = candidates.find { it.text.contains(Regex("\\d+\\.?\\d*\\s*mi\\b")) }
                val match = wordMatch ?: distMatch ?: candidates.firstOrNull()

                if (match?.bounds != null) {
                    Log.i(TAG, "Selecting destination result: '${match.text}'")
                    ActionExecutor.tapAtCoordinates(service, ActionExecutor.screenCenterX(service), match.bounds!!.centerY().toFloat())
                } else {
                    Log.w(TAG, "No destination results found")
                    return@AutomationStep StepResult.Retry("No destination results")
                }
            }

            // Step 5: Wait for ride options to load
            kotlinx.coroutines.delay(4000)
            val finalOcr = ScreenReader.captureAndRead(service)
            if (finalOcr != null) {
                val hasPrice = finalOcr.fullText.contains("LKR", true) || finalOcr.fullText.contains("Rs", true)
                val hasVehicle = finalOcr.fullText.contains("Moto", true) ||
                        finalOcr.fullText.contains("Tuk", true) || finalOcr.fullText.contains("Zip", true)
                if (hasPrice && hasVehicle) {
                    Log.i(TAG, "Ride options loaded after navigation")
                    return@AutomationStep StepResult.Success
                }
            }

            StepResult.Retry("Ride options not loaded after navigation")
        }
    )

    /**
     * Handle "Is this trip for you or someone else?" prompt.
     * If far from pickup, Uber asks this. Tap "For me" or skip if not shown.
     */
    private fun handleForMePrompt() = AutomationStep(
        name = "Handle 'For me' prompt (if any)",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 8_000,
        delayAfterMs = 1500,
        maxRetries = 2,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            // Try accessibility first
            val forMeNode = NodeFinder.findByText(root, "For me")
                ?: NodeFinder.findByText(root, "For Me")
            if (forMeNode != null) {
                Log.i(TAG, "Found 'For me' prompt, tapping")
                if (ActionExecutor.click(forMeNode)) {
                    return@AutomationStep StepResult.Success
                }
            }

            // OCR fallback
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "For-me check OCR: ${ocr.fullText.take(300)}")

                val forMeBlocks = ScreenReader.findTextBlocks(ocr, "For me")
                if (forMeBlocks.isNotEmpty() && forMeBlocks.first().bounds != null) {
                    val b = forMeBlocks.first().bounds!!
                    Log.i(TAG, "Tapping 'For me' via OCR at (${b.centerX()}, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }

                // Check if we're already past this (confirm pickup or requesting)
                if (ocr.fullText.contains("Confirm", ignoreCase = true) ||
                    ocr.fullText.contains("Looking for", ignoreCase = true) ||
                    ocr.fullText.contains("Requesting", ignoreCase = true)) {
                    Log.i(TAG, "No 'For me' prompt, already on next screen")
                    return@AutomationStep StepResult.Skip("No 'For me' prompt")
                }
            }

            // Not shown — skip
            Log.i(TAG, "No 'For me' prompt detected, skipping")
            StepResult.Skip("No 'For me' prompt")
        }
    )

    /**
     * Tap "Confirm pickup" button after choosing ride type.
     */
    private fun tapConfirmPickup() = AutomationStep(
        name = "Tap Confirm Pickup",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 15_000,
        delayAfterMs = 2000,
        maxRetries = 5,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            // Try accessibility first
            val confirmNode = NodeFinder.findByText(root, "Confirm pickup")
                ?: NodeFinder.findByText(root, "Confirm Pickup")
                ?: NodeFinder.findByText(root, "CONFIRM PICKUP")
                ?: NodeFinder.findByText(root, "Confirm")
            if (confirmNode != null) {
                Log.i(TAG, "Tapping 'Confirm pickup' via accessibility")
                if (ActionExecutor.click(confirmNode)) {
                    return@AutomationStep StepResult.Success
                }
            }

            // OCR fallback
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "Confirm screen OCR: ${ocr.fullText.take(300)}")

                // Check if ride is already being requested (skip confirm)
                if (ocr.fullText.contains("Looking for", ignoreCase = true) ||
                    ocr.fullText.contains("Requesting", ignoreCase = true)) {
                    Log.i(TAG, "Ride already being requested, skipping confirm")
                    return@AutomationStep StepResult.Skip("Already requesting")
                }

                val blocks = ScreenReader.findTextBlocks(ocr, "Confirm")
                    .sortedByDescending { it.bounds?.top ?: 0 } // Bottom-most = button
                if (blocks.isNotEmpty() && blocks.first().bounds != null) {
                    val b = blocks.first().bounds!!
                    Log.i(TAG, "Tapping 'Confirm pickup' via OCR at (${b.centerX()}, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }
            }

            StepResult.Retry("Could not find 'Confirm pickup' button")
        }
    )
}
