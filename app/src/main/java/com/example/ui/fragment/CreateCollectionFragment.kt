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
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.AppConfig
import com.example.AppNavigator
import com.example.R
import com.example.data.local.CollectionStatus
import com.example.data.local.ItemConversionStatus
import com.example.data.local.StickerItemEntity
import com.example.databinding.FragmentCreateCollectionBinding
import com.example.ui.adapter.ConversionStatusAdapter
import com.example.ui.adapter.SelectedPreviewAdapter
import com.example.ui.viewmodel.CreateCollectionViewModel
import com.example.whatsapp.StickerPackFormat
import com.example.whatsapp.WhatsAppPackBuilder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class CreateCollectionFragment : Fragment() {

    private var _binding: FragmentCreateCollectionBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CreateCollectionViewModel by viewModels()

    private lateinit var previewAdapter: SelectedPreviewAdapter
    private lateinit var statusAdapter: ConversionStatusAdapter
    private var promptedTrimItemIds = mutableSetOf<String>()
    private var hasAutoNavigatedToSummary = false

    private val suggestedName: String by lazy { requireArguments().getString(ARG_SUGGESTED_NAME).orEmpty() }
    private val existingBuildId: String? by lazy { requireArguments().getString(ARG_EXISTING_COLLECTION_ID) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreateCollectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        hasAutoNavigatedToSummary = savedInstanceState?.getBoolean(KEY_AUTO_NAVIGATED, false) ?: false

        val isEditing = viewModel.session.editingCollectionId.value != null
        (activity as? AppNavigator)?.configureToolbar(
            title = getString(
                if (existingBuildId != null) R.string.build_progress_title
                else if (isEditing) R.string.edit_collection_title
                else R.string.create_collection_title
            ),
            showBack = true,
            showCreatePackAction = false
        )

        setupReviewUi(isEditing)
        setupProgressUi()

        if (savedInstanceState == null && existingBuildId != null) {
            viewModel.attachExistingBuild(existingBuildId!!)
        }

        observeReviewSelection()
        observeBuildProgress()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AUTO_NAVIGATED, hasAutoNavigatedToSummary)
    }

    private fun setupReviewUi(isEditing: Boolean) {
        if (binding.etCollectionName.text.isNullOrBlank() && suggestedName.isNotBlank()) {
            binding.etCollectionName.setText(suggestedName)
        }
        binding.btnConfirmCreate.setText(
            if (isEditing) R.string.confirm_update_pack else R.string.confirm_create_pack
        )

        val spanCount = maxOf(3, resources.configuration.screenWidthDp / 115)
        previewAdapter = SelectedPreviewAdapter { emote ->
            viewModel.session.removeSelected(emote.id)
        }
        binding.rvPreviewEmotes.layoutManager = GridLayoutManager(requireContext(), spanCount)
        binding.rvPreviewEmotes.adapter = previewAdapter

        binding.btnConfirmCreate.setOnClickListener {
            val name = binding.etCollectionName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                binding.tilCollectionName.error = getString(R.string.collection_name_required)
                return@setOnClickListener
            }
            binding.tilCollectionName.error = null
            if (viewModel.session.selectedEmotes.value.isEmpty()) {
                Snackbar.make(binding.root, R.string.select_at_least_one, Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!viewModel.hasEnoughStorage()) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.insufficient_storage_error, viewModel.requiredSpaceMb()),
                    Snackbar.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            hasAutoNavigatedToSummary = false
            viewModel.startBuild(name)
        }
    }

    private fun setupProgressUi() {
        statusAdapter = ConversionStatusAdapter { item ->
            showTrimConfirmationDialog(item)
        }
        binding.rvConversionItems.layoutManager = LinearLayoutManager(requireContext())
        binding.rvConversionItems.adapter = statusAdapter

        binding.btnRetryFailed.setOnClickListener { viewModel.retryFailed() }
        binding.btnCancelBuild.setOnClickListener { confirmCancelBuild() }
        binding.btnOpenCreatedPack.setOnClickListener {
            val id = viewModel.activeCollectionId.value ?: return@setOnClickListener
            hasAutoNavigatedToSummary = true
            (activity as? AppNavigator)?.openCollectionDetail(
                collectionId = id,
                isCreationSummary = true,
                popCurrentProcessing = true
            )
        }
    }

    private fun confirmCancelBuild() {
        val colName = viewModel.activeCollectionDetails.value?.collection?.name ?: suggestedName
        val isEdit = viewModel.session.editingCollectionId.value != null
        val msg = getString(
            if (isEdit) R.string.cancel_creation_edit_message else R.string.cancel_creation_new_message,
            colName
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cancel_creation_title)
            .setMessage(msg)
            .setPositiveButton(R.string.confirm_cancel_creation) { _, _ ->
                viewModel.discardOrRevertBuild {
                    if (isAdded) {
                        parentFragmentManager.popBackStack()
                    }
                }
            }
            .setNegativeButton(R.string.keep_editing, null)
            .show()
    }

    private fun observeReviewSelection() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.session.selectedEmotes.collect { selectedMap ->
                    val list = selectedMap.values.toList()
                    previewAdapter.submitList(list)

                    val animCount = list.count { it.animated }
                    val staticCount = list.count { !it.animated }
                    binding.tvSummaryTotal.text = getString(R.string.summary_total, list.size)
                    binding.tvSummaryAnimated.text = getString(R.string.summary_animated, animCount)
                    binding.tvSummaryStatic.text = getString(R.string.summary_static, staticCount)

                    binding.tvWhatsappSplitInfo.text = when (WhatsAppPackBuilder.determinePackFormat(list)) {
                        StickerPackFormat.STATIC -> getString(R.string.pack_format_static)
                        StickerPackFormat.ANIMATED -> getString(R.string.pack_format_animated)
                        StickerPackFormat.MIXED_FAKE_VID -> getString(R.string.pack_format_mixed_fake_vid, list.size)
                    }

                    val isDraft = list.size in 1 until AppConfig.MIN_STICKERS_FOR_WHATSAPP
                    binding.tvWhatsappDraftWarning.visibility = if (isDraft) View.VISIBLE else View.GONE
                    binding.btnConfirmCreate.isEnabled = list.isNotEmpty()
                }
            }
        }
    }

    private fun observeBuildProgress() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.activeCollectionDetails.collect { details ->
                    if (details == null) {
                        binding.reviewContainer.visibility = View.VISIBLE
                        binding.progressContainer.visibility = View.GONE
                        return@collect
                    }

                    binding.reviewContainer.visibility = View.GONE
                    binding.progressContainer.visibility = View.VISIBLE

                    val items = details.items
                    statusAdapter.submitList(items)

                    val total = items.size.coerceAtLeast(1)
                    val completed = items.count { it.status == ItemConversionStatus.COMPLETED }
                    val failed = items.count {
                        it.status == ItemConversionStatus.FAILED || it.status == ItemConversionStatus.CANCELLED
                    }
                    val needsTrim = items.filter { it.status == ItemConversionStatus.NEEDS_TRIM }
                    val pending = items.count {
                        it.status == ItemConversionStatus.PENDING ||
                            it.status == ItemConversionStatus.DOWNLOADING ||
                            it.status == ItemConversionStatus.CONVERTING ||
                            it.status == ItemConversionStatus.VALIDATING
                    }

                    binding.progressBarOverall.max = total
                    binding.progressBarOverall.progress = completed + failed + needsTrim.size
                    binding.tvProgressCounts.text = getString(
                        R.string.progress_counts_format,
                        completed,
                        pending + needsTrim.size,
                        failed
                    )

                    val isRunning = details.collection.status == CollectionStatus.PROCESSING
                    binding.btnCancelBuild.visibility = View.VISIBLE
                    binding.btnRetryFailed.visibility = if (!isRunning && failed > 0) View.VISIBLE else View.GONE
                    binding.btnOpenCreatedPack.visibility = if (!isRunning && completed > 0) View.VISIBLE else View.GONE

                    // Prompt user explicitly if any animation exceeds 10 seconds
                    val firstNeedsTrim = needsTrim.firstOrNull { it.id !in promptedTrimItemIds }
                    if (firstNeedsTrim != null && !isRunning) {
                        promptedTrimItemIds.add(firstNeedsTrim.id)
                        showTrimConfirmationDialog(firstNeedsTrim)
                    }

                    // Auto-navigate to the final Summary screen once all items finish cleanly without pending or errors
                    val finishedCleanly = !isRunning &&
                        pending == 0 &&
                        needsTrim.isEmpty() &&
                        failed == 0 &&
                        completed > 0
                    if (finishedCleanly && !hasAutoNavigatedToSummary) {
                        hasAutoNavigatedToSummary = true
                        (activity as? AppNavigator)?.openCollectionDetail(
                            collectionId = details.collection.id,
                            isCreationSummary = true,
                            popCurrentProcessing = true
                        )
                    }
                }
            }
        }
    }

    private fun showTrimConfirmationDialog(item: StickerItemEntity) {
        val secs = item.durationMs / 1000f
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trim_dialog_title)
            .setMessage(getString(R.string.trim_dialog_message, item.emoteName, secs))
            .setPositiveButton(R.string.trim_one_button) { _, _ ->
                viewModel.trimAndRetryItem(item.id)
            }
            .setNeutralButton(R.string.trim_all_button) { _, _ ->
                viewModel.trimAllOver10s()
            }
            .setNegativeButton(R.string.skip_emote_button) { _, _ ->
                viewModel.skipTrimItem(item.id)
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_SUGGESTED_NAME = "suggested_name"
        private const val ARG_EXISTING_COLLECTION_ID = "existing_collection_id"
        private const val KEY_AUTO_NAVIGATED = "auto_navigated"

        fun newForSelection(suggestedName: String) = CreateCollectionFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SUGGESTED_NAME, suggestedName)
            }
        }

        fun newForExistingBuild(collectionId: String) = CreateCollectionFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_EXISTING_COLLECTION_ID, collectionId)
            }
        }
    }
}
