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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import com.chatbuddy.domain.model.ModelStatus
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
import com.chatbuddy.presentation.ocr.OcrBoundingBoxOverlay
import com.chatbuddy.presentation.ocr.OcrImagePreview
import com.chatbuddy.presentation.settings.PersonaViewModel
import com.chatbuddy.presentation.rag.DocumentViewModel
import com.chatbuddy.utils.formatBytes
import com.chatbuddy.domain.model.ChatMessage
import com.chatbuddy.domain.model.OcrResult
import com.chatbuddy.domain.model.Persona
import java.util.UUID
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.content.ContextCompat

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
    Scaffold(
        topBar = { TopAppBar(title = { Text(titleFor(state.selectedTab)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
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
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (compact) Modifier.padding(horizontal = 16.dp) else Modifier.padding(horizontal = 24.dp))
                    .widthIn(max = 600.dp)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (state.selectedTab) {
                    HomeTab.CHAT -> ChatTab(state, viewModel) { folderPicker.launch(null) }
                    HomeTab.TRANSLATE -> TranslateTab(state, viewModel)
                    HomeTab.OCR -> OcrTab(viewModel)
                    HomeTab.SETTINGS -> SettingsTab(state, viewModel) { folderPicker.launch(null) }
                }
            }
        }
    }
}

@Composable
private fun ChatTab(state: HomeUiState, viewModel: HomeViewModel, chooseFolder: () -> Unit) {
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
        ChatContent(state, viewModel)
    }
}

