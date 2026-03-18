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

        if (pickupAddress.isNotBlank()) {
            // Custom pickup — sequential flow matching Uber's UI:
            // 1. Type pickup → select from dropdown (Uber stays on search screen)
            // 2. Type destination → select from dropdown (Uber navigates to ride options)
            steps.add(enterPickupAddress(pickupAddress))
            steps.add(selectPickupSearchResult(pickupAddress))
            steps.add(enterDestination(destination))
            steps.add(selectSearchResult(destination))
        } else {
            // No custom pickup: enter destination first directly in the "Where to?" field.
            // This avoids the cached destination issue entirely since we go straight to dest.
            steps.add(enterDestination(destination))
            steps.add(selectSearchResult(destination))
        }

        steps.addAll(listOf(
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
            tapConfirmPickup(),
            handleNotForSomeoneElsePrompt()
        )
    }

    /**
     * Quick booking steps — brings Uber back to foreground (still on ride options screen)
     * and taps Choose ride. Used when the app was already navigated during price reading.
     */
    fun buildQuickBookingSteps(context: Context, rideType: String, destination: String = ""): List<AutomationStep> {
        // resumeApp preserves app state (ride options screen with correct destination),
        // so no need to verify destination — just select ride type and book.
        return listOf(
            resumeApp(context),
            selectRideType(mapRideType(rideType)),
            tapChooseRide(mapRideType(rideType)),
            handleForMePrompt(),
            tapConfirmPickup(),
            handleNotForSomeoneElsePrompt()
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

    /**
     * After resuming Uber, verify the ride options screen still shows the correct destination.
     * Uber sometimes replaces the destination with a cached location (e.g. "Home") when
     * the app is brought back from the background.
     * If the destination is wrong, navigate back to search and re-enter it.
     */
    private fun verifyDestinationAfterResume(destination: String) = AutomationStep(
        name = "Verify destination after resume",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 30_000,
        delayAfterMs = 1500,
        maxRetries = 5,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            val ocr = ScreenReader.captureAndRead(service)
            if (ocr == null) {
                return@AutomationStep StepResult.Retry("Could not read screen")
            }

            // Check we're on ride options
            val hasPrice = ocr.fullText.contains("LKR", true) || ocr.fullText.contains("Rs", true)
            val hasVehicle = ocr.fullText.contains("Moto", true) ||
                    ocr.fullText.contains("Tuk", true) || ocr.fullText.contains("Zip", true)

            if (!hasPrice || !hasVehicle) {
                Log.w(TAG, "Not on ride options screen after resume — retrying")
                return@AutomationStep StepResult.Retry("Not on ride options screen")
            }

            // Check if the correct destination is shown on screen (route info at the top)
            val destWords = destination.split(" ").filter { it.length > 2 }
            val topBlocks = ocr.blocks.filter { block ->
                val top = block.bounds?.top ?: 0
                top in 0..400
            }
            val topText = topBlocks.joinToString(" ") { it.text }
            val hasCorrectDest = destWords.any { word ->
                topText.contains(word, ignoreCase = true)
            }

            if (hasCorrectDest) {
                Log.i(TAG, "Destination verified after resume: found destination words in '$topText'")
                return@AutomationStep StepResult.Success
            }

            // Wrong destination — Uber swapped it (e.g. to "Home")
            Log.w(TAG, "Wrong destination after resume! Route shows: '$topText', expected words from: '$destination'")
            Log.i(TAG, "Navigating back to fix destination...")

            // Tap the route/destination area at the top to go back to search
            val routeBlock = topBlocks.filter { it.text.length > 3 }.lastOrNull { it.bounds != null }
            if (routeBlock?.bounds != null) {
                val b = routeBlock.bounds!!
                Log.i(TAG, "Tapping route area: '${routeBlock.text}' at (${b.centerX()}, ${b.centerY()})")
                ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
            } else {
                Log.i(TAG, "Tapping top area to edit route")
                ActionExecutor.tapAtCoordinates(service, ActionExecutor.screenCenterX(service), ActionExecutor.getScreenSize(service).second * 0.085f)
            }
            kotlinx.coroutines.delay(2000)

            // Now on search screen — find and fill destination field
            val freshRoot = service.getRootNode() ?: root
            val editFields = NodeFinder.findAllNodesRecursive(freshRoot) { it.isEditable }
            Log.i(TAG, "Found ${editFields.size} editable field(s) after navigating back")

            val destField = if (editFields.size >= 2) {
                editFields.find { f ->
                    val t = f.text?.toString() ?: ""
                    t.isEmpty() || t.contains("Where to", true) || t.contains("Search", true) ||
                    t.contains("destination", true)
                } ?: editFields.last()
            } else {
                editFields.firstOrNull()
            }

            if (destField != null) {
                ActionExecutor.tapNodeCenter(service, destField)
                kotlinx.coroutines.delay(500)

                val freshRoot2 = service.getRootNode() ?: freshRoot
                val freshFields = NodeFinder.findAllNodesRecursive(freshRoot2) { it.isEditable }
                val activeField = freshFields.find { f ->
                    val t = f.text?.toString() ?: ""
                    t.isEmpty() || t.contains("Where to", true) || t.contains("Search", true) ||
                    t.contains("destination", true)
                } ?: freshFields.lastOrNull() ?: destField

                ActionExecutor.click(activeField)
                kotlinx.coroutines.delay(300)
                activeField.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                ActionExecutor.clearText(activeField)
                ActionExecutor.setTextWithRetrigger(activeField, destination)
                Log.i(TAG, "Re-entered destination: $destination")
                kotlinx.coroutines.delay(1500)

                // Select search result
                val searchOcr = ScreenReader.captureAndRead(service)
                if (searchOcr != null) {
                    val skipTexts = setOf("Pick-up now", "For me", "Search in a different", "Plan your trip", "Where to")
                    val screenHeight = ActionExecutor.getScreenSize(service).second
                    val maxY = (screenHeight * 0.65).toInt()
                    // Get bottom of editable fields to avoid tapping the text field itself
                    val freshRoot2 = service.getRootNode() ?: root
                    val editFields = NodeFinder.findAllNodesRecursive(freshRoot2) { it.isEditable }
                    val fieldsBottom = editFields.maxOfOrNull { f ->
                        val r = android.graphics.Rect(); f.getBoundsInScreen(r); r.bottom
                    } ?: (screenHeight * 0.30).toInt()
                    val minY = fieldsBottom + 20
                    val candidates = searchOcr.blocks.filter { block ->
                        val top = block.bounds?.top ?: 0
                        top in minY..maxY && block.text.length > 3 &&
                        skipTexts.none { block.text.contains(it, true) }
                    }.sortedBy { it.bounds?.top ?: Int.MAX_VALUE }

                    val leftTapX = ActionExecutor.getScreenSize(service).first * 0.25f

                    // Word match first
                    val wordMatch = destWords.firstNotNullOfOrNull { word ->
                        candidates.find { it.text.contains(word, true) }
                    }
                    val distMatch = candidates.find { it.text.contains(Regex("\\d+\\.?\\d*\\s*mi\\b")) }
                    val match = wordMatch ?: distMatch ?: candidates.firstOrNull()

                    if (match?.bounds != null) {
                        val b = match.bounds!!
                        Log.i(TAG, "Selecting corrected destination result: '${match.text}'")
                        ActionExecutor.tapAtCoordinates(service, leftTapX, b.centerY().toFloat())
                        kotlinx.coroutines.delay(3000)

                        // Verify we're back on ride options
                        val afterOcr = ScreenReader.captureAndRead(service)
                        if (afterOcr != null) {
                            val backOnRideOptions = (afterOcr.fullText.contains("LKR", true) || afterOcr.fullText.contains("Rs", true)) &&
                                    (afterOcr.fullText.contains("Moto", true) || afterOcr.fullText.contains("Tuk", true) || afterOcr.fullText.contains("Zip", true))
                            if (backOnRideOptions) {
                                Log.i(TAG, "Destination corrected, back on ride options")
                                return@AutomationStep StepResult.Success
                            }
                        }
                    }
                }
            }

            StepResult.Retry("Could not correct destination after resume")
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
                // Tap the field like a real user to activate the text listener
                ActionExecutor.click(pickupField)
                kotlinx.coroutines.delay(300)
                pickupField.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                ActionExecutor.clearText(pickupField)
                if (ActionExecutor.setTextWithRetrigger(pickupField, pickupAddress)) {
                    Log.i(TAG, "Pickup address set: $pickupAddress")
                    // Wait for search results to load before the next step selects one
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
        timeoutMs = 15_000,
        delayAfterMs = 3000,
        maxRetries = 5,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            // If already on ride options, Uber resolved pickup automatically — skip
            val preCheckOcr = ScreenReader.captureAndRead(service)
            if (preCheckOcr != null) {
                val hasPrice = preCheckOcr.fullText.contains("LKR", true) || preCheckOcr.fullText.contains("Rs", true)
                val hasVehicle = preCheckOcr.fullText.contains("Moto", true) ||
                        preCheckOcr.fullText.contains("Tuk", true) || preCheckOcr.fullText.contains("Zip", true)
                if (hasPrice && hasVehicle) {
                    Log.i(TAG, "Already on ride options — pickup was auto-resolved, skipping")
                    return@AutomationStep StepResult.Skip("Pickup auto-resolved")
                }
            }

            val pickupWords = pickupAddress.split(" ").filter { it.length > 2 }
            val screenHeight = ActionExecutor.getScreenSize(service).second
            val resultsMaxY = (screenHeight * 0.65).toInt()

            // Find the bottom of the editable fields so we only tap search results BELOW them.
            val editableFields = NodeFinder.findAllNodesRecursive(root) { it.isEditable }
            val fieldsBottomY = editableFields.maxOfOrNull { field ->
                val rect = android.graphics.Rect()
                field.getBoundsInScreen(rect)
                rect.bottom
            } ?: (screenHeight * 0.30).toInt()
            val resultsMinY = fieldsBottomY + 20
            Log.i(TAG, "Pickup results Y range: $resultsMinY..$resultsMaxY (fields bottom: $fieldsBottomY)")

            val skipTexts = setOf("Pick-up now", "For me", "Search in a different",
                "Plan your trip", "Get more results", "Where to", "Saved places",
                "Set location on map")

            // Wait for search results to stabilize (Uber loads them progressively)
            var stableOcr: com.jayathu.automata.engine.OcrResult? = null
            var previousFirstResult: String? = null
            for (attempt in 1..3) {
                val snapshot = ScreenReader.captureAndRead(service)
                if (snapshot != null) {
                    val candidates = snapshot.blocks.filter { block ->
                        val top = block.bounds?.top ?: 0
                        top in resultsMinY..resultsMaxY && block.text.length > 3 &&
                        skipTexts.none { block.text.contains(it, ignoreCase = true) }
                    }.sortedBy { it.bounds?.top ?: Int.MAX_VALUE }

                    val firstText = candidates.firstOrNull()?.text
                    Log.i(TAG, "Pickup stabilization attempt $attempt: first='$firstText', candidates=${candidates.size}")

                    if (firstText != null && firstText == previousFirstResult) {
                        stableOcr = snapshot
                        break
                    }
                    // If we found a word match, use immediately
                    val hasWordMatch = candidates.any { c ->
                        pickupWords.any { word -> c.text.contains(word, ignoreCase = true) }
                    }
                    if (hasWordMatch && attempt >= 2) {
                        stableOcr = snapshot
                        break
                    }
                    previousFirstResult = firstText
                    stableOcr = snapshot
                }
                if (attempt < 3) kotlinx.coroutines.delay(1000)
            }

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

            // OCR approach
            val ocr = stableOcr
            if (ocr != null) {
                Log.i(TAG, "Pickup search results OCR: ${ocr.fullText.take(500)}")

                // Log all blocks for debugging
                for (block in ocr.blocks) {
                    if (block.bounds != null) {
                        Log.i(TAG, "OCR block: '${block.text.take(60)}' at Y=${block.bounds!!.top}-${block.bounds!!.bottom}")
                    }
                }

                val resultCandidates = ocr.blocks.filter { block ->
                    val top = block.bounds?.top ?: 0
                    top in resultsMinY..resultsMaxY && block.text.length > 3 &&
                    skipTexts.none { block.text.contains(it, ignoreCase = true) }
                }.sortedBy { it.bounds?.top ?: Int.MAX_VALUE }

                Log.i(TAG, "Pickup result candidates (Y=$resultsMinY..$resultsMaxY): ${resultCandidates.size}")

                // Tap on the LEFT side to avoid "Saved places" button on the right
                val leftTapX = ActionExecutor.getScreenSize(service).first * 0.25f

                // Strategy 1: Match by pickup address words
                for (word in pickupWords) {
                    val match = resultCandidates.find { it.text.contains(word, ignoreCase = true) }
                    if (match?.bounds != null) {
                        val b = match.bounds!!
                        Log.i(TAG, "Tapping pickup result matching '$word': '${match.text}' at (left, ${b.centerY()})")
                        ActionExecutor.tapAtCoordinates(service, leftTapX, b.centerY().toFloat())
                        return@AutomationStep StepResult.Success
                    }
                }

                // Strategy 2: Match by distance pattern
                val distanceBlock = resultCandidates.find { block ->
                    block.text.contains(Regex("\\d+\\.?\\d*\\s*mi\\b"))
                }
                if (distanceBlock?.bounds != null) {
                    val b = distanceBlock.bounds!!
                    Log.i(TAG, "Tapping pickup distance line: '${distanceBlock.text}' at (left, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, leftTapX, b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }

                // Strategy 3: Tap the first result in the list (topmost candidate)
                val firstResult = resultCandidates.firstOrNull()
                if (firstResult?.bounds != null) {
                    val b = firstResult.bounds!!
                    Log.i(TAG, "Tapping first pickup result: '${firstResult.text}' at (left, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, leftTapX, b.centerY().toFloat())
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
        timeoutMs = 20_000,
        delayAfterMs = 2000,
        maxRetries = 7,
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
                    // If we already typed AND selected the destination in a prior retry, trust it.
                    val alreadySelected = stepContext.collectedData["uber_dest_selected"] == "true"
                    if (alreadySelected) {
                        Log.i(TAG, "Already on ride options after selecting destination — skipping")
                        stepContext.collectedData["destination_verified"] = "true"
                        return@AutomationStep StepResult.Skip("Destination was selected, now on ride options")
                    }

                    // Check if destination words are on screen
                    val destWords = destination.split(" ").filter { it.length > 2 }
                    val hasCorrectDest = destWords.any { word ->
                        checkOcr.fullText.contains(word, ignoreCase = true)
                    }
                    if (hasCorrectDest) {
                        Log.i(TAG, "Already on ride options with correct destination — skipping")
                        stepContext.collectedData["destination_verified"] = "true"
                        return@AutomationStep StepResult.Skip("Already on ride options with correct destination")
                    }

                    // Wrong destination on ride options — tap the route/destination area
                    // at the top to edit it. This preserves the pickup and only changes
                    // the destination. Do NOT press Back — that resets both fields.
                    Log.i(TAG, "On ride options with wrong destination — tapping route area to edit for '$destination'")

                    val routeBlocks = checkOcr.blocks.filter { block ->
                        val top = block.bounds?.top ?: 0
                        top in 50..350 && block.text.length > 3
                    }
                    // Tap the LAST route block (usually the destination line, below pickup)
                    val routeBlock = routeBlocks.lastOrNull { it.bounds != null }
                    if (routeBlock?.bounds != null) {
                        val b = routeBlock.bounds!!
                        Log.i(TAG, "Tapping destination in route area: '${routeBlock.text}' at (${b.centerX()}, ${b.centerY()})")
                        ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                    } else {
                        // Fallback: tap the route area where destination typically appears
                        val screenHeight = ActionExecutor.getScreenSize(service).second
                        Log.i(TAG, "Tapping route area fallback for destination edit")
                        ActionExecutor.tapAtCoordinates(service, ActionExecutor.screenCenterX(service), screenHeight * 0.12f)
                    }
                    kotlinx.coroutines.delay(2000)
                    return@AutomationStep StepResult.Retry("Navigating to edit destination")
                }
            }

            // Not on ride options — we're on the search screen. Find editable fields.
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
                // Tap the field to activate it (ACTION_FOCUS alone may not work in Uber)
                Log.i(TAG, "Tapping destination field to activate it")
                ActionExecutor.tapNodeCenter(service, destField)
                kotlinx.coroutines.delay(500)

                // Re-fetch the field after tapping (node references can go stale)
                val freshRoot = service.getRootNode() ?: root
                val freshFields = NodeFinder.findAllNodesRecursive(freshRoot) { it.isEditable }
                val activeField = freshFields.find { field ->
                    val text = field.text?.toString() ?: ""
                    text.isEmpty() || text.contains("Where to", ignoreCase = true) ||
                    text.contains("Search", ignoreCase = true) ||
                    text.contains("destination", ignoreCase = true)
                } ?: freshFields.lastOrNull() ?: destField

                ActionExecutor.click(activeField)
                kotlinx.coroutines.delay(300)
                activeField.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                ActionExecutor.clearText(activeField)
                if (ActionExecutor.setTextWithRetrigger(activeField, destination)) {
                    Log.i(TAG, "Destination text set: $destination")
                    stepContext.collectedData["uber_dest_typed"] = "true"
                    // Wait for search results to load
                    kotlinx.coroutines.delay(1500)
                    return@AutomationStep StepResult.Success
                } else {
                    Log.w(TAG, "setText failed — retrying")
                }
            }

            StepResult.Retry("Could not enter destination text")
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
                    // Only skip if destination was explicitly verified or already selected
                    val verified = stepContext.collectedData["destination_verified"] == "true"
                    val selected = stepContext.collectedData["uber_dest_selected"] == "true"
                    if (verified || selected) {
                        Log.i(TAG, "Already on ride options (verified=$verified, selected=$selected) — skipping")
                        return@AutomationStep StepResult.Skip("Already on ride options")
                    }
                    // Typed but not yet selected — check if destination words match screen
                    val destWords2 = destination.split(" ").filter { it.length > 2 }
                    val hasCorrectDest = destWords2.any { word ->
                        checkOcr.fullText.contains(word, ignoreCase = true)
                    }
                    if (hasCorrectDest) {
                        Log.i(TAG, "On ride options with correct destination words — accepting")
                        stepContext.collectedData["uber_dest_selected"] = "true"
                        return@AutomationStep StepResult.Skip("Already on ride options with correct destination")
                    }
                    Log.w(TAG, "On ride options but wrong destination — retrying")
                    return@AutomationStep StepResult.Retry("Wrong destination on ride options")
                }
            }

            Log.i(TAG, "Looking for search results...")

            val destWords = destination.split(" ").filter { it.length > 2 }
            val screenHeight = ActionExecutor.getScreenSize(service).second
            val resultsMaxY = (screenHeight * 0.65).toInt()

            // Find the bottom of the editable fields so we only tap search results BELOW them.
            // This avoids tapping the text field itself when OCR picks up the typed text.
            val editableFields = NodeFinder.findAllNodesRecursive(root) { it.isEditable }
            val fieldsBottomY = editableFields.maxOfOrNull { field ->
                val rect = android.graphics.Rect()
                field.getBoundsInScreen(rect)
                rect.bottom
            } ?: (screenHeight * 0.30).toInt()
            val resultsMinY = fieldsBottomY + 20 // small gap below fields
            Log.i(TAG, "Search results Y range: $resultsMinY..$resultsMaxY (fields bottom: $fieldsBottomY)")

            // Wait for search results to stabilize (stop changing) before reading.
            // Uber loads results progressively, so early OCR may show stale/partial results.
            val skipTexts = setOf("Pick-up now", "For me", "Search in a different",
                "Plan your trip", "Get more results", "Where to", "Saved places",
                "Set location on map")

            var stableOcr: com.jayathu.automata.engine.OcrResult? = null
            var previousFirstResult: String? = null
            for (attempt in 1..4) {
                val snapshot = ScreenReader.captureAndRead(service)
                if (snapshot != null) {
                    val candidates = snapshot.blocks
                        .filter { block ->
                            val top = block.bounds?.top ?: 0
                            top in resultsMinY..resultsMaxY && block.text.length > 3 &&
                            skipTexts.none { block.text.contains(it, ignoreCase = true) }
                        }
                        .sortedBy { it.bounds?.top ?: Int.MAX_VALUE }
                    val firstText = candidates.firstOrNull()?.text
                    val hasWordMatch = candidates.any { c ->
                        destWords.any { word -> c.text.contains(word, ignoreCase = true) }
                    }
                    Log.i(TAG, "Search stabilization attempt $attempt: first='$firstText', hasWordMatch=$hasWordMatch")

                    if (firstText != null && firstText == previousFirstResult) {
                        stableOcr = snapshot
                        break
                    }
                    // If we found a word match, results are relevant — use immediately
                    if (hasWordMatch && attempt >= 2) {
                        Log.i(TAG, "Found word match for destination — results ready")
                        stableOcr = snapshot
                        break
                    }
                    previousFirstResult = firstText
                    stableOcr = snapshot
                }
                if (attempt < 4) kotlinx.coroutines.delay(1000)
            }

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
                    stepContext.collectedData["uber_dest_selected"] = "true"
                    return@AutomationStep StepResult.Success
                }
            }

            // OCR approach — tap the search result by coordinates.
            val ocr = stableOcr
            if (ocr != null) {
                Log.i(TAG, "Search results OCR: ${ocr.fullText.take(500)}")

                // Log all blocks with bounds for debugging
                for (block in ocr.blocks) {
                    if (block.bounds != null) {
                        Log.i(TAG, "OCR block: '${block.text.take(60)}' at Y=${block.bounds!!.top}-${block.bounds!!.bottom}")
                    }
                }

                // Filter to result candidates below search fields, above keyboard
                val resultCandidates = ocr.blocks
                    .filter { block ->
                        val top = block.bounds?.top ?: 0
                        top in resultsMinY..resultsMaxY && block.text.length > 3 &&
                        skipTexts.none { block.text.contains(it, ignoreCase = true) }
                    }
                    .sortedBy { it.bounds?.top ?: Int.MAX_VALUE }

                // Tap on the LEFT side of search results to avoid hitting the
                // "Saved places" button on the right side of the row.
                val leftTapX = ActionExecutor.getScreenSize(service).first * 0.25f

                // Strategy 1: Match by destination words (most reliable)
                for (word in destWords) {
                    val match = resultCandidates.find { it.text.contains(word, ignoreCase = true) }
                    if (match?.bounds != null) {
                        val b = match.bounds!!
                        Log.i(TAG, "Tapping result matching '$word': '${match.text}' at (left, ${b.centerY()})")
                        ActionExecutor.tapAtCoordinates(service, leftTapX, b.centerY().toFloat())
                        stepContext.collectedData["uber_dest_selected"] = "true"
                        return@AutomationStep StepResult.Success
                    }
                }

                // Strategy 2: Find the distance line (e.g. "6.9 mi 72 Bauddhaloka Mawatha")
                val distanceBlock = resultCandidates.find { block ->
                    block.text.contains(Regex("\\d+\\.?\\d*\\s*mi\\b"))
                }
                if (distanceBlock?.bounds != null) {
                    val b = distanceBlock.bounds!!
                    Log.i(TAG, "Tapping distance/address line via OCR: '${distanceBlock.text}' at (left, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, leftTapX, b.centerY().toFloat())
                    stepContext.collectedData["uber_dest_selected"] = "true"
                    return@AutomationStep StepResult.Success
                }

                // Strategy 3: Only tap first result if it looks like an actual search result
                // (contains a distance like "6.9 mi" or an address-like pattern).
                // Never blindly tap — that's how we get wrong destinations.
                val firstResult = resultCandidates.firstOrNull()
                if (firstResult?.bounds != null) {
                    val looksLikeResult = firstResult.text.contains(Regex("\\d+\\.?\\d*\\s*mi\\b")) ||
                            destWords.any { word -> firstResult.text.contains(word, ignoreCase = true) }
                    if (looksLikeResult) {
                        val b = firstResult.bounds!!
                        Log.w(TAG, "Tapping first result as fallback: '${firstResult.text}'")
                        ActionExecutor.tapAtCoordinates(service, leftTapX, b.centerY().toFloat())
                        stepContext.collectedData["uber_dest_selected"] = "true"
                        return@AutomationStep StepResult.Success
                    } else {
                        Log.w(TAG, "First result '${firstResult.text}' doesn't look like a search result — skipping blind tap")
                    }
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
                    // Try original text first, then try with OCR misread corrections
                    // only in the numeric portion (after the currency prefix)
                    val match = pricePattern.find(block.text)
                        ?: run {
                            // Only fix OCR misreads in the digits after currency prefix
                            val cleaned = block.text.replace(Regex("(?<=\\d)[lI](?=\\d)")) { m ->
                                "1"
                            }.replace(Regex("(?<=\\d)O(?=\\d)")) { "0" }
                            pricePattern.find(cleaned)
                        }
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
                    ActionExecutor.click(pickupField)
                    kotlinx.coroutines.delay(300)
                    pickupField.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                    ActionExecutor.clearText(pickupField)
                    ActionExecutor.setTextWithRetrigger(pickupField, pickupAddress)
                    kotlinx.coroutines.delay(1500)

                    // Select pickup result
                    val pickupOcr = ScreenReader.captureAndRead(service)
                    if (pickupOcr != null) {
                        val pickupWords = pickupAddress.split(" ").filter { it.length > 2 }
                        val skipTexts = setOf("Pick-up now", "For me", "Search in a different", "Plan your trip", "Where to")
                        val screenSize = ActionExecutor.getScreenSize(service)
                        val maxY = (screenSize.second * 0.65).toInt()
                        val editFields1 = NodeFinder.findAllNodesRecursive(service.getRootNode() ?: root) { it.isEditable }
                        val fieldsBot1 = editFields1.maxOfOrNull { f ->
                            val r = android.graphics.Rect(); f.getBoundsInScreen(r); r.bottom
                        } ?: (screenSize.second * 0.30).toInt()
                        val minY = fieldsBot1 + 20
                        val candidates = pickupOcr.blocks.filter { block ->
                            val top = block.bounds?.top ?: 0
                            top in minY..maxY && block.text.length > 3 &&
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
                ActionExecutor.click(destField)
                kotlinx.coroutines.delay(300)
                destField.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                ActionExecutor.clearText(destField)
                ActionExecutor.setTextWithRetrigger(destField, destination)
                kotlinx.coroutines.delay(1500)
            } else {
                Log.w(TAG, "No editable field found for destination")
                return@AutomationStep StepResult.Retry("No destination field found")
            }

            // Step 4: Select destination result
            val destOcr = ScreenReader.captureAndRead(service)
            if (destOcr != null) {
                val destWords = destination.split(" ").filter { it.length > 2 }
                val skipTexts = setOf("Pick-up now", "For me", "Search in a different", "Plan your trip", "Where to")
                val screenSize2 = ActionExecutor.getScreenSize(service)
                val maxY2 = (screenSize2.second * 0.65).toInt()
                val editFields2 = NodeFinder.findAllNodesRecursive(service.getRootNode() ?: root) { it.isEditable }
                val fieldsBot2 = editFields2.maxOfOrNull { f ->
                    val r = android.graphics.Rect(); f.getBoundsInScreen(r); r.bottom
                } ?: (screenSize2.second * 0.30).toInt()
                val minY2 = fieldsBot2 + 20
                val candidates = destOcr.blocks.filter { block ->
                    val top = block.bounds?.top ?: 0
                    top in minY2..maxY2 && block.text.length > 3 &&
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
            kotlinx.coroutines.delay(2000)
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

            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "For-me check OCR: ${ocr.fullText.take(300)}")

                // Check if we're already past this (confirm pickup or requesting)
                if (ocr.fullText.contains("Confirm", ignoreCase = true) ||
                    ocr.fullText.contains("Looking for", ignoreCase = true) ||
                    ocr.fullText.contains("Requesting", ignoreCase = true)) {
                    Log.i(TAG, "No 'For me' prompt, already on next screen")
                    return@AutomationStep StepResult.Skip("No 'For me' prompt")
                }

                // If we still see ride prices, the screen hasn't transitioned yet from
                // ride options. The "For me" on ride options is a label, NOT the booking prompt.
                // Don't tap it — retry until the screen changes.
                val hasPrice = ocr.fullText.contains("LKR", true) || ocr.fullText.contains("Rs", true)
                val hasChoose = ocr.fullText.contains("Choose", true)
                if (hasPrice || hasChoose) {
                    Log.i(TAG, "Still on ride options screen (hasPrice=$hasPrice, hasChoose=$hasChoose) — waiting for transition")
                    return@AutomationStep StepResult.Retry("Still on ride options screen")
                }

                // Now look for the actual "For me" prompt (appears after ride options transition)
                val forMeNode = NodeFinder.findByText(root, "For me")
                    ?: NodeFinder.findByText(root, "For Me")
                if (forMeNode != null) {
                    Log.i(TAG, "Found 'For me' prompt, tapping")
                    if (ActionExecutor.click(forMeNode)) {
                        return@AutomationStep StepResult.Success
                    }
                }

                val forMeBlocks = ScreenReader.findTextBlocks(ocr, "For me")
                if (forMeBlocks.isNotEmpty() && forMeBlocks.first().bounds != null) {
                    val b = forMeBlocks.first().bounds!!
                    Log.i(TAG, "Tapping 'For me' via OCR at (${b.centerX()}, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
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

    /**
     * Handle "Is this trip for someone else?" prompt that appears AFTER
     * confirming the pickup location when you are far from the pickup.
     * Tap "No, it's for me" if shown, otherwise skip.
     */
    private fun handleNotForSomeoneElsePrompt() = AutomationStep(
        name = "Handle 'someone else' prompt (if any)",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 6_000,
        delayAfterMs = 1000,
        maxRetries = 1,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            // Check if the ride is already being requested (no prompt appeared)
            val alreadyRequesting = NodeFinder.findByText(root, "Looking for")
                ?: NodeFinder.findByText(root, "Requesting")
                ?: NodeFinder.findByText(root, "Finding your ride")
            if (alreadyRequesting != null) {
                Log.i(TAG, "Ride already being requested, no 'someone else' prompt")
                return@AutomationStep StepResult.Skip("Already requesting")
            }

            // Try accessibility node first
            val noForMeNode = NodeFinder.findByText(root, "No, it's for me")
                ?: NodeFinder.findByText(root, "No, It's For Me")
                ?: NodeFinder.findByText(root, "it's for me")
            if (noForMeNode != null) {
                Log.i(TAG, "Found 'No, it's for me' prompt, tapping")
                if (ActionExecutor.click(noForMeNode)) {
                    return@AutomationStep StepResult.Success
                }
            }

            // OCR fallback
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "Someone-else prompt OCR: ${ocr.fullText.take(300)}")

                // Already requesting — no prompt
                if (ocr.fullText.contains("Looking for", ignoreCase = true) ||
                    ocr.fullText.contains("Requesting", ignoreCase = true)) {
                    Log.i(TAG, "Ride already being requested, skipping")
                    return@AutomationStep StepResult.Skip("Already requesting")
                }

                // Look for "it's for me" text via OCR
                val blocks = ScreenReader.findTextBlocks(ocr, "it's for me")
                if (blocks.isNotEmpty() && blocks.first().bounds != null) {
                    val b = blocks.first().bounds!!
                    Log.i(TAG, "Tapping 'No, it's for me' via OCR at (${b.centerX()}, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }
            }

            // Prompt not shown — skip
            Log.i(TAG, "No 'someone else' prompt detected, skipping")
            StepResult.Skip("No 'someone else' prompt")
        }
    )
}
