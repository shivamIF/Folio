package app.folio.notes.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.folio.core.model.Cover
import app.folio.core.model.CoverStyle
import app.folio.core.model.Folder
import app.folio.core.model.NotebookMeta
import app.folio.core.model.PaperTemplate
import app.folio.notes.ui.CoverColors
import java.text.DateFormat
import java.util.Date

private sealed interface Dialog {
    data object NewNotebook : Dialog
    data object NewFolder : Dialog
    data object Settings : Dialog
    data class EditNotebook(val meta: NotebookMeta) : Dialog
    data class RenameFolder(val folder: Folder) : Dialog
    data class ConfirmDeleteForever(val meta: NotebookMeta) : Dialog
    data object ConfirmEmptyTrash : Dialog
    data class ConfirmDeleteFolder(val folder: Folder) : Dialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenNotebook: (String) -> Unit,
    importRequest: android.net.Uri? = null,
    onImportHandled: () -> Unit = {},
    vm: LibraryViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pickPdf = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.importDocument(uri, onCreated = onOpenNotebook) }
    // A PDF opened or shared from another app.
    LaunchedEffect(importRequest) {
        if (importRequest != null) { vm.importDocument(importRequest, onCreated = onOpenNotebook); onImportHandled() }
    }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var searching by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    BackHandler(enabled = state.showingTrash) { vm.showTrash(false) }
    BackHandler(enabled = !state.showingTrash && state.folderId != null) { vm.goUp() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) OutlinedTextField(
                        value = state.query, onValueChange = vm::setQuery, singleLine = true,
                        placeholder = { Text("Search notebooks") }, modifier = Modifier.fillMaxWidth().testTag("search"),
                    ) else Text(if (state.showingTrash) "Trash" else state.currentFolder?.name ?: "Notebooks", fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    if (state.showingTrash) IconButton(onClick = { vm.showTrash(false) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to notebooks")
                    } else if (state.folderId != null) IconButton(onClick = vm::goUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to parent folder")
                    }
                },
                actions = if (state.showingTrash) ({
                    TextButton(onClick = { dialog = Dialog.ConfirmEmptyTrash }, enabled = state.trashed.isNotEmpty()) { Text("Empty trash") }
                }) else ({
                    IconButton(onClick = { searching = !searching; if (!searching) vm.setQuery("") }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort") }
                        DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                            SortOrder.entries.forEach { o ->
                                DropdownMenuItem(
                                    text = { Text((if (o == state.sort) "✓ " else "") + o.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                    onClick = { vm.setSort(o); sortMenu = false },
                                )
                            }
                        }
                    }
                    // Any file: the type is detected from its contents (PDF, GoodNotes).
                    IconButton(onClick = { pickPdf.launch(arrayOf("*/*")) }, enabled = !state.importing) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "Import PDF or GoodNotes")
                    }
                    IconButton(onClick = { dialog = Dialog.NewFolder }) { Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder") }
                    IconButton(onClick = { vm.showTrash(true) }) { Icon(Icons.Outlined.Delete, contentDescription = "Trash") }
                    IconButton(onClick = { dialog = Dialog.Settings }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                }),
            )
        },
        floatingActionButton = {
            if (!state.showingTrash) ExtendedFloatingActionButton(
                onClick = { dialog = Dialog.NewNotebook },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New notebook") },
                modifier = Modifier.testTag("new-notebook").semantics { contentDescription = "New notebook" },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (state.importing) androidx.compose.material3.LinearProgressIndicator(
            Modifier.fillMaxWidth().padding(pad).semantics { contentDescription = "Importing" },
        )
        if (state.showingTrash) {
            TrashContent(state, pad, onRestore = vm::restoreNotebook, onDeleteForever = { dialog = Dialog.ConfirmDeleteForever(it) })
            return@Scaffold
        }
        val empty = state.subfolders.isEmpty() && state.notebooks.isEmpty()
        if (empty) {
            Box(Modifier.fillMaxSize().padding(pad).padding(24.dp), contentAlignment = Alignment.Center) {
                if (!state.loading) Text(
                    if (state.query.isNotBlank()) "No notebooks match \"${state.query}\"" else "No notebooks yet. Tap New notebook to start writing.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else LazyVerticalGrid(
            columns = GridCells.Adaptive(170.dp),
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            if (state.subfolders.isNotEmpty()) {
                items(state.subfolders, key = { "f" + it.id }) { f ->
                    FolderCard(f, onOpen = { vm.openFolder(f.id) },
                        onRename = { dialog = Dialog.RenameFolder(f) }, onDelete = { dialog = Dialog.ConfirmDeleteFolder(f) })
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(0.dp)) }
            }
            items(state.notebooks, key = { it.id }) { nb ->
                NotebookCard(
                    nb, onOpen = { onOpenNotebook(nb.id) },
                    onEdit = { dialog = Dialog.EditNotebook(nb) },
                    onToggleFavorite = { vm.updateNotebook(nb.copy(favorite = !nb.favorite)) },
                    preview = state.defaults.showsPreview(nb),
                    onDelete = {
                        vm.trashNotebook(nb.id)
                        scope.launch {
                            val r = snackbar.showSnackbar("\"${nb.title}\" moved to Trash", actionLabel = "Undo", duration = SnackbarDuration.Long)
                            if (r == SnackbarResult.ActionPerformed) vm.restoreNotebook(nb.id)
                        }
                    },
                )
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        Dialog.NewNotebook -> NotebookDialog(null, state.defaults, onDismiss = { dialog = null }) { title, cover, paper ->
            dialog = null
            vm.createNotebook(title, cover, paper, onCreated = onOpenNotebook)
        }
        is Dialog.EditNotebook -> NotebookDialog(d.meta, state.defaults, onDismiss = { dialog = null }) { title, cover, _ ->
            dialog = null
            vm.updateNotebook(d.meta.copy(title = title.ifBlank { d.meta.title }, cover = cover))
        }
        Dialog.Settings -> SettingsDialog(state.defaults, onDismiss = { dialog = null }) { dialog = null; vm.saveDefaults(it) }
        Dialog.NewFolder -> FolderDialog("New folder", "", onDismiss = { dialog = null }) { name, color ->
            dialog = null; vm.createFolder(name, color)
        }
        is Dialog.RenameFolder -> FolderDialog("Rename folder", d.folder.name, onDismiss = { dialog = null }) { name, _ ->
            dialog = null; vm.renameFolder(d.folder.id, name)
        }
        is Dialog.ConfirmDeleteForever -> ConfirmDialog(
            "Delete \"${d.meta.title}\" forever?", "This permanently removes the notebook and all its pages. It can't be undone.",
            onDismiss = { dialog = null }, onConfirm = { dialog = null; vm.deleteForever(d.meta.id) },
        )
        Dialog.ConfirmEmptyTrash -> ConfirmDialog(
            "Empty Trash?", "All ${state.trashed.size} notebooks in Trash will be deleted forever. This can't be undone.",
            onDismiss = { dialog = null }, onConfirm = { dialog = null; vm.emptyTrash() },
        )
        is Dialog.ConfirmDeleteFolder -> ConfirmDialog(
            "Delete folder \"${d.folder.name}\"?", "Notebooks inside are kept and moved up one level.",
            onDismiss = { dialog = null }, onConfirm = { dialog = null; vm.deleteFolder(d.folder.id) },
        )
    }
}

@Composable
private fun FolderCard(f: Folder, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .semantics { contentDescription = "Folder ${f.name}" },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1.35f).clip(RoundedCornerShape(16.dp)).background(Color(f.color)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Folder, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(48.dp)) }
            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp), fontWeight = FontWeight.Medium)
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun NotebookCard(nb: NotebookMeta, onOpen: () -> Unit, onEdit: () -> Unit, onToggleFavorite: () -> Unit, preview: Boolean, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .semantics { contentDescription = "Notebook ${nb.title}" }.testTag("notebook-${nb.title}"),
        ) {
            if (preview) PagePreview(nb, Modifier.fillMaxWidth().aspectRatio(0.75f))
            else CoverArt(nb.cover, nb.title, Modifier.fillMaxWidth().aspectRatio(0.75f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                if (nb.favorite) Icon(Icons.Default.Star, contentDescription = "Favourite", tint = Color(0xFFE9C46A), modifier = Modifier.size(16.dp))
                Text(nb.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            }
            Text(
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(nb.modifiedAt)) + " · ${nb.pageIds.size} page" + if (nb.pageIds.size == 1) "" else "s",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename & cover") }, onClick = { menu = false; onEdit() })
            DropdownMenuItem(text = { Text(if (nb.favorite) "Remove from favourites" else "Add to favourites") }, onClick = { menu = false; onToggleFavorite() })
            DropdownMenuItem(text = { Text("Move to Trash") }, onClick = { menu = false; onDelete() })
        }
    }
}

