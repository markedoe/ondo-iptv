package com.markedoe.ondo.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.markedoe.ondo.R
import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.data.OndoRepository.PinCheck
import com.markedoe.ondo.ui.tv.FocusOnOpen
import com.markedoe.ondo.ui.tv.focusFrame
import com.markedoe.ondo.ui.tv.rememberTvTextField
import com.markedoe.ondo.ui.tv.tvSafeArea
import kotlin.math.abs
import kotlinx.coroutines.delay

/** The profiles' colours and icons, the same as Apple's `ProfileStyle` (docs/PROFILES.md). */
object ProfileStyle {
    /** Indigo, pink, orange, teal, green, purple, blue, red. */
    val colors = listOf(
        Color(0xFF5856D6), Color(0xFFFF2D55), Color(0xFFFF9500), Color(0xFF30B0C7),
        Color(0xFF34C759), Color(0xFFAF52DE), Color(0xFF007AFF), Color(0xFFFF3B30),
    )

    fun color(index: Int): Color = colors[abs(index) % colors.size]

    /** Android's drawing of each of [Profile.SYMBOLS]. */
    fun icon(symbol: String): ImageVector? = when (symbol) {
        "person.fill" -> Icons.Rounded.Person
        "face.smiling" -> Icons.Rounded.Face
        "star.fill" -> Icons.Rounded.Star
        "heart.fill" -> Icons.Rounded.Favorite
        "gamecontroller.fill" -> Icons.Rounded.SportsEsports
        "film.fill" -> Icons.Rounded.Movie
        "pawprint.fill" -> Icons.Rounded.Pets
        "leaf.fill" -> Icons.Rounded.Eco
        else -> null
    }

    /** An emoji to draw: not the initial, not an icon, short and without letters or digits; else null. */
    fun emoji(symbol: String): String? =
        symbol.takeIf { it != Profile.INITIAL && icon(it) == null && it.isNotBlank() && it.length <= 16 && it.none { c -> c.isLetterOrDigit() } }

    /** Every avatar offered: the initial, the icons, then the emoji. */
    val choices: List<String> = listOf(Profile.INITIAL) + Profile.SYMBOLS + Profile.EMOJI
}

/** A profile's round avatar: its colour, with its icon, emoji or initial; a small lock when it has a PIN. */
@Composable
fun ProfileAvatar(profile: Profile, size: Dp, modifier: Modifier = Modifier, showLock: Boolean = true) {
    Box(modifier.size(size)) {
        Box(Modifier.size(size).clip(CircleShape).background(ProfileStyle.color(profile.colorIndex)), contentAlignment = Alignment.Center) {
            val icon = ProfileStyle.icon(profile.symbol)
            val emoji = ProfileStyle.emoji(profile.symbol)
            when {
                icon != null -> Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
                emoji != null -> Text(emoji, fontSize = (size.value * 0.5f).sp, textAlign = TextAlign.Center)
                else -> Text(profile.initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp, textAlign = TextAlign.Center)
            }
        }
        if (showLock && profile.isProtected && size >= 40.dp) {
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = size * 0.04f, y = size * 0.04f).size(size * 0.32f).clip(CircleShape).background(Color(0xFF2C2C2E)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.18f)) }
        }
    }
}

/** What the profile switcher and "Who's watching?" do. */
class ProfilesActions(
    /** The profile chosen, its PIN checked: it opens ([alwaysOpen]: ticked "Always open this profile"). */
    val onSwitch: (Profile, alwaysOpen: Boolean) -> Unit = { _, _ -> },
    val onCheckPin: (Profile, String) -> PinCheck = { _, _ -> PinCheck.OK },
    val onAdd: () -> Unit = {},
    val onEdit: (Profile) -> Unit = {},
    /** Ticks or unticks "Always open this profile" for the profile in use (the switcher). */
    val onAlwaysOpen: (Boolean) -> Unit = {},
    val onBack: () -> Unit = {},
)

/**
 * The profiles, side by side: "Who's watching?" at launch ([whoIsWatching]), else the switcher of the top bar's avatar,
 * which also adds, edits and deletes them (Edit, then a profile). OK on a profile with a PIN asks for it first.
 */
