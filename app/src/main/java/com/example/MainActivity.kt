package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.example.data.model.SearchOrigin
import com.example.data.model.SearchOriginType
import com.example.data.model.SevenTvUser
import com.example.databinding.ActivityMainBinding
import com.example.ui.fragment.CollectionDetailFragment
import com.example.ui.fragment.CreateCollectionFragment
import com.example.ui.fragment.EmoteSearchFragment
import com.example.ui.fragment.HomeFragment
import com.example.ui.fragment.MyPacksFragment
import com.example.ui.fragment.SettingsFragment
import com.example.ui.fragment.StreamerEmotesFragment
import com.google.android.material.tabs.TabLayout

interface AppNavigator {
    fun openHome()
    fun openMyPacks()
    fun openSettings()
    fun openStreamerEmotes(user: SevenTvUser, initialSetId: String? = null, startFreshSession: Boolean = true)
    fun openEmoteSearch(query: String, startFreshSession: Boolean = true)
    fun openCreateCollection(suggestedName: String)
    fun openBuildProgress(collectionId: String)
    fun openCollectionDetail(
        collectionId: String,
        isCreationSummary: Boolean = false,
        popCurrentProcessing: Boolean = false
    )
    fun openEditFromOrigin(origin: SearchOrigin)
    fun configureToolbar(
        title: String? = null,
        showBack: Boolean = false,
        showCreatePackAction: Boolean = false,
        selectedCount: Int = 0,
        isEditing: Boolean = false,
        onCreatePackClick: (() -> Unit)? = null
    )
}

class MainActivity : AppCompatActivity(), AppNavigator {

    companion object {
        private const val KEY_ACTIVE_TAB = "active_root_tab"
        private const val TAB_HOME = 0
        private const val TAB_MY_PACKS = 1
    }

    private lateinit var binding: ActivityMainBinding
    private var currentCreateAction: (() -> Unit)? = null
    private var activeRootTabIndex: Int = TAB_HOME
    private var suppressTabSelection = false

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notification permission is optional; worker proceeds regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        activeRootTabIndex = savedInstanceState?.getInt(KEY_ACTIVE_TAB, TAB_HOME) ?: TAB_HOME

        applyEdgeToEdgeInsets()
        setupTopNavigationAndToolbar()
        setupBackNavigation()
        requestNotificationsIfNeeded()

