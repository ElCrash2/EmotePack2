package com.example.ui.fragment

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import coil.load
import com.example.AppConfig
import com.example.AppNavigator
import com.example.R
import com.example.data.local.CollectionWithDetails
import com.example.data.local.ItemConversionStatus
import com.example.data.local.WhatsAppSubpackEntity
import com.example.databinding.FragmentCollectionDetailBinding
import com.example.ui.adapter.LocalStickerAdapter
import com.example.ui.viewmodel.CollectionDetailViewModel
import com.example.whatsapp.PackValidationResult
import com.example.whatsapp.WhatsAppPackExporter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.io.File

class CollectionDetailFragment : Fragment() {

    private var _binding: FragmentCollectionDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CollectionDetailViewModel by viewModels()

    private lateinit var stickerAdapter: LocalStickerAdapter
    private var pendingPackId: String? = null
    private var navigatingToEdit = false

    private val collectionId: String by lazy { requireArguments().getString(ARG_COLLECTION_ID).orEmpty() }
    private val isCreationSummary: Boolean by lazy {
        requireArguments().getBoolean(ARG_IS_CREATION_SUMMARY, false)
    }

    private val whatsappLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val packId = pendingPackId
        pendingPackId = null
        if (result.resultCode == Activity.RESULT_OK) {
            if (packId != null) {
                viewModel.markSubpackAdded(packId, true)
            }
            Snackbar.make(binding.root, R.string.whatsapp_add_confirmed, Snackbar.LENGTH_SHORT).show()
        } else {
            val errorMsg = result.data?.getStringExtra("validation_error")
            if (!errorMsg.isNullOrBlank()) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.whatsapp_add_error, errorMsg),
                    Snackbar.LENGTH_LONG
                ).show()
            } else {
                Snackbar.make(binding.root, R.string.whatsapp_add_cancelled, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCollectionDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val spanCount = maxOf(3, resources.configuration.screenWidthDp / 115)
        stickerAdapter = LocalStickerAdapter()
        binding.rvLocalStickers.layoutManager = GridLayoutManager(requireContext(), spanCount)
        binding.rvLocalStickers.adapter = stickerAdapter

        binding.btnEditCollection.setOnClickListener {
            val origin = viewModel.beginEditSession() ?: return@setOnClickListener
            navigatingToEdit = true
            (activity as? AppNavigator)?.openEditFromOrigin(origin)
        }

        if (savedInstanceState == null) {
            viewModel.load(collectionId)
        }

        observeDetails()
    }

    private fun observeDetails() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.details.collect { details ->
                    if (details == null) return@collect
                    bindDetails(details)
                }
            }
        }
    }

    private fun bindDetails(details: CollectionWithDetails) {
        val col = details.collection
        val ctx = requireContext()

        (activity as? AppNavigator)?.configureToolbar(
            title = col.name,
            showBack = true,
            showCreatePackAction = false
        )

        val trayFile = col.trayIconPath?.let(::File)?.takeIf { it.exists() }
        if (trayFile != null) {
            binding.ivDetailTrayIcon.load(trayFile) {
                placeholder(R.drawable.ic_launcher_art)
                error(R.drawable.ic_launcher_art)
            }
        } else {
            binding.ivDetailTrayIcon.setImageResource(R.drawable.ic_launcher_art)
        }

        binding.tvDetailName.text = col.name
        binding.tvDetailCounts.text = getString(
            R.string.pack_card_counts,
            col.totalCount,
            col.animatedCount,
            col.staticCount
        )

        binding.tvDetailFormat.setText(
            when {
                !col.isAnimatedPack -> R.string.detail_format_static
                col.animatedCount > 0 && col.staticCount > 0 -> R.string.detail_format_mixed
                else -> R.string.detail_format_animated
            }
        )

        val completedStickers = details.items
            .filter { it.status == ItemConversionStatus.COMPLETED }
            .sortedBy { it.sortOrder }
        val failedOrSkipped = details.items.count {
            it.status == ItemConversionStatus.FAILED ||
                it.status == ItemConversionStatus.CANCELLED ||
                it.status == ItemConversionStatus.NEEDS_TRIM
        }

        if (failedOrSkipped > 0) {
            binding.tvDetailSummaryStatus.text = getString(
                R.string.summary_processing_partial,
                completedStickers.size,
                failedOrSkipped
            )
            binding.tvDetailSummaryStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_warning))
        } else {
            binding.tvDetailSummaryStatus.text = getString(
                R.string.summary_processing_ready,
                completedStickers.size
            )
            binding.tvDetailSummaryStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_success))
        }

        val env = WhatsAppPackExporter.inspectEnvironment(ctx)
        val waStatus = getString(if (env.consumerInstalled) R.string.status_installed else R.string.status_not_installed)
        val wbStatus = getString(if (env.businessInstalled) R.string.status_installed else R.string.status_not_installed)
        binding.tvDetailWhatsappEnv.text = getString(
            R.string.whatsapp_compatibility_status,
            waStatus,
            wbStatus
        )

        val singlePack = details.subpacks.firstOrNull()
        val validation = WhatsAppPackExporter.validatePackReadyForWhatsApp(singlePack, details.items)
        val isWhitelisted = singlePack != null &&
            (singlePack.addedToWhatsApp || WhatsAppPackExporter.isWhitelistedInWhatsApp(ctx, singlePack.id))

        when (validation) {
            is PackValidationResult.Valid -> {
                binding.btnSendToWhatsapp.isEnabled = true
                binding.btnSendToWhatsapp.setText(
                    if (isWhitelisted) R.string.already_in_whatsapp else R.string.send_to_whatsapp
                )
                binding.tvWhatsappRequirementHint.visibility = View.GONE
                binding.btnSendToWhatsapp.setOnClickListener {
                    if (singlePack != null) {
                        exportPackToWhatsApp(singlePack)
                    }
                }
            }
            is PackValidationResult.TooFewStickers -> {
                binding.btnSendToWhatsapp.isEnabled = false
                binding.btnSendToWhatsapp.setText(R.string.send_to_whatsapp)
                val missing = maxOf(1, AppConfig.MIN_STICKERS_FOR_WHATSAPP - validation.count)
                binding.tvWhatsappRequirementHint.text = getString(R.string.pack_draft_too_few, missing)
                binding.tvWhatsappRequirementHint.visibility = View.VISIBLE
            }
            is PackValidationResult.TooManyStickers -> {
                binding.btnSendToWhatsapp.isEnabled = false
                binding.btnSendToWhatsapp.setText(R.string.send_to_whatsapp)
                binding.tvWhatsappRequirementHint.text = getString(R.string.max_30_stickers_reached)
                binding.tvWhatsappRequirementHint.visibility = View.VISIBLE
            }
            is PackValidationResult.InvalidAssets -> {
                binding.btnSendToWhatsapp.isEnabled = false
                binding.btnSendToWhatsapp.setText(R.string.send_to_whatsapp)
                binding.tvWhatsappRequirementHint.text = getString(R.string.pack_invalid_assets, validation.reason)
                binding.tvWhatsappRequirementHint.visibility = View.VISIBLE
            }
        }

        val showCancelCreation = isCreationSummary || viewModel.hasPendingEditSnapshot()
        if (showCancelCreation) {
            binding.btnDeleteCollection.setText(R.string.cancel_creation)
            binding.btnDeleteCollection.setOnClickListener {
                val isEditRevert = viewModel.hasPendingEditSnapshot()
                val msg = getString(
                    if (isEditRevert) R.string.cancel_creation_edit_message else R.string.cancel_creation_new_message,
                    col.name
                )
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.cancel_creation_title)
                    .setMessage(msg)
                    .setPositiveButton(R.string.confirm_cancel_creation) { _, _ ->
                        viewModel.cancelCreationOrRevertEdit {
                            if (isAdded) {
                                parentFragmentManager.popBackStack()
                            }
                        }
                    }
                    .setNegativeButton(R.string.keep_editing, null)
                    .show()
            }
        } else {
            binding.btnDeleteCollection.setText(R.string.delete_collection)
            binding.btnDeleteCollection.setOnClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(getString(R.string.delete_dialog_title, col.name))
                    .setMessage(R.string.delete_dialog_message)
                    .setPositiveButton(R.string.confirm_delete) { _, _ ->
                        viewModel.deleteCollection {
                            if (isAdded) {
                                parentFragmentManager.popBackStack()
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        stickerAdapter.submitList(completedStickers)
        binding.tvAllStickersHeader.text = getString(
            R.string.collection_detail_stickers_title,
            completedStickers.size
        )
    }

    private fun exportPackToWhatsApp(pack: WhatsAppSubpackEntity) {
        val env = WhatsAppPackExporter.inspectEnvironment(requireContext())
        if (!env.anyInstalled) {
            Snackbar.make(binding.root, R.string.whatsapp_not_installed, Snackbar.LENGTH_LONG).show()
            return
        }
        if (!env.anyCompatible) {
            Snackbar.make(binding.root, R.string.whatsapp_incompatible, Snackbar.LENGTH_LONG).show()
            return
        }

        if (env.consumerCompatible && env.businessCompatible) {
            val options = arrayOf(
                getString(R.string.whatsapp_personal),
                getString(R.string.whatsapp_business)
            )
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.whatsapp_choose_app)
                .setItems(options) { _, which ->
                    val targetPkg = if (which == 0) {
                        WhatsAppPackExporter.WHATSAPP_CONSUMER_PACKAGE
                    } else {
                        WhatsAppPackExporter.WHATSAPP_BUSINESS_PACKAGE
                    }
                    launchWhatsAppIntent(pack, targetPkg)
                }
                .show()
        } else {
            val targetPkg = if (env.consumerCompatible) {
                WhatsAppPackExporter.WHATSAPP_CONSUMER_PACKAGE
            } else {
                WhatsAppPackExporter.WHATSAPP_BUSINESS_PACKAGE
            }
            launchWhatsAppIntent(pack, targetPkg)
        }
    }

    private fun launchWhatsAppIntent(pack: WhatsAppSubpackEntity, targetPkg: String) {
        try {
            pendingPackId = pack.id
            val intent = WhatsAppPackExporter.buildAddToWhatsAppIntent(
                subpackId = pack.id,
                subpackName = pack.displayName,
                targetPackage = targetPkg
            )
            whatsappLauncher.launch(intent)
        } catch (e: Exception) {
            pendingPackId = null
            Snackbar.make(
                binding.root,
                getString(R.string.whatsapp_add_error, e.message ?: "Error"),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!navigatingToEdit && (isRemoving || requireActivity().isFinishing)) {
            viewModel.commitCreationSummary()
        }
    }

    companion object {
        private const val ARG_COLLECTION_ID = "collection_id"
        private const val ARG_IS_CREATION_SUMMARY = "is_creation_summary"

        fun newInstance(collectionId: String, isCreationSummary: Boolean = false) =
            CollectionDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_COLLECTION_ID, collectionId)
                    putBoolean(ARG_IS_CREATION_SUMMARY, isCreationSummary)
                }
            }
    }
}
