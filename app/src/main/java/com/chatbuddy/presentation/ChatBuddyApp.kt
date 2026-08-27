package com.chatbuddy.presentation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import com.chatbuddy.domain.model.ModelStatus
import com.chatbuddy.domain.model.ModelCacheState
import com.chatbuddy.domain.model.TranslationProviderKind
import com.chatbuddy.presentation.common.ModelGate
import com.chatbuddy.presentation.home.HomeTab
import com.chatbuddy.presentation.home.HomeUiState
import com.chatbuddy.presentation.home.HomeViewModel
import com.chatbuddy.presentation.chat.ChatViewModel
import com.chatbuddy.presentation.translate.TranslationViewModel
import com.chatbuddy.presentation.translate.LanguageDropdown
import com.chatbuddy.presentation.translate.TranslationUiState
import com.chatbuddy.presentation.ocr.OcrViewModel
import com.chatbuddy.presentation.ocr.CameraPreview
import com.chatbuddy.data.repository.CameraOcrAnalyzer
import com.chatbuddy.presentation.ocr.OcrBoundingBoxOverlay
import com.chatbuddy.presentation.ocr.OcrImagePreview
import com.chatbuddy.presentation.ocr.LiveOcrTranscript
import com.chatbuddy.presentation.ocr.TranslatedBlockOverlay
import com.chatbuddy.presentation.settings.PersonaEvent
import com.chatbuddy.presentation.settings.PersonaViewModel
import com.chatbuddy.presentation.settings.WebSettingsViewModel
import com.chatbuddy.presentation.rag.DocumentViewModel
import com.chatbuddy.utils.formatBytes
import com.chatbuddy.domain.model.ChatMessage
import com.chatbuddy.domain.model.ChatCitation
import com.chatbuddy.domain.model.ChatCitationKind
import com.chatbuddy.domain.model.OcrResult
import com.chatbuddy.domain.model.Persona
import com.chatbuddy.domain.model.BuiltInPersonaCatalog
import com.chatbuddy.domain.model.LiveTranslationPhase
import java.util.UUID
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
fun ChatBuddyApp(windowSizeClass: WindowSizeClass, viewModel: HomeViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycleCompat()
    val snackbarHostState = remember { SnackbarHostState() }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.selectStorageFolder(it.toString()) }
    }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is com.chatbuddy.presentation.home.HomeEvent.Message -> snackbarHostState.showSnackbar(event.value)
            }
        }
    }

    val compact = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact
    val useNavigationRail = !compact
    val horizontalPadding = when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Compact -> 16.dp
        WindowWidthSizeClass.Medium -> 24.dp
        else -> 32.dp
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(titleFor(state.selectedTab)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!useNavigationRail) {
                NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                    HomeTab.values().forEach { tab ->
                        NavigationBarItem(
                            selected = state.selectedTab == tab,
                            onClick = { viewModel.selectTab(tab) },
                            icon = { Icon(tabIcon(tab), contentDescription = tabLabel(tab)) },
                            label = { Text(tabLabel(tab)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (useNavigationRail) {
                NavigationRail(
                    modifier = Modifier
                        .fillMaxHeight()
                        .navigationBarsPadding()
                ) {
                    HomeTab.values().forEach { tab ->
                        NavigationRailItem(
                            selected = state.selectedTab == tab,
                            onClick = { viewModel.selectTab(tab) },
                            icon = { Icon(tabIcon(tab), contentDescription = tabLabel(tab)) },
                            label = { Text(tabLabel(tab)) }
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.TopCenter
            ) {
                val contentModifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .widthIn(max = 600.dp)
                    .imePadding()
                    .padding(vertical = 16.dp)
                if (state.selectedTab == HomeTab.CHAT) {
                    Column(
                        modifier = contentModifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ChatTab(state, viewModel, snackbarHostState) { folderPicker.launch(null) }
                    }
                } else {
                    Column(
                        modifier = contentModifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        when (state.selectedTab) {
                            HomeTab.TRANSLATE -> TranslateTab(state, viewModel, snackbarHostState)
                            HomeTab.OCR -> OcrTab(viewModel, snackbarHostState)
                            HomeTab.SETTINGS -> SettingsTab(state, viewModel) { folderPicker.launch(null) }
                            HomeTab.CHAT -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatTab(
    state: HomeUiState,
    viewModel: HomeViewModel,
    snackbarHostState: SnackbarHostState,
    chooseFolder: () -> Unit
) {
    val llm = state.modelStates.firstOrNull { it.artifact.id.startsWith("gemma-") }
    if (!state.storageConfigured) {
        SetupCard(onChooseFolder = chooseFolder)
        return
    }
    ModelGate(
        status = llm?.status ?: ModelStatus.Unavailable,
        onDownload = { llm?.artifact?.id?.let(viewModel::downloadModel) },
        onPause = { llm?.artifact?.id?.let(viewModel::pauseModel) },
        modelName = llm?.artifact?.displayName
    ) {
        ChatContent(state, viewModel, snackbarHostState)
    }
}

@Composable
private fun ChatContent(
    state: HomeUiState,
    homeViewModel: HomeViewModel,
    snackbarHostState: SnackbarHostState
) {
    val viewModel = hiltViewModel<ChatViewModel>()
    val chatState by viewModel.state.collectAsStateWithLifecycleCompat()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) {
        viewModel.events.collect { event -> snackbarHostState.showSnackbar(event) }
    }
    LaunchedEffect(state.pendingChatText) {
        state.pendingChatText?.let {
            viewModel.setInput(it)
            homeViewModel.consumeChatText()
        }
    }
    LaunchedEffect(chatState.messages.size, chatState.messages.lastOrNull()?.text, chatState.streaming) {
        val lastItem = listState.layoutInfo.totalItemsCount - 1
        if (lastItem >= 0) listState.animateScrollToItem(lastItem)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ChatBentoHeader(chatState)
        }
        chatState.errorMessage?.let { message ->
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(message, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = viewModel::clearError) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }
        if (chatState.activePersona == null) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Start with Sunny Companion", style = MaterialTheme.typography.titleMedium)
                        Text("A cheerful, grounded persona that asks one focused question when your request needs clarification.")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = viewModel::activateDefaultPersona,
                                enabled = !chatState.personaActionInProgress,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (chatState.personaActionInProgress) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("Use Sunny")
                                }
                            }
                            OutlinedButton(
                                onClick = { homeViewModel.selectTab(HomeTab.SETTINGS) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Browse personas")
                            }
                        }
                    }
                }
            }
        }
        item {
            ChatKnowledgeBento(chatState, viewModel)
        }
        items(chatState.messages, key = { it.id }) { message ->
            ChatBubble(
                message = message,
                onCopy = {
                    clipboard.setText(AnnotatedString(message.text))
                    scope.launch { snackbarHostState.showSnackbar("Message copied") }
                }
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = chatState.input,
                            onValueChange = viewModel::setInput,
                            label = { Text("Ask ChatBuddy") },
                            modifier = Modifier.weight(1f),
                            minLines = 1,
                            maxLines = 5
                        )
                        IconButton(
                            onClick = viewModel::send,
                            enabled = !chatState.streaming && chatState.input.isNotBlank(),
                            modifier = Modifier.semantics {
                                contentDescription = "Send message"
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null)
                        }
                    }
                    when {
                        chatState.webSearching -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                "Searching Wikipedia for a grounded source…",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.semantics {
                                    liveRegion = LiveRegionMode.Polite
                                }
                            )
                        }
                        chatState.streaming -> Text(
                            "ChatBuddy is responding…",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBentoHeader(state: com.chatbuddy.presentation.chat.ChatUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text("AI companion", style = MaterialTheme.typography.titleLarge)
                Text(
                    state.activePersona?.name ?: "Persona required",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    if (state.allowWebFallback) "Local + web" else "Local-first",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun ChatKnowledgeBento(
    state: com.chatbuddy.presentation.chat.ChatUiState,
    viewModel: ChatViewModel
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Knowledge", style = MaterialTheme.typography.titleMedium)
            Text(
                "Local documents are checked first. Web search is optional.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Description, contentDescription = null)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("Use local documents")
                    Text(
                        "Answer from indexed RAG sources",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Switch(
                    checked = state.useRag && state.ragAvailable,
                    onCheckedChange = viewModel::setUseRag,
                    enabled = state.ragAvailable,
                    modifier = Modifier.semantics {
                        contentDescription = if (state.ragAvailable) {
                            "Use local document evidence"
                        } else {
                            "Local document evidence unavailable until embedding models are ready"
                        }
                    }
                )
            }
            if (!state.ragAvailable) {
                Text(
                    "Download the embedding bundles in Settings to enable local documents.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 32.dp)
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Public, contentDescription = null)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("Search web if local sources miss")
                    Text(
                        "Only the question is sent over HTTPS",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Switch(
                    checked = state.allowWebFallback,
                    onCheckedChange = viewModel::setAllowWebFallback,
                    modifier = Modifier.semantics {
                        contentDescription = "Allow web search fallback"
                    }
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, onCopy: () -> Unit) {
    val userMessage = message.role == ChatMessage.Role.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (userMessage) Alignment.End else Alignment.Start
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 540.dp),
            color = if (userMessage) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    if (userMessage) "You" else "ChatBuddy",
                    style = MaterialTheme.typography.labelLarge
                )
                SelectionContainer { Text(message.text) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier.semantics { contentDescription = "Copy message" }
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    }
                }
                if (!userMessage && message.citations.isNotEmpty()) {
                    ChatSources(message.citations)
                }
            }
        }
    }
}

@Composable
private fun ChatSources(citations: List<ChatCitation>) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
        Text("Sources", style = MaterialTheme.typography.labelLarge)
        citations.forEach { citation ->
            val uri = citation.uri
            val sourceModifier = if (citation.kind == ChatCitationKind.WEB && uri != null) {
                Modifier
                    .clickable { runCatching { uriHandler.openUri(uri) } }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Open source ${citation.title}"
                    }
            } else {
                Modifier
            }
            Surface(
                modifier = sourceModifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        if (citation.kind == ChatCitationKind.WEB) {
                            Icons.Outlined.Public
                        } else {
                            Icons.Outlined.Description
                        },
                        contentDescription = null
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(citation.title, style = MaterialTheme.typography.labelLarge)
                        Text(
                            "${citation.provider}${citation.score?.let { " · ${"%.2f".format(java.util.Locale.US, it)}" } ?: ""}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            citation.excerpt,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (citation.kind == ChatCitationKind.WEB && uri != null) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open source")
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslateTab(
    state: HomeUiState,
    homeViewModel: HomeViewModel,
    snackbarHostState: SnackbarHostState
) {
    val viewModel = hiltViewModel<TranslationViewModel>()
    val translationState by viewModel.state.collectAsStateWithLifecycleCompat()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var microphonePermissionGranted by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> microphonePermissionGranted = granted }
    LaunchedEffect(state.pendingTranslationText) {
        state.pendingTranslationText?.let {
            viewModel.setSourceText(it)
            homeViewModel.consumeTranslationText()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LanguageSelectorRow(translationState, viewModel, "Swap source and target languages")
        TranslationModelCard(translationState, viewModel)
        OutlinedTextField(
            value = translationState.sourceText,
            onValueChange = viewModel::setSourceText,
            label = { Text("Enter text") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 8
        )
        LiveTranslationCard(
            state = translationState,
            viewModel = viewModel,
            microphonePermissionGranted = microphonePermissionGranted,
            onRequestMicrophone = { microphonePermission.launch(Manifest.permission.RECORD_AUDIO) },
            onOpenModelSetup = { homeViewModel.selectTab(HomeTab.SETTINGS) }
        )
        if (translationState.loading || translationState.result != null || translationState.error != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (translationState.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    translationState.result?.let { result ->
                        SelectionContainer { Text(result.text, style = MaterialTheme.typography.bodyLarge) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val clipboard = LocalClipboardManager.current
                            val context = LocalContext.current
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(result.text))
                                    scope.launch { snackbarHostState.showSnackbar("Translation copied") }
                                },
                                modifier = Modifier.semantics { contentDescription = "Copy translation" }
                            ) { Icon(Icons.Outlined.ContentCopy, null) }
                            IconButton(
                                onClick = {
                                    shareText(context, result.text)
                                    scope.launch { snackbarHostState.showSnackbar("Share sheet opened") }
                                },
                                modifier = Modifier.semantics { contentDescription = "Share translation" }
                            ) { Icon(Icons.Outlined.Share, null) }
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                providerLabel(result.provider),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    translationState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        TranslationHistoryCard(translationState, viewModel)
    }
}

@Composable
private fun TranslationHistoryCard(
    state: TranslationUiState,
    viewModel: TranslationViewModel
) {
    if (state.history.isEmpty() && state.historyError == null) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Recent translations", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (state.history.isNotEmpty()) {
                    OutlinedButton(onClick = viewModel::clearHistory) { Text("Clear") }
                }
            }
            state.history.take(3).forEach { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${entry.sourceLanguage} → ${entry.targetLanguage}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(entry.sourceText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        entry.translatedText,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            state.historyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun LiveTranslationCard(
    state: TranslationUiState,
    viewModel: TranslationViewModel,
    microphonePermissionGranted: Boolean,
    onRequestMicrophone: () -> Unit,
    onOpenModelSetup: () -> Unit
) {
    val active = state.liveEnabled
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Live conversation", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "One phone · pause between turns",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Icon(
                    imageVector = if (active) Icons.Outlined.Mic else Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = if (active) "Microphone active" else "Voice conversation"
                )
            }
            Text(
                livePhaseLabel(state.livePhase),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            if (!microphonePermissionGranted && !active) {
                Text(
                    "Microphone access is required for live conversation.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.liveChecking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    if (active) {
                        viewModel.stopLiveTranslation()
                    } else if (!microphonePermissionGranted) {
                        onRequestMicrophone()
                    } else {
                        viewModel.toggleLiveTranslation()
                    }
                },
                enabled = !state.liveChecking,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (active) Icons.Outlined.Stop else Icons.Outlined.Mic,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(if (active) "Stop live translation" else "Start live translation")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (state.voiceCapabilities?.offlineTtsReady == true) {
                        Icons.AutoMirrored.Outlined.VolumeUp
                    } else {
                        Icons.AutoMirrored.Outlined.VolumeOff
                    },
                    contentDescription = null
                )
                Text(
                    "Speak translation",
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                Switch(
                    checked = state.liveSpeakTranslation &&
                        state.voiceCapabilities?.offlineTtsReady == true,
                    onCheckedChange = viewModel::setLiveSpeakTranslation,
                    enabled = state.voiceCapabilities?.offlineTtsReady == true
                )
            }
            if (state.liveTranscript.isNotBlank()) {
                VoiceTurnText(label = "You said", text = state.liveTranscript)
            }
            state.liveTranslation?.let { translation ->
                VoiceTurnText(label = "Translation", text = translation.text)
                Text(
                    "${providerLabel(translation.provider)} · ${translation.targetLanguage}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.liveError?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
                if (state.voiceCapabilities?.whisperReady == false) {
                    OutlinedButton(onClick = onOpenModelSetup, modifier = Modifier.fillMaxWidth()) {
                        Text("Open model setup")
                    }
                }
            }
            state.voiceCapabilities?.takeIf { !it.offlineTtsReady }?.let {
                Text(
                    "Offline voice output is unavailable for this language; text translation remains available.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun VoiceTurnText(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Text(
                text,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )
        }
    }
}

private fun livePhaseLabel(phase: LiveTranslationPhase): String = when (phase) {
    LiveTranslationPhase.Idle -> "Ready for a live conversation"
    LiveTranslationPhase.Starting -> "Preparing on-device voice"
    LiveTranslationPhase.Listening -> "Listening for the next sentence"
    LiveTranslationPhase.Transcribing -> "Transcribing on device"
    LiveTranslationPhase.Translating -> "Translating on device"
    LiveTranslationPhase.Speaking -> "Playing the translation"
    LiveTranslationPhase.Error -> "Live translation stopped"
}


@Composable
private fun TranslationModelCard(
    state: TranslationUiState,
    viewModel: TranslationViewModel,
    compact: Boolean = false
) {
    if (compact) {
        CompactTranslationModelCard(state, viewModel)
        return
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val status = when {
                state.modelDownloading -> "Downloading offline language pack…"
                state.modelChecking -> "Checking offline language pack…"
                state.modelReady -> "Offline translation ready"
                else -> "Download language pack to start"
            }
            Text(status, style = MaterialTheme.typography.titleSmall)
            Text("Provider: Play services managed model", style = MaterialTheme.typography.bodySmall)
            when {
                state.modelDownloading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                !state.modelReady && !state.modelChecking -> Button(
                    onClick = viewModel::downloadLanguageModels,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Download language pack")
                }
            }
            state.modelError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun CompactTranslationModelCard(
    state: TranslationUiState,
    viewModel: TranslationViewModel
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        val status = when {
            state.modelDownloading -> "Downloading language pack"
            state.modelChecking -> "Checking translation"
            state.modelReady -> "Offline translation ready"
            else -> "Translation needs a language pack"
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth < 420.dp) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(status, style = MaterialTheme.typography.titleSmall)
                    Text("ML Kit offline provider", style = MaterialTheme.typography.bodySmall)
                    state.modelError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    when {
                        state.modelDownloading -> CircularProgressIndicator(
                            modifier = Modifier.width(24.dp),
                            strokeWidth = 2.dp
                        )
                        !state.modelReady && !state.modelChecking -> OutlinedButton(
                            onClick = viewModel::downloadLanguageModels,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Get language pack")
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(status, style = MaterialTheme.typography.titleSmall)
                        Text("ML Kit offline provider", style = MaterialTheme.typography.bodySmall)
                        state.modelError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    when {
                        state.modelDownloading -> CircularProgressIndicator(
                            modifier = Modifier.width(24.dp),
                            strokeWidth = 2.dp
                        )
                        !state.modelReady && !state.modelChecking -> OutlinedButton(
                            onClick = viewModel::downloadLanguageModels
                        ) {
                            Text("Get pack")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageSelectorRow(
    state: TranslationUiState,
    viewModel: TranslationViewModel,
    swapDescription: String
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 420.dp) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LanguageDropdown(
                    label = "From",
                    selected = state.sourceLanguage,
                    languages = state.languages,
                    onSelected = viewModel::setSourceLanguage,
                    modifier = Modifier.fillMaxWidth()
                )
                IconButton(
                    onClick = viewModel::swapLanguages,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .semantics { contentDescription = swapDescription }
                ) {
                    Icon(Icons.Outlined.SwapHoriz, contentDescription = null)
                }
                LanguageDropdown(
                    label = "To",
                    selected = state.targetLanguage,
                    languages = state.languages,
                    onSelected = viewModel::setTargetLanguage,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LanguageDropdown(
                    label = "From",
                    selected = state.sourceLanguage,
                    languages = state.languages,
                    onSelected = viewModel::setSourceLanguage,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = viewModel::swapLanguages,
                    modifier = Modifier.semantics { contentDescription = swapDescription }
                ) {
                    Icon(Icons.Outlined.SwapHoriz, contentDescription = null)
                }
                LanguageDropdown(
                    label = "To",
                    selected = state.targetLanguage,
                    languages = state.languages,
                    onSelected = viewModel::setTargetLanguage,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun providerLabel(provider: TranslationProviderKind): String = when (provider) {
    TranslationProviderKind.ML_KIT_PLAY_SERVICES -> "Play services"
    TranslationProviderKind.LOCAL_OPUS_ONNX -> "Local model"
    TranslationProviderKind.UNAVAILABLE -> "Unavailable"
}

@Composable
private fun OcrTab(
    homeViewModel: HomeViewModel,
    snackbarHostState: SnackbarHostState
) {
    val viewModel = hiltViewModel<OcrViewModel>()
    val translationViewModel = hiltViewModel<TranslationViewModel>()
    val ocrState by viewModel.state.collectAsStateWithLifecycleCompat()
    val translationState by translationViewModel.state.collectAsStateWithLifecycleCompat()
    val cameraAnalyzer = remember { CameraOcrAnalyzer() }
    val ocrResult = ocrState.result
    val selectedImageUri = ocrState.imageUri
    val context = LocalContext.current
    var cameraError by rememberSaveable { mutableStateOf<String?>(null) }
    var captureRequest by rememberSaveable { mutableStateOf(0L) }
    var cameraEnabled by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.recognize(it.toString(), translationState.sourceLanguage)
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraEnabled = granted
        if (granted) {
            cameraError = null
            viewModel.clearCameraError()
        } else {
            val message = "Camera permission is required for live OCR."
            cameraError = message
            viewModel.setCameraError(message)
        }
    }
    val stopCamera = {
        cameraEnabled = false
        cameraError = null
        viewModel.clearCameraError()
    }
    val startCamera = {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            cameraEnabled = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    DisposableEffect(cameraAnalyzer, viewModel) {
        cameraAnalyzer.setCallbacks(
            onResult = viewModel::onCameraResult,
            onError = viewModel::onCameraError
        )
        onDispose {
            cameraAnalyzer.clearCallbacks()
            cameraAnalyzer.close()
        }
    }
    LaunchedEffect(cameraAnalyzer, translationState.sourceLanguage, translationState.targetLanguage) {
        cameraAnalyzer.setLanguageTag(translationState.sourceLanguage)
        viewModel.setTranslationLanguages(
            translationState.sourceLanguage,
            translationState.targetLanguage
        )
    }
    LaunchedEffect(
        translationState.modelReady,
        ocrState.result?.text,
        ocrState.translationError
    ) {
        if (translationState.modelReady &&
            ocrState.result != null &&
            ocrState.translationError != null &&
            ocrState.translatedBlocks.isEmpty()
        ) {
            viewModel.retryTranslation()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LanguageSelectorRow(translationState, translationViewModel, "Swap OCR and translation languages")
        if (!cameraEnabled) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth < 420.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { picker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Choose image")
                        }
                        CameraActionButton(
                            enabled = false,
                            onStop = stopCamera,
                            onStart = startCamera,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { picker.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) {
                            Text("Choose image")
                        }
                        CameraActionButton(
                            enabled = false,
                            onStop = stopCamera,
                            onStart = startCamera,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        if (!cameraEnabled) {
            TranslationModelCard(translationState, translationViewModel, compact = true)
        }
        if (cameraEnabled && cameraError == null) {
            CameraPreview(
                analyzer = cameraAnalyzer,
                captureRequest = captureRequest,
                onCapturedUri = { uri ->
                    viewModel.recognize(uri, translationState.sourceLanguage)
                },
                onError = { message ->
                    cameraError = message
                    viewModel.setCameraError(message)
                },
                onReady = {
                    cameraError = null
                    viewModel.clearCameraError()
                },
                overlay = {
                    OcrBoundingBoxOverlay(ocrResult, modifier = Modifier.fillMaxSize())
                    LiveOcrTranscript(
                        result = ocrResult,
                        translationState = translationState,
                        translatedBlocks = ocrState.translatedBlocks,
                        translationProcessing = ocrState.translationProcessing,
                        translationError = ocrState.translationError,
                        translationProvider = translationState.result
                            ?.takeIf { translationState.sourceText.trim() == ocrResult?.text?.trim() }
                            ?.let { providerLabel(it.provider) }
                            ?: ocrState.translatedBlocks.firstOrNull()?.let {
                                providerLabel(it.provider)
                            },
                        onDownloadTranslation = translationViewModel::downloadLanguageModels,
                        onStopCamera = stopCamera,
                        onCapture = { captureRequest += 1L },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(8.dp)
                    )
                },
                modifier = Modifier.semantics { contentDescription = "Live camera OCR preview" }
            )
        } else if (cameraEnabled && cameraError != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Camera unavailable", style = MaterialTheme.typography.titleSmall)
                    cameraError?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    OutlinedButton(onClick = {
                        cameraError = null
                        viewModel.clearCameraError()
                    }) {
                        Text("Retry camera")
                    }
                }
            }
        } else if (selectedImageUri != null) {
            OcrImagePreview(
                uri = selectedImageUri,
                result = ocrResult,
                translatedBlocks = ocrState.translatedBlocks,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .heightIn(min = 220.dp, max = 420.dp)
            )
        } else if (ocrResult == null) {
            OcrEmptyState()
        }
        if (ocrState.processing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        ocrState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!cameraEnabled) ocrResult?.takeIf { it.text.isNotBlank() }?.let { result ->
            OcrResultPanel(
                result = result,
                translationState = translationState,
                translatedBlocks = ocrState.translatedBlocks,
                translationProcessing = ocrState.translationProcessing,
                translationError = ocrState.translationError,
                onCopy = {
                    clipboard.setText(AnnotatedString(result.text))
                    scope.launch { snackbarHostState.showSnackbar("OCR text copied") }
                },
                onSendToChat = { homeViewModel.sendToChat(result.text) },
                onSendToTranslation = { homeViewModel.sendToTranslation(result.text) },
                onTranslate = { translationViewModel.setSourceText(result.text) }
            )
        }
        if (!cameraEnabled && ocrResult != null && ocrResult.text.isBlank() && !ocrState.processing) {
            Text("No text found in this image.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CameraActionButton(
    enabled: Boolean,
    onStop: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = if (enabled) onStop else onStart,
        modifier = modifier
    ) {
        Text(if (enabled) "Stop camera" else "Use camera")
    }
}

@Composable
private fun OcrEmptyState() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.CameraAlt, contentDescription = null)
            Text("Ready to scan", style = MaterialTheme.typography.titleMedium)
            Text(
                "Choose a photo or start the camera.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun OcrResultPanel(
    result: OcrResult,
    translationState: TranslationUiState,
    translatedBlocks: List<com.chatbuddy.domain.model.TranslatedBlock>,
    translationProcessing: Boolean,
    translationError: String?,
    onCopy: () -> Unit,
    onSendToChat: () -> Unit,
    onSendToTranslation: () -> Unit,
    onTranslate: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Detected text", style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(result.text) }
            Row {
                IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, "Copy OCR text") }
                IconButton(onClick = onSendToChat) { Icon(Icons.AutoMirrored.Outlined.Chat, "Send OCR text to chat") }
                IconButton(onClick = onSendToTranslation) { Icon(Icons.Outlined.Language, "Send OCR text to translation") }
            }
            OutlinedButton(onClick = onTranslate, modifier = Modifier.fillMaxWidth()) {
                Text("Translate detected text")
            }
            if (translationProcessing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (translatedBlocks.isNotEmpty()) {
                Text("Image translation", style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Text(translatedBlocks.joinToString(" ") { it.translatedText })
                }
            }
            translationState.result?.let { translated ->
                Text("Live translation", style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(translated.text) }
                Text(providerLabel(translated.provider), style = MaterialTheme.typography.bodySmall)
            }
            translationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            translationState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("${result.blocks.size} text blocks · ${result.languageTag}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsTab(
    state: HomeUiState,
    viewModel: HomeViewModel,
    launchFolderPicker: () -> Unit
) {
    val personaViewModel = hiltViewModel<PersonaViewModel>()
    val webSettingsViewModel = hiltViewModel<WebSettingsViewModel>()
    val documentViewModel = hiltViewModel<DocumentViewModel>()
    val personaState by personaViewModel.state.collectAsStateWithLifecycleCompat()
    val webSettingsState by webSettingsViewModel.state.collectAsStateWithLifecycleCompat()
    val documentState by documentViewModel.state.collectAsStateWithLifecycleCompat()
    var personaMessage by remember { mutableStateOf<PersonaEvent?>(null) }
    var documentMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { personaViewModel.events.collect { personaMessage = it } }
    LaunchedEffect(Unit) { documentViewModel.events.collect { documentMessage = it } }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var temperature by rememberSaveable { mutableStateOf(0.7f) }
    var topP by rememberSaveable { mutableStateOf(0.9f) }
    var maxTokens by rememberSaveable { mutableStateOf("2048") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var templateMenuOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(personaState.editing?.id) {
        personaState.editing?.let { persona ->
            editingId = persona.id
            name = persona.name
            description = persona.description
            prompt = persona.systemPrompt
            temperature = persona.temperature
            topP = persona.topP
            maxTokens = persona.maxTokens.toString()
        }
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { documentViewModel.add(it.toString()) }
    }
    val documentFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { documentViewModel.addFolder(it.toString()) }
    }
    Text("Local model storage", style = MaterialTheme.typography.headlineSmall)
    if (!state.storageConfigured) {
        SetupCard(onChooseFolder = launchFolderPicker)
    } else {
        Text("SAF folder connected. Files remain outside app-private storage.")
    }
    Text("AI model bundles", style = MaterialTheme.typography.titleLarge)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        state.modelStates.forEach { model ->
            ModelCard(
                model = model,
                viewModel = viewModel,
                storageConfigured = state.storageConfigured,
                onChooseStorage = launchFolderPicker
            )
        }
    }
    RuntimeStatusCard(state, documentState)
    WebProviderSettingsCard(webSettingsState, webSettingsViewModel)
    Text("AI persona", style = MaterialTheme.typography.headlineSmall)
    Box {
        OutlinedButton(onClick = { templateMenuOpen = true }) {
            Text("Use a persona template")
        }
        DropdownMenu(
            expanded = templateMenuOpen,
            onDismissRequest = { templateMenuOpen = false }
        ) {
            BuiltInPersonaCatalog.all.forEach { template ->
                DropdownMenuItem(
                    text = { Text(template.name) },
                    onClick = {
                        name = template.name
                        description = template.description
                        prompt = template.systemPrompt
                        temperature = template.temperature
                        topP = template.topP
                        maxTokens = template.maxTokens.toString()
                        templateMenuOpen = false
                    }
                )
            }
        }
    }
    OutlinedTextField(
        name,
        { name = it },
        label = { Text("Name") },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Persona name" }
    )
    OutlinedTextField(
        description,
        { description = it },
        label = { Text("Description") },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Persona description" }
    )
    OutlinedTextField(
        prompt,
        { prompt = it },
        label = { Text("System prompt") },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Persona system prompt" },
        minLines = 4
    )
    Text("Temperature ${"%.2f".format(temperature)}")
    Slider(
        value = temperature,
        onValueChange = { temperature = it },
        valueRange = 0.1f..1f,
        modifier = Modifier.semantics { contentDescription = "Persona temperature" }
    )
    Text("Top-P ${"%.2f".format(topP)}")
    Slider(
        value = topP,
        onValueChange = { topP = it },
        valueRange = 0.5f..1f,
        modifier = Modifier.semantics { contentDescription = "Persona top-p" }
    )
    OutlinedTextField(
        maxTokens,
        { maxTokens = it.filter(Char::isDigit) },
        label = { Text("Max tokens (256–4096)") },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Persona maximum tokens" }
    )
    Button(
        onClick = {
            personaViewModel.save(
                Persona(
                    id = editingId ?: UUID.randomUUID().toString(),
                    name = name,
                    description = description,
                    systemPrompt = prompt,
                    temperature = temperature,
                    topP = topP,
                    maxTokens = maxTokens.toIntOrNull() ?: 0,
                    active = personaState.editing?.active == true
                )
            )
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (editingId == null) "Save persona" else "Update persona") }
    OutlinedButton(
        onClick = {
            personaViewModel.saveAndActivate(
                Persona(
                    id = editingId ?: UUID.randomUUID().toString(),
                    name = name,
                    description = description,
                    systemPrompt = prompt,
                    temperature = temperature,
                    topP = topP,
                    maxTokens = maxTokens.toIntOrNull() ?: 0,
                    active = true
                )
            )
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Save and activate") }
    if (editingId != null) {
        OutlinedButton(
            onClick = {
                editingId = null
                personaViewModel.clearEditing()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Cancel editing") }
    }
    personaMessage?.let { event ->
        Text(
            event.message,
            color = if (event.isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.tertiary
            },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
    if (personaState.personas.isEmpty()) {
        Text("No persona saved")
    } else {
        personaState.personas.forEach { persona ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(persona.name, style = MaterialTheme.typography.titleMedium)
                        Text(persona.description.ifBlank { "No description" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (persona.active) {
                            Text(
                                "Active",
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.labelLarge
                            )
                        } else {
                            OutlinedButton(
                                onClick = { personaViewModel.setActive(persona.id) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Activate persona") }
                        }
                        IconButton(
                            onClick = { personaViewModel.edit(persona) },
                            modifier = Modifier.semantics { contentDescription = "Edit ${persona.name}" }
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = null)
                        }
                        IconButton(
                            onClick = { personaViewModel.duplicate(persona) },
                            modifier = Modifier.semantics { contentDescription = "Duplicate ${persona.name}" }
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                        }
                        IconButton(onClick = { personaViewModel.delete(persona.id) }) {
                            Icon(Icons.Outlined.Delete, "Delete ${persona.name}")
                        }
                    }
                }
            }
        }
    }
    Text("RAG documents", style = MaterialTheme.typography.headlineSmall)
    Text("TXT, PDF, and DOCX are read from SAF as bounded streams and chunked without loading a 200 MB document into memory.", style = MaterialTheme.typography.bodySmall)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 420.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        documentFolderPicker.launch(null)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Choose SAF folder") }
                OutlinedButton(
                    onClick = {
                        documentPicker.launch(
                            arrayOf(
                                "text/plain",
                                "application/pdf",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add one document") }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { documentFolderPicker.launch(null) },
                    modifier = Modifier.weight(1f)
                ) { Text("Choose SAF folder") }
                OutlinedButton(
                    onClick = {
                        documentPicker.launch(
                            arrayOf(
                                "text/plain",
                                "application/pdf",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Add one document") }
            }
        }
    }
    if (documentState.processing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    documentMessage?.let { Text(it, color = if (it == "Document indexed") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error) }
    if (documentState.documents.isEmpty()) {
        Text("No document indexed")
    } else {
        documentState.documents.forEach { document ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(document.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (document.indexed) "${document.chunkCount} chunks" else "Not indexed", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { documentViewModel.delete(document.id.value) }) { Icon(Icons.Outlined.Delete, "Delete ${document.displayName}") }
                }
            }
        }
    }
}

@Composable
private fun RuntimeStatusCard(
    state: HomeUiState,
    documentState: com.chatbuddy.presentation.rag.DocumentUiState
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Runtime status", style = MaterialTheme.typography.titleMedium)
            state.cacheStatuses.forEach { cache ->
                val label = when (cache.state) {
                    ModelCacheState.HIT -> "cacheDir hit"
                    ModelCacheState.MISS -> "cacheDir miss"
                    ModelCacheState.UNAVAILABLE -> "cache unavailable"
                }
                Text(
                    "${cache.displayName}: $label",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(cache.detail, style = MaterialTheme.typography.labelSmall)
            }
            state.cacheError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            documentState.vectorStatus?.let { vector ->
                Text(
                    "Vector backend: ${vector.backend.name}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(vector.detail, style = MaterialTheme.typography.labelSmall)
            }
            documentState.vectorStatusError?.let {
                Text("Vector backend: $it", color = MaterialTheme.colorScheme.error)
            }
            Text(
                "CacheDir is an optional speed-up; SAF remains the durable model source.",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun WebProviderSettingsCard(
    state: com.chatbuddy.presentation.settings.WebSettingsUiState,
    viewModel: WebSettingsViewModel
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Optional web search", style = MaterialTheme.typography.titleMedium)
            Text(
                "Wikipedia is available without a key. An optional Brave Search key enables broader official web results. Only your query is sent.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = state.apiKeyInput,
                onValueChange = viewModel::setApiKey,
                label = { Text("Brave Search API key") },
                placeholder = { Text("Stored encrypted on this device") },
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Brave Search API key" },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = viewModel::save, enabled = !state.busy && state.apiKeyInput.isNotBlank()) {
                    Text("Save key")
                }
                if (state.braveApiKeyConfigured) {
                    OutlinedButton(onClick = viewModel::clear, enabled = !state.busy) {
                        Text("Remove key")
                    }
                }
            }
            when {
                state.busy -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.message != null -> Text(state.message, color = MaterialTheme.colorScheme.secondary)
                state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
                state.braveApiKeyConfigured -> Text(
                    "Brave Search key configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: com.chatbuddy.domain.model.ModelState,
    viewModel: HomeViewModel,
    storageConfigured: Boolean,
    onChooseStorage: () -> Unit
) {
    val status = model.status
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(model.artifact.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${formatBytes(model.artifact.sizeBytes)} · ${model.artifact.license}", style = MaterialTheme.typography.bodySmall)
            if (!storageConfigured && status !is ModelStatus.Ready) {
                Text(
                    "Reconnect a writable SAF folder before downloading this bundle.",
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(
                    onClick = onChooseStorage,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Reconnect SAF folder") }
            } else when (status) {
                is ModelStatus.Queued -> {
                    Text(
                        if (status.downloadedBytes > 0L) {
                            "${formatBytes(status.downloadedBytes)} downloaded · waiting for network"
                        } else {
                            "Download queued · resumes automatically"
                        }
                    )
                    OutlinedButton(
                        onClick = { viewModel.pauseModel(model.artifact.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Pause download") }
                }
                is ModelStatus.Downloading -> {
                    val progress = if (status.totalBytes > 0L) {
                        (status.downloadedBytes.toFloat() / status.totalBytes).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100).toInt()}%")
                    OutlinedButton(
                        onClick = { viewModel.pauseModel(model.artifact.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Pause download") }
                }
                is ModelStatus.Paused -> {
                    Text("${formatBytes(status.downloadedBytes)} downloaded")
                    Button(
                        onClick = { viewModel.downloadModel(model.artifact.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Resume download") }
                }
                is ModelStatus.Verifying -> {
                    Text("Verifying checksum")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is ModelStatus.Ready -> Text("Ready · ${model.artifact.storageKind}")
                is ModelStatus.Error -> {
                    Text(status.message, color = MaterialTheme.colorScheme.error)
                    Button(
                        onClick = { viewModel.downloadModel(model.artifact.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Retry download") }
                }
                ModelStatus.NotInstalled ->
                    Button(
                        onClick = { viewModel.downloadModel(model.artifact.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Download") }
                ModelStatus.Unavailable -> Text("Unavailable on this device")
            }
        }
    }
}

@Composable
private fun SetupCard(onChooseFolder: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose persistent storage", style = MaterialTheme.typography.titleLarge)
            Text("ChatBuddy stores model bundles and indexes in a folder you choose with Android SAF.")
            Button(
                onClick = onChooseFolder,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Choose persistent SAF folder" }
            ) { Text("Choose SAF folder") }
        }
    }
}

private fun titleFor(tab: HomeTab): String = when (tab) {
    HomeTab.CHAT -> "ChatBuddy"
    HomeTab.TRANSLATE -> "Translate"
    HomeTab.OCR -> "OCR"
    HomeTab.SETTINGS -> "Settings"
}

private fun tabLabel(tab: HomeTab): String = titleFor(tab)

private fun tabIcon(tab: HomeTab) = when (tab) {
    HomeTab.CHAT -> Icons.AutoMirrored.Outlined.Chat
    HomeTab.TRANSLATE -> Icons.Outlined.Language
    HomeTab.OCR -> Icons.Outlined.CameraAlt
    HomeTab.SETTINGS -> Icons.Outlined.Settings
}

private fun shareText(context: android.content.Context, text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }, "Share text"))
}