/** First page of a notebook, rendered in the background; the cover shows until it's ready. */
@Composable
private fun PagePreview(nb: NotebookMeta, modifier: Modifier = Modifier) {
    val thumbs = (androidx.compose.ui.platform.LocalContext.current.applicationContext as app.folio.notes.FolioApp).thumbnails
    val bmp by androidx.compose.runtime.produceState(thumbs.cached(nb), nb.id, nb.modifiedAt) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { thumbs.load(nb) }
    }
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .semantics { contentDescription = "First page of ${nb.title}" },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) androidx.compose.foundation.Image(
            b.asImageBitmap(), contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        ) else CoverArt(nb.cover, nb.title, Modifier.fillMaxSize())
    }
}

@Composable
private fun TrashContent(state: LibraryUiState, pad: androidx.compose.foundation.layout.PaddingValues, onRestore: (String) -> Unit, onDeleteForever: (NotebookMeta) -> Unit) {
    Column(Modifier.fillMaxSize().padding(pad)) {
        Text(
            "Notebooks in Trash are deleted forever after ${LibraryViewModel.TRASH_DAYS} days.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        if (state.trashed.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Trash is empty", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(170.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            items(state.trashed, key = { it.id }) { nb ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    Column(
                        Modifier.combinedClickable(onClick = { menu = true }, onLongClick = { menu = true })
                            .semantics { contentDescription = "Trashed notebook ${nb.title}" },
                    ) {
                        if (state.defaults.showsPreview(nb)) PagePreview(nb, Modifier.fillMaxWidth().aspectRatio(0.75f))
                        else CoverArt(nb.cover, nb.title, Modifier.fillMaxWidth().aspectRatio(0.75f))
                        Text(nb.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                        val daysLeft = LibraryViewModel.TRASH_DAYS - ((System.currentTimeMillis() - (nb.deletedAt ?: 0)) / 86_400_000L).toInt()
                        Text(
                            "Deleted in ${daysLeft.coerceAtLeast(0)} day" + if (daysLeft == 1) "" else "s",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        )
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Restore") }, onClick = { menu = false; onRestore(nb.id) })
                        DropdownMenuItem(text = { Text("Delete forever") }, onClick = { menu = false; onDeleteForever(nb) })
                    }
                }
            }
        }
    }
}

/** Original cover artwork: a coloured board with a spine and an optional decoration. */
@Composable
fun CoverArt(cover: Cover, title: String, modifier: Modifier = Modifier) {
    val base = Color(cover.color)
    Box(modifier.clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp)).background(base)) {
        Box(Modifier.width(12.dp).fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
        when (cover.style) {
            CoverStyle.PLAIN -> Unit
            CoverStyle.STRIPE -> Box(Modifier.fillMaxWidth().height(28.dp).align(Alignment.BottomCenter).padding(bottom = 18.dp).background(Color.White.copy(alpha = 0.35f)))
            CoverStyle.CORNER -> Box(Modifier.size(56.dp).align(Alignment.TopEnd).clip(RoundedCornerShape(bottomStart = 56.dp)).background(Color.White.copy(alpha = 0.3f)))
        }
        Box(
            Modifier.align(Alignment.Center).padding(horizontal = 22.dp).clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.92f)).padding(horizontal = 10.dp, vertical = 6.dp),
        ) { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, color = Color(0xFF293241)) }
    }
}

