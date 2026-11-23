package com.example.ui.fragment

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.AppNavigator
import com.example.R
import com.example.data.local.AppLanguage
import com.example.databinding.FragmentSettingsBinding
import com.example.ui.viewmodel.BackupEvent
import com.example.ui.viewmodel.SettingsViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()

    private var suppressRadio = false

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
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? AppNavigator)?.configureToolbar(
            title = getString(R.string.settings_title),
            showBack = false,
            showCreatePackAction = false
        )

        binding.rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            if (suppressRadio) return@setOnCheckedChangeListener
            val selected = when (checkedId) {
                R.id.rb_lang_es -> AppLanguage.SPANISH
                R.id.rb_lang_en -> AppLanguage.ENGLISH
                else -> AppLanguage.SYSTEM
            }
            viewModel.setLanguage(selected)
        }

        binding.btnClearCache.setOnClickListener {
            viewModel.clearCache {
                Snackbar.make(binding.root, R.string.settings_cache_cleared, Snackbar.LENGTH_SHORT).show()
            }
        }

        binding.btnSettingsExport.setOnClickListener {
            exportLauncher.launch("emotepack_backup.zip")
        }

        binding.btnSettingsImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }

        binding.btnOpenAppInfo.setOnClickListener {
            (activity as? AppNavigator)?.openAppInfo()
        }

        binding.btnOpenPrivacy.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.privacy_policy_title)
                .setMessage(R.string.privacy_policy_body)
                .setPositiveButton(R.string.ok, null)
                .show()
        }

        viewModel.refreshStorageStats()
        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.language.collect { lang ->
                        suppressRadio = true
                        val checkedId = when (lang) {
                            AppLanguage.SYSTEM -> R.id.rb_lang_system
                            AppLanguage.SPANISH -> R.id.rb_lang_es
                            AppLanguage.ENGLISH -> R.id.rb_lang_en
                        }
                        binding.rgLanguage.check(checkedId)
                        suppressRadio = false

                        val label = when (lang) {
                            AppLanguage.SYSTEM -> getString(R.string.lang_system)
                            AppLanguage.SPANISH -> getString(R.string.lang_es)
                            AppLanguage.ENGLISH -> getString(R.string.lang_en)
                        }
                        binding.tvCurrentLanguage.text = getString(R.string.settings_language_current, label)
                    }
                }

                launch {
                    viewModel.storageStats.collect { stats ->
                        val ctx = requireContext()
                        binding.tvStorageCollections.text = getString(
                            R.string.settings_storage_collections,
                            Formatter.formatFileSize(ctx, stats.collectionsBytes)
                        )
                        binding.tvStorageCache.text = getString(
                            R.string.settings_storage_cache,
                            Formatter.formatFileSize(ctx, stats.cacheBytes)
                        )
                        binding.tvStorageFree.text = getString(
                            R.string.settings_storage_free,
                            Formatter.formatFileSize(ctx, stats.freeDeviceBytes)
                        )
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
