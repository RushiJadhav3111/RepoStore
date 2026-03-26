package com.samyak.repostore.ui.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.samyak.repostore.R
import com.samyak.repostore.RepoStoreApp
import com.samyak.repostore.data.model.GitHubRelease
import com.samyak.repostore.data.model.GitHubRepo
import com.samyak.repostore.databinding.FragmentDetailBinding
import com.samyak.repostore.databinding.LayoutReleaseVariantPickerBinding
import com.samyak.repostore.ui.activity.DeveloperActivity
import com.samyak.repostore.ui.activity.ScreenshotViewerActivity
import com.samyak.repostore.ui.adapter.ReleaseVariantAdapter
import com.samyak.repostore.ui.adapter.ScreenshotAdapter
import com.samyak.repostore.ui.viewmodel.DetailUiState
import com.samyak.repostore.ui.viewmodel.DetailViewModel
import com.samyak.repostore.ui.viewmodel.DetailViewModelFactory
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.samyak.repostore.data.model.ReleaseAsset
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import com.samyak.repostore.util.RateLimitDialog
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DetailFragment : Fragment() {

    private var _binding: FragmentDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var markwon: Markwon

    private val viewModel: DetailViewModel by viewModels {
        DetailViewModelFactory((requireActivity().application as RepoStoreApp).repository)
    }

    private lateinit var screenshotAdapter: ScreenshotAdapter
    private var owner: String = ""
    private var repoName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            owner = it.getString(ARG_OWNER, "")
            repoName = it.getString(ARG_REPO, "")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMarkwon()
        setupToolbar()
        setupScreenshotsRecyclerView()
        observeViewModel()
        viewModel.loadAppDetails(owner, repoName)
    }

    private fun setupMarkwon() {
        markwon = Markwon.builder(requireContext())
            .usePlugin(GlideImagesPlugin.create(requireContext()))
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(TablePlugin.create(requireContext()))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(HtmlPlugin.create())
            .build()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            if (parentFragmentManager.backStackEntryCount > 0) {
                parentFragmentManager.popBackStack()
            } else {
                activity?.finish()
            }
        }
    }

    private var currentScreenshots: List<String> = emptyList()

    private fun setupScreenshotsRecyclerView() {
        screenshotAdapter = ScreenshotAdapter { _, position ->
            openScreenshotViewer(position)
        }

        binding.rvScreenshots.apply {
            adapter = screenshotAdapter
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        }
    }

    private fun openScreenshotViewer(position: Int) {
        if (currentScreenshots.isNotEmpty()) {
            val intent = ScreenshotViewerActivity.newIntent(
                requireContext(),
                ArrayList(currentScreenshots),
                position
            )
            startActivity(intent)
            requireActivity().overridePendingTransition(R.anim.slide_up, R.anim.fade_out)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        handleUiState(state)
                    }
                }

                launch {
                    viewModel.readme.collect { readme ->
                        readme?.let {
                            // Render markdown
                            markwon.setMarkdown(binding.tvReadme, it)
                            binding.cardReadme.visibility = View.VISIBLE
                        }
                    }
                }

                launch {
                    viewModel.screenshots.collect { screenshots ->
                        if (screenshots.isNotEmpty()) {
                            currentScreenshots = screenshots
                            binding.layoutScreenshots.visibility = View.VISIBLE
                            screenshotAdapter.submitList(screenshots)
                        } else {
                            currentScreenshots = emptyList()
                            binding.layoutScreenshots.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun handleUiState(state: DetailUiState) {
        when (state) {
            is DetailUiState.Loading -> {
                binding.progressBar.visibility = View.VISIBLE
                binding.scrollContent.visibility = View.GONE
                binding.tvError.visibility = View.GONE
            }
            is DetailUiState.Success -> {
                binding.progressBar.visibility = View.GONE
                binding.scrollContent.visibility = View.VISIBLE
                binding.tvError.visibility = View.GONE
                bindRepoData(state.repo, state.release)
            }
            is DetailUiState.Error -> {
                binding.progressBar.visibility = View.GONE
                binding.scrollContent.visibility = View.GONE
                binding.tvError.visibility = View.VISIBLE
                binding.tvError.text = "${state.message}\n\n${getString(R.string.tap_to_retry)}"
                binding.tvError.setOnClickListener {
                    viewModel.retry(owner, repoName)
                }
                
                // Show rate limit dialog if applicable
                RateLimitDialog.showIfNeeded(requireContext(), state.message)
            }
        }
    }

    private fun bindRepoData(repo: GitHubRepo, release: GitHubRelease?) {
        binding.apply {
            tvAppName.text = repo.name
            tvDeveloper.text = repo.owner.login
            tvDescription.text = repo.description ?: getString(R.string.no_description)

            // Developer click - open developer page
            tvDeveloper.setOnClickListener {
                val intent = DeveloperActivity.newIntent(
                    requireContext(),
                    repo.owner.login,
                    repo.owner.avatarUrl
                )
                startActivity(intent)
            }

            // Stats
            tvStars.text = formatNumber(repo.stars)
            tvForks.text = formatNumber(repo.forks)
            tvLanguage.text = repo.language ?: "Code"
            tvUpdated.text = formatDate(repo.updatedAt)

            // Load avatar
            Glide.with(this@DetailFragment)
                .load(repo.owner.avatarUrl)
                .placeholder(R.drawable.ic_app_placeholder)
                .into(ivAppIcon)

            // Topics as chips
            if (!repo.topics.isNullOrEmpty()) {
                chipGroupTopics.removeAllViews()
                repo.topics.take(6).forEach { topic ->
                    val chip = Chip(requireContext()).apply {
                        text = topic
                        isClickable = false
                        setChipBackgroundColorResource(R.color.chip_background)
                    }
                    chipGroupTopics.addView(chip)
                }
                chipGroupTopics.visibility = View.VISIBLE
            } else {
                chipGroupTopics.visibility = View.GONE
            }

            // Archived badge
            chipArchived.visibility = if (repo.archived) View.VISIBLE else View.GONE

            // Release info
            if (release != null) {
                cardRelease.visibility = View.VISIBLE
                tvVersion.text = release.tagName
                tvReleaseName.text = release.name ?: release.tagName
                
                // Render release notes as markdown
                val releaseNotes = release.body ?: getString(R.string.no_release_notes)
                markwon.setMarkdown(tvReleaseNotes, releaseNotes)
                
                tvReleaseDate.text = formatDate(release.publishedAt)

                // Find all APK/AAB assets
                val installableAssets = release.assets.filter {
                    it.name.endsWith(".apk", ignoreCase = true) || 
                    it.name.endsWith(".aab", ignoreCase = true)
                }

                if (installableAssets.isNotEmpty()) {
                    btnDownload.text = getString(R.string.install)
                    btnDownload.setOnClickListener {
                        if (installableAssets.size > 1) {
                            showReleaseVariantPicker(installableAssets)
                        } else {
                            openUrl(installableAssets[0].downloadUrl)
                        }
                    }
                } else {
                    btnDownload.text = getString(R.string.view_release)
                    btnDownload.setOnClickListener {
                        openUrl(release.htmlUrl)
                    }
                }
            } else {
                cardRelease.visibility = View.GONE
                btnDownload.text = getString(R.string.view_on_github)
                btnDownload.setOnClickListener {
                    openUrl(repo.htmlUrl)
                }
            }

            // GitHub button
            btnGithub.setOnClickListener {
                openUrl(repo.htmlUrl)
            }
        }
    }

    private fun formatNumber(number: Int): String {
        return when {
            number >= 1_000_000 -> String.format(Locale.US, "%.1fM", number / 1_000_000.0)
            number >= 1_000 -> String.format(Locale.US, "%.1fK", number / 1_000.0)
            else -> NumberFormat.getInstance(Locale.US).format(number)
        }
    }

    private fun formatDate(isoDate: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            inputFormat.timeZone = TimeZone.getTimeZone("UTC")
            val date = inputFormat.parse(isoDate)
            val outputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
            date?.let { outputFormat.format(it) } ?: isoDate
        } catch (e: Exception) {
            isoDate
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    private fun showReleaseVariantPicker(assets: List<ReleaseAsset>) {
        val bottomSheet = BottomSheetDialog(requireContext())
        val pickerBinding = LayoutReleaseVariantPickerBinding.inflate(layoutInflater)
        bottomSheet.setContentView(pickerBinding.root)

        val adapter = ReleaseVariantAdapter { asset ->
            openUrl(asset.downloadUrl)
            bottomSheet.dismiss()
        }

        pickerBinding.rvVariants.apply {
            this.adapter = adapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        adapter.submitList(assets)
        bottomSheet.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_OWNER = "owner"
        private const val ARG_REPO = "repo"

        fun newInstance(owner: String, repo: String) = DetailFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_OWNER, owner)
                putString(ARG_REPO, repo)
            }
        }
    }
}
