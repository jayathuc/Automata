package com.jayathu.automata.engine

/**
 * Maps technical step names and error reasons to user-friendly messages
 * with recovery suggestions.
 */
object ErrorMapper {

    data class FriendlyError(
        val title: String,
        val message: String,
        val suggestion: String
    )

    fun map(stepName: String, reason: String): FriendlyError {
        val friendlyStep = mapStepName(stepName)
        val (message, suggestion) = mapReason(stepName, reason)
        return FriendlyError(
            title = friendlyStep,
            message = message,
            suggestion = suggestion
        )
    }

    private fun mapStepName(stepName: String): String {
        // Exact matches first
        val mapped = stepNameMap.entries.firstOrNull { (key, _) ->
            stepName.equals(key, ignoreCase = true)
        }?.value
        if (mapped != null) return mapped

        // Partial/pattern matches
        return when {
            stepName.contains("Launch", true) -> "Opening app"
            stepName.contains("Resume", true) -> "Resuming app"
            stepName.contains("Enter pickup", true) -> "Entering pickup location"
            stepName.contains("Enter destination", true) || stepName.contains("DROP field", true) -> "Entering destination"
            stepName.contains("Select pickup", true) -> "Selecting pickup location"
            stepName.contains("Select search result", true) || stepName.contains("Select ride type", true) -> "Selecting option"
            stepName.contains("search bar", true) || stepName.contains("Where to", true) -> "Opening search"
            stepName.contains("Wait for ride options", true) || stepName.contains("Navigate to ride options", true) -> "Loading ride options"
            stepName.contains("Read", true) && stepName.contains("price", true) -> "Reading price"
            stepName.contains("Book Now", true) || stepName.contains("Confirm", true) || stepName.contains("Choose", true) -> "Booking ride"
            stepName.contains("Compare", true) -> "Comparing prices"
            stepName.contains("Force-close", true) -> "Preparing apps"
            stepName.contains("home screen", true) -> "Navigating"
            stepName.contains("Check winner", true) || stepName.contains("Set winner", true) -> "Deciding best ride"
            stepName.contains("rides availability", true) -> "Checking PickMe availability"
            stepName.contains("location services", true) -> "Checking location"
            stepName.contains("Verify", true) && stepName.contains("installed", true) -> "Checking app"
            stepName.contains("Handle", true) && stepName.contains("prompt", true) -> "Handling popup"
            else -> stepName
        }
    }

    private fun mapReason(stepName: String, reason: String): Pair<String, String> {
        // Pre-flight errors (stepName is empty)
        if (stepName.isBlank()) {
            return Pair(reason, "")
        }

        // Timeout errors
        if (reason.contains("Timed out", true)) {
            return mapTimeout(stepName)
        }

        // Max retries
        if (reason.contains("Max retries", true)) {
            return mapRetryExhausted(stepName)
        }

        // Exceptions
        if (reason.startsWith("Exception:", true)) {
            val msg = reason.removePrefix("Exception: ").removePrefix("exception: ")
            return Pair(
                "Something unexpected happened: $msg",
                "Try again. If this keeps happening, restart the app."
            )
        }

        // Accessibility service
        if (reason.contains("Accessibility service", true)) {
            return Pair(
                "Accessibility service is not running.",
                "Go to Settings > Accessibility > Automata and enable it."
            )
        }

        // App not found / not installed
        if (reason.contains("not installed", true) || reason.contains("Could not launch", true) || reason.contains("Could not resume", true)) {
            val app = when {
                stepName.contains("PickMe", true) -> "PickMe"
                stepName.contains("Uber", true) -> "Uber"
                else -> "The app"
            }
            return Pair(
                "$app could not be opened.",
                "Make sure $app is installed and up to date."
            )
        }

        // App not set up / registration incomplete
        if (reason.contains("not set up", true) || reason.contains("complete the registration", true)) {
            val app = when {
                stepName.contains("PickMe", true) -> "PickMe"
                stepName.contains("Uber", true) -> "Uber"
                else -> "The app"
            }
            return Pair(
                "$app needs to be set up first.",
                "Open $app manually, complete the sign-up process, and try again."
            )
        }

        // Ride type not available (intercity / limited options)
        if (reason.startsWith("RIDE_TYPE_UNAVAILABLE:")) {
            val parts = reason.split(":")
            val requested = parts.getOrElse(1) { "selected" }
            val available = parts.getOrElse(2) { "" }

            val friendlyRequested = when (requested.lowercase()) {
                "moto" -> "Bike/Moto"
                "tuk" -> "Tuk"
                "zip" -> "Car/Zip"
                else -> requested
            }

            return if (available.isNotBlank()) {
                Pair(
                    "$friendlyRequested is not available for this route on Uber.",
                    "Available options: $available. Try selecting a different ride type."
                )
            } else {
                Pair(
                    "$friendlyRequested is not available for this route on Uber.",
                    "This may be an intercity route with limited options. Try a different ride type."
                )
            }
        }

        // Price reading failures
        if (reason.contains("Could not determine prices", true) || reason.contains("No price found", true)) {
            return Pair(
                "Could not read the price from the screen.",
                "The app layout may have changed. Try again."
            )
        }

        // Generic / pass-through
        return Pair(reason, "Try running the task again.")
    }

