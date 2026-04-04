package com.samyak.repostore.util

import android.util.Log
import com.samyak.gitcore.util.PackageIdResolver
import com.samyak.repostore.data.model.GitHubRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Fetches the real Android applicationId (package name) directly from the 
 * GitHub repository source code (build.gradle, AndroidManifest.xml, etc.).
 *
 * This provides 100% accurate package detection WITHOUT requiring the user
 * to install the app first. It works by:
 * 1. Fetching build.gradle → parsing `applicationId "com.example.app"`
 * 2. Fetching AndroidManifest.xml → parsing `package="com.example.app"`
 *
 * Results are cached in the InstalledAppMapping database so subsequent
 * lookups are instant.
 */
object PackageIdFetcher {

    private const val TAG = "PackageIdFetcher"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ==================== Gradle Patterns ====================

    // applicationId "com.example.app"  or  applicationId = "com.example.app"
    private val APPLICATION_ID_PATTERN = Pattern.compile(
        """applicationId\s*[=]?\s*["']([a-zA-Z][a-zA-Z0-9_.]+)["']""",
        Pattern.MULTILINE
    )

    // namespace "com.example.app"  or  namespace = "com.example.app"
    private val NAMESPACE_PATTERN = Pattern.compile(
        """namespace\s*[=]?\s*["']([a-zA-Z][a-zA-Z0-9_.]+)["']""",
        Pattern.MULTILINE
    )

    // Variable-based: val appId = "com.example.app" (common in .kts)
    private val VAL_APP_ID_PATTERN = Pattern.compile(
        """(?:val|var)\s+(?:appId|applicationId|packageName|APP_ID)\s*=\s*["']([a-zA-Z][a-zA-Z0-9_.]+)["']""",
        Pattern.MULTILINE or Pattern.CASE_INSENSITIVE
    )

    // ==================== Manifest Patterns ====================

    // <manifest package="com.example.app"
    private val MANIFEST_PACKAGE_PATTERN = Pattern.compile(
        """<manifest[^>]*\bpackage\s*=\s*["']([a-zA-Z][a-zA-Z0-9_.]+)["']""",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )

    // ==================== Build Configuration Patterns ====================

    // defaultConfig { ... applicationId ... } — helps avoid catching test/debug applicationIds
    private val DEFAULT_CONFIG_BLOCK_PATTERN = Pattern.compile(
        """defaultConfig\s*\{([^}]*(?:\{[^}]*\}[^}]*)*)\}""",
        Pattern.DOTALL
    )

    // productFlavors — to catch flavor-specific applicationIds
    private val FLAVOR_APP_ID_PATTERN = Pattern.compile(
        """applicationId\s*[=]?\s*["']([a-zA-Z][a-zA-Z0-9_.]+)["']""",
        Pattern.MULTILINE
    )

    /**
     * Fetch the real applicationId from a GitHub repo's source code.
     * Returns the package name string, or null if not found.
     */
    suspend fun fetchPackageId(repo: GitHubRepo): String? {
        return fetchPackageId(repo.owner.login, repo.name, repo.defaultBranch, repo.language)
    }

    /**
     * Fetch the real applicationId from a GitHub repo's source code.
     */
    suspend fun fetchPackageId(
        owner: String,
        name: String,
        defaultBranch: String?,
        language: String?
    ): String? = withContext(Dispatchers.IO) {
        val urls = PackageIdResolver.resolve(owner, name, defaultBranch, language)
        Log.d(TAG, "Resolving packageId for $owner/$name — checking ${urls.size} URLs")

        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "text/plain")
                    .build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val content = response.body?.string() ?: continue
                    val packageId = parsePackageId(url, content)