        if (savedInstanceState == null) {
            navigateRoot(HomeFragment(), TAB_HOME)
        } else {
            selectTopTabSilently(activeRootTabIndex)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_ACTIVE_TAB, activeRootTabIndex)
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun applyEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.fragmentContainer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun setupTopNavigationAndToolbar() {
        binding.topTabLayout.apply {
            addTab(newTab().setText(R.string.nav_home))
            addTab(newTab().setText(R.string.nav_my_packs))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (suppressTabSelection) return
                    when (tab.position) {
                        TAB_HOME -> if (activeRootTabIndex != TAB_HOME || supportFragmentManager.backStackEntryCount > 0) {
                            openHome()
                        }
                        TAB_MY_PACKS -> if (activeRootTabIndex != TAB_MY_PACKS || supportFragmentManager.backStackEntryCount > 0) {
                            openMyPacks()
                        }
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }

        binding.toolbar.setNavigationOnClickListener {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
            }
        }

        binding.btnToolbarSettings.setOnClickListener {
            openSettings()
        }

        binding.toolbarCreatePackBtn.setOnClickListener {
            currentCreateAction?.invoke()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun selectTopTabSilently(tabIndex: Int) {
        val tab = binding.topTabLayout.getTabAt(tabIndex) ?: return
        if (!tab.isSelected) {
            suppressTabSelection = true
            tab.select()
            suppressTabSelection = false
        }
    }

    private fun navigateRoot(fragment: Fragment, tabIndex: Int) {
        activeRootTabIndex = tabIndex
        selectTopTabSilently(tabIndex)
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun pushFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    override fun openHome() = navigateRoot(HomeFragment(), TAB_HOME)
    override fun openMyPacks() = navigateRoot(MyPacksFragment(), TAB_MY_PACKS)
    override fun openSettings() = pushFragment(SettingsFragment())

    override fun openStreamerEmotes(user: SevenTvUser, initialSetId: String?, startFreshSession: Boolean) {
        val app = application as EmotePackApplication
        if (startFreshSession) {
            app.selectionSession.startFreshSession(
                SearchOrigin(
                    type = SearchOriginType.STREAMER,
                    query = user.displayName,
                    streamerId = user.id,
                    streamerName = user.displayName,
                    streamerUsername = user.username,
                    streamerAvatarUrl = user.avatarUrl,
                    emoteSetId = initialSetId
                )
            )
        }
        pushFragment(
            StreamerEmotesFragment.newInstance(
                userId = user.id,
                displayName = user.displayName,
                username = user.username,
                avatarUrl = user.avatarUrl,
                initialSetId = initialSetId
            )
        )
    }

    override fun openEmoteSearch(query: String, startFreshSession: Boolean) {
        val app = application as EmotePackApplication
        if (startFreshSession) {
            app.selectionSession.startFreshSession(
                SearchOrigin(type = SearchOriginType.EMOTE, query = query)
            )
        }
        pushFragment(EmoteSearchFragment.newInstance(query))
    }

    override fun openCreateCollection(suggestedName: String) {
        pushFragment(CreateCollectionFragment.newForSelection(suggestedName))
    }

    override fun openBuildProgress(collectionId: String) {
        pushFragment(CreateCollectionFragment.newForExistingBuild(collectionId))
    }

    override fun openCollectionDetail(
        collectionId: String,
        isCreationSummary: Boolean,
        popCurrentProcessing: Boolean
    ) {
        if (popCurrentProcessing && supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        }
        pushFragment(CollectionDetailFragment.newInstance(collectionId, isCreationSummary))
    }

    override fun openEditFromOrigin(origin: SearchOrigin) {
        if (origin.type == SearchOriginType.STREAMER && !origin.streamerId.isNullOrBlank()) {
            val user = SevenTvUser(
                id = origin.streamerId,
                username = origin.streamerUsername ?: origin.streamerName.orEmpty(),
                displayName = origin.streamerName ?: origin.query,
                avatarUrl = origin.streamerAvatarUrl
            )
            openStreamerEmotes(user, initialSetId = origin.emoteSetId, startFreshSession = false)
        } else {
            openEmoteSearch(origin.query, startFreshSession = false)
        }
    }

    override fun configureToolbar(
        title: String?,
        showBack: Boolean,
        showCreatePackAction: Boolean,
        selectedCount: Int,
        isEditing: Boolean,
        onCreatePackClick: (() -> Unit)?
    ) {
        binding.toolbarTitle.text = title ?: getString(R.string.app_name)
        binding.toolbarLogo.visibility = if (showBack) View.GONE else View.VISIBLE
        binding.topTabLayout.visibility = if (showBack) View.GONE else View.VISIBLE

        if (showBack) {
            binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            binding.toolbar.navigationContentDescription = getString(R.string.nav_back)
        } else {
            binding.toolbar.navigationIcon = null
            binding.toolbar.navigationContentDescription = null
            selectTopTabSilently(activeRootTabIndex)
        }

        currentCreateAction = onCreatePackClick
        if (showCreatePackAction) {
            binding.toolbarCreatePackBtn.visibility = View.VISIBLE
            binding.btnToolbarSettings.visibility = View.GONE
            val baseLabel = getString(if (isEditing) R.string.save_edits_action else R.string.create_pack_action)
            binding.toolbarCreatePackBtn.text = if (selectedCount > 0) {
                "$baseLabel ($selectedCount)"
            } else {
                baseLabel
            }
        } else {
            binding.toolbarCreatePackBtn.visibility = View.GONE
            binding.btnToolbarSettings.visibility = if (showBack) View.GONE else View.VISIBLE
        }
    }
}
