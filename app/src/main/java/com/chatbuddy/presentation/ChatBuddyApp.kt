package com.chatbuddy.presentation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.speech.tts.TextToSpeech
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.chatbuddy.presentation.translate.TranslateMode
import com.chatbuddy.presentation.translate.SayMiConversationMode
import com.chatbuddy.domain.model.LanguageOption
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import com.chatbuddy.domain.model.LiveConversationTurn
import com.chatbuddy.domain.model.LiveSpeaker
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
                when (state.selectedTab) {
                    HomeTab.CHAT -> {
                        Column(
                            modifier = contentModifier.fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            ChatTab(state, viewModel, snackbarHostState) { folderPicker.launch(null) }
                        }
                    }
                    HomeTab.TRANSLATE -> {
                        Column(
                            modifier = contentModifier.fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            TranslateTab(state, viewModel, snackbarHostState)
                        }
                    }
                    else -> {
                        val scrollState = rememberScrollState()
                        Column(
                            modifier = contentModifier.verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            when (state.selectedTab) {
                                HomeTab.OCR -> OcrTab(viewModel, snackbarHostState, scrollState)
                                HomeTab.SETTINGS -> SettingsTab(state, viewModel) { folderPicker.launch(null) }
                                HomeTab.CHAT, HomeTab.TRANSLATE -> Unit
                            }
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
                                "Searching web & scraping content…",
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
            viewModel.setTranslateMode(TranslateMode.TEXT_INPUT)
            homeViewModel.consumeTranslationText()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Material 3 Clean Mode Selector (Segmented TabRow)
        TabRow(
            selectedTabIndex = if (translationState.translateMode == TranslateMode.LIVE_CONVERSATION) 0 else 1,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
        ) {
            Tab(
                selected = translationState.translateMode == TranslateMode.LIVE_CONVERSATION,
                onClick = { viewModel.setTranslateMode(TranslateMode.LIVE_CONVERSATION) },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "Percakapan Langsung",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp)
            )
            Tab(
                selected = translationState.translateMode == TranslateMode.TEXT_INPUT,
                onClick = { viewModel.setTranslateMode(TranslateMode.TEXT_INPUT) },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "Terjemahan Teks",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }

        if (translationState.translateMode == TranslateMode.LIVE_CONVERSATION) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                TwoWayLiveConversationSection(
                    state = translationState,
                    viewModel = viewModel,
                    microphonePermissionGranted = microphonePermissionGranted,
                    onRequestMicrophone = { microphonePermission.launch(Manifest.permission.RECORD_AUDIO) },
                    onOpenModelSetup = { homeViewModel.selectTab(HomeTab.SETTINGS) }
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TextInputTranslationSection(
                    state = translationState,
                    viewModel = viewModel,
                    snackbarHostState = snackbarHostState
                )
            }
        }
    }
}

@Composable
private fun LanguagePickerDialog(
    title: String,
    currentLanguageTag: String,
    languages: List<LanguageOption>,
    onSelectLanguage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(languages) { lang ->
                    val isSelected = lang.tag == currentLanguageTag
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectLanguage(lang.tag)
                                onDismiss()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${lang.displayName} (${lang.tag.uppercase()})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Tutup")
            }
        }
    )
}

