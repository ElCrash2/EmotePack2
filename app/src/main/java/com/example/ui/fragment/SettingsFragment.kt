package com.example.ui.fragment

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.AppNavigator
import com.example.BuildConfig
import com.example.R
import com.example.data.local.AppLanguage
import com.example.databinding.FragmentSettingsBinding
import com.example.ui.viewmodel.SettingsViewModel
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()

    private var suppressRadio = false

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
            showBack = true,
            showCreatePackAction = false
        )

        binding.tvAppVersion.text = getString(R.string.info_version, BuildConfig.VERSION_NAME)

        binding.rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            if (suppressRadio) return@setOnCheckedChangeListener
            val selected = when (checkedId) {
                R.id.rb_lang_es -> AppLanguage.SPANISH
                R.id.rb_lang_en -> AppLanguage.ENGLISH
                R.id.rb_lang_pt -> AppLanguage.PORTUGUESE
                R.id.rb_lang_ru -> AppLanguage.RUSSIAN
                R.id.rb_lang_fr -> AppLanguage.FRENCH
                R.id.rb_lang_de -> AppLanguage.GERMAN
                R.id.rb_lang_hi -> AppLanguage.HINDI
                R.id.rb_lang_id -> AppLanguage.INDONESIAN
                else -> AppLanguage.SYSTEM
            }
            viewModel.setLanguage(selected)
        }

        binding.btnClearCache.setOnClickListener {
            viewModel.clearCache {
                Snackbar.make(binding.root, R.string.settings_cache_cleared, Snackbar.LENGTH_SHORT).show()
            }
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
                            AppLanguage.PORTUGUESE -> R.id.rb_lang_pt
                            AppLanguage.RUSSIAN -> R.id.rb_lang_ru
                            AppLanguage.FRENCH -> R.id.rb_lang_fr
                            AppLanguage.GERMAN -> R.id.rb_lang_de
                            AppLanguage.HINDI -> R.id.rb_lang_hi
                            AppLanguage.INDONESIAN -> R.id.rb_lang_id
                        }
                        binding.rgLanguage.check(checkedId)
                        suppressRadio = false

                        val label = when (lang) {
                            AppLanguage.SYSTEM -> getString(R.string.lang_system)
                            AppLanguage.SPANISH -> getString(R.string.lang_es)
                            AppLanguage.ENGLISH -> getString(R.string.lang_en)
                            AppLanguage.PORTUGUESE -> getString(R.string.lang_pt)
                            AppLanguage.RUSSIAN -> getString(R.string.lang_ru)
                            AppLanguage.FRENCH -> getString(R.string.lang_fr)
                            AppLanguage.GERMAN -> getString(R.string.lang_de)
                            AppLanguage.HINDI -> getString(R.string.lang_hi)
                            AppLanguage.INDONESIAN -> getString(R.string.lang_id)
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
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
