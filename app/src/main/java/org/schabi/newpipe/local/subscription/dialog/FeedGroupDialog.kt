package org.schabi.newpipe.local.subscription.dialog

import android.app.Dialog
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.OnItemClickListener
import com.xwray.groupie.Section
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedContentSelection
import org.schabi.newpipe.database.feed.model.FeedContentType
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.FeedGroupSubscriptionEntity
import org.schabi.newpipe.databinding.DialogFeedGroupCreateBinding
import org.schabi.newpipe.databinding.FeedGroupContentRuleItemBinding
import org.schabi.newpipe.databinding.ToolbarSearchLayoutBinding
import org.schabi.newpipe.fragments.BackPressable
import org.schabi.newpipe.local.subscription.FeedGroupIcon
import org.schabi.newpipe.local.subscription.dialog.FeedGroupDialog.ScreenState.ContentRuleSubscriptionsScreen
import org.schabi.newpipe.local.subscription.dialog.FeedGroupDialog.ScreenState.ContentRulesScreen
import org.schabi.newpipe.local.subscription.dialog.FeedGroupDialog.ScreenState.DeleteScreen
import org.schabi.newpipe.local.subscription.dialog.FeedGroupDialog.ScreenState.IconPickerScreen
import org.schabi.newpipe.local.subscription.dialog.FeedGroupDialog.ScreenState.InitialScreen
import org.schabi.newpipe.local.subscription.dialog.FeedGroupDialog.ScreenState.SubscriptionsPickerScreen
import org.schabi.newpipe.local.subscription.dialog.FeedGroupDialogViewModel.DialogEvent.ProcessingEvent
import org.schabi.newpipe.local.subscription.dialog.FeedGroupDialogViewModel.DialogEvent.SuccessEvent
import org.schabi.newpipe.local.subscription.item.EmptyPlaceholderItem
import org.schabi.newpipe.local.subscription.item.PickerIconItem
import org.schabi.newpipe.local.subscription.item.PickerSubscriptionItem
import org.schabi.newpipe.util.DeviceUtils
import org.schabi.newpipe.util.ThemeHelper
import java.io.Serializable

class FeedGroupDialog : DialogFragment(), BackPressable {
    private var _feedGroupCreateBinding: DialogFeedGroupCreateBinding? = null
    private val feedGroupCreateBinding get() = _feedGroupCreateBinding!!

    private var _searchLayoutBinding: ToolbarSearchLayoutBinding? = null
    private val searchLayoutBinding get() = _searchLayoutBinding!!

    private lateinit var viewModel: FeedGroupDialogViewModel
    private var groupId: Long = NO_GROUP_SELECTED
    private var groupIcon: FeedGroupIcon? = null
    private var groupSortOrder: Long = -1

    sealed class ScreenState : Serializable {
        data object InitialScreen : ScreenState()
        data object IconPickerScreen : ScreenState()
        data object SubscriptionsPickerScreen : ScreenState()
        data object ContentRulesScreen : ScreenState()
        data object ContentRuleSubscriptionsScreen : ScreenState()
        data object DeleteScreen : ScreenState()
    }

    @JvmField var selectedIcon: FeedGroupIcon? = null
    @JvmField var selectedSubscriptions: HashSet<Long> = HashSet()
    @JvmField var wasSubscriptionSelectionChanged: Boolean = false
    @JvmField var selectedContentSelectionMask: Int = FeedContentSelection.ALL.mask
    @JvmField var contentSelectionOverrides: HashMap<Long, Int> = HashMap()
    @JvmField var wasContentRulesChanged: Boolean = false
    @JvmField var editingContentSelectionMask: Int = 0
    @JvmField var editingRuleOriginalSubscriptions: HashSet<Long> = HashSet()
    @JvmField var editingRuleSelectedSubscriptions: HashSet<Long> = HashSet()
    @JvmField var currentScreen: ScreenState = InitialScreen

    @JvmField var subscriptionsListState: Parcelable? = null
    @JvmField var iconsListState: Parcelable? = null
    @JvmField var wasSearchSubscriptionsVisible = false
    @JvmField var subscriptionsCurrentSearchQuery = ""
    @JvmField var subscriptionsShowOnlyUngrouped = false