@Composable
private fun SayMiLanguageHeader(
    sourceLangName: String,
    targetLangName: String,
    headsetConnected: Boolean,
    onOpenSourcePicker: () -> Unit,
    onOpenTargetPicker: () -> Unit,
    onSwap: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Pill: Source (Speaker 1)
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpenSourcePicker)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Icon(
                        imageVector = if (headsetConnected) Icons.Outlined.Headphones else Icons.Outlined.Smartphone,
                        contentDescription = null,
                        tint = Color(0xFFE54D38),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = sourceLangName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Center Swap Button
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier
                .size(38.dp)
                .clickable(onClick = onSwap)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.SwapHoriz,
                    contentDescription = "Tukar bahasa",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Right Pill: Target (Speaker 2)
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpenTargetPicker)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Smartphone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = targetLangName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun SayMiTravelToolbar(
    mode: SayMiConversationMode,
    headsetConnected: Boolean,
    hasHistory: Boolean,
    onSelectMode: (SayMiConversationMode) -> Unit,
    onClearHistory: () -> Unit
) {
    var showModeMenu by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Mode Selector Pill
            Box {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.clickable { showModeMenu = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (mode == SayMiConversationMode.FREE_TALK) "🌐 Bicara Bebas" else "🎧 Sentuh 2-Arah",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            imageVector = Icons.Outlined.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                DropdownMenu(
                    expanded = showModeMenu,
                    onDismissRequest = { showModeMenu = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("🌐 Mode Bicara Bebas", fontWeight = FontWeight.SemiBold)
                                Text("Auto turn-taking tanpa sentuh layar (Timekettle)", style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = {
                            onSelectMode(SayMiConversationMode.FREE_TALK)
                            showModeMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("🎧 Mode Sentuh 2-Arah", fontWeight = FontWeight.SemiBold)
                                Text("Sentuh tombol mic saat berbicara (Lenovo Erazer)", style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = {
                            onSelectMode(SayMiConversationMode.TOUCH_TWO_WAY)
                            showModeMenu = false
                        }
                    )
                }
            }

            // Status Badges & Actions
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Audio Device Badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (headsetConnected) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (headsetConnected) Icons.Outlined.Headphones else Icons.Outlined.Smartphone,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = if (headsetConnected) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (headsetConnected) "TWS" else "Ponsel",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (headsetConnected) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 100% Offline Badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E8E3E).copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "100% OFFLINE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E8E3E),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                // Clear history button
                if (hasHistory) {
                    IconButton(
                        onClick = onClearHistory,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Hapus percakapan",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioWaveformBar(
    tint: Color,
    isPlaying: Boolean = false,
    label: String? = null,
    onReplay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "audio_bar")
    val animScale by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(350, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "waveform_scale"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isPlaying) tint.copy(alpha = 0.32f) else tint.copy(alpha = 0.15f),
        modifier = modifier.clickable(onClick = onReplay)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
                contentDescription = if (isPlaying) "Hentikan suara" else "Putar ulang suara",
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint
                )
            }
            val barHeights = listOf(6, 12, 16, 10, 14, 8, 18, 12, 6, 14, 10, 16, 8, 12)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                barHeights.forEachIndexed { index, h ->
                    val dynamicH = if (isPlaying) {
                        (h * if (index % 2 == 0) animScale else (1.8f - animScale)).coerceIn(4f, 22f)
                    } else {
                        h.toFloat()
                    }
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(dynamicH.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(tint)
                    )
                }
            }
        }
    }
}


