package dev.tatliving.palmvellum.organizers.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInk
import dev.tatliving.palmvellum.organizers.ui.theme.PalmInkMute
import dev.tatliving.palmvellum.organizers.ui.theme.PalmLine
import dev.tatliving.palmvellum.organizers.ui.theme.PalmOnDark
import dev.tatliving.palmvellum.organizers.ui.theme.PalmSurfaceHi
import dev.tatliving.palmvellum.organizers.ui.theme.PalmSurfaceLo
import dev.tatliving.palmvellum.organizers.ui.theme.PalmTitleBar

/** A "+ new" style action button for the title bar. */
@Composable
fun TitleAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = PalmOnDark,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** Bordered Palm list container. */
@Composable
fun PalmListCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PalmSurfaceLo)
            .border(1.dp, PalmLine),
    ) { content() }
}

/** A single tappable list row: title, optional meta (right), optional body. */
@Composable
fun PalmRow(
    title: String,
    meta: String? = null,
    body: String? = null,
    dim: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    metaColor: Color = PalmInkMute,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = title,
                color = if (dim) PalmInkMute else PalmInk,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (meta != null) {
                Text(text = meta, color = metaColor, fontSize = 13.sp)
            }
        }
        if (!body.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(text = body, color = PalmInkMute, fontSize = 13.sp, maxLines = 2)
        }
    }
}

@Composable
fun PalmDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(PalmLine))
}

/** Horizontal filter strip (To Do open/done/all, Memo all/note, ...). */
@Composable
fun PalmCategoryStrip(
    options: List<Pair<String, String>>, // value to label
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Text(
                text = label,
                color = if (active) PalmOnDark else PalmInk,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .background(
                        color = if (active) PalmTitleBar else PalmSurfaceHi,
                        shape = RoundedCornerShape(4.dp),
                    )
                    .border(1.dp, PalmLine, RoundedCornerShape(4.dp))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Labelled Palm input field. */
@Composable
fun PalmField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    minLines: Int = 1,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, color = PalmInkMute, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            minLines = minLines,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = PalmSurfaceHi,
                unfocusedContainerColor = PalmSurfaceHi,
                focusedBorderColor = PalmTitleBar,
                unfocusedBorderColor = PalmLine,
                focusedTextColor = PalmInk,
                unfocusedTextColor = PalmInk,
                cursorColor = PalmTitleBar,
            ),
        )
    }
}

/** Empty-state placeholder. */
@Composable
fun PalmEmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = PalmInkMute, fontSize = 14.sp)
    }
}

/**
 * Full-screen editor frame: dark header with Cancel / title / Save, body
 * below. Used by every app's add/edit form. Replaces the list while open.
 */
@Composable
fun EditorScaffold(
    title: String,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean = true,
    body: @Composable () -> Unit,
) {
    Surface(color = dev.tatliving.palmvellum.organizers.ui.theme.PalmBg) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = PalmTitleBar, contentColor = PalmOnDark) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .height(44.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Cancel",
                        color = PalmOnDark,
                        fontSize = 15.sp,
                        modifier = Modifier.clickable(onClick = onCancel),
                    )
                    Text(
                        title,
                        color = PalmOnDark,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    Text(
                        "Save",
                        color = if (saveEnabled) PalmOnDark else Color(0x66FFFFFF),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(enabled = saveEnabled, onClick = onSave),
                    )
                }
            }
            Column(Modifier.fillMaxWidth()) { body() }
        }
    }
}
