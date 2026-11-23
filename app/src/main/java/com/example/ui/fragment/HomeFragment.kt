package com.example.ui.fragment

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.AppNavigator
import com.example.R
import com.example.data.model.SearchOriginType
import com.example.databinding.FragmentHomeBinding
import com.example.ui.adapter.StreamerAdapter
import com.example.ui.viewmodel.HomeViewModel
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()

    private lateinit var streamerAdapter: StreamerAdapter
    private var suppressWatchers = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? AppNavigator)?.configureToolbar(
            title = getString(R.string.app_name),
            showBack = false,
            showCreatePackAction = false
        )

        setupRecyclerView()
        setupMutualExclusionSearchBoxes()
        observeState()
    }

    private fun setupRecyclerView() {
        streamerAdapter = StreamerAdapter { user ->
            hideKeyboard()
            (activity as? AppNavigator)?.openStreamerEmotes(user)
        }
        binding.rvStreamers.layoutManager = LinearLayoutManager(requireContext())
        binding.rvStreamers.adapter = streamerAdapter

        binding.btnLoadMoreStreamers.setOnClickListener {
            viewModel.loadMoreStreamers()
        }
    }

    private fun setupMutualExclusionSearchBoxes() {
        // Focusing or tapping "Search streamer" automatically clears "Search emote"
        binding.etSearchStreamer.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                activateStreamerMode()
                showKeyboard(v)
            }
        }
        binding.etSearchStreamer.setOnClickListener { v ->
            activateStreamerMode()
            showKeyboard(v)
        }

        // Focusing or tapping "Search emote" automatically clears "Search streamer"
        binding.etSearchEmote.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                activateEmoteMode()
                showKeyboard(v)
            }
        }
        binding.etSearchEmote.setOnClickListener { v ->
            activateEmoteMode()
            showKeyboard(v)
        }

        binding.etSearchStreamer.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatchers) return
                val text = s?.toString().orEmpty()
                if (text.isNotEmpty() && !binding.etSearchEmote.text.isNullOrEmpty()) {
                    suppressWatchers = true
                    binding.etSearchEmote.setText("")
                    suppressWatchers = false
                }
                viewModel.updateStreamerQuery(text)
            }
        })

        binding.etSearchEmote.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatchers) return
                val text = s?.toString().orEmpty()
                if (text.isNotEmpty() && !binding.etSearchStreamer.text.isNullOrEmpty()) {
                    suppressWatchers = true
                    binding.etSearchStreamer.setText("")
                    suppressWatchers = false
                }
                viewModel.updateEmoteQuery(text)
            }
        })

        binding.etSearchStreamer.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                executeActiveSearch()
                true
            } else false
        }

        binding.etSearchEmote.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                executeActiveSearch()
                true
            } else false
        }

        binding.btnSearch.setOnClickListener {
            executeActiveSearch()
        }
    }

    private fun activateStreamerMode() {
        if (!binding.etSearchEmote.text.isNullOrEmpty()) {
            suppressWatchers = true
            binding.etSearchEmote.setText("")
            suppressWatchers = false
        }
        binding.tilSearchEmote.error = null
        viewModel.onStreamerFieldActivated()
    }

    private fun activateEmoteMode() {
        if (!binding.etSearchStreamer.text.isNullOrEmpty()) {
            suppressWatchers = true
            binding.etSearchStreamer.setText("")
            suppressWatchers = false
        }
        binding.tilSearchStreamer.error = null
        viewModel.onEmoteFieldActivated()
    }

    private fun executeActiveSearch() {
        val state = viewModel.uiState.value
        val streamerText = binding.etSearchStreamer.text?.toString()?.trim().orEmpty()
        val emoteText = binding.etSearchEmote.text?.toString()?.trim().orEmpty()

        when (state.activeMode) {
            SearchOriginType.STREAMER -> {
                if (streamerText.isEmpty()) {
                    binding.tilSearchStreamer.error = getString(R.string.search_empty_validation)
                    Snackbar.make(binding.root, R.string.search_empty_validation, Snackbar.LENGTH_SHORT).show()
                    return
                }
                binding.tilSearchStreamer.error = null
                hideKeyboard()
                viewModel.searchStreamers()
            }
            SearchOriginType.EMOTE -> {
                if (emoteText.isEmpty()) {
                    binding.tilSearchEmote.error = getString(R.string.search_empty_validation)
                    Snackbar.make(binding.root, R.string.search_empty_validation, Snackbar.LENGTH_SHORT).show()
                    return
                }
                binding.tilSearchEmote.error = null
                hideKeyboard()
                (activity as? AppNavigator)?.openEmoteSearch(emoteText)
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (state.activeMode == SearchOriginType.STREAMER) {
                        binding.activeModeBadge.setText(R.string.search_active_mode_streamer)
                        binding.activeModeBadge.setBackgroundResource(R.drawable.bg_badge_static)
                        binding.activeModeBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_cyan))
                    } else {
                        binding.activeModeBadge.setText(R.string.search_active_mode_emote)
                        binding.activeModeBadge.setBackgroundResource(R.drawable.bg_badge_animated)
                        binding.activeModeBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_violet))
                    }

                    val showResultsSection = state.hasSearchedStreamer || state.isLoading || state.streamers.isNotEmpty()
                    binding.streamerResultsContainer.visibility = if (showResultsSection) View.VISIBLE else View.GONE

                    binding.progressStreamers.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    streamerAdapter.submitList(state.streamers)

                    when {
                        state.isLoading -> {
                            binding.tvStreamersStatus.visibility = View.VISIBLE
                            binding.tvStreamersStatus.setText(R.string.search_loading_streamers)
                        }
                        state.errorMessage != null -> {
                            binding.tvStreamersStatus.visibility = View.VISIBLE
                            binding.tvStreamersStatus.text = if (state.isNetworkError) {
                                getString(R.string.error_network)
                            } else {
                                getString(R.string.error_api, state.errorMessage)
                            }
                        }
                        state.hasSearchedStreamer && state.streamers.isEmpty() -> {
                            binding.tvStreamersStatus.visibility = View.VISIBLE
                            binding.tvStreamersStatus.text = getString(
                                R.string.search_no_streamers_found,
                                state.lastSearchedStreamerQuery
                            )
                        }
                        else -> {
                            binding.tvStreamersStatus.visibility = View.GONE
                        }
                    }

                    binding.btnLoadMoreStreamers.visibility =
                        if (state.hasMoreStreamers && !state.isLoading) View.VISIBLE else View.GONE
                    binding.btnLoadMoreStreamers.isEnabled = !state.isLoadingMore
                }
            }
        }
    }

    private fun showKeyboard(target: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