@Composable
private fun PulsingWaveform(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "waveform")
    val anim1 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "w1"
    )
    val anim2 by transition.animateFloat(
        initialValue = 0.8f, targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(550, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "w2"
    )
    val anim3 by transition.animateFloat(
        initialValue = 0.2f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(350, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "w3"
    )
    val anim4 by transition.animateFloat(
        initialValue = 0.6f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "w4"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        val scales = listOf(anim1, anim2, anim3, anim4, anim2, anim1)
        scales.forEach { scale ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((20 * scale).coerceAtLeast(4f).dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun SayMiDialogueBubble(
    turn: LiveConversationTurn,
    partnerFlipped: Boolean,
    activePlayingText: String?,
    onReplayTranslation: () -> Unit,
    onReplaySource: () -> Unit
) {
    val isSpeakerA = turn.speaker == LiveSpeaker.SPEAKER_A
    val shouldFlip = !isSpeakerA && partnerFlipped

    val coralRed = Color(0xFFE54D38)
    val charcoal = Color(0xFF2C2D30)

    val containerColor = if (isSpeakerA) coralRed else charcoal
    val contentColor = Color.White

    val isTranslationPlaying = activePlayingText == turn.translatedText
    val isSourcePlaying = activePlayingText == turn.sourceText

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (shouldFlip) Modifier.graphicsLayer { rotationZ = 180f } else Modifier)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Icon(
                imageVector = if (isSpeakerA) Icons.Outlined.Smartphone else Icons.Outlined.Public,
                contentDescription = null,
                tint = if (isSpeakerA) coralRed else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = if (isSpeakerA) "Anda (${turn.sourceLanguage.uppercase()})" else "Lawan Bicara (${turn.sourceLanguage.uppercase()})",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isSpeakerA) 4.dp else 16.dp,
                bottomEnd = if (isSpeakerA) 16.dp else 4.dp
            ),
            color = containerColor,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "“${turn.sourceText}”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.82f)
                )

                HorizontalDivider(color = contentColor.copy(alpha = 0.15f))

                Text(
                    text = turn.translatedText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSourcePlaying) contentColor.copy(alpha = 0.32f) else contentColor.copy(alpha = 0.12f),
                        modifier = Modifier.clickable(onClick = onReplaySource)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (isSourcePlaying) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
                                contentDescription = if (isSourcePlaying) "Hentikan suara asli" else "Putar suara asli",
                                tint = contentColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Asli (${turn.sourceLanguage.uppercase()})",
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor
                            )
                        }
                    }

                    AudioWaveformBar(
                        tint = contentColor,
                        isPlaying = isTranslationPlaying,
                        label = "Terjemahan (${turn.targetLanguage.uppercase()})",
                        onReplay = onReplayTranslation
                    )
                }
            }
        }
    }
}


