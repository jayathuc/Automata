# Automata ProGuard Rules

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# OSMDroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Open Location Code
-keep class com.google.openlocationcode.** { *; }

# Accessibility service — must not be renamed
-keep class com.jayathu.automata.service.AutomataAccessibilityService { *; }

# Notification listener — must not be renamed
-keep class com.jayathu.automata.notification.RideNotificationListener { *; }

# Compose — handled by default rules, but keep navigation arguments
-keep class * extends androidx.navigation.NavArgs { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
