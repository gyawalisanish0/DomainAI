package sg.act.domain.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sg.act.domain.R
import sg.act.domain.data.local.ModelSource
import sg.act.domain.data.model.Conversation
import sg.act.domain.data.model.Message
import sg.act.domain.data.model.Role
import sg.act.domain.data.model.messagesAfter
import sg.act.domain.inference.InstalledModel
import sg.act.domain.inference.ModelManager
import sg.act.domain.inference.ModelProfile
import sg.act.domain.inference.ProviderType
import sg.act.domain.ui.components.ContextLengthRow
import sg.act.domain.ui.components.KillSwitchChip
import sg.act.domain.ui.components.MessageBubble
import sg.act.domain.ui.components.SettingSwitchRow
import sg.act.domain.ui.components.ThreadCountRow
import sg.act.domain.ui.components.TypingIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Follow the conversation as it grows: animate to a brand-new message, and
    // keep the bottom in view while a reply streams in token-by-token — but only
    // when the user is already parked at the bottom, so scrolling up to read
    // earlier messages isn't yanked back down.
    val messages = state.conversation.messages
    val lastIndex = messages.lastIndex
    val lastLength = messages.lastOrNull()?.text?.length ?: 0
    // The one reply a regenerate would replace: the final message, when it is a
    // reply at all (a send that failed outright can leave a question last).
    val replaceableReplyId = messages.lastOrNull()?.takeIf { it.role != Role.USER }?.id
    // True while isGenerating but no reply bubble has appeared yet — the
    // standalone typing placeholder is visible as a LazyColumn item at this point.
    val showTypingPlaceholder = state.isGenerating && messages.lastOrNull()?.role == Role.USER
    var prevIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(lastIndex, lastLength) {
        if (lastIndex < 0) return@LaunchedEffect
        val isNewMessage = lastIndex != prevIndex
        prevIndex = lastIndex
        if (isNewMessage) {
            listState.animateScrollToItem(lastIndex)
        } else if (listState.isNearBottom(lastIndex)) {
            // Instant (not animated) so it tracks fast token output without lag.
            listState.scrollToItem(lastIndex, Int.MAX_VALUE)
        }
    }
    // Scroll to reveal the typing placeholder the moment it appears (between the
    // user bubble and the oracle bubble being seeded).
    LaunchedEffect(showTypingPlaceholder) {
        if (showTypingPlaceholder && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size)
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showInferencePanel by remember { mutableStateOf(false) }

    if (showModelPicker) {
        ModelPickerSheet(
            state = state,
            onSelectLocal = { viewModel.selectLocalModel(it); showModelPicker = false },
            onSwitchToProfile = { viewModel.switchToProfile(it); showModelPicker = false },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showInferencePanel) {
        InferencePanelSheet(
            state = state,
            onSelectLocal = { viewModel.selectLocalModel(it) },
            onSwitchToProfile = { viewModel.switchToProfile(it) },
            onSetGpu = viewModel::setGpuEnabled,
            onSetContext = viewModel::setContextTokens,
            onSetThreads = viewModel::setThreadCount,
            onDismiss = { showInferencePanel = false },
        )
    }

    renaming?.let { convo ->
        RenameDialog(
            initial = convo.title,
            onConfirm = { title -> viewModel.renameConversation(convo.id, title); renaming = null },
            onCancel = { renaming = null },
        )
    }
    deleting?.let { convo ->
        DeleteDialog(
            title = convo.title,
            onConfirm = { viewModel.deleteConversation(convo.id); deleting = null },
            onCancel = { deleting = null },
        )
    }
    editing?.let { message ->
        EditMessageDialog(
            initial = message.text,
            discarded = state.conversation.messagesAfter(message.id),
            onConfirm = { text -> viewModel.editAndResend(message.id, text); editing = null },
            onCancel = { editing = null },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HistoryDrawer(
                conversations = state.conversations,
                activeId = state.activeId,
                onSelect = { id -> viewModel.selectConversation(id); scope.launch { drawerState.close() } },
                onNew = { viewModel.newConversation(); scope.launch { drawerState.close() } },
                onRename = { renaming = it },
                onDelete = { deleting = it },
            )
        },
    ) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.action_history))
                    }
                },
                title = {
                    Column {
                        Text(stringResource(R.string.chat_title))
                        Text(
                            selectedModelLabel(state),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::newConversation) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_new_chat))
                    }
                    IconButton(onClick = { showModelPicker = true }) {
                        Icon(
                            Icons.Filled.Memory,
                            contentDescription = stringResource(R.string.cd_model_picker),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.privacy.networkKillSwitch) {
                KillSwitchChip(
                    modifier = Modifier.padding(
                        horizontal = dimensionResource(R.dimen.space_l),
                        vertical = dimensionResource(R.dimen.space_s),
                    ),
                )
            }

            if (state.modelState is ModelManager.State.Loading) {
                ModelLoadingBanner()
            }
            ModelTransferBanner(state.transfer)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) {
                    EmptyState(onSuggestion = viewModel::updateInput)
                } else {
                    // The reply currently streaming in: render it as plain text so
                    // we don't re-parse Markdown (and re-run syntax highlighting) on
                    // every token. It re-renders as full Markdown once it completes.
                    val streamingId = if (state.isGenerating) {
                        messages.lastOrNull { it.role == Role.DOMAIN }?.id
                    } else {
                        null
                    }
                    val gap = dimensionResource(R.dimen.space_m)
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = dimensionResource(R.dimen.space_l)),
                        verticalArrangement = Arrangement.spacedBy(gap),
                        contentPadding = PaddingValues(vertical = gap),
                    ) {
                        items(messages, key = Message::id) { message ->
                            // Turn-level actions are offered only between
                            // generations, so a reply can't be rewritten from under
                            // a stream that is still filling it in.
                            val actionable = !state.isGenerating
                            MessageBubble(
                                message = message,
                                streaming = message.id == streamingId,
                                // Only the newest reply is replaceable: regenerating
                                // an older one would silently discard everything
                                // said after it.
                                onRegenerate = if (actionable && message.id == replaceableReplyId) {
                                    { viewModel.regenerate() }
                                } else {
                                    null
                                },
                                onEdit = if (actionable && message.role == Role.USER) {
                                    { editing = message }
                                } else {
                                    null
                                },
                            )
                        }
                        // Shown during the gap between the user bubble appearing and
                        // the oracle bubble being seeded (history prep + routing).
                        if (showTypingPlaceholder) {
                            item(key = "typing_placeholder") {
                                PendingTypingBubble()
                            }
                        }
                    }
                    JumpToLatest(
                        listState = listState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = gap),
                    )
                }
            }

            InputBar(
                input = state.input,
                isGenerating = state.isGenerating,
                onInputChange = viewModel::updateInput,
                onSend = viewModel::onSend,
                onStop = viewModel::stopGeneration,
                onOpenPanel = { showInferencePanel = true },
            )
        }
    }
    }
}