@Composable
private fun LiveInterimCard(
    activeSpeaker: LiveSpeaker?,
    livePhase: LiveTranslationPhase,
    liveTranscript: String,
    sourceLang: String,
    targetLang: String,
    partnerFlipped: Boolean
) {
    val isSpeakerA = activeSpeaker == LiveSpeaker.SPEAKER_A
    val shouldFlip = !isSpeakerA && partnerFlipped
    val coralRed = Color(0xFFE54D38)
    val accentColor = if (isSpeakerA) coralRed else MaterialTheme.colorScheme.secondary

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.5.dp, accentColor),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (shouldFlip) Modifier.graphicsLayer { rotationZ = 180f } else Modifier)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (isSpeakerA) "Anda berbicara (${sourceLang.uppercase()})…" else "Lawan bicara berbicara (${targetLang.uppercase()})…",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                }

                PulsingWaveform(color = accentColor)
            }

            Text(
                text = if (liveTranscript.isNotBlank()) "“$liveTranscript”" else "Mendengarkan audio...",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (liveTranscript.isNotBlank()) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = livePhaseLabel(livePhase, activeSpeaker, sourceLang, targetLang),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun TravelCompanionEmptyCard(
    sourceLangName: String,
    targetLangName: String
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xFFE54D38).copy(alpha = 0.12f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Flight,
                        contentDescription = null,
                        tint = Color(0xFFE54D38),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Text(
                text = "AI Travel Companion Offline",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Percakapan dua arah langsung dengan lawan bicara di mana saja tanpa kuota internet. Gunakan mikrofon ponsel atau earphone Bluetooth TWS apapun.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔴 Tombol Kiri", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFE54D38))
                    Text(sourceLangName, style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔵 Tombol Kanan", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                    Text(targetLangName, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SayMiBottomMicControls(
    state: TranslationUiState,
    sourceLangName: String,
    targetLangName: String,
    microphonePermissionGranted: Boolean,
    onRequestMicrophone: () -> Unit,
    onToggleSpeakerA: () -> Unit,
    onToggleSpeakerB: () -> Unit,
    onToggleTts: (Boolean) -> Unit,
    onTogglePartnerFlipped: () -> Unit
) {
    val active = state.liveEnabled
    val isSpeakerAActive = active && state.activeSpeaker == LiveSpeaker.SPEAKER_A
    val isSpeakerBActive = active && state.activeSpeaker == LiveSpeaker.SPEAKER_B
    val coralRed = Color(0xFFE54D38)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Quick auxiliary controls row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // TTS toggle chip
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (state.liveSpeakTranslation) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { onToggleTts(!state.liveSpeakTranslation) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (state.liveSpeakTranslation) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff,
                        contentDescription = null,
                        tint = if (state.liveSpeakTranslation) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (state.liveSpeakTranslation) "Suara TTS Aktif" else "Suara TTS Mati",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (state.liveSpeakTranslation) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 180° Flip toggle chip
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (state.partnerFlipped) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable(onClick = onTogglePartnerFlipped)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SwapHoriz,
                        contentDescription = null,
                        tint = if (state.partnerFlipped) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (state.partnerFlipped) "Rotasi 180° Aktif" else "Rotasi 180°",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (state.partnerFlipped) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Dual Prominent Pill Buttons (SayMi / Lenovo Erazer Style)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Left Pill: Speaker 1 (Anda / Source Language)
            Button(
                onClick = {
                    if (!microphonePermissionGranted && !active) {
                        onRequestMicrophone()
                    } else {
                        onToggleSpeakerA()
                    }
                },
                enabled = !state.liveChecking,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSpeakerAActive) coralRed else coralRed.copy(alpha = 0.15f),
                    contentColor = if (isSpeakerAActive) Color.White else coralRed
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isSpeakerAActive) Icons.Outlined.Stop else Icons.Outlined.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = if (isSpeakerAActive) "Selesai Bicara" else sourceLangName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Right Pill: Speaker 2 (Lawan Bicara / Target Language)
            Button(
                onClick = {
                    if (!microphonePermissionGranted && !active) {
                        onRequestMicrophone()
                    } else {
                        onToggleSpeakerB()
                    }
                },
                enabled = !state.liveChecking,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSpeakerBActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (isSpeakerBActive) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSecondaryContainer
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isSpeakerBActive) Icons.Outlined.Stop else Icons.Outlined.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = if (isSpeakerBActive) "Selesai Bicara" else targetLangName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun TwoWayLiveConversationSection(
    state: TranslationUiState,
    viewModel: TranslationViewModel,
    microphonePermissionGranted: Boolean,
    onRequestMicrophone: () -> Unit,
    onOpenModelSetup: () -> Unit
) {
    val active = state.liveEnabled
    val listState = rememberLazyListState()

    var showSourcePicker by remember { mutableStateOf(false) }
    var showTargetPicker by remember { mutableStateOf(false) }

    val sourceLangName = state.languages.firstOrNull { it.tag == state.sourceLanguage }?.displayName
        ?: state.sourceLanguage.uppercase()
    val targetLangName = state.languages.firstOrNull { it.tag == state.targetLanguage }?.displayName
        ?: state.targetLanguage.uppercase()

    LaunchedEffect(state.conversationTurns.size, state.liveTranscript, active, state.livePhase) {
        val totalItems = state.conversationTurns.size + (if (active) 1 else 0)
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Top SayMi Language Bar: [ 📱 Inggris ▾ ] [ ⇄ ] [ 📱 Indonesia ▾ ]
        SayMiLanguageHeader(
            sourceLangName = sourceLangName,
            targetLangName = targetLangName,
            headsetConnected = state.voiceCapabilities?.headsetConnected == true,
            onOpenSourcePicker = { showSourcePicker = true },
            onOpenTargetPicker = { showTargetPicker = true },
            onSwap = viewModel::swapLanguages
        )

        // 2. SayMi Travel Status Toolbar
        SayMiTravelToolbar(
            mode = state.sayMiMode,
            headsetConnected = state.voiceCapabilities?.headsetConnected == true,
            hasHistory = state.conversationTurns.isNotEmpty(),
            onSelectMode = viewModel::setSayMiMode,
            onClearHistory = viewModel::clearConversationSession
        )

        if (state.liveChecking) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // 3. Central Timeline: "Riwayat Dialog di paling akhir"
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (state.conversationTurns.isEmpty() && !active) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    TravelCompanionEmptyCard(
                        sourceLangName = sourceLangName,
                        targetLangName = targetLangName
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(state.conversationTurns) { turn ->
                        SayMiDialogueBubble(
                            turn = turn,
                            partnerFlipped = state.partnerFlipped,
                            activePlayingText = state.activePlayingAudioText,
                            onReplayTranslation = { viewModel.replayTurnSpeech(turn, speakTranslation = true) },
                            onReplaySource = { viewModel.replayTurnSpeech(turn, speakTranslation = false) }
                        )
                    }

                    if (active) {
                        item {
                            LiveInterimCard(
                                activeSpeaker = state.activeSpeaker,
                                livePhase = state.livePhase,
                                liveTranscript = state.liveTranscript,
                                sourceLang = state.sourceLanguage,
                                targetLang = state.targetLanguage,
                                partnerFlipped = state.partnerFlipped
                            )
                        }
                    }
                }
            }
        }

        // Error banner
        state.liveError?.let { message ->
            val context = LocalContext.current
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    if (state.voiceCapabilities?.whisperReady == false) {
                        OutlinedButton(onClick = onOpenModelSetup, modifier = Modifier.fillMaxWidth()) {
                            Text("Buka Pengaturan Model")
                        }
                    } else if (message.contains("TTS", ignoreCase = true) || message.contains("suara", ignoreCase = true)) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                                runCatching { context.startActivity(intent) }.onFailure {
                                    runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Buka Pengaturan Suara / TTS")
                        }
                    }
                }
            }
        }

        // 4. Fixed Bottom Microphone Controls (Thumb Zone)
        SayMiBottomMicControls(
            state = state,
            sourceLangName = sourceLangName,
            targetLangName = targetLangName,
            microphonePermissionGranted = microphonePermissionGranted,
            onRequestMicrophone = onRequestMicrophone,
            onToggleSpeakerA = { viewModel.toggleSpeaker(LiveSpeaker.SPEAKER_A) },
            onToggleSpeakerB = { viewModel.toggleSpeaker(LiveSpeaker.SPEAKER_B) },
            onToggleTts = viewModel::setLiveSpeakTranslation,
            onTogglePartnerFlipped = viewModel::togglePartnerFlipped
        )
    }

    if (showSourcePicker) {
        LanguagePickerDialog(
            title = "Pilih Bahasa Anda (Speaker 1)",
            currentLanguageTag = state.sourceLanguage,
            languages = state.languages,
            onSelectLanguage = viewModel::setSourceLanguage,
            onDismiss = { showSourcePicker = false }
        )
    }

    if (showTargetPicker) {
        LanguagePickerDialog(
            title = "Pilih Bahasa Lawan Bicara (Speaker 2)",
            currentLanguageTag = state.targetLanguage,
            languages = state.languages,
            onSelectLanguage = viewModel::setTargetLanguage,
            onDismiss = { showTargetPicker = false }
        )
    }
}