@Composable
fun ProfilesScreen(
    profiles: List<Profile>,
    active: Profile,
    launchProfileId: String?,
    whoIsWatching: Boolean,
    actions: ProfilesActions,
) = OnBackground { ProfilesPage(profiles, active, launchProfileId, whoIsWatching, actions) }

/**
 * The page's text and icons in the theme's colour for its background, whatever is around it ("Who's watching?" opens
 * before the tabs, outside any Surface).
 */
@Composable
private fun OnBackground(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfilesPage(
    profiles: List<Profile>,
    active: Profile,
    launchProfileId: String?,
    whoIsWatching: Boolean,
    actions: ProfilesActions,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var alwaysOpen by rememberSaveable { mutableStateOf(false) }
    var asking by remember { mutableStateOf<Profile?>(null) }
    if (!whoIsWatching) BackHandler { if (editing) editing = false else actions.onBack() }
    fun choose(profile: Profile) {
        when {
            editing -> actions.onEdit(profile)
            profile.id == active.id && !whoIsWatching -> actions.onBack()
            profile.isProtected && profile.id != active.id -> asking = profile
            else -> actions.onSwitch(profile, alwaysOpen)
        }
    }
    // A profile with a PIN: the PIN is asked in the page, in place of the profiles; Cancel comes back to them.
    asking?.let { profile ->
        BackHandler { asking = null }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).tvSafeArea(), contentAlignment = Alignment.Center) {
            PinForm(
                title = profile.name,
                message = stringResource(R.string.pin_enter),
                check = { pin -> actions.onCheckPin(profile, pin) },
                onSuccess = {
                    asking = null
                    actions.onSwitch(profile, alwaysOpen)
                },
                onDismiss = { asking = null },
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }
    val first = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).tvSafeArea().padding(24.dp)
            .testTag(if (whoIsWatching) "whoIsWatching" else "profileSwitcher"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(if (whoIsWatching) R.string.whos_watching else if (editing) R.string.edit_profile else R.string.profiles),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            profiles.forEachIndexed { index, profile ->
                ProfileTile(
                    profile,
                    current = profile.id == active.id && !whoIsWatching,
                    editing = editing,
                    onClick = { choose(profile) },
                    modifier = (if (index == 0) Modifier.focusRequester(first) else Modifier).testTag("profile-${profile.id}"),
                )
            }
            if (!whoIsWatching && !editing) AddProfileTile(actions.onAdd)
        }
        FocusOnOpen(first)
        if (whoIsWatching) {
            CheckRow(stringResource(R.string.always_open_profile), alwaysOpen, { alwaysOpen = it }, Modifier.testTag("alwaysOpen"))
        } else {
            CheckRow(stringResource(R.string.always_open_profile), launchProfileId == active.id, actions.onAlwaysOpen, Modifier.testTag("alwaysOpen"))
            OutlinedButton(onClick = { editing = !editing }, modifier = Modifier.focusFrame(RoundedCornerShape(24.dp)).testTag("editProfiles")) {
                Icon(if (editing) Icons.Rounded.Check else Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (editing) R.string.done else R.string.edit))
            }
        }
    }
}