@Composable
private fun ColorRow(colors: List<Long>, selected: Long, onPick: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { c ->
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                    .border(if (c == selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .combinedClickable(onClick = { onPick(c) })
                    .semantics { contentDescription = "Colour ${"%08X".format(c)}" + if (c == selected) ", selected" else "" },
            )
        }
    }
}

@Composable
private fun NotebookDialog(
    existing: NotebookMeta?, defaults: NotebookDefaults, onDismiss: () -> Unit, onDone: (String, Cover, PaperTemplate) -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var color by remember { mutableStateOf(existing?.cover?.color ?: CoverColors.first()) }
    var style by remember { mutableStateOf(existing?.cover?.style ?: CoverStyle.PLAIN) }
    var paper by remember { mutableStateOf(existing?.paper ?: defaults.paper) }
    val confirm = { onDone(title, Cover(color, style), paper) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New notebook" else "Edit notebook") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CoverArt(Cover(color, style), title.ifBlank { "Untitled" }, Modifier.width(90.dp).aspectRatio(0.75f))
                    OutlinedTextField(
                        title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.testTag("title-field"),
                        // On a landscape tablet the keyboard can cover the dialog buttons; "Done" confirms directly.
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
                        keyboardActions = KeyboardActions(onDone = { confirm() }),
                    )
                }
                Text("Cover", style = MaterialTheme.typography.labelLarge)
                ColorRow(CoverColors.take(5), color) { color = it }
                ColorRow(CoverColors.drop(5), color) { color = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoverStyle.entries.forEach { s -> FilterChip(s == style, { style = s }, label = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                }
                if (existing == null) {
                    Text("Paper", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaperTemplate.entries.forEach { p -> FilterChip(p == paper, { paper = p }, label = { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                    }
                    Text(
                        "Page: ${defaults.describe()} · change the default in Settings",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { confirm() }, modifier = Modifier.testTag("create")) { Text(if (existing == null) "Create" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FolderDialog(heading: String, initial: String, onDismiss: () -> Unit, onDone: (String, Long) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var color by remember { mutableStateOf(CoverColors[3]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                if (initial.isEmpty()) { ColorRow(CoverColors.take(5), color) { color = it }; ColorRow(CoverColors.drop(5), color) { color = it } }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(name, color) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsDialog(current: NotebookDefaults, onDismiss: () -> Unit, onSave: (NotebookDefaults) -> Unit) {
    var size by remember { mutableStateOf(current.size) }
    var landscape by remember { mutableStateOf(current.landscape) }
    var paper by remember { mutableStateOf(current.paper) }
    var preview by remember { mutableStateOf(current.libraryPreview) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Default page size for new notebooks", style = MaterialTheme.typography.labelLarge)
                PageSizeChoice.entries.forEach { c ->
                    FilterChip(c == size, { size = c }, label = { Text(c.label) }, modifier = Modifier.fillMaxWidth())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!landscape, { landscape = false }, label = { Text("Portrait") })
                    FilterChip(landscape, { landscape = true }, label = { Text("Landscape") })
                }
                Text("Library shows", style = MaterialTheme.typography.labelLarge)
                LibraryPreview.entries.forEach { p ->
                    FilterChip(p == preview, { preview = p }, label = { Text(p.label) }, modifier = Modifier.fillMaxWidth())
                }
                Text("Default paper", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaperTemplate.entries.forEach { p -> FilterChip(p == paper, { paper = p }, label = { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                }
                Text(
                    "Existing notebooks keep their size. Auto shapes the page like this screen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(NotebookDefaults(size, landscape, paper, preview)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