@Composable
private fun TextInputTranslationSection(
    state: TranslationUiState,
    viewModel: TranslationViewModel,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LanguageSelectorRow(state, viewModel, "Tukar bahasa sumber dan tujuan")
        TranslationModelCard(state, viewModel)
        OutlinedTextField(
            value = state.sourceText,
            onValueChange = viewModel::setSourceText,
            label = { Text("Ketik teks untuk diterjemahkan") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 8
        )
        if (state.loading || state.result != null || state.error != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    state.result?.let { result ->
                        SelectionContainer { Text(result.text, style = MaterialTheme.typography.bodyLarge) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val clipboard = LocalClipboardManager.current
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(result.text))
                                    scope.launch { snackbarHostState.showSnackbar("Terjemahan disalin") }
                                },
                                modifier = Modifier.semantics { contentDescription = "Salin terjemahan" }
                            ) { Icon(Icons.Outlined.ContentCopy, null) }
                            IconButton(
                                onClick = {
                                    shareText(context, result.text)
                                    scope.launch { snackbarHostState.showSnackbar("Menu bagikan terbuka") }
                                },
                                modifier = Modifier.semantics { contentDescription = "Bagikan terjemahan" }
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
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        TranslationHistoryCard(state, viewModel)
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
private fun ConversationTurnCard(
    turn: LiveConversationTurn,
    onReplay: () -> Unit
) {
    val isSpeakerA = turn.speaker == LiveSpeaker.SPEAKER_A
    val containerColor = if (isSpeakerA) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isSpeakerA) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isSpeakerA) {
                        "Speaker 1 (${turn.sourceLanguage.uppercase()}) → ${turn.targetLanguage.uppercase()}"
                    } else {
                        "Speaker 2 (${turn.sourceLanguage.uppercase()}) → ${turn.targetLanguage.uppercase()}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.8f)
                )
                IconButton(onClick = onReplay, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = "Replay audio",
                        tint = contentColor
                    )
                }
            }
            Text(
                text = "“${turn.sourceText}”",
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.85f)
            )
            HorizontalDivider(color = contentColor.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 2.dp))
            Text(
                text = turn.translatedText,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor
            )
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

