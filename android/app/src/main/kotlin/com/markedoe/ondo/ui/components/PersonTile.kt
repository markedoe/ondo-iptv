package com.markedoe.ondo.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.markedoe.ondo.ui.model.PersonRow
import com.markedoe.ondo.ui.theme.OndoColors
import com.markedoe.ondo.ui.tv.focusFrame

/** An actor or a director kept: a round photo (TMDb's) over their initials, the name beneath; opens their page. */
@Composable
fun PersonTile(person: PersonRow, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 96.dp) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .focusFrame(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(vertical = 6.dp)
            .testTag("keptPerson-${person.name}"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PersonPhoto(person.name, person.photoUrl, size)
        Text(
            person.name,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
        )
    }
}

/** A round photo over the person's initials, which show while it loads and when there is none. */
@Composable
fun PersonPhoto(name: String, photoUrl: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(OndoColors.SurfaceHigh), contentAlignment = Alignment.Center) {
        Text(personInitials(name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f))
        if (photoUrl != null) {
            AsyncImage(model = photoUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

fun personInitials(name: String): String =
    name.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