@Composable
private fun HistoryDrawer(
    conversations: List<Conversation>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.padding(dimensionResource(R.dimen.space_m))) {
            Text(
                stringResource(R.string.history_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(
                    horizontal = dimensionResource(R.dimen.space_s),
                    vertical = dimensionResource(R.dimen.space_s),
                ),
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.action_new_chat)) },
                selected = false,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = onNew,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = dimensionResource(R.dimen.space_s)))

            LazyColumn {
                items(conversations, key = Conversation::id) { convo ->
                    NavigationDrawerItem(
                        label = {
                            Text(
                                convo.title.ifBlank { stringResource(R.string.history_untitled) },
                                maxLines = 1,
                            )
                        },
                        selected = convo.id == activeId,
                        onClick = { onSelect(convo.id) },
                        badge = {
                            Row {
                                IconButton(onClick = { onRename(convo) }) {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = stringResource(R.string.history_rename),
                                        modifier = Modifier.size(dimensionResource(R.dimen.icon_small)),
                                    )
                                }
                                IconButton(onClick = { onDelete(convo) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.history_delete),
                                        modifier = Modifier.size(dimensionResource(R.dimen.icon_small)),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.history_rename)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.history_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.model_download_confirm_cancel)) }
        },
    )
}

@Composable
private fun DeleteDialog(
    title: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.history_delete)) },
        text = { Text(stringResource(R.string.history_delete_confirm, title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.history_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.model_download_confirm_cancel)) }
        },
    )
}

/**
 * Reword a question and ask it again. Because the conversation continues from
 * that turn, everything said after it goes — so the dialog says how much, rather
 * than dropping it silently on confirm.
 */