private fun livePhaseLabel(
    phase: LiveTranslationPhase,
    speaker: LiveSpeaker? = null,
    sourceLang: String = "en",
    targetLang: String = "id"
): String = when (phase) {
    LiveTranslationPhase.Idle -> "Ready for two-way live conversation"
    LiveTranslationPhase.Starting -> "Preparing on-device voice engine…"
    LiveTranslationPhase.Listening -> {
        val spk = if (speaker == LiveSpeaker.SPEAKER_B) "Speaker 2 (${targetLang.uppercase()})" else "Speaker 1 (${sourceLang.uppercase()})"
        "Listening to $spk…"
    }
    LiveTranslationPhase.Transcribing -> "Transcribing speech on device…"
    LiveTranslationPhase.Translating -> "Translating on device…"
    LiveTranslationPhase.Speaking -> "Playing translated speech…"
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
    snackbarHostState: SnackbarHostState,
    scrollState: ScrollState? = null
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
        captureRequest = 0L
        cameraEnabled = false
        cameraError = null
        viewModel.clearCameraError()
    }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val startCamera: () -> Unit = {
        captureRequest = 0L
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.unfreezeCamera()
            cameraEnabled = true
            cameraError = null
            viewModel.clearCameraError()
            scope.launch { scrollState?.scrollTo(0) }
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
        Unit
    }
    LaunchedEffect(cameraEnabled) {
        if (cameraEnabled) {
            scrollState?.scrollTo(0)
        }
    }
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
        if (!cameraEnabled && selectedImageUri == null) {
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
        if (!cameraEnabled && selectedImageUri == null) {
            TranslationModelCard(translationState, translationViewModel, compact = true)
        }
        if (cameraEnabled && cameraError == null) {
            CameraPreview(
                analyzer = cameraAnalyzer,
                captureRequest = captureRequest,
                onCapturedUri = { uri ->
                    captureRequest = 0L
                    cameraEnabled = false
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
                    if (ocrState.isLiveTranslateEnabled && ocrState.translatedBlocks.isNotEmpty() && ocrResult != null) {
                        TranslatedBlockOverlay(
                            result = ocrResult,
                            blocks = ocrState.translatedBlocks,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        OcrBoundingBoxOverlay(ocrResult, modifier = Modifier.fillMaxSize())
                    }
                    LiveOcrTranscript(
                        result = ocrResult,
                        translationState = translationState,
                        translatedBlocks = ocrState.translatedBlocks,
                        fullTranslation = ocrState.fullTranslation,
                        translationProcessing = ocrState.translationProcessing,
                        translationError = ocrState.translationError,
                        translationProvider = translationState.result
                            ?.takeIf { translationState.sourceText.trim() == ocrResult?.text?.trim() }
                            ?.let { providerLabel(it.provider) }
                            ?: ocrState.translatedBlocks.firstOrNull()?.let {
                                providerLabel(it.provider)
                            },
                        isLiveTranslateEnabled = ocrState.isLiveTranslateEnabled,
                        isFrozen = ocrState.isFrozen,
                        onToggleLiveTranslate = viewModel::toggleLiveTranslate,
                        onDownloadTranslation = translationViewModel::downloadLanguageModels,
                        onStopCamera = stopCamera,
                        onCapture = {
                            viewModel.freezeFrame()
                            captureRequest += 1L
                        },
                        onResumeCamera = startCamera,
                        onSpeakTranslation = { text ->
                            translationViewModel.speak(text, translationState.targetLanguage)
                        },
                        onCopyText = { text ->
                            clipboard.setText(AnnotatedString(text))
                            scope.launch { snackbarHostState.showSnackbar("Translation copied") }
                        },
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
                        captureRequest = 0L
                        viewModel.clearCameraError()
                        startCamera()
                    }) {
                        Text("Retry camera")
                    }
                }
            }
        } else if (selectedImageUri != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = startCamera,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Resume camera (Live scanning)")
                    }
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("image/*")) }
                    ) {
                        Text("Choose image")
                    }
                }
                OcrImagePreview(
                    uri = selectedImageUri,
                    result = ocrResult,
                    translatedBlocks = ocrState.translatedBlocks,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .heightIn(min = 320.dp, max = 580.dp)
                )
            }
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
                fullTranslation = ocrState.fullTranslation.orEmpty(),
                translationProcessing = ocrState.translationProcessing,
                translationError = ocrState.translationError,
                onCopy = {
                    clipboard.setText(AnnotatedString(result.text))
                    scope.launch { snackbarHostState.showSnackbar("OCR text copied") }
                },
                onCopyTranslation = { text ->
                    clipboard.setText(AnnotatedString(text))
                    scope.launch { snackbarHostState.showSnackbar("Translation copied") }
                },
                onSpeakTranslation = { text ->
                    translationViewModel.speak(text, translationState.targetLanguage)
                },
                onSendToChat = { homeViewModel.sendToChat(result.text) },
                onSendToTranslation = { homeViewModel.sendToTranslation(result.text) },
                onTranslate = {
                    viewModel.retryTranslation()
                    translationViewModel.setSourceText(result.text)
                }
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
    fullTranslation: String = "",
    translationProcessing: Boolean,
    translationError: String?,
    onCopy: () -> Unit,
    onCopyTranslation: (String) -> Unit = {},
    onSpeakTranslation: (String) -> Unit = {},
    onSendToChat: () -> Unit,
    onSendToTranslation: () -> Unit,
    onTranslate: () -> Unit
) {
    val displayTranslation = fullTranslation.takeIf { it.isNotBlank() && it.trim() != result.text.trim() }
        ?: translatedBlocks
            .filter { it.translatedText.isNotBlank() && it.translatedText.trim() != it.source.text.trim() }
            .joinToString(" ") { it.translatedText }
            .takeIf { it.isNotBlank() && it.trim() != result.text.trim() }
        ?: translationState.result?.text?.takeIf { it.isNotBlank() && it.trim() != result.text.trim() }

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
            if (displayTranslation != null) {
                Text("Translation", style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Text(displayTranslation)
                }
                Row {
                    IconButton(onClick = { onCopyTranslation(displayTranslation) }) {
                        Icon(Icons.Outlined.ContentCopy, "Copy translation")
                    }
                    IconButton(onClick = { onSpeakTranslation(displayTranslation) }) {
                        Icon(Icons.AutoMirrored.Outlined.VolumeUp, "Speak translation")
                    }
                }
            }
            translationState.result?.takeIf { displayTranslation == null }?.let { translated ->
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
