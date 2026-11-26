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
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.AppNavigator
import com.example.R
import com.example.databinding.FragmentMyPacksBinding
import com.example.ui.adapter.CollectionPackAdapter
import com.example.ui.viewmodel.MyPacksViewModel
import kotlinx.coroutines.launch

class MyPacksFragment : Fragment() {

    private var _binding: FragmentMyPacksBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MyPacksViewModel by viewModels()

    private lateinit var packAdapter: CollectionPackAdapter

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
            title = getString(R.string.app_name),
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
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
