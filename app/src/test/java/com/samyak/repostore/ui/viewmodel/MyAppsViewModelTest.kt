package com.samyak.repostore.ui.viewmodel

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.samyak.repostore.data.db.FavoriteAppDao
import com.samyak.repostore.data.db.InstalledAppMappingDao
import com.samyak.repostore.data.model.FavoriteApp
import com.samyak.repostore.data.model.InstalledAppMapping
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MyAppsViewModelTest {

    private lateinit var favoriteAppDao: FavoriteAppDao
    private lateinit var installedAppMappingDao: InstalledAppMappingDao
    private lateinit var packageManager: PackageManager
    private lateinit var viewModel: MyAppsViewModel
    
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        favoriteAppDao = mockk(relaxed = true)
        installedAppMappingDao = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)

        // Default behavior: no favorites, no mappings
        every { favoriteAppDao.getAllFavorites() } returns flowOf(emptyList())
        every { installedAppMappingDao.getAllMappingsFlow() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `uiState shows Empty when no favorites and no mappings`() = runTest {
        viewModel = MyAppsViewModel(favoriteAppDao, installedAppMappingDao, packageManager)
        
        val state = viewModel.uiState.first()
        assertTrue(state is MyAppsUiState.Empty)
    }

    @Test
    fun `uiState shows Success when a favorite is installed`() = runTest {
        val favorite = FavoriteApp(1, "owner/repo", "repo", "owner", "", "desc", 10, "Kotlin")
        val mapping = InstalledAppMapping("owner", "repo", "com.example.repo")

        every { favoriteAppDao.getAllFavorites() } returns flowOf(listOf(favorite))
        every { installedAppMappingDao.getAllMappingsFlow() } returns flowOf(listOf(mapping))
        
        // Mock package manager to say it's installed
        every { packageManager.getPackageInfo("com.example.repo", 0) } returns PackageInfo()
        
        viewModel = MyAppsViewModel(favoriteAppDao, installedAppMappingDao, packageManager)
        
        val state = viewModel.uiState.first()
        assertTrue(state is MyAppsUiState.Success)
        assertEquals(1, (state as MyAppsUiState.Success).apps.size)
        assertEquals("repo", state.apps[0].name)
    }

    @Test
    fun `uiState shows Success when a mapping is installed but not a favorite`() = runTest {
        val mapping = InstalledAppMapping("owner", "repo", "com.example.repo")
        every { installedAppMappingDao.getAllMappingsFlow() } returns flowOf(listOf(mapping))
        every { packageManager.getPackageInfo("com.example.repo", 0) } returns PackageInfo()
        
        viewModel = MyAppsViewModel(favoriteAppDao, installedAppMappingDao, packageManager)
        
        val state = viewModel.uiState.first()
        assertTrue(state is MyAppsUiState.Success)
        assertEquals(1, (state as MyAppsUiState.Success).apps.size)
        assertEquals("repo", state.apps[0].name)
        assertEquals("Installed via RepoStore", state.apps[0].description)
    }

    @Test
    fun `uiState avoids duplicates when app is both favorite and mapping`() = runTest {
        val favorite = FavoriteApp(1, "owner/repo", "repo", "owner", "", "desc", 10, "Kotlin")
        val mapping = InstalledAppMapping("owner", "repo", "com.example.repo")
        
        every { favoriteAppDao.getAllFavorites() } returns flowOf(listOf(favorite))
        coEvery { installedAppMappingDao.getAllMappings() } returns listOf(mapping)
        
        // Mock as installed via mapping
        coEvery { installedAppMappingDao.getPackageName("owner", "repo") } returns "com.example.repo"
        every { packageManager.getPackageInfo("com.example.repo", 0) } returns PackageInfo()
        
        viewModel = MyAppsViewModel(favoriteAppDao, installedAppMappingDao, packageManager)
        
        val state = viewModel.uiState.first()
        assertTrue(state is MyAppsUiState.Success)
        assertEquals(1, (state as MyAppsUiState.Success).apps.size)
        assertEquals("desc", state.apps[0].description) // Preferred from favorite
    }
}
