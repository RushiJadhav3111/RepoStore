package com.samyak.gitcore.util

/**
 * Generates prioritized URLs to files that may contain the applicationId / package name
 * for a GitHub repository's Android app (build.gradle, build.gradle.kts, AndroidManifest.xml).
 *
 * Similar to AppNameResolver but specifically targets the package/applicationId.
 */
object PackageIdResolver {

    private const val RAW_BASE_URL = "https://raw.githubusercontent.com"

    /**
     * Returns a prioritized list of raw URLs where the applicationId might be declared.
     *
     * Priority:
     * 1. build.gradle / build.gradle.kts (contains `applicationId "com.example.app"`)
     * 2. AndroidManifest.xml (contains `package="com.example.app"`)
     * 3. Cross-platform config files (pubspec.yaml, package.json for the android dir)
     */
    fun resolve(owner: String, name: String, defaultBranch: String?, language: String?): List<String> {
        val branch = defaultBranch ?: "main"
        val baseUrl = "$RAW_BASE_URL/$owner/$name/$branch"
        val lang = language?.lowercase() ?: ""

        // Standard Android project layouts
        val gradlePaths = listOf(
            "app/build.gradle.kts",
            "app/build.gradle",
            "build.gradle.kts",
            "build.gradle"
        )

        val manifestPaths = listOf(
            "app/src/main/AndroidManifest.xml",
            "src/main/AndroidManifest.xml"
        )

        // Cross-platform (Flutter, React Native, etc.)
        val crossPlatformPaths = listOf(
            "android/app/build.gradle.kts",
            "android/app/build.gradle",
            "android/app/src/main/AndroidManifest.xml"
        )

        // Dynamic module name patterns
        val dynamicPaths = listOf(
            "$name/build.gradle.kts",
            "$name/build.gradle",
            "$name-android/build.gradle.kts",
            "$name-android/build.gradle",
            "$name/src/main/AndroidManifest.xml",
            "$name-android/src/main/AndroidManifest.xml"
        )

        // KMM (Kotlin Multiplatform) layouts
        val kmmPaths = listOf(
            "androidApp/build.gradle.kts",
            "androidApp/build.gradle",
            "androidApp/src/main/AndroidManifest.xml"
        )

        // Flutter pubspec (contains the package name in the android block)
        val flutterPaths = listOf(
            "android/app/build.gradle",
            "android/app/build.gradle.kts"
        )

        // Prioritize based on detected language
        val prioritizedPaths = when (lang) {
            "dart" -> flutterPaths + crossPlatformPaths + gradlePaths + manifestPaths + dynamicPaths + kmmPaths
            "javascript", "typescript" -> crossPlatformPaths + gradlePaths + manifestPaths + dynamicPaths + kmmPaths
            "kotlin", "java" -> gradlePaths + manifestPaths + dynamicPaths + kmmPaths + crossPlatformPaths
            else -> gradlePaths + manifestPaths + dynamicPaths + kmmPaths + crossPlatformPaths
        }

        return prioritizedPaths.distinct().map { "$baseUrl/$it" }
    }
}