    private fun mapTimeout(stepName: String): Pair<String, String> {
        return when {
            stepName.contains("home screen", true) || stepName.contains("Launch", true) ->
                Pair(
                    "The app took too long to open.",
                    "Make sure the app is installed and your phone is not running slow."
                )
            stepName.contains("search bar", true) || stepName.contains("Where to", true) ->
                Pair(
                    "Could not find the search bar.",
                    "The app may have updated its layout. Try again."
                )
            stepName.contains("pickup", true) || stepName.contains("destination", true) || stepName.contains("DROP", true) ->
                Pair(
                    "Could not enter the address.",
                    "Make sure you have an internet connection and try again."
                )
            stepName.contains("search result", true) || stepName.contains("Select", true) ->
                Pair(
                    "No matching location found.",
                    "Check the address spelling or try a more specific address."
                )
            stepName.contains("ride options", true) ->
                Pair(
                    "Ride options did not load.",
                    "The app might be slow. Check your internet and try again."
                )
            stepName.contains("price", true) ->
                Pair(
                    "Could not read the price.",
                    "Ride options may not have fully loaded. Try again."
                )
            stepName.contains("Book", true) || stepName.contains("Confirm", true) || stepName.contains("Choose", true) ->
                Pair(
                    "Could not complete the booking.",
                    "The app screen may have changed. Check the app manually."
                )
            stepName.contains("location", true) ->
                Pair(
                    "Location services are not enabled.",
                    "Turn on Location in your phone settings and try again."
                )
            else ->
                Pair(
                    "The screen did not respond in time.",
                    "Try again. If this keeps happening, restart both apps."
                )
        }
    }

    private fun mapRetryExhausted(stepName: String): Pair<String, String> {
        return when {
            stepName.contains("destination", true) || stepName.contains("DROP", true) ->
                Pair(
                    "Could not enter the destination after multiple attempts.",
                    "Check the destination address and make sure you have internet."
                )
            stepName.contains("intercity", true) || stepName.contains("departure time", true) ->
                Pair(
                    "Could not complete the intercity booking flow.",
                    "The Uber app may have changed its layout. Try booking manually."
                )
            stepName.contains("Confirm", true) && stepName.contains("pickup", true) ->
                Pair(
                    "Could not confirm the pickup location.",
                    "The app screen may have changed. Check the app manually."
                )
            stepName.contains("pickup", true) ->
                Pair(
                    "Could not set the pickup location.",
                    "Check the pickup address or leave it empty to use current location."
                )
            stepName.contains("search result", true) || stepName.contains("Select", true) ->
                Pair(
                    "Could not find a matching location in search results.",
                    "Try using a more specific or well-known address."
                )
            stepName.contains("price", true) ->
                Pair(
                    "Failed to read the price after several tries.",
                    "The app may be loading slowly. Try again."
                )
            stepName.contains("rides availability", true) ->
                Pair(
                    "PickMe rides are currently not available.",
                    "This is a temporary issue with PickMe. Try again later."
                )
            stepName.contains("ride options", true) ->
                Pair(
                    "Ride options did not appear.",
                    "The app might be having issues. Open it manually to check."
                )
            else ->
                Pair(
                    "This step failed after multiple attempts.",
                    "Try again. If the issue persists, check the app manually."
                )
        }
    }

    private val stepNameMap = mapOf(
        "Force-close apps" to "Preparing apps",
        "Return to home screen" to "Navigating",
        "Compare prices" to "Comparing prices",
        "Wait for PickMe home screen" to "Opening PickMe",
        "Check location services" to "Checking location services",
        "Tap search bar on home screen" to "Opening search",
        "Wait for ride options" to "Loading ride options",
        "Navigate to ride options" to "Loading ride options",
        "Tap Book Now" to "Booking ride",
        "Tap Confirm Pickup" to "Confirming pickup",
        "Tap 'Where to?' field" to "Opening search",
    )
}
