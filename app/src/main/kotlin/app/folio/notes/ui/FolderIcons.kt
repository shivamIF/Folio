package app.folio.notes.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector
import app.folio.core.model.FolderIcon

/** Artwork for each folder icon (Material Icons, Apache 2.0). NONE has no glyph. */
val FolderIcon.vector: ImageVector?
    get() = when (this) {
        FolderIcon.NONE -> null
        FolderIcon.BOOK -> Icons.AutoMirrored.Filled.MenuBook
        FolderIcon.NOTES -> Icons.Filled.Description
        FolderIcon.SCHOOL -> Icons.Filled.School
        FolderIcon.INSTITUTION -> Icons.Filled.AccountBalance
        FolderIcon.LAW -> Icons.Filled.Gavel
        FolderIcon.GLOBE -> Icons.Filled.Public
        FolderIcon.HISTORY -> Icons.Filled.HistoryEdu
        FolderIcon.ETHICS -> Icons.Filled.Balance
        FolderIcon.ECONOMY -> Icons.Filled.Payments
        FolderIcon.CHART -> Icons.Filled.BarChart
        FolderIcon.SCIENCE -> Icons.Filled.Science
        FolderIcon.BIOLOGY -> Icons.Filled.Biotech
        FolderIcon.MATH -> Icons.Filled.Calculate
        FolderIcon.FUNCTIONS -> Icons.Filled.Functions
        FolderIcon.CODE -> Icons.Filled.Code
        FolderIcon.LANGUAGE -> Icons.Filled.Translate
        FolderIcon.PSYCHOLOGY -> Icons.Filled.Psychology
        FolderIcon.IDEA -> Icons.Filled.Lightbulb
        FolderIcon.COMPASS -> Icons.Filled.Explore
        FolderIcon.ART -> Icons.Filled.Palette
        FolderIcon.MUSIC -> Icons.Filled.MusicNote
        FolderIcon.WORK -> Icons.Filled.Work
        FolderIcon.HOME -> Icons.Filled.Home
        FolderIcon.HEART -> Icons.Filled.Favorite
        FolderIcon.STAR -> Icons.Filled.Star
        FolderIcon.SMILE -> Icons.Filled.Mood
    }

val FolderIcon.label: String get() = if (this == FolderIcon.NONE) "No icon" else name.lowercase().replaceFirstChar { it.uppercase() }
