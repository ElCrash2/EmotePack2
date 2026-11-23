package com.example.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.AppNavigator
import com.example.R
import com.example.databinding.FragmentEmoteGridBinding
import com.example.ui.adapter.EmoteGridAdapter
import com.example.ui.state.ToggleSelectionResult
import com.example.ui.viewmodel.EmoteGridViewModel
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class EmoteSearchFragment : Fragment() {

    private var _binding: FragmentEmoteGridBinding? = null
    private val binding get() = _binding!!
    private val viewModel: EmoteGridViewModel by viewModels()

    private lateinit var emoteAdapter: EmoteGridAdapter

    private val initialQuery: String by lazy { requireArguments().getString(ARG_QUERY).orEmpty() }

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

        binding.inlineSearchBar.visibility = View.VISIBLE
        binding.etInlineQuery.setText(initialQuery)
        binding.btnInlineSearch.setOnClickListener { runInlineSearch() }
        binding.etInlineQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runInlineSearch()
                true
            } else false
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

        // Progressive 30-emote pagination when scrolling near bottom
        binding.rvEmotesGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val totalItems = gridLayoutManager.itemCount
                val lastVisible = gridLayoutManager.findLastVisibleItemPosition()
                if (totalItems > 0 && lastVisible >= totalItems - 9) {
                    viewModel.loadNextEmotePage()
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
            val current = viewModel.uiState.value.searchQuery.ifBlank { initialQuery }
            viewModel.searchEmotes(current)
        }

        if (savedInstanceState == null && initialQuery.isNotBlank()) {
            viewModel.searchEmotes(initialQuery)
        }

        observeState()
    }

    private fun showMaxLimitSnackbar() {
        Snackbar.make(binding.root, R.string.max_30_stickers_reached, Snackbar.LENGTH_SHORT).show()
    }

    private fun runInlineSearch() {
        val q = binding.etInlineQuery.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) {
            Snackbar.make(binding.root, R.string.search_empty_validation, Snackbar.LENGTH_SHORT).show()
            return
        }
        viewModel.searchEmotes(q)
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

                    val activeQuery = state.searchQuery.ifBlank { initialQuery }
                    (activity as? AppNavigator)?.configureToolbar(
                        title = getString(R.string.emote_search_title, activeQuery),
                        showBack = true,
                        showCreatePackAction = true,
                        selectedCount = selectedMap.size,
                        isEditing = isEditing,
                        onCreatePackClick = {
                            if (selectedMap.isEmpty()) {
                                Snackbar.make(binding.root, R.string.select_at_least_one, Snackbar.LENGTH_SHORT).show()
                            } else {
                                val suggested = editingName ?: activeQuery
                                (activity as? AppNavigator)?.openCreateCollection(suggested)
                            }
                        }
                    )

                    emoteAdapter.submitList(state.displayedEmotes)
                    emoteAdapter.updateSelection(selectedMap.keys)

                    binding.tvSelectedCount.text = getString(R.string.selected_count_format, selectedMap.size)
                    binding.btnSelectAll.setText(R.string.select_first_30)

                    binding.progressGlobalSelect.visibility =
                        if (state.isLoadingMore) View.VISIBLE else View.GONE

                    binding.progressEmotes.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    val showEmptyOrError = !state.isLoading && (state.errorMessage != null || state.displayedEmotes.isEmpty())
                    binding.emptyErrorContainer.visibility = if (showEmptyOrError) View.VISIBLE else View.GONE
                    if (state.errorMessage != null) {
                        binding.tvEmotesStatus.text = if (state.isNetworkError) {
                            getString(R.string.error_network)
                        } else {
                            getString(R.string.error_api, state.errorMessage)
                        }
                        binding.btnRetryEmotes.visibility = View.VISIBLE
                    } else if (state.displayedEmotes.isEmpty() && !state.isLoading) {
                        binding.tvEmotesStatus.setText(R.string.no_emotes_found)
                        binding.btnRetryEmotes.visibility = View.GONE
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_QUERY = "query"

        fun newInstance(query: String) = EmoteSearchFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_QUERY, query)
            }
        }
    }
}