@Composable
private fun EditMessageDialog(
    initial: String,
    discarded: Int,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.edit_message_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s))) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    maxLines = integerResource(R.integer.input_max_lines),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (discarded > 0) {
                    Text(
                        pluralStringResource(R.plurals.edit_message_discards, discarded, discarded),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.edit_message_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.model_download_confirm_cancel)) }
        },
    )
}

/**
 * The first screen of a new chat.
 *
 * It used to be a title and a 24-word paragraph restating the privacy model —
 * true, already said on the first-run screen, and no help at all to someone
 * wondering what to type. Now it says one line and offers four starter prompts.
 *
 * Tapping one fills the input rather than sending it: the point is to get past
 * the blank page, and people almost always want to adjust the wording before
 * they commit. Each prompt is also a different shape of task, so the set doubles
 * as a hint about what a small on-device model can do.
 */
@Composable
private fun EmptyState(onSuggestion: (String) -> Unit) {
    val suggestions = listOf(
        stringResource(R.string.empty_suggestion_explain),
        stringResource(R.string.empty_suggestion_write),
        stringResource(R.string.empty_suggestion_summarize),
        stringResource(R.string.empty_suggestion_code),
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(dimensionResource(R.dimen.space_xl)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.empty_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = dimensionResource(R.dimen.space_xs)),
        )
        Column(
            modifier = Modifier.padding(top = dimensionResource(R.dimen.space_xl)),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
        ) {
            for (suggestion in suggestions) {
                SuggestionChip(text = suggestion, onClick = { onSuggestion(suggestion) })
            }
        }
    }
}

/** A starter prompt. Full-width and left-aligned, so the four read as a list. */
@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimensionResource(R.dimen.group_corner)))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(role = SemanticsRole.Button, onClick = onClick)
            .padding(
                horizontal = dimensionResource(R.dimen.space_l),
                vertical = dimensionResource(R.dimen.space_m),
            ),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InputBar(
    input: String,
    isGenerating: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenPanel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(dimensionResource(R.dimen.space_m)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
    ) {
        // Quick inference panel: model switch + GPU + context length.
        IconButton(onClick = onOpenPanel) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = stringResource(R.string.cd_inference_panel),
            )
        }
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.input_hint_local)) },
            maxLines = integerResource(R.integer.input_max_lines),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )

        if (isGenerating) {
            // While generating, the action button becomes a Stop control.
            IconButton(onClick = onStop) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = stringResource(R.string.action_stop),
                    tint = colorResource(R.color.brand_blocked),
                )
            }
        } else {
            IconButton(
                onClick = onSend,
                enabled = input.isNotBlank(),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.action_send),
                )
            }
        }
    }
}

/**
 * True when the last item is at (or just above) the bottom of the viewport — i.e.
 * the user is parked at the end of the chat and wants to follow new content. If
 * they've scrolled up to read history, this is false and auto-scroll backs off.
 */
private fun LazyListState.isNearBottom(lastIndex: Int): Boolean {
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return true
    return lastVisible >= lastIndex - 1
}

/**
 * The way back down. Auto-scroll deliberately stops following once you scroll up
 * to re-read something, which without this leaves the only route back a long
 * manual flick — and a reply that is still streaming keeps lengthening the
 * distance. It appears only when the end of the conversation is genuinely off
 * screen, so it never sits over the newest message.
 */
@Composable
private fun JumpToLatest(listState: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    // A tall final message can be the last *item* and still run well past the
    // bottom edge, so the test is where its content ends, not which item it is.
    val visible by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            last.index < info.totalItemsCount - 1 ||
                last.offset + last.size > info.viewportEndOffset + JUMP_SLACK_PX
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier,
    ) {
        SmallFloatingActionButton(
            onClick = {
                scope.launch {
                    val target = listState.layoutInfo.totalItemsCount - 1
                    if (target < 0) return@launch
                    listState.animateScrollToItem(target)
                    // Land flush against the bottom even when that item is taller
                    // than the viewport.
                    listState.scrollToItem(target, Int.MAX_VALUE)
                }
            },
            // The brand pair, not a Material tonal default: the tonal slots this
            // theme doesn't override come from the stock M3 palette and would put
            // an unrelated hue on top of the conversation.
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.cd_jump_to_latest),
            )
        }
    }
}

