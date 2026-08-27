package com.chatbuddy.presentation.translate

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.chatbuddy.domain.model.LanguageOption
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
@Composable
internal fun LanguageDropdown(
    label: String,
    selected: String,
    languages: List<LanguageOption>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable(label) { mutableStateOf(false) }
    var query by rememberSaveable("${label}Query") { mutableStateOf("") }
    val selectedLanguage = languages.firstOrNull { it.tag == selected }
    val canExpand = languages.isNotEmpty()
    val filteredLanguages = remember(languages, query) {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        languages.filter { language ->
            normalizedQuery.isBlank() ||
                language.displayName.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                language.tag.lowercase(Locale.ROOT).contains(normalizedQuery)
        }
    }
    val menuExpanded = expanded && filteredLanguages.isNotEmpty()

    LaunchedEffect(selected, selectedLanguage?.displayName) {
        query = selectedLanguage?.displayName ?: selected.ifBlank { "" }
    }

    ExposedDropdownMenuBox(
        expanded = menuExpanded,
        onExpandedChange = {
            if (canExpand) {
                expanded = it
                if (it) query = ""
            }
        },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                expanded = canExpand
            },
            enabled = canExpand,
            singleLine = true,
            label = { Text(label) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
                .semantics { contentDescription = "$label language search" }
        )
        ExposedDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = {
                expanded = false
                query = selectedLanguage?.displayName ?: selected.ifBlank { "" }
            },
            modifier = Modifier.heightIn(max = 280.dp)
        ) {
            filteredLanguages.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.displayName) },
                    onClick = {
                        query = language.displayName
                        expanded = false
                        onSelected(language.tag)
                    }
                )
            }
        }
    }
}
