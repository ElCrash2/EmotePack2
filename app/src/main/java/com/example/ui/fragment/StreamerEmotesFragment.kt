package com.example.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.AppNavigator
import com.example.R
import com.example.databinding.FragmentEmoteGridBinding
import com.example.ui.adapter.EmoteGridAdapter
import com.example.ui.state.ToggleSelectionResult
import com.example.ui.viewmodel.EmoteGridUiState
import com.example.ui.viewmodel.EmoteGridViewModel
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class StreamerEmotesFragment : Fragment() {

    private var _binding: FragmentEmoteGridBinding? = null
    private val binding get() = _binding!!
    private val viewModel: EmoteGridViewModel by viewModels()

    private lateinit var emoteAdapter: EmoteGridAdapter
    private var renderedSetSignature: List<String> = emptyList()

    private val userId: String by lazy { requireArguments().getString(ARG_USER_ID).orEmpty() }
    private val displayName: String by lazy { requireArguments().getString(ARG_DISPLAY_NAME).orEmpty() }
    private val username: String by lazy { requireArguments().getString(ARG_USERNAME).orEmpty() }
    private val avatarUrl: String? by lazy { requireArguments().getString(ARG_AVATAR_URL) }
    private val initialSetId: String? by lazy { requireArguments().getString(ARG_INITIAL_SET_ID) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEmoteGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.streamerHeaderContainer.visibility = View.VISIBLE
        binding.tvHeaderName.text = displayName
        binding.tvHeaderSubtitle.text = getString(R.string.streamer_username_handle, username)
        if (!avatarUrl.isNullOrBlank()) {
            binding.ivHeaderAvatar.load(avatarUrl) {
                placeholder(R.drawable.ic_person)
                error(R.drawable.ic_person)
            }
        } else {
            binding.ivHeaderAvatar.setImageResource(R.drawable.ic_person)
        }

        val spanCount = maxOf(3, resources.configuration.screenWidthDp / 115)
        val gridLayoutManager = GridLayoutManager(requireContext(), spanCount)
        emoteAdapter = EmoteGridAdapter { emote ->
            if (viewModel.session.toggleEmote(emote) == ToggleSelectionResult.LIMIT_REACHED) {
                showMaxLimitSnackbar()
            }
        }
        binding.rvEmotesGrid.layoutManager = gridLayoutManager
        binding.rvEmotesGrid.adapter = emoteAdapter

        // Progressive 30-emote reveal on scroll for streamer sets
        binding.rvEmotesGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val totalItems = gridLayoutManager.itemCount
                val lastVisible = gridLayoutManager.findLastVisibleItemPosition()
                if (totalItems > 0 && lastVisible >= totalItems - 9) {
                    viewModel.loadNextStreamerPage()
                }
            }
        })

        binding.btnSelectAll.setOnClickListener {
            val res = viewModel.selectFirst30()
            if (res.limitReached) {
                showMaxLimitSnackbar()
            }
        }

        binding.btnRetryEmotes.setOnClickListener {
            viewModel.loadStreamer(userId, initialSetId)
        }

        if (savedInstanceState == null) {
            viewModel.loadStreamer(userId, initialSetId)
        }

        observeState()
    }

    private fun showMaxLimitSnackbar() {
        Snackbar.make(binding.root, R.string.max_30_stickers_reached, Snackbar.LENGTH_SHORT).show()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.uiState,
                    viewModel.session.selectedEmotes,
                    viewModel.session.editingCollectionName
                ) { state, selectedMap, editingName ->
                    Triple(state, selectedMap, editingName)
                }.collect { (state, selectedMap, editingName) ->
                    val isEditing = !editingName.isNullOrBlank()
                    if (isEditing) {
                        binding.tvEditingBanner.visibility = View.VISIBLE
                        binding.tvEditingBanner.text = getString(
                            R.string.editing_banner,
                            editingName,
                            selectedMap.size
                        )
                    } else {
                        binding.tvEditingBanner.visibility = View.GONE
                    }

                    (activity as? AppNavigator)?.configureToolbar(
                        title = getString(R.string.streamer_emotes_title, displayName),
                        showBack = true,
                        showCreatePackAction = true,
                        selectedCount = selectedMap.size,
                        isEditing = isEditing,
                        onCreatePackClick = {
                            if (selectedMap.isEmpty()) {
                                Snackbar.make(binding.root, R.string.select_at_least_one, Snackbar.LENGTH_SHORT).show()
                            } else {
                                val suggested = editingName ?: displayName
                                (activity as? AppNavigator)?.openCreateCollection(suggested)
                            }
                        }
                    )

                    val setSignature = state.emoteSets.map { "${it.id}:${it.emotes.size}:${state.activeSetId == it.id}" }
                    if (setSignature != renderedSetSignature) {
                        renderedSetSignature = setSignature
                        renderSetChips(state)
                    }

                    emoteAdapter.submitList(state.displayedEmotes)
                    emoteAdapter.updateSelection(selectedMap.keys)

                    binding.tvSelectedCount.text = getString(R.string.selected_count_format, selectedMap.size)
                    binding.btnSelectAll.setText(R.string.select_first_30)
                    binding.progressGlobalSelect.visibility =
                        if (state.isLoadingSet || state.isLoadingMore) View.VISIBLE else View.GONE

                    binding.progressEmotes.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    val showEmptyOrError = !state.isLoading && !state.isLoadingSet &&
                        (state.errorMessage != null || state.displayedEmotes.isEmpty())
                    binding.emptyErrorContainer.visibility = if (showEmptyOrError) View.VISIBLE else View.GONE
                    if (state.errorMessage != null) {
                        binding.tvEmotesStatus.text = if (state.isNetworkError) {
                            getString(R.string.error_network)
                        } else {
                            getString(R.string.error_api, state.errorMessage)
                        }
                        binding.btnRetryEmotes.visibility = View.VISIBLE
                    } else if (state.displayedEmotes.isEmpty() && !state.isLoading && !state.isLoadingSet) {
                        binding.tvEmotesStatus.setText(R.string.no_emotes_found)
                        binding.btnRetryEmotes.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun renderSetChips(state: EmoteGridUiState) {
        val group = binding.chipGroupSets
        group.removeAllViews()

        val totalLoadedUnique = state.emoteSets.flatMap { it.emotes }.distinctBy { it.id }.size
        val allChip = Chip(requireContext()).apply {
            id = View.generateViewId()
            text = getString(R.string.filter_all_sets, totalLoadedUnique)
            isCheckable = true
            isChecked = state.activeSetId == null
            setOnClickListener { viewModel.selectEmoteSetFilter(null) }
        }
        group.addView(allChip)

        state.emoteSets.forEach { set ->
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                text = if (set.emotes.isNotEmpty()) "${set.name} (${set.emotes.size})" else set.name
                isCheckable = true
                isChecked = state.activeSetId == set.id
                setOnClickListener { viewModel.selectEmoteSetFilter(set.id) }
            }
            group.addView(chip)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_USER_ID = "user_id"
        private const val ARG_DISPLAY_NAME = "display_name"
        private const val ARG_USERNAME = "username"
        private const val ARG_AVATAR_URL = "avatar_url"
        private const val ARG_INITIAL_SET_ID = "initial_set_id"

        fun newInstance(
            userId: String,
            displayName: String,
            username: String,
            avatarUrl: String?,
            initialSetId: String? = null
        ) = StreamerEmotesFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_USER_ID, userId)
                putString(ARG_DISPLAY_NAME, displayName)
                putString(ARG_USERNAME, username)
                putString(ARG_AVATAR_URL, avatarUrl)
                putString(ARG_INITIAL_SET_ID, initialSetId)
            }
        }
    }
}
