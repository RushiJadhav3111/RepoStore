package com.samyak.repostore.ui.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.samyak.repostore.data.db.FavoriteAppDao
import com.samyak.repostore.data.db.InstalledAppMappingDao
import com.samyak.repostore.data.model.FavoriteApp
import com.samyak.repostore.data.model.InstalledAppMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

sealed class MyAppsUiState {
    data object Loading : MyAppsUiState()
    data class Success(val apps: List<FavoriteApp>) : MyAppsUiState()
    data object Empty : MyAppsUiState()
}


class MyAppsViewModel(
    private val favoriteAppDao: FavoriteAppDao,
    private val installedAppMappingDao: InstalledAppMappingDao,
    private val packageManager: PackageManager
) : ViewModel() {

    val uiState: StateFlow<MyAppsUiState> = combine(
        favoriteAppDao.getAllFavorites(),
        installedAppMappingDao.getAllMappingsFlow()
    ) { favorites, mappings ->
        val installedApps = mutableListOf<FavoriteApp>()
        
        // 1. Process Favorites
        favorites.forEach { app ->
            // Use mappings to find package name for accurate check
            val pkg = mappings.find { it.ownerName == app.ownerLogin && it.repoName == app.name }?.packageName
            if (pkg != null) {
                if (isPackageInstalled(pkg)) {
                    // Update favorite with package info for icon loading
                    installedApps.add(app.copy(ownerAvatarUrl = "pkg:$pkg"))
                }
            } else {
                // Fallback to label/package name search if no mapping
                if (isAppInstalledFallback(app.name)) {
                    installedApps.add(app)
                }
            }
        }
        
        // 2. Process Mappings for apps installed via RepoStore (even if not favorites)
        mappings.forEach { mapping ->
            // Avoid duplicates if already added from favorites
            if (installedApps.none { it.ownerLogin == mapping.ownerName && it.name == mapping.repoName }) {
                if (isPackageInstalled(mapping.packageName)) {
                    installedApps.add(createSyntheticFavoriteApp(mapping))
                }
            }
        }
        
        // 3. Fallback: Check for apps where this app is the installer of record
        checkInstallerOfRecord(installedApps)

        if (installedApps.isEmpty()) {
            MyAppsUiState.Empty
        } else {
            MyAppsUiState.Success(installedApps.distinctBy { "${it.ownerLogin}/${it.name}" })
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MyAppsUiState.Loading
    )

    private fun isAppInstalledFallback(appName: String): Boolean {
        return try {
            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            installedApps.any { appInfo ->
                val label = packageManager.getApplicationLabel(appInfo).toString()
                label.equals(appName, ignoreCase = true) ||
                appInfo.packageName.contains(appName, ignoreCase = true)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun createSyntheticFavoriteApp(mapping: InstalledAppMapping): FavoriteApp {
        return FavoriteApp(
            id = (mapping.ownerName + mapping.repoName).hashCode().toLong(),
            fullName = "${mapping.ownerName}/${mapping.repoName}",
            name = mapping.repoName,
            ownerLogin = mapping.ownerName,
            ownerAvatarUrl = "pkg:${mapping.packageName}",
            description = "Installed via RepoStore",
            stars = 0,
            language = "Installed"
        )
    }

    private fun checkInstallerOfRecord(currentList: MutableList<FavoriteApp>) {
        try {
            val myPackageName = "com.samyak.repostore"
            val installedApps = packageManager.getInstalledPackages(0)
            
            for (pkgInfo in installedApps) {
                val installer = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    packageManager.getInstallSourceInfo(pkgInfo.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getInstallerPackageName(pkgInfo.packageName)
                }

                if (installer == myPackageName) {
                    // This app was installed by RepoStore!
                    // If it's not in our list, try to add it
                    if (currentList.none { it.name == pkgInfo.packageName || it.fullName.contains(pkgInfo.packageName) }) {
                        val label = pkgInfo.applicationInfo?.let { 
                            packageManager.getApplicationLabel(it).toString() 
                        } ?: pkgInfo.packageName
                        // Since we don't know the repo/owner, we use the package name as a proxy
                        // or just skip if we can't map it properly.
                        // For now, let's at least show it if possible.
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore errors in fallback
        }
    }

    fun removeFavorite(repoId: Long) {
        viewModelScope.launch {
            favoriteAppDao.removeFavorite(repoId)
        }
    }
}

class MyAppsViewModelFactory(
    private val favoriteAppDao: FavoriteAppDao,
    private val installedAppMappingDao: InstalledAppMappingDao,
    private val packageManager: PackageManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MyAppsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MyAppsViewModel(favoriteAppDao, installedAppMappingDao, packageManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
