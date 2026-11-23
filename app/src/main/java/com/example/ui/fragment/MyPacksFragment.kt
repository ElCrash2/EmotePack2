package com.example.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.AppNavigator
import com.example.R
import com.example.databinding.FragmentMyPacksBinding
import com.example.ui.adapter.CollectionPackAdapter
import com.example.ui.viewmodel.BackupEvent
import com.example.ui.viewmodel.MyPacksViewModel
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MyPacksFragment : Fragment() {

    private var _binding: FragmentMyPacksBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MyPacksViewModel by viewModels()

    private lateinit var packAdapter: CollectionPackAdapter

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) viewModel.exportBackup(uri)
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importBackup(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMyPacksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? AppNavigator)?.configureToolbar(
            title = getString(R.string.my_packs_title),
            showBack = false,
            showCreatePackAction = false
        )

        packAdapter = CollectionPackAdapter { collection ->
            (activity as? AppNavigator)?.openCollectionDetail(collection.id)
        }
        binding.rvMyPacks.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMyPacks.adapter = packAdapter

        binding.btnGoHome.setOnClickListener {
            (activity as? AppNavigator)?.openHome()
        }

        binding.btnExportBackup.setOnClickListener {
            exportLauncher.launch("emotepack_backup.zip")
        }

        binding.btnImportBackup.setOnClickListener {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }

        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.collections.collect { list ->
                        packAdapter.submitList(list)
                        binding.emptyPacksContainer.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.activeProcessing.collect { active ->
                        if (active != null) {
                            binding.cardResumeJob.visibility = View.VISIBLE
                            binding.tvResumeJob.text = getString(
                                R.string.resume_progress_banner,
                                active.name,
                                active.animatedCount + active.staticCount,
                                active.totalCount
                            )
                            binding.cardResumeJob.setOnClickListener {
                                (activity as? AppNavigator)?.openBuildProgress(active.id)
                            }
                        } else {
                            binding.cardResumeJob.visibility = View.GONE
                        }
                    }
                }

                launch {
                    viewModel.backupEvents.collect { ev ->
                        val msg = when (ev) {
                            is BackupEvent.ExportSuccess -> getString(R.string.backup_export_success, ev.count)
                            is BackupEvent.ExportEmpty -> getString(R.string.backup_export_empty)
                            is BackupEvent.ImportSuccess -> getString(R.string.backup_import_success, ev.count)
                            is BackupEvent.Error -> getString(R.string.backup_error, ev.message)
                        }
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