    private val subscriptionMainSection = Section()
    private val subscriptionEmptyFooter = Section()
    private lateinit var subscriptionGroupAdapter: GroupieAdapter
    private var latestMembershipSubscriptions: List<PickerSubscriptionItem> = emptyList()
    private var latestContentRuleSubscriptions: List<PickerSubscriptionItem> = emptyList()
    private var contentDefaultsLoaded = false
    private var contentMembershipsLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            selectedIcon = savedInstanceState.getString("selectedIcon")?.let { FeedGroupIcon.valueOf(it) }
            selectedSubscriptions = HashSet(savedInstanceState.getLongArray("selectedSubscriptions")?.toList() ?: emptyList())
            wasSubscriptionSelectionChanged = savedInstanceState.getBoolean("wasSubscriptionSelectionChanged", false)
            selectedContentSelectionMask = savedInstanceState.getInt(
                "selectedContentSelectionMask",
                FeedContentSelection.ALL.mask
            )
            contentSelectionOverrides = HashMap<Long, Int>().apply {
                val subscriptionIds = savedInstanceState.getLongArray("contentOverrideIds")
                    ?: longArrayOf()
                val selectionMasks = savedInstanceState.getIntArray("contentOverrideMasks")
                    ?: intArrayOf()
                subscriptionIds.zip(selectionMasks.toTypedArray()).forEach { (id, mask) ->
                    put(id, mask)
                }
            }
            wasContentRulesChanged = savedInstanceState.getBoolean(
                "wasContentRulesChanged",
                false
            )
            editingContentSelectionMask = savedInstanceState.getInt(
                "editingContentSelectionMask",
                0
            )
            editingRuleOriginalSubscriptions = HashSet(
                savedInstanceState.getLongArray("editingRuleOriginalSubscriptions")
                    ?.toList().orEmpty()
            )
            editingRuleSelectedSubscriptions = HashSet(
                savedInstanceState.getLongArray("editingRuleSelectedSubscriptions")
                    ?.toList().orEmpty()
            )
            currentScreen = savedInstanceState.getSerializable("currentScreen") as? ScreenState ?: InitialScreen
            subscriptionsListState = savedInstanceState.getParcelable("subscriptionsListState")
            iconsListState = savedInstanceState.getParcelable("iconsListState")
            wasSearchSubscriptionsVisible = savedInstanceState.getBoolean("wasSearchSubscriptionsVisible", false)
            subscriptionsCurrentSearchQuery = savedInstanceState.getString("subscriptionsCurrentSearchQuery", "")
            subscriptionsShowOnlyUngrouped = savedInstanceState.getBoolean("subscriptionsShowOnlyUngrouped", false)
        }

        setStyle(STYLE_NO_TITLE, ThemeHelper.getMinWidthDialogTheme(requireContext()))
        groupId = arguments?.getLong(KEY_GROUP_ID, NO_GROUP_SELECTED) ?: NO_GROUP_SELECTED
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_feed_group_create, container)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return object : Dialog(requireActivity(), theme) {
            override fun onBackPressed() {
                if (!this@FeedGroupDialog.onBackPressed()) {
                    super.onBackPressed()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()

        wasSearchSubscriptionsVisible = isSearchVisible()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        iconsListState = feedGroupCreateBinding.iconSelector.layoutManager?.onSaveInstanceState()
        subscriptionsListState = feedGroupCreateBinding.subscriptionsSelectorList.layoutManager?.onSaveInstanceState()

        outState.putString("selectedIcon", selectedIcon?.name)
        outState.putLongArray("selectedSubscriptions", selectedSubscriptions.toLongArray())
        outState.putBoolean("wasSubscriptionSelectionChanged", wasSubscriptionSelectionChanged)
        outState.putInt("selectedContentSelectionMask", selectedContentSelectionMask)
        val contentOverrideEntries = contentSelectionOverrides.entries.toList()
        outState.putLongArray(
            "contentOverrideIds",
            contentOverrideEntries.map { it.key }.toLongArray()
        )
        outState.putIntArray(
            "contentOverrideMasks",
            contentOverrideEntries.map { it.value }.toIntArray()
        )
        outState.putBoolean("wasContentRulesChanged", wasContentRulesChanged)
        outState.putInt("editingContentSelectionMask", editingContentSelectionMask)
        outState.putLongArray(
            "editingRuleOriginalSubscriptions",
            editingRuleOriginalSubscriptions.toLongArray()
        )
        outState.putLongArray(
            "editingRuleSelectedSubscriptions",
            editingRuleSelectedSubscriptions.toLongArray()
        )
        outState.putSerializable("currentScreen", currentScreen)
        outState.putParcelable("subscriptionsListState", subscriptionsListState)
        outState.putParcelable("iconsListState", iconsListState)
        outState.putBoolean("wasSearchSubscriptionsVisible", wasSearchSubscriptionsVisible)
        outState.putString("subscriptionsCurrentSearchQuery", subscriptionsCurrentSearchQuery)
        outState.putBoolean("subscriptionsShowOnlyUngrouped", subscriptionsShowOnlyUngrouped)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _feedGroupCreateBinding = DialogFeedGroupCreateBinding.bind(view)
        _searchLayoutBinding = feedGroupCreateBinding.subscriptionsHeaderSearchContainer

        viewModel = ViewModelProvider(
            this,
            FeedGroupDialogViewModel.Factory(
                requireContext(),
                groupId,
                subscriptionsCurrentSearchQuery,
                subscriptionsShowOnlyUngrouped
            )
        ).get(FeedGroupDialogViewModel::class.java)

        viewModel.groupLiveData.observe(viewLifecycleOwner, Observer(::handleGroup))
        viewModel.subscriptionsLiveData.observe(viewLifecycleOwner) {
            setupSubscriptionPicker(
                it.membershipSubscriptions,
                it.contentRuleSubscriptions,
                it.memberships
            )
        }
        viewModel.dialogEventLiveData.observe(viewLifecycleOwner) {
            when (it) {
                ProcessingEvent -> disableInput()
                SuccessEvent -> dismiss()
            }
        }

        subscriptionGroupAdapter = GroupieAdapter().apply {
            add(subscriptionMainSection)
            add(subscriptionEmptyFooter)
            spanCount = 4
        }
        feedGroupCreateBinding.subscriptionsSelectorList.apply {
            // Disable animations, too distracting.
            itemAnimator = null
            adapter = subscriptionGroupAdapter
            layoutManager = GridLayoutManager(
                requireContext(), subscriptionGroupAdapter.spanCount,
                RecyclerView.VERTICAL, false
            ).apply {
                spanSizeLookup = subscriptionGroupAdapter.spanSizeLookup
            }
        }

        setupIconPicker()
        setupContentRules()
        setupListeners()

        showScreen(currentScreen)

        if (currentScreen in listOf(
                SubscriptionsPickerScreen,
                ContentRuleSubscriptionsScreen
            ) && wasSearchSubscriptionsVisible
        ) {
            showSearch()
        } else if (currentScreen == InitialScreen && groupId == NO_GROUP_SELECTED) {
            showKeyboard()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        feedGroupCreateBinding.subscriptionsSelectorList.adapter = null
        feedGroupCreateBinding.iconSelector.adapter = null

        _feedGroupCreateBinding = null
        _searchLayoutBinding = null
    }

    /*/​//////////////////////////////////////////////////////////////////////////
    // Setup
    //​//////////////////////////////////////////////////////////////////////// */

    override fun onBackPressed(): Boolean {
        if (currentScreen in listOf(
                SubscriptionsPickerScreen,
                ContentRuleSubscriptionsScreen
            ) && isSearchVisible()
        ) {
            hideSearch()
            return true
        } else if (currentScreen is ContentRuleSubscriptionsScreen) {
            showScreen(ContentRulesScreen)
            return true
        } else if (currentScreen !is InitialScreen) {
            showScreen(InitialScreen)
            return true
        }

        return false
    }

    private fun setupListeners() {
        feedGroupCreateBinding.deleteButton.setOnClickListener { showScreen(DeleteScreen) }

        feedGroupCreateBinding.cancelButton.setOnClickListener {
            when (currentScreen) {
                InitialScreen -> dismiss()
                ContentRuleSubscriptionsScreen -> showScreen(ContentRulesScreen)
                else -> showScreen(InitialScreen)
            }
        }

        feedGroupCreateBinding.groupNameInputContainer.error = null
        feedGroupCreateBinding.groupNameInput.doOnTextChanged { text, _, _, _ ->
            if (feedGroupCreateBinding.groupNameInputContainer.isErrorEnabled && !text.isNullOrBlank()) {
                feedGroupCreateBinding.groupNameInputContainer.error = null
            }
        }

        feedGroupCreateBinding.confirmButton.setOnClickListener { handlePositiveButton() }

        feedGroupCreateBinding.selectChannelButton.setOnClickListener {
            feedGroupCreateBinding.subscriptionsSelectorList.scrollToPosition(0)
            showScreen(SubscriptionsPickerScreen)
        }

        feedGroupCreateBinding.contentRulesButton.setOnClickListener {
            showScreen(ContentRulesScreen)
        }

        val headerMenu = feedGroupCreateBinding.subscriptionsHeaderToolbar.menu
        requireActivity().menuInflater.inflate(R.menu.menu_feed_group_dialog, headerMenu)

        headerMenu.findItem(R.id.action_search).setOnMenuItemClickListener {
            showSearch()
            true
        }

        headerMenu.findItem(R.id.feed_group_toggle_show_only_ungrouped_subscriptions).apply {
            isChecked = subscriptionsShowOnlyUngrouped
            setOnMenuItemClickListener {
                subscriptionsShowOnlyUngrouped = !subscriptionsShowOnlyUngrouped
                it.isChecked = subscriptionsShowOnlyUngrouped
                viewModel.toggleShowOnlyUngrouped(subscriptionsShowOnlyUngrouped)
                true
            }
        }

        searchLayoutBinding.toolbarSearchClear.setOnClickListener {
            if (searchLayoutBinding.toolbarSearchEditText.text.isNullOrEmpty()) {
                hideSearch()
                return@setOnClickListener
            }
            resetSearch()
            showKeyboardSearch()
        }

        searchLayoutBinding.toolbarSearchEditText.setOnClickListener {
            if (DeviceUtils.isTv(context)) {
                showKeyboardSearch()
            }
        }

        searchLayoutBinding.toolbarSearchEditText.doOnTextChanged { _, _, _, _ ->
            val newQuery: String = searchLayoutBinding.toolbarSearchEditText.text.toString()
            subscriptionsCurrentSearchQuery = newQuery
            viewModel.filterSubscriptionsBy(newQuery)
        }

        subscriptionGroupAdapter.setOnItemClickListener(subscriptionPickerItemListener)
    }

    private fun handlePositiveButton() = when {
        currentScreen is InitialScreen -> handlePositiveButtonInitialScreen()
        currentScreen is DeleteScreen -> viewModel.deleteGroup()
        currentScreen in listOf(
            SubscriptionsPickerScreen,
            ContentRuleSubscriptionsScreen
        ) && isSearchVisible() -> hideSearch()
        currentScreen is ContentRuleSubscriptionsScreen -> {
            applyEditingContentRule()
            showScreen(ContentRulesScreen)
        }
        else -> showScreen(InitialScreen)
    }

    private fun handlePositiveButtonInitialScreen() {
        if (!contentRulesLoaded()) return
        val name = feedGroupCreateBinding.groupNameInput.text.toString().trim()
        val icon = selectedIcon ?: groupIcon ?: FeedGroupIcon.ALL

        if (name.isBlank()) {
            feedGroupCreateBinding.groupNameInputContainer.error = getString(R.string.feed_group_dialog_empty_name)
            feedGroupCreateBinding.groupNameInput.text = null
            feedGroupCreateBinding.groupNameInput.requestFocus()
            return
        } else {
            feedGroupCreateBinding.groupNameInputContainer.error = null
        }

        if (selectedSubscriptions.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.feed_group_dialog_empty_selection), Toast.LENGTH_SHORT).show()
            return
        }

        when (groupId) {
            NO_GROUP_SELECTED -> viewModel.createGroup(
                name,
                icon,
                selectedSubscriptions,
                selectedContentSelection(),
                selectedContentOverrides()
            )
            else -> viewModel.updateGroup(
                name,
                icon,
                selectedSubscriptions,
                groupSortOrder,
                selectedContentSelection(),
                selectedContentOverrides()
            )
        }
    }

    private fun handleGroup(feedGroupEntity: FeedGroupEntity? = null) {
        contentDefaultsLoaded = true
        val icon = feedGroupEntity?.icon ?: FeedGroupIcon.ALL
        val name = feedGroupEntity?.name ?: ""
        groupIcon = feedGroupEntity?.icon
        groupSortOrder = feedGroupEntity?.sortOrder ?: -1
        if (!wasContentRulesChanged) {
            selectedContentSelectionMask = feedGroupEntity?.contentSelection?.mask
                ?: FeedContentSelection.ALL.mask
        }
        updateContentRulesUi()

        val feedGroupIcon = if (selectedIcon == null) icon else selectedIcon!!
        feedGroupCreateBinding.iconPreview.setImageResource(feedGroupIcon.getDrawableRes())

        if (feedGroupCreateBinding.groupNameInput.text.isNullOrBlank()) {
            feedGroupCreateBinding.groupNameInput.setText(name)
        }
    }

    private val subscriptionPickerItemListener = OnItemClickListener { item, view ->
        if (item is PickerSubscriptionItem) {
            val subscriptionId = item.subscriptionEntity.uid
            val selectedIds = if (currentScreen is ContentRuleSubscriptionsScreen) {
                editingRuleSelectedSubscriptions
            } else {
                wasSubscriptionSelectionChanged = true
                selectedSubscriptions
            }

            val isSelected = if (subscriptionId in selectedIds) {
                selectedIds.remove(subscriptionId)
                false
            } else {
                selectedIds.add(subscriptionId)
                true
            }

            item.updateSelected(view, isSelected)
            updateSubscriptionSelectedCount()
        }
    }

    private fun setupSubscriptionPicker(
        membershipSubscriptions: List<PickerSubscriptionItem>,
        contentRuleSubscriptions: List<PickerSubscriptionItem>,
        memberships: List<FeedGroupSubscriptionEntity>
    ) {
        contentMembershipsLoaded = true
        latestMembershipSubscriptions = membershipSubscriptions
        latestContentRuleSubscriptions = contentRuleSubscriptions
        if (!wasSubscriptionSelectionChanged) {
            this.selectedSubscriptions.addAll(memberships.map { it.subscriptionId })
        }
        if (!wasContentRulesChanged) {
            contentSelectionOverrides.clear()
            memberships.forEach { membership ->
                membership.contentSelectionOverride?.let {
                    contentSelectionOverrides[membership.subscriptionId] = it.mask
                }
            }
        }

        renderSubscriptionPicker()
        updateContentRulesUi()
    }

    private fun renderSubscriptionPicker() {
        updateSubscriptionSelectedCount()

        val isEditingContentRule = currentScreen is ContentRuleSubscriptionsScreen
        val subscriptions = subscriptionPickerCandidates(
            currentScreen,
            latestMembershipSubscriptions,
            latestContentRuleSubscriptions,
            selectedSubscriptions
        )
        val selectedIds = if (isEditingContentRule) {
            editingRuleSelectedSubscriptions
        } else {
            selectedSubscriptions
        }

        if (subscriptions.isEmpty()) {
            subscriptionEmptyFooter.clear()
            subscriptionEmptyFooter.add(EmptyPlaceholderItem())
        } else {
            subscriptionEmptyFooter.clear()
        }

        subscriptionMainSection.update(
            subscriptionPickerPresentationItems(subscriptions, selectedIds),
            false
        )

        if (subscriptionsListState != null) {
            feedGroupCreateBinding.subscriptionsSelectorList.layoutManager?.onRestoreInstanceState(subscriptionsListState)
            subscriptionsListState = null
        } else {
            feedGroupCreateBinding.subscriptionsSelectorList.scrollToPosition(0)
        }
    }

    private fun updateSubscriptionSelectedCount() {
        val selectedCount = if (currentScreen is ContentRuleSubscriptionsScreen) {
            editingRuleSelectedSubscriptions.size
        } else {
            selectedSubscriptions.size
        }
        val selectedCountText = resources.getQuantityString(
            R.plurals.feed_group_dialog_selection_count,
            selectedCount, selectedCount
        )
        feedGroupCreateBinding.selectedSubscriptionCountView.text = selectedCountText
        feedGroupCreateBinding.subscriptionsHeaderInfo.text = selectedCountText
    }

    private fun setupContentRules() {
        feedGroupCreateBinding.defaultContentVideos.setOnClickListener {
            updateDefaultContentSelection(it as android.widget.CheckBox)
        }
        feedGroupCreateBinding.defaultContentShorts.setOnClickListener {
            updateDefaultContentSelection(it as android.widget.CheckBox)
        }
        feedGroupCreateBinding.defaultContentLive.setOnClickListener {
            updateDefaultContentSelection(it as android.widget.CheckBox)
        }
        feedGroupCreateBinding.addContentExceptionButton.setOnClickListener {
            showContentSelectionDialog()
        }
        updateContentRulesUi()
    }

    private fun updateDefaultContentSelection(changedCheckbox: android.widget.CheckBox) {
        val mask = defaultContentSelectionMaskFromUi()
        if (mask == 0) {
            changedCheckbox.isChecked = true
            Toast.makeText(
                requireContext(),
                R.string.feed_group_content_nonempty,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        selectedContentSelectionMask = mask
        wasContentRulesChanged = true
        // Normalize on save; intermediate defaults must not erase channel exceptions.
        updateContentRulesUi()
    }

    private fun defaultContentSelectionMaskFromUi(): Int {
        var mask = 0
        if (feedGroupCreateBinding.defaultContentVideos.isChecked) {
            mask = mask or FeedContentType.VIDEOS.mask
        }
        if (feedGroupCreateBinding.defaultContentShorts.isChecked) {
            mask = mask or FeedContentType.SHORTS.mask
        }
        if (feedGroupCreateBinding.defaultContentLive.isChecked) {
            mask = mask or FeedContentType.LIVE.mask
        }
        return mask
    }

    private fun contentRulesLoaded() = groupId == NO_GROUP_SELECTED
        || (contentDefaultsLoaded && contentMembershipsLoaded)

    private fun updateContentRulesUi() {
        if (_feedGroupCreateBinding == null) {
            return
        }

        val loaded = contentRulesLoaded()
        listOf(feedGroupCreateBinding.contentRulesButton,
            feedGroupCreateBinding.defaultContentVideos,
            feedGroupCreateBinding.defaultContentShorts,
            feedGroupCreateBinding.defaultContentLive,
            feedGroupCreateBinding.addContentExceptionButton
        ).forEach { it.isEnabled = loaded }

        val defaultSelection = selectedContentSelection()
        feedGroupCreateBinding.defaultContentVideos.isChecked =
            defaultSelection.contains(FeedContentType.VIDEOS)
        feedGroupCreateBinding.defaultContentShorts.isChecked =
            defaultSelection.contains(FeedContentType.SHORTS)
        feedGroupCreateBinding.defaultContentLive.isChecked =
            defaultSelection.contains(FeedContentType.LIVE)
        feedGroupCreateBinding.contentRulesButton.text = getString(
            R.string.feed_group_content_summary,
            contentSelectionLabel(defaultSelection)
        )

        val rows = feedGroupCreateBinding.contentExceptionRows
        rows.removeAllViews()
        selectedContentOverrides()
            .entries
            .groupBy({ it.value }, { it.key })
            .toSortedMap(compareBy { it.mask })
            .forEach { (selection, subscriptionIds) ->
                val binding = FeedGroupContentRuleItemBinding.inflate(
                    layoutInflater,
                    rows,
                    false
                )
                binding.editContentRuleButton.text = resources.getQuantityString(
                    R.plurals.feed_group_content_rule_summary,
                    subscriptionIds.size,
                    contentSelectionLabel(selection),
                    subscriptionIds.size
                )
                binding.editContentRuleButton.isEnabled = loaded
                binding.deleteContentRuleButton.isEnabled = loaded
                binding.editContentRuleButton.setOnClickListener {
                    beginEditingContentRule(selection)
                }
                binding.deleteContentRuleButton.setOnClickListener {
                    subscriptionIds.forEach(contentSelectionOverrides::remove)
                    wasContentRulesChanged = true
                    updateContentRulesUi()
                }
                rows.addView(binding.root)
            }
    }

    private fun showContentSelectionDialog() {
        val selections = (1..FeedContentSelection.ALL.mask)
            .map(FeedContentSelection::fromMask)
        val labels = selections.map(::contentSelectionLabel).toTypedArray()
        var selectedIndex = 0

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.feed_group_content_add_exception)
            .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val selection = selections[selectedIndex]
                if (selection == selectedContentSelection()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.feed_group_content_matches_default,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    beginEditingContentRule(selection)
                }
            }
            .show()
    }

    private fun beginEditingContentRule(selection: FeedContentSelection) {
        editingContentSelectionMask = selection.mask
        editingRuleOriginalSubscriptions = HashSet(
            contentSelectionOverrides.filterValues { it == selection.mask }.keys
        )
        editingRuleSelectedSubscriptions = HashSet(editingRuleOriginalSubscriptions)
        showScreen(ContentRuleSubscriptionsScreen)
        renderSubscriptionPicker()
    }

    private fun applyEditingContentRule() {
        editingRuleOriginalSubscriptions.forEach(contentSelectionOverrides::remove)
        editingRuleSelectedSubscriptions.forEach { subscriptionId ->
            contentSelectionOverrides[subscriptionId] = editingContentSelectionMask
        }
        wasContentRulesChanged = true
        editingContentSelectionMask = 0
        editingRuleOriginalSubscriptions.clear()
        editingRuleSelectedSubscriptions.clear()
        updateContentRulesUi()
    }

    private fun selectedContentSelection(): FeedContentSelection {
        return FeedContentSelection.fromMask(selectedContentSelectionMask)
    }

    private fun selectedContentOverrides(): Map<Long, FeedContentSelection> {
        return contentSelectionOverrides.mapValues { (_, mask) ->
            FeedContentSelection.fromMask(mask)
        }.filterKeys { it in selectedSubscriptions }
    }

    private fun contentSelectionLabel(selection: FeedContentSelection): String {
        return buildList {
            if (selection.contains(FeedContentType.VIDEOS)) {
                add(getString(R.string.channel_tab_videos))
            }
            if (selection.contains(FeedContentType.SHORTS)) {
                add(getString(R.string.channel_tab_shorts))
            }
            if (selection.contains(FeedContentType.LIVE)) {
                add(getString(R.string.channel_tab_livestreams))
            }
        }.joinToString(", ")
    }

    private fun setupIconPicker() {
        val groupAdapter = GroupieAdapter()
        groupAdapter.addAll(FeedGroupIcon.values().map { PickerIconItem(it) })

        feedGroupCreateBinding.iconSelector.apply {
            layoutManager = GridLayoutManager(requireContext(), 7, RecyclerView.VERTICAL, false)
            adapter = groupAdapter

            if (iconsListState != null) {
                layoutManager?.onRestoreInstanceState(iconsListState)
                iconsListState = null
            }
        }

        groupAdapter.setOnItemClickListener { item, _ ->
            when (item) {
                is PickerIconItem -> {
                    selectedIcon = item.icon
                    feedGroupCreateBinding.iconPreview.setImageResource(item.iconRes)

                    showScreen(InitialScreen)
                }
            }
        }
        feedGroupCreateBinding.iconPreview.setOnClickListener {
            feedGroupCreateBinding.iconSelector.scrollToPosition(0)
            showScreen(IconPickerScreen)
        }

        if (groupId == NO_GROUP_SELECTED) {
            val icon = selectedIcon ?: FeedGroupIcon.ALL
            feedGroupCreateBinding.iconPreview.setImageResource(icon.getDrawableRes())
        }
    }

    /*/​//////////////////////////////////////////////////////////////////////////
    // Screen Selector
    //​//////////////////////////////////////////////////////////////////////// */

    private fun showScreen(screen: ScreenState) {
        currentScreen = screen
        updateSubscriptionSelectedCount()

        feedGroupCreateBinding.optionsRoot.onlyVisibleIn(InitialScreen)
        feedGroupCreateBinding.contentRulesSelector.onlyVisibleIn(ContentRulesScreen)
        feedGroupCreateBinding.iconSelector.onlyVisibleIn(IconPickerScreen)
        feedGroupCreateBinding.subscriptionsSelector.onlyVisibleIn(
            SubscriptionsPickerScreen,
            ContentRuleSubscriptionsScreen
        )
        feedGroupCreateBinding.deleteScreenMessage.onlyVisibleIn(DeleteScreen)

        feedGroupCreateBinding.separator.onlyVisibleIn(
            SubscriptionsPickerScreen,
            ContentRuleSubscriptionsScreen,
            ContentRulesScreen,
            IconPickerScreen
        )
        feedGroupCreateBinding.cancelButton.onlyVisibleIn(InitialScreen, DeleteScreen)

        feedGroupCreateBinding.confirmButton.setText(
            when {
                currentScreen == InitialScreen && groupId == NO_GROUP_SELECTED -> R.string.create
                else -> R.string.ok
            }
        )

        feedGroupCreateBinding.deleteButton.isGone = currentScreen != InitialScreen || groupId == NO_GROUP_SELECTED

        feedGroupCreateBinding.subscriptionsHeaderTitle.setText(
            if (currentScreen is ContentRuleSubscriptionsScreen) {
                R.string.feed_group_content_exceptions
            } else {
                R.string.tab_subscriptions
            }
        )

        feedGroupCreateBinding.subscriptionsHeaderToolbar.menu
            .findItem(R.id.feed_group_toggle_show_only_ungrouped_subscriptions)
            .apply {
                isVisible = currentScreen is SubscriptionsPickerScreen
                isChecked = subscriptionsShowOnlyUngrouped
            }

        if (currentScreen in listOf(
                SubscriptionsPickerScreen,
                ContentRuleSubscriptionsScreen
            )
        ) {
            renderSubscriptionPicker()
        }

        hideKeyboard()
        hideSearch()
    }

    private fun View.onlyVisibleIn(vararg screens: ScreenState) {
        isVisible = currentScreen in screens
    }

    /*/​//////////////////////////////////////////////////////////////////////////
    // Utils
    //​//////////////////////////////////////////////////////////////////////// */

    private fun isSearchVisible() = _searchLayoutBinding?.root?.visibility == View.VISIBLE

    private fun resetSearch() {
        searchLayoutBinding.toolbarSearchEditText.setText("")
        subscriptionsCurrentSearchQuery = ""
        viewModel.clearSubscriptionsFilter()
    }

    private fun hideSearch() {
        resetSearch()
        searchLayoutBinding.root.visibility = View.GONE
        feedGroupCreateBinding.subscriptionsHeaderInfoContainer.visibility = View.VISIBLE
        feedGroupCreateBinding.subscriptionsHeaderToolbar.menu.findItem(R.id.action_search).isVisible = true
        hideKeyboardSearch()
    }

    private fun showSearch() {
        searchLayoutBinding.root.visibility = View.VISIBLE
        feedGroupCreateBinding.subscriptionsHeaderInfoContainer.visibility = View.GONE
        feedGroupCreateBinding.subscriptionsHeaderToolbar.menu.findItem(R.id.action_search).isVisible = false
        showKeyboardSearch()
    }

    private val inputMethodManager by lazy {
        requireActivity().getSystemService<InputMethodManager>()!!
    }

    private fun showKeyboardSearch() {
        if (searchLayoutBinding.toolbarSearchEditText.requestFocus()) {
            inputMethodManager.showSoftInput(
                searchLayoutBinding.toolbarSearchEditText,
                InputMethodManager.SHOW_IMPLICIT
            )
        }
    }

    private fun hideKeyboardSearch() {
        inputMethodManager.hideSoftInputFromWindow(
            searchLayoutBinding.toolbarSearchEditText.windowToken,
            InputMethodManager.RESULT_UNCHANGED_SHOWN
        )
        searchLayoutBinding.toolbarSearchEditText.clearFocus()
    }

    private fun showKeyboard() {
        if (feedGroupCreateBinding.groupNameInput.requestFocus()) {
            inputMethodManager.showSoftInput(
                feedGroupCreateBinding.groupNameInput,
                InputMethodManager.SHOW_IMPLICIT
            )
        }
    }

    private fun hideKeyboard() {
        inputMethodManager.hideSoftInputFromWindow(
            feedGroupCreateBinding.groupNameInput.windowToken,
            InputMethodManager.RESULT_UNCHANGED_SHOWN
        )
        feedGroupCreateBinding.groupNameInput.clearFocus()
    }

    private fun disableInput() {
        _feedGroupCreateBinding?.deleteButton?.isEnabled = false
        _feedGroupCreateBinding?.confirmButton?.isEnabled = false
        _feedGroupCreateBinding?.cancelButton?.isEnabled = false
        isCancelable = false

        hideKeyboard()
    }

    companion object {
        private const val KEY_GROUP_ID = "KEY_GROUP_ID"
        private const val NO_GROUP_SELECTED = -1L
        @JvmStatic
        fun newInstance(groupId: Long = NO_GROUP_SELECTED): FeedGroupDialog {
            val dialog = FeedGroupDialog()
            dialog.arguments = bundleOf(KEY_GROUP_ID to groupId)
            return dialog
        }
    }
}