                    if (packageId != null && isValidPackageId(packageId)) {
                        Log.d(TAG, "✓ Found packageId '$packageId' from $url")
                        return@withContext packageId
                    }
                }
            } catch (e: Exception) {
                // Ignore network error, try next URL
                Log.v(TAG, "Failed to fetch $url: ${e.message}")
            }
        }

        Log.d(TAG, "✗ Could not find packageId for $owner/$name")
        return@withContext null
    }

    /**
     * Parse the applicationId from file content based on file type.
     */
    private fun parsePackageId(url: String, content: String): String? {
        val lowerUrl = url.lowercase()

        return when {
            // Gradle files (primary source — most reliable)
            lowerUrl.endsWith(".gradle") || lowerUrl.endsWith(".gradle.kts") -> {
                parseFromGradle(content)
            }
            // AndroidManifest.xml (fallback)
            lowerUrl.endsWith("androidmanifest.xml") -> {
                parseFromManifest(content)
            }
            else -> null
        }
    }

    /**
     * Extract applicationId from build.gradle / build.gradle.kts content.
     *
     * Strategy:
     * 1. Try to find applicationId inside defaultConfig block (most accurate)
     * 2. Fall back to first applicationId found anywhere in file
     * 3. Fall back to namespace declaration
     * 4. Fall back to variable-based declarations
     */
    private fun parseFromGradle(content: String): String? {
        // Strategy 1: Look inside defaultConfig block first
        val defaultConfigMatcher = DEFAULT_CONFIG_BLOCK_PATTERN.matcher(content)
        if (defaultConfigMatcher.find()) {
            val configBlock = defaultConfigMatcher.group(1) ?: ""
            val appIdMatcher = APPLICATION_ID_PATTERN.matcher(configBlock)
            if (appIdMatcher.find()) {
                val id = appIdMatcher.group(1)
                if (id != null && isValidPackageId(id)) {
                    return id
                }
            }
        }

        // Strategy 2: Find applicationId anywhere (handles non-standard layouts)
        val globalAppIdMatcher = APPLICATION_ID_PATTERN.matcher(content)
        if (globalAppIdMatcher.find()) {
            val id = globalAppIdMatcher.group(1)
            if (id != null && isValidPackageId(id)) {
                return id
            }
        }

        // Strategy 3: namespace (used in newer AGP versions as the package)
        val namespaceMatcher = NAMESPACE_PATTERN.matcher(content)
        if (namespaceMatcher.find()) {
            val id = namespaceMatcher.group(1)
            if (id != null && isValidPackageId(id)) {
                return id
            }
        }

        // Strategy 4: Variable-based (val appId = "...")
        val valMatcher = VAL_APP_ID_PATTERN.matcher(content)
        if (valMatcher.find()) {
            val id = valMatcher.group(1)
            if (id != null && isValidPackageId(id)) {
                return id
            }
        }

        return null
    }

    /**
     * Extract package name from AndroidManifest.xml content.
     */
    private fun parseFromManifest(content: String): String? {
        val matcher = MANIFEST_PACKAGE_PATTERN.matcher(content)
        if (matcher.find()) {
            val pkg = matcher.group(1)
            if (pkg != null && isValidPackageId(pkg)) {
                return pkg
            }
        }
        return null
    }

    /**
     * Validate that the extracted string looks like a real Android package name.
     * Must have at least 2 segments (com.example) and only valid characters.
     */
    private fun isValidPackageId(id: String): Boolean {
        // Must contain at least one dot (e.g., "com.app")
        if (!id.contains('.')) return false

        // Must have at least 2 segments
        val segments = id.split('.')
        if (segments.size < 2) return false

        // Each segment must be non-empty and start with a letter
        for (segment in segments) {
            if (segment.isEmpty()) return false
            if (!segment[0].isLetter()) return false
        }

        // Reject common false positives
        val lower = id.lowercase()
        if (lower.startsWith("com.android.tools")) return false
        if (lower.startsWith("org.gradle")) return false
        if (lower.startsWith("com.github.")) return false  // dependency reference
        if (lower.startsWith("org.jetbrains.")) return false

        // Must be a reasonable length
        if (id.length < 5 || id.length > 100) return false

        return true
    }
}