/**
 * How far the last message may overhang the viewport before the jump button
 * appears. A couple of pixels of rounding shouldn't count as "there's more
 * below"; the height of a line of text should.
 */
private const val JUMP_SLACK_PX = 24

/** The subtitle under "Domain AI": the name of the currently selected model. */
@Composable
private fun selectedModelLabel(state: ChatUiState): String {
    val activeProfile = state.savedProfiles.firstOrNull { it.id == state.activeProfileId }
    if (activeProfile != null) return activeProfile.name
    return when (val s = state.modelState) {
        is ModelManager.State.Ready -> s.modelName
        is ModelManager.State.Loading -> s.modelName
        else -> stringResource(R.string.model_picker_offline)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    state: ChatUiState,
    onSelectLocal: (String) -> Unit,
    onSwitchToProfile: (ModelProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = dimensionResource(R.dimen.space_l),
                    vertical = dimensionResource(R.dimen.space_m),
                ),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
        ) {
            Text(
                stringResource(R.string.model_picker_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            ModelChoices(
                state = state,
                onSelectLocal = onSelectLocal,
                onSwitchToProfile = onSwitchToProfile,
            )
        }
    }
}

/**
 * The on-device + cloud profile selection list, shared by the top-bar model picker
 * and the chat inference panel so model switching has a single implementation.
 */
@Composable
private fun ModelChoices(
    state: ChatUiState,
    onSelectLocal: (String) -> Unit,
    onSwitchToProfile: (ModelProfile) -> Unit,
) {
    val isLocal = state.activeProfileId == null

    // On-device models.
    Text(
        stringResource(R.string.model_picker_local),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = dimensionResource(R.dimen.space_s)),
    )
    if (state.installed.isEmpty()) {
        Text(
            stringResource(R.string.model_picker_no_local),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        for (model in state.installed) {
            ModelPickerRow(
                title = model.displayName,
                subtitle = stringResource(
                    if (model.source == ModelSource.IMPORT) R.string.model_source_import
                    else R.string.model_source_download,
                ),
                icon = Icons.Filled.Smartphone,
                selected = isLocal && model.isActive,
                onClick = { onSelectLocal(model.fileName) },
            )
        }
    }

    // My server profiles (Space).
    val serverProfiles = state.savedProfiles.filter { it.type == ProviderType.SPACE }
    if (serverProfiles.isNotEmpty()) {
        HorizontalDivider(modifier = Modifier.padding(vertical = dimensionResource(R.dimen.space_s)))
        Text(
            stringResource(R.string.model_picker_my_server),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (profile in serverProfiles) {
            ModelPickerRow(
                title = profile.name,
                subtitle = profile.model,
                icon = Icons.Filled.Cloud,
                selected = profile.id == state.activeProfileId,
                onClick = { onSwitchToProfile(profile) },
            )
        }
    }

    // Cloud API profiles (OpenRouter + Custom).
    val apiProfiles = state.savedProfiles.filter { it.type != ProviderType.SPACE }
    if (apiProfiles.isNotEmpty()) {
        HorizontalDivider(modifier = Modifier.padding(vertical = dimensionResource(R.dimen.space_s)))
        Text(
            stringResource(R.string.model_picker_cloud_api),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (profile in apiProfiles) {
            ModelPickerRow(
                title = profile.name,
                subtitle = profile.model,
                icon = Icons.Filled.Cloud,
                selected = profile.id == state.activeProfileId,
                onClick = { onSwitchToProfile(profile) },
            )
        }
    }

    if (serverProfiles.isEmpty() && apiProfiles.isEmpty()) {
        HorizontalDivider(modifier = Modifier.padding(vertical = dimensionResource(R.dimen.space_s)))
        Text(
            stringResource(R.string.model_picker_no_profiles),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Bottom-left chat quick-panel: switch model + GPU toggle + context length. Opens
 * from the input-bar button; all three reload the active model when changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InferencePanelSheet(
    state: ChatUiState,
    onSelectLocal: (String) -> Unit,
    onSwitchToProfile: (ModelProfile) -> Unit,
    onSetGpu: (Boolean) -> Unit,
    onSetContext: (Int) -> Unit,
    onSetThreads: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = dimensionResource(R.dimen.space_l),
                    vertical = dimensionResource(R.dimen.space_m),
                ),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
        ) {
            Text(
                stringResource(R.string.inference_panel_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Text(
                stringResource(R.string.inference_panel_model),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelChoices(
                state = state,
                onSelectLocal = onSelectLocal,
                onSwitchToProfile = onSwitchToProfile,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = dimensionResource(R.dimen.space_s)))
            Text(
                stringResource(R.string.inference_panel_performance),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingSwitchRow(
                title = stringResource(R.string.setting_gpu_title),
                summary = stringResource(R.string.setting_gpu_summary),
                checked = state.gpuEnabled,
                onCheckedChange = onSetGpu,
            )
            ContextLengthRow(
                chosenTokens = state.contextTokens,
                effectiveTokens = state.effectiveContextTokens,
                options = state.contextOptions,
                onSelect = onSetContext,
            )
            ThreadCountRow(
                chosenThreads = state.threadCount,
                effectiveThreads = state.effectiveThreads,
                options = state.threadOptions,
                onSelect = onSetThreads,
            )
        }
    }
}

@Composable
private fun ModelPickerRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = dimensionResource(R.dimen.space_s)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_m)),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(dimensionResource(R.dimen.icon_small)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.model_in_use),
                tint = colorResource(R.color.brand_local),
                modifier = Modifier.size(dimensionResource(R.dimen.icon_small)),
            )
        }
    }
}

