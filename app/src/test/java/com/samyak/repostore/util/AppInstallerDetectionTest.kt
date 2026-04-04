package com.samyak.repostore.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.samyak.repostore.RepoStoreApp
import com.samyak.repostore.data.db.InstalledAppMappingDao
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for AppInstaller's detection logic.
 * Priorities:
 * 1. Database Mapping (100% accurate)
 * 2. Token-Based Fuzzy Scoring (Heuristic)
 */
class AppInstallerDetectionTest {

    private lateinit var context: Context
    private lateinit var repoStoreApp: RepoStoreApp
    private lateinit var packageManager: PackageManager
    private lateinit var installedAppMappingDao: InstalledAppMappingDao
    private lateinit var appInstaller: AppInstaller

    private val installedPackages = mutableMapOf<String, String>() // packageName -> label

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        repoStoreApp = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)
        installedAppMappingDao = mockk(relaxed = true)

        every { context.applicationContext } returns repoStoreApp
        every { repoStoreApp.installedAppMappingDao } returns installedAppMappingDao
        every { context.packageManager } returns packageManager

        every { installedAppMappingDao.getPackageNameSync(any(), any()) } returns null

        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } answers {
            val pkg = firstArg<String>()
            if (pkg in installedPackages) {
                PackageInfo().apply { packageName = pkg }
            } else {
                throw PackageManager.NameNotFoundException(pkg)
            }
        }

        every { packageManager.getInstalledApplications(any<Int>()) } answers {
            installedPackages.map { (pkg, _) ->
                ApplicationInfo().apply { packageName = pkg }
            }
        }

        // Mock getApplicationLabel to return proper labels
        every { packageManager.getApplicationLabel(any()) } answers {
            val appInfo = firstArg<ApplicationInfo>()
            val label = installedPackages[appInfo.packageName] ?: ""
            label as CharSequence
        }

        val constructor = AppInstaller::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        appInstaller = constructor.newInstance(context)
    }

    private fun addInstalledApp(packageName: String, label: String = "") {
        installedPackages[packageName] = label
    }

    @After
    fun tearDown() {
        installedPackages.clear()
        unmockkAll()
    }

    // ==================== 1. Database Mapping Tests ====================

    @Test
    fun findPackage_usesDatabaseMapping_whenAvailable() {
        every { installedAppMappingDao.getPackageNameSync("FossifyOrg", "Calendar") } returns "org.fossify.calendar"
        addInstalledApp("org.fossify.calendar", "Fossify Calendar")

        val result = appInstaller.findPackage("Calendar", "FossifyOrg")
        assertEquals("org.fossify.calendar", result)
    }

    // ==================== 2. Fuzzy Scoring Tests ====================

    @Test
    fun findPackage_fuzzy_matches_FossifyCalculator() {
        // Repo: FossifyOrg/Calculator → org.fossify.calculator
        // Tokens: fossify(owner), org(owner), calculator(repo)
        // All match in package. Owner confirms. Score > threshold.
        addInstalledApp("org.fossify.calculator", "Fossify Calculator")
        val result = appInstaller.findPackage("Calculator", "FossifyOrg")
        assertEquals("org.fossify.calculator", result)
    }

    @Test
    fun findPackage_fuzzy_matches_SimpleGallery() {
        // Repo: SimpleMobileTools/Simple-Gallery → com.simplemobiletools.gallery
        addInstalledApp("com.simplemobiletools.gallery", "Simple Gallery")
        val result = appInstaller.findPackage("Simple-Gallery", "SimpleMobileTools")
        assertEquals("com.simplemobiletools.gallery", result)
    }

    @Test
    fun findPackage_fuzzy_rejects_GoogleCalculator() {
        // Repo: FossifyOrg/Calculator vs com.google.android.calculator
        // "calculator" is generic. Owner "FossifyOrg" has zero match.
        // System package penalty also applies. Must REJECT.
        addInstalledApp("com.google.android.calculator", "Calculator")
        val result = appInstaller.findPackage("Calculator", "FossifyOrg")
        assertNull("Should reject Google Calculator due to owner mismatch", result)
    }

    @Test
    fun findPackage_fuzzy_matches_RepoStore() {
        // Repo: Samya/RepoStore → com.samyak.repostore
        addInstalledApp("com.samyak.repostore", "RepoStore")
        val result = appInstaller.findPackage("RepoStore", "Samya")
        assertEquals("com.samyak.repostore", result)
    }

    // ==================== 3. Phone/Dialer Mismatch Tests ====================

    @Test
    fun findPackage_rejects_GoogleDialer_for_FossifyPhone() {
        // CRITICAL: FossifyOrg/Phone must NOT match com.google.android.dialer
        // "phone" is generic. Owner "FossifyOrg" has zero match in Google's package.
        // System package penalty applies. Must REJECT.
        addInstalledApp("com.google.android.dialer", "Phone")
        val result = appInstaller.findPackage("Phone", "FossifyOrg")
        assertNull("Should reject Google Dialer for FossifyOrg/Phone", result)
    }

    @Test
    fun findPackage_matches_FossifyPhone_correctly() {
        // FossifyOrg/Phone should match org.fossify.phone
        // "phone" is generic but owner "fossify" + "org" match perfectly.
        addInstalledApp("org.fossify.phone", "Fossify Phone")
        val result = appInstaller.findPackage("Phone", "FossifyOrg")
        assertEquals("org.fossify.phone", result)
    }

    @Test
    fun findPackage_prefers_FossifyPhone_over_GoogleDialer() {
        // When BOTH are installed, must pick org.fossify.phone, not com.google.android.dialer
        addInstalledApp("com.google.android.dialer", "Phone")
        addInstalledApp("org.fossify.phone", "Fossify Phone")
        val result = appInstaller.findPackage("Phone", "FossifyOrg")
        assertEquals("org.fossify.phone", result)
    }

    @Test
    fun findPackage_rejects_GoogleContacts_for_FossifyContacts() {
        addInstalledApp("com.google.android.contacts", "Contacts")
        val result = appInstaller.findPackage("Contacts", "FossifyOrg")
        assertNull("Should reject Google Contacts for FossifyOrg/Contacts", result)
    }

    @Test
    fun findPackage_rejects_GoogleCalendar_for_FossifyCalendar() {
        addInstalledApp("com.google.android.calendar", "Calendar")
        val result = appInstaller.findPackage("Calendar", "FossifyOrg")
        assertNull("Should reject Google Calendar for FossifyOrg/Calendar", result)
    }

    @Test
    fun findPackage_rejects_thirdParty_genericName_match() {
        // Even non-system third-party apps with generic names should be rejected
        // when the owner doesn't match at all
        addInstalledApp("com.truecaller.dialer", "Phone")
        val result = appInstaller.findPackage("Phone", "FossifyOrg")
        assertNull("Should reject Truecaller for FossifyOrg/Phone", result)
    }

    // ==================== Tokenizer Tests ====================

    @Test
    fun tokenize_splitsCamelCase() {
        val tokens = appInstaller.tokenize("RetroMusicPlayer")
        assertTrue(tokens.contains("retro"))
        assertTrue(tokens.contains("music"))
        assertTrue(tokens.contains("player"))
    }
}