@Composable
private fun ProfileTile(profile: Profile, current: Boolean, editing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(128.dp)
            .focusFrame(RoundedCornerShape(16.dp), grow = 1.06f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .semantics { selected = current }
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            ProfileAvatar(profile, 96.dp)
            if (editing) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.edit_profile), tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }
        Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val notes = listOfNotNull(
            stringResource(R.string.current_profile).takeIf { current },
            stringResource(R.string.kids).takeIf { profile.isKids },
        )
        if (notes.isNotEmpty()) {
            Text(notes.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun AddProfileTile(onClick: () -> Unit) {
    val label = stringResource(R.string.add_profile)
    Column(
        Modifier
            .width(128.dp)
            .focusFrame(RoundedCornerShape(16.dp), grow = 1.06f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(10.dp)
            .testTag("addProfile"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(96.dp).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(40.dp))
        }
        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .focusFrame(RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .clickable { onChange(!checked) }
            .semantics { selected = checked }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchLine(text: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .focusFrame(RoundedCornerShape(14.dp), grow = 1.01f)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onChange(!checked) }
            .semantics { selected = checked }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A PIN field: digits only, hidden, [Profile.MAX_PIN_LENGTH] at most. */
@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val field = rememberTvTextField()
    OutlinedTextField(
        value = value,
        onValueChange = { typed -> onChange(typed.filter { it in '0'..'9' }.take(Profile.MAX_PIN_LENGTH)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        readOnly = field.readOnly,
        modifier = modifier.fillMaxWidth().then(field.modifier),
    )
}

/** Asks for a PIN; [check] says whether it opens. Wrong: asked again; too many wrong: the wait is said. */
@Composable
private fun PinForm(title: String, message: String, check: (String) -> PinCheck, onSuccess: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var pin by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<PinCheck?>(null) }
    val focus = remember { FocusRequester() }
    fun submit() {
        if (pin.isEmpty()) return
        val answer = check(pin)
        pin = ""
        if (answer == PinCheck.OK) onSuccess() else result = answer
    }
    Column(modifier.widthIn(max = 480.dp).testTag("pinDialog"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            when (result) {
                PinCheck.WRONG -> stringResource(R.string.pin_wrong)
                PinCheck.WAIT -> stringResource(R.string.pin_wait)
                else -> message
            },
            color = if (result == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("pinMessage"),
        )
        PinField(pin, { pin = it }, stringResource(R.string.pin), ::submit, Modifier.focusRequester(focus).testTag("pinField"))
        FocusOnOpen(focus)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            TextButton(onClick = onDismiss, modifier = Modifier.focusFrame(RoundedCornerShape(20.dp)).testTag("pinCancel")) { Text(stringResource(R.string.cancel)) }
            Button(onClick = ::submit, enabled = pin.isNotEmpty(), modifier = Modifier.focusFrame(RoundedCornerShape(20.dp)).testTag("pinOpen")) { Text(stringResource(R.string.open)) }
        }
    }
}

/**
 * In a Kids profile, what could weaken it (Settings, the categories, a new playlist, the profiles) first asks a parent's
 * PIN ([locked]); Back leaves. Otherwise [content] shows.
 */
@Composable
fun ParentGate(locked: Boolean, check: (String) -> PinCheck, onBack: () -> Unit, content: @Composable () -> Unit) {
    if (!locked) return content()
    OnBackground { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).tvSafeArea().testTag("parentGate"), contentAlignment = Alignment.Center) {
        PinForm(stringResource(R.string.parent_pin), stringResource(R.string.parent_pin_hint), check, onSuccess = {}, onDismiss = onBack, modifier = Modifier.padding(24.dp))
    } }
}

/** Adds a profile ([profile] null) or changes one: name, colour, avatar, Kids, PIN; deletes it when [canDelete]. */
@Composable
fun ProfileEditScreen(
    profile: Profile?,
    canDelete: Boolean,
    onSave: (name: String, colorIndex: Int, symbol: String, isKids: Boolean, pin: String?, removePin: Boolean) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) = OnBackground { ProfileEditPage(profile, canDelete, onSave, onDelete, onBack) }

@Composable
private fun ProfileEditPage(
    profile: Profile?,
    canDelete: Boolean,
    onSave: (name: String, colorIndex: Int, symbol: String, isKids: Boolean, pin: String?, removePin: Boolean) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(profile?.name.orEmpty()) }
    var colorIndex by rememberSaveable { mutableStateOf(profile?.colorIndex ?: 0) }
    var symbol by rememberSaveable { mutableStateOf(profile?.symbol ?: Profile.INITIAL) }
    var isKids by rememberSaveable { mutableStateOf(profile?.isKids ?: false) }
    var pin by rememberSaveable { mutableStateOf("") }
    var removePin by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    val valid = name.isNotBlank() && (pin.isEmpty() || Profile.isValidPin(pin))
    fun save() {
        if (valid) onSave(name, colorIndex, symbol, isKids, pin.ifEmpty { null }, removePin && pin.isEmpty())
    }
    val first = remember { FocusRequester() }
    // As the playlist form: the page, then the form scrolling inside it.
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).tvSafeArea().testTag("profileEdit"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { Column(
        Modifier.verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 640.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(stringResource(if (profile == null) R.string.new_profile else R.string.edit_profile), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        ProfileAvatar(Profile(id = Profile.DEFAULT_ID, name = name, colorIndex = colorIndex, symbol = symbol, isKids = isKids), 88.dp)
        Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val field = rememberTvTextField()
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME) },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                readOnly = field.readOnly,
                modifier = Modifier.fillMaxWidth().then(field.modifier).focusRequester(first).testTag("profileName"),
            )
            // The page gives its name field the focus itself, once it can take it (after the page's fade).
            LaunchedEffect(first) {
                repeat(NAME_FOCUS_TRIES) {
                    if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                    delay(50)
                }
            }
            Text(stringResource(R.string.profile_color), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProfileStyle.colors.forEachIndexed { index, color ->
                    Box(
                        Modifier
                            .size(40.dp)
                            .focusFrame(CircleShape, grow = 1f)
                            .clip(CircleShape)
                            .background(color)
                            .clickable { colorIndex = index }
                            .semantics { selected = index == colorIndex }
                            .testTag("profileColor-$index"),
                        contentAlignment = Alignment.Center,
                    ) { if (index == colorIndex) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White) }
                }
            }
            Text(stringResource(R.string.profile_avatar), style = MaterialTheme.typography.titleSmall)
            val initialLabel = stringResource(R.string.profile_initial)
            // Rows of [AVATARS_PER_ROW]: the remote goes down from one to the next.
            ProfileStyle.choices.withIndex().chunked(AVATARS_PER_ROW).forEach { line -> Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                line.forEach { (index, choice) ->
                    Box(
                        Modifier
                            .focusFrame(CircleShape, grow = 1f)
                            .clip(CircleShape)
                            .border(2.dp, if (choice == symbol) MaterialTheme.colorScheme.onBackground else Color.Transparent, CircleShape)
                            .clickable { symbol = choice }
                            .semantics {
                                selected = choice == symbol
                                if (choice == Profile.INITIAL) contentDescription = initialLabel
                            }
                            .padding(3.dp)
                            .testTag("profileSymbol-$index"),
                    ) { ProfileAvatar(Profile(id = Profile.DEFAULT_ID, name = name, colorIndex = colorIndex, symbol = choice), 40.dp, showLock = false) }
                }
            } }
            SwitchLine(stringResource(R.string.kids_profile), stringResource(R.string.kids_profile_hint), isKids, { isKids = it }, Modifier.testTag("profileKids"))
            Text(stringResource(R.string.profile_pin), style = MaterialTheme.typography.titleSmall)
            PinField(
                pin,
                { pin = it },
                stringResource(if (profile?.isProtected == true) R.string.pin_new_keep else R.string.pin_optional),
                ::save,
                Modifier.testTag("profilePin"),
            )
            Text(stringResource(R.string.pin_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (profile?.isProtected == true) {
                SwitchLine(stringResource(R.string.pin_remove), null, removePin, { removePin = it }, Modifier.testTag("profileRemovePin"))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                if (canDelete) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.focusFrame(RoundedCornerShape(20.dp)).testTag("profileDelete")) {
                        Text(stringResource(R.string.delete_profile), color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onBack, modifier = Modifier.focusFrame(RoundedCornerShape(20.dp)).testTag("profileCancel")) { Text(stringResource(R.string.cancel)) }
                Button(onClick = ::save, enabled = valid, modifier = Modifier.focusFrame(RoundedCornerShape(20.dp)).testTag("profileSave")) { Text(stringResource(R.string.save)) }
            }
        }
    }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_profile)) },
            text = { Text(stringResource(R.string.delete_profile_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }, modifier = Modifier.testTag("confirmOk")) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }, modifier = Modifier.testTag("confirmCancel")) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private const val MAX_NAME = 40

private const val AVATARS_PER_ROW = 8

/** How many times (50 ms apart) the profile page tries to focus its name field. */
private const val NAME_FOCUS_TRIES = 40