/**
 * The bubble that stands in before the reply exists. This covers history prep,
 * the occasional local summarization pass, and routing — work that happens
 * before the model has even seen the prompt, and which on a big conversation is
 * not instant. Saying "Preparing…" here and "Reading your message…" once prefill
 * starts turns one undifferentiated wait into two legible ones.
 */
@Composable
private fun PendingTypingBubble() {
    val corner = dimensionResource(R.dimen.bubble_corner)
    val tail = dimensionResource(R.dimen.bubble_corner_tail)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = corner,
                topEnd = corner,
                bottomStart = tail,
                bottomEnd = corner,
            ),
        ) {
            TypingIndicator(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = TYPING_LABEL_ALPHA),
                label = stringResource(R.string.chat_phase_preparing),
                modifier = Modifier.padding(
                    horizontal = dimensionResource(R.dimen.bubble_pad_h),
                    vertical = dimensionResource(R.dimen.bubble_pad_v),
                ),
            )
        }
    }
}

private const val TYPING_LABEL_ALPHA = 0.6f

@Composable
private fun ModelLoadingBanner() {
    Surface(
        color = colorResource(R.color.brand_local).copy(
            alpha = integerResource(R.integer.alpha_container_pct) / 100f,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.space_l), vertical = dimensionResource(R.dimen.space_s))) {
            Text(
                stringResource(R.string.chat_model_loading),
                style = MaterialTheme.typography.labelLarge,
                color = colorResource(R.color.brand_local),
            )
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = dimensionResource(R.dimen.space_xs)),
            )
        }
    }
}

/**
 * Banner for a model download/import in progress — distinct from [ModelLoadingBanner]
 * (the in-memory load), so a long download shows real progress instead of looking
 * like a stuck "loading". Renders nothing when idle/failed (failures surface in
 * Settings, where Retry lives).
 */
@Composable
private fun ModelTransferBanner(transfer: ModelManager.TransferState) {
    val text: String
    val fraction: Float?
    when (transfer) {
        is ModelManager.TransferState.Downloading -> {
            text = stringResource(
                R.string.chat_model_downloading,
                transfer.modelName,
                ((transfer.progress?.fraction ?: 0f) * 100).toInt(),
            )
            fraction = transfer.progress?.fraction
        }
        is ModelManager.TransferState.Importing -> {
            text = stringResource(R.string.chat_model_importing, transfer.modelName)
            fraction = null
        }
        else -> return
    }
    Surface(
        color = colorResource(R.color.brand_local).copy(
            alpha = integerResource(R.integer.alpha_container_pct) / 100f,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.space_l), vertical = dimensionResource(R.dimen.space_s))) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = colorResource(R.color.brand_local),
            )
            val barModifier = Modifier
                .fillMaxWidth()
                .padding(top = dimensionResource(R.dimen.space_xs))
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = barModifier)
            } else {
                LinearProgressIndicator(modifier = barModifier)
            }
        }
    }
}