@Composable
private fun ChatContent(state: HomeUiState, homeViewModel: HomeViewModel) {
    val viewModel = hiltViewModel<ChatViewModel>()
    val chatState by viewModel.state.collectAsStateWithLifecycleCompat()
    LaunchedEffect(state.pendingChatText) {
        state.pendingChatText?.let {
            viewModel.setInput(it)
            homeViewModel.consumeChatText()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Local RAG chat", style = MaterialTheme.typography.headlineSmall)
        Text("Model file is managed through SAF. The native llama.cpp runtime must be available before answers can be generated.")
        if (chatState.activePersona == null) {
            Text("Create and activate a persona in Settings before chatting.", color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().height(260.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(chatState.messages, key = { it.id }) { message -> ChatBubble(message) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use document evidence", modifier = Modifier.weight(1f))
            Switch(checked = chatState.useRag, onCheckedChange = viewModel::setUseRag)
        }
        OutlinedTextField(
            value = chatState.input,
            onValueChange = viewModel::setInput,
            label = { Text("Message") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 5
        )
        Button(onClick = viewModel::send, enabled = !chatState.streaming && chatState.input.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (chatState.streaming) "Generating" else "Send message")
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(if (message.role == ChatMessage.Role.USER) "You" else "ChatBuddy", style = MaterialTheme.typography.labelLarge)
            Text(message.text)
        }
    }
}

@Composable
private fun TranslateTab(state: HomeUiState, homeViewModel: HomeViewModel) {
    val viewModel = hiltViewModel<TranslationViewModel>()
    val translationState by viewModel.state.collectAsStateWithLifecycleCompat()
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
        if (translationState.loading || translationState.result != null || translationState.error != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (translationState.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    translationState.result?.let { result ->
                        Row {
                            val clipboard = LocalClipboardManager.current
                            val context = LocalContext.current
                            IconButton(onClick = { clipboard.setText(AnnotatedString(result.text)) }) { Icon(Icons.Outlined.ContentCopy, "Copy translation") }
                            IconButton(onClick = { shareText(context, result.text) }) { Icon(Icons.Outlined.Share, "Share translation") }
                            Text(providerLabel(result.provider), modifier = Modifier.padding(start = 8.dp, top = 12.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    translationState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val status = when {
                    state.modelDownloading -> "Downloading language pack"
                    state.modelChecking -> "Checking translation"
                    state.modelReady -> "Offline translation ready"
                    else -> "Translation needs a language pack"
                }
                Text(status, style = MaterialTheme.typography.titleSmall)
                Text("ML Kit offline provider", style = MaterialTheme.typography.bodySmall)
                state.modelError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            when {
                state.modelDownloading -> CircularProgressIndicator(modifier = Modifier.width(24.dp), strokeWidth = 2.dp)
                !state.modelReady && !state.modelChecking -> OutlinedButton(onClick = viewModel::downloadLanguageModels) {
                    Text("Get pack")
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
        if (maxWidth < 360.dp) {
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
private fun OcrTab(homeViewModel: HomeViewModel) {
    val viewModel = hiltViewModel<OcrViewModel>()
    val translationViewModel = hiltViewModel<TranslationViewModel>()
    val ocrState by viewModel.state.collectAsStateWithLifecycleCompat()
    val translationState by translationViewModel.state.collectAsStateWithLifecycleCompat()
    val ocrResult = ocrState.result
    val selectedImageUri = ocrState.imageUri
    val context = LocalContext.current
    var cameraError by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraEnabled by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.recognize(it.toString(), translationState.sourceLanguage) }
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
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(translationState.sourceLanguage) {
        viewModel.setCameraLanguage(translationState.sourceLanguage)
    }
    LaunchedEffect(cameraEnabled, ocrState.result?.text) {
        if (cameraEnabled) {
            ocrState.result?.text?.takeIf(String::isNotBlank)?.let(translationViewModel::setSourceText)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LanguageSelectorRow(translationState, translationViewModel, "Swap OCR and translation languages")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { picker.launch("image/*") }, modifier = Modifier.weight(1f)) {
                Text("Choose image")
            }
            OutlinedButton(
                onClick = {
                    if (cameraEnabled) {
                        cameraEnabled = false
                        cameraError = null
                        viewModel.clearCameraError()
                    } else if (
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        cameraEnabled = true
                    } else {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text(if (cameraEnabled) "Stop camera" else "Use camera") }
        }
        TranslationModelCard(translationState, translationViewModel, compact = true)
        if (cameraEnabled && cameraError == null) {
            CameraPreview(
                analyzer = viewModel.cameraAnalyzer,
                onError = { message ->
                    cameraError = message
                    viewModel.setCameraError(message)
                },
                onReady = {
                    cameraError = null
                    viewModel.clearCameraError()
                },
                overlay = { OcrBoundingBoxOverlay(ocrResult) },
                modifier = Modifier.semantics { contentDescription = "Live camera OCR preview" }
            )
            Text("Live OCR is running", style = MaterialTheme.typography.labelMedium)
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
        ocrResult?.takeIf { it.text.isNotBlank() }?.let { result ->
            OcrResultPanel(
                result = result,
                translationState = translationState,
                onCopy = { clipboard.setText(AnnotatedString(result.text)) },
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
            if (translationState.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            translationState.result?.let { translated ->
                Text("Live translation", style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(translated.text) }
                Text(providerLabel(translated.provider), style = MaterialTheme.typography.bodySmall)
            }
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
    val documentViewModel = hiltViewModel<DocumentViewModel>()
    val personaState by personaViewModel.state.collectAsStateWithLifecycleCompat()
    val documentState by documentViewModel.state.collectAsStateWithLifecycleCompat()
    var personaMessage by remember { mutableStateOf<String?>(null) }
    var documentMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { personaViewModel.events.collect { personaMessage = it } }
    LaunchedEffect(Unit) { documentViewModel.events.collect { documentMessage = it } }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var temperature by rememberSaveable { mutableStateOf(0.7f) }
    var topP by rememberSaveable { mutableStateOf(0.9f) }
    var maxTokens by rememberSaveable { mutableStateOf("1024") }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { documentViewModel.add(it.toString()) }
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
            ModelCard(model, viewModel)
        }
    }
    Text("AI persona", style = MaterialTheme.typography.headlineSmall)
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
            personaViewModel.save(Persona(UUID.randomUUID().toString(), name, description, prompt, temperature, topP, maxTokens.toIntOrNull() ?: 0))
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Save persona") }
    personaMessage?.let { Text(it, color = if (it.contains("saved", true)) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error) }
    if (personaState.personas.isEmpty()) {
        Text("No persona saved")
    } else {
        personaState.personas.forEach { persona ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(persona.name, style = MaterialTheme.typography.titleMedium)
                        Text(persona.description.ifBlank { "No description" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Button(onClick = { personaViewModel.setActive(persona.id) }, enabled = !persona.active) { Text(if (persona.active) "Active" else "Activate") }
                    IconButton(onClick = { personaViewModel.delete(persona.id) }) { Icon(Icons.Outlined.Delete, "Delete ${persona.name}") }
                }
            }
        }
    }
    Text("RAG documents", style = MaterialTheme.typography.headlineSmall)
    Text("TXT, PDF, and DOCX are read from SAF as bounded streams and chunked without loading a 200 MB document into memory.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = { documentPicker.launch(arrayOf("text/plain", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) }, modifier = Modifier.fillMaxWidth()) {
        Text("Add document")
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
private fun ModelCard(model: com.chatbuddy.domain.model.ModelState, viewModel: HomeViewModel) {
    val status = model.status
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(model.artifact.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${formatBytes(model.artifact.sizeBytes)} · ${model.artifact.license}", style = MaterialTheme.typography.bodySmall)
            when (status) {
                is ModelStatus.Queued -> {
                    Text("Download scheduled · waiting for network")
                    OutlinedButton(onClick = { viewModel.pauseModel(model.artifact.id) }) { Text("Pause download") }
                }
                is ModelStatus.Downloading -> {
                    val progress = if (status.totalBytes > 0L) {
                        (status.downloadedBytes.toFloat() / status.totalBytes).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${(progress * 100).toInt()}%", modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { viewModel.pauseModel(model.artifact.id) }) { Text("Pause download") }
                    }
                }
                is ModelStatus.Paused -> {
                    Text("${formatBytes(status.downloadedBytes)} downloaded")
                    Button(onClick = { viewModel.downloadModel(model.artifact.id) }) { Text("Resume download") }
                }
                is ModelStatus.Verifying -> {
                    Text("Verifying checksum")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is ModelStatus.Ready -> Text("Ready · ${model.artifact.storageKind}")
                is ModelStatus.Error -> {
                    Text(status.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { viewModel.downloadModel(model.artifact.id) }) { Text("Retry download") }
                }
                ModelStatus.NotInstalled ->
                    Button(onClick = { viewModel.downloadModel(model.artifact.id) }) { Text("Download") }
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
