package com.navigator.app.ui.screens

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.navigator.app.settings.AppSettings
import com.navigator.app.ui.OpenDashPermissions
import com.navigator.app.ui.components.Eyebrow
import com.navigator.app.ui.components.KtmOutlineButton
import com.navigator.app.ui.components.KtmPrimaryButton
import com.navigator.app.ui.theme.Barlow
import com.navigator.app.ui.theme.BarlowCondensed
import com.navigator.app.ui.theme.JetBrainsMono
import com.navigator.app.ui.theme.Ktm
import com.navigator.app.ui.theme.OpenDashIcons

/**
 * First-run onboarding: a five-step walkthrough — brand welcome, two feature
 * highlights (dash mirroring, handlebar remote), a name step, and an explained
 * permission checklist. Nothing is requested silently on launch: the rider
 * grants permissions here with context, which also fixes the crash where the
 * pairing screen scanned before the Bluetooth permission existed.
 *
 * The intro steps are skippable straight to setup; every step is individually
 * scrollable so the layout survives small screens and short aspect ratios
 * without clipping the pinned action buttons.
 */
private const val STEP_COUNT = 6
private const val STEP_NAME = 4

@Composable
fun OnboardingScreen(settings: AppSettings, onComplete: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var userName by remember { mutableStateOf(settings.userName ?: "") }

    // "Skip all": one tap out of the whole wizard (R21 field request - after a
    // reinstall everything is already granted and the walkthrough is friction).
    // Still fires the runtime-permission request so a genuinely fresh install
    // isn't left with a scan that can't run; the system returns instantly when
    // everything is already granted. Completes regardless of the answer, same
    // as the permission step's "Proceed anyway".
    val skipPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        settings.onboardingComplete = true
        onComplete()
    }
    val skipAll = {
        settings.userName = userName
        skipPermLauncher.launch(OpenDashPermissions.runtimePermissions())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ktm.Screen)
            .systemBarsPadding()
            .padding(horizontal = 22.dp)
            .padding(top = 12.dp, bottom = 18.dp),
    ) {
        OnboardingHeader(step = step)
        Spacer(Modifier.height(26.dp))

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(280)) { w -> dir * w / 6 } + fadeIn(tween(220)))
                    .togetherWith(
                        slideOutHorizontally(tween(280)) { w -> -dir * w / 6 } + fadeOut(tween(160))
                    )
            },
            label = "onboarding-step",
            modifier = Modifier.weight(1f),
        ) { current ->
            Column(modifier = Modifier.fillMaxSize()) {
                when (current) {
                    0 -> WelcomeStep(
                        onContinue = { step = 1 },
                        onSkip = skipAll,
                    )
                    1 -> FeatureStep(
                        eyebrow = "Mirror",
                        headline = "See it\nwithout\nlooking down.",
                        body = "Incoming notifications and live turn-by-turn navigation are mirrored " +
                            "straight to your bike's dash — so your phone stays in your pocket.",
                        illustration = { DashPreview() },
                        onContinue = { step = 2 },
                        onBack = { step = 0 },
                        onSkip = skipAll,
                    )
                    2 -> FeatureStep(
                        eyebrow = "Handlebar remote",
                        headline = "Control it\nfrom the bar.",
                        body = "Hold Back on the dash to hand its buttons to your phone: music, volume, " +
                            "calls, Google Assistant and Maps — hands-free while you ride.",
                        illustration = { RemotePreview() },
                        onContinue = { step = 3 },
                        onBack = { step = 1 },
                        onSkip = skipAll,
                    )
                    3 -> RideIntelligenceStep(
                        settings = settings,
                        onContinue = { step = STEP_NAME },
                        onBack = { step = 2 },
                        onSkip = skipAll,
                    )
                    STEP_NAME -> NameStep(
                        userName = userName,
                        onNameChange = { userName = it; settings.userName = it },
                        onBack = { step = 3 },
                        onContinue = { step = 5 },
                    )
                    else -> PermissionsStep(
                        onBack = { step = STEP_NAME },
                        onProceed = {
                            settings.userName = userName
                            settings.onboardingComplete = true
                            onComplete()
                        },
                    )
                }
            }
        }
    }
}

/** Wordmark + a segmented progress indicator that widens the current step. */
@Composable
private fun OnboardingHeader(step: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("NAVIGATOR", color = Ktm.White, fontFamily = BarlowCondensed,
            fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, fontSize = 22.sp)
        Text("GEN3", color = Ktm.Orange, fontFamily = BarlowCondensed,
            fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, fontSize = 22.sp)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(STEP_COUNT) { i ->
                Box(
                    modifier = Modifier
                        .size(width = if (i == step) 20.dp else 7.dp, height = 7.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                i == step -> Ktm.Orange
                                i < step -> Ktm.OrangeDeep
                                else -> Ktm.Border
                            }
                        ),
                )
            }
        }
    }
}

/* ----------------------------------------------------------------------- */
/* Intro steps                                                             */
/* ----------------------------------------------------------------------- */

@Composable
private fun ColumnScope.WelcomeStep(onContinue: () -> Unit, onSkip: () -> Unit) {
    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(4.dp))
        BrandBadge()
        Spacer(Modifier.height(24.dp))
        Eyebrow("Welcome", color = Ktm.Orange, fontSize = 12, letterSpacing = 2.0)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your bike's\ndash, just got\nsmarter.",
            color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic, fontSize = 40.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Navigator Gen3 mirrors your notifications and navigation to the KTM dash, and turns " +
                "the handlebar remote into a hands-free controller for your phone.",
            color = Ktm.Muted2, fontFamily = Barlow, fontSize = 15.sp, lineHeight = 22.sp,
        )
        Spacer(Modifier.height(18.dp))
        PrivacyRow()
    }
    Spacer(Modifier.height(14.dp))
    KtmPrimaryButton("Get started", onClick = onContinue)
    IntroFooter(onBack = null, onSkip = onSkip)
}

@Composable
private fun ColumnScope.FeatureStep(
    eyebrow: String,
    headline: String,
    body: String,
    illustration: @Composable () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
    ) {
        Eyebrow(eyebrow, color = Ktm.Orange, fontSize = 12, letterSpacing = 2.0)
        Spacer(Modifier.height(8.dp))
        Text(
            headline,
            color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic, fontSize = 38.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            body,
            color = Ktm.Muted2, fontFamily = Barlow, fontSize = 15.sp, lineHeight = 22.sp,
        )
        Spacer(Modifier.height(24.dp))
        illustration()
    }
    Spacer(Modifier.height(14.dp))
    KtmPrimaryButton("Continue", onClick = onContinue)
    IntroFooter(onBack = onBack, onSkip = onSkip)
}

/**
 * Feature step 3: the riding brain - approach beeps, spoken turns, engine
 * detection, and the GPX ride log - with the two opt-ins that matter up
 * front (voice prompts, power saver) so the rider decides here rather than
 * discovering them in Settings later.
 */
@Composable
private fun ColumnScope.RideIntelligenceStep(
    settings: AppSettings,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    var powerSave by remember { mutableStateOf(settings.powerSaveEnabled) }
    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
    ) {
        Eyebrow("Ride intelligence", color = Ktm.Orange, fontSize = 12, letterSpacing = 2.0)
        Spacer(Modifier.height(8.dp))
        Text(
            "It listens,\nbeeps, and\nkeeps the log.",
            color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic, fontSize = 38.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Stereo beeps count you down to every turn (left ear = left turn) and go quiet when " +
                "you're stopped. The phone's motion sensor learns your engine's rumble - calibrate " +
                "it later from Settings - and every ride is saved as a GPX with a speed-colored map " +
                "that knows traffic from a chai stop.",
            color = Ktm.Muted2, fontFamily = Barlow, fontSize = 15.sp, lineHeight = 22.sp,
        )
        Spacer(Modifier.height(20.dp))
        OnboardToggleCard(
            title = "Power saver",
            desc = "Full-rate GPS + sensors only while the phone is on bike power; gentle on battery otherwise. Turn off for full rate always.",
            checked = powerSave,
            onToggle = { powerSave = it; settings.powerSaveEnabled = it },
        )
    }
    Spacer(Modifier.height(14.dp))
    KtmPrimaryButton("Continue", onClick = onContinue)
    IntroFooter(onBack = onBack, onSkip = onSkip)
}

@Composable
private fun OnboardToggleCard(title: String, desc: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusRow))
            .background(Ktm.Surface)
            .border(1.dp, if (checked) Ktm.ConnBorder else Ktm.Border, RoundedCornerShape(Ktm.RadiusRow))
            .clickable { onToggle(!checked) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                fontSize = 16.sp, letterSpacing = 0.3.sp)
            Text(desc, color = Ktm.Dim, fontFamily = Barlow, fontSize = 12.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 2.dp))
        }
        com.navigator.app.ui.components.KtmToggle(checked, onToggle)
    }
}

/** Back (left) + Skip (right) row shared by the intro steps. */
@Composable
private fun IntroFooter(onBack: (() -> Unit)?, onSkip: (() -> Unit)?) {
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Text(
                "Back", color = Ktm.Dim, fontFamily = Barlow, fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onBack).padding(8.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (onSkip != null) {
            Text(
                "Skip all", color = Ktm.Dim, fontFamily = Barlow, fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onSkip).padding(8.dp),
            )
        }
    }
}

/** Orange-ringed bike glyph badge for the welcome hero. */
@Composable
private fun BrandBadge() {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(RoundedCornerShape(Ktm.RadiusCard))
            .background(Ktm.Surface)
            .border(1.dp, Ktm.Bezel, RoundedCornerShape(Ktm.RadiusCard)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(OpenDashIcons.Bike, contentDescription = null, tint = Ktm.Orange,
            modifier = Modifier.size(40.dp))
    }
}

/** "No sign-in · no cloud · on-device" reassurance chip row. */
@Composable
private fun PrivacyRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusRow))
            .background(Ktm.ConnBanner)
            .border(1.dp, Ktm.ConnBorder, RoundedCornerShape(Ktm.RadiusRow))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(OpenDashIcons.ShieldCheck, contentDescription = null, tint = Ktm.Green,
            modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text("Private by design", color = Ktm.White, fontFamily = BarlowCondensed,
                fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.3.sp)
            Text("No sign-in, no cloud — everything stays on your phone.",
                color = Ktm.ConnSub, fontFamily = Barlow, fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 1.dp))
        }
    }
}

/* ----------------------------------------------------------------------- */
/* Feature illustrations                                                   */
/* ----------------------------------------------------------------------- */

/** Miniature of the bike dash the app drives (turn info + notification banner). */
@Composable
private fun DashPreview() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusCard))
            .background(Ktm.ScreenDeep)
            .border(1.dp, Ktm.Bezel, RoundedCornerShape(Ktm.RadiusCard))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(OpenDashIcons.TurnArrow, contentDescription = null, tint = Ktm.Orange,
                modifier = Modifier.size(44.dp))
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("400", color = Ktm.White, fontFamily = BarlowCondensed,
                        fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 40.sp)
                    Text(" m", color = Ktm.Muted, fontFamily = BarlowCondensed,
                        fontWeight = FontWeight.Bold, fontSize = 20.sp,
                        modifier = Modifier.padding(bottom = 4.dp))
                }
                Text("OUTER RING ROAD", color = Ktm.Orange, fontFamily = BarlowCondensed,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.5.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("ETA", color = Ktm.Dim2, fontFamily = BarlowCondensed,
                    fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.5.sp)
                Text("11:09", color = Ktm.White, fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Ktm.RowDivider))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Ktm.DashBanner)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(OpenDashIcons.ShieldCheck, contentDescription = null, tint = Ktm.Orange,
                modifier = Modifier.size(22.dp))
            Text("Messages — running 5 min late", color = Ktm.TextPrimary, fontFamily = BarlowCondensed,
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/** The four handlebar keys and what they do (see HandlebarRemote). */
@Composable
private fun RemotePreview() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusCard))
            .background(Ktm.Surface)
            .border(1.dp, Ktm.Border, RoundedCornerShape(Ktm.RadiusCard))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RemoteRow("UP", "Volume up · hold: Assistant")
        RemoteRow("DOWN", "Volume down · hold: Maps")
        RemoteRow("SET", "Play / pause · answer call")
        RemoteRow("BACK", "Next · double: previous · reject")
    }
}

@Composable
private fun RemoteRow(key: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(58.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Ktm.Orange)
                .padding(vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(key, color = Ktm.Screen, fontFamily = BarlowCondensed,
                fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
        }
        Text(action, color = Ktm.TextSecondary, fontFamily = Barlow, fontSize = 14.sp,
            modifier = Modifier.padding(start = 14.dp))
    }
}

/* ----------------------------------------------------------------------- */
/* Name + permission steps                                                 */
/* ----------------------------------------------------------------------- */

@Composable
private fun ColumnScope.NameStep(
    userName: String,
    onNameChange: (String) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Eyebrow("Almost there", color = Ktm.Orange, fontSize = 12, letterSpacing = 2.0)
        Spacer(Modifier.height(8.dp))
        Text(
            "What should\nwe call you?",
            color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic, fontSize = 38.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Used to personalize the dash greeting and test messages. This never leaves your phone.",
            color = Ktm.Muted2, fontFamily = Barlow, fontSize = 15.sp, lineHeight = 22.sp,
        )
        Spacer(Modifier.height(26.dp))
        Eyebrow("Your name", color = Ktm.Dim, fontSize = 11, letterSpacing = 1.5)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = userName,
            onValueChange = onNameChange,
            singleLine = true,
            placeholder = { Text("e.g. Rider", color = Ktm.Dim, fontFamily = Barlow) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            colors = ktmFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(14.dp))
    KtmPrimaryButton("Continue", enabled = userName.isNotBlank(), onClick = onContinue)
    IntroFooter(onBack = onBack, onSkip = null)
}

@Composable
private fun ColumnScope.PermissionsStep(
    onBack: () -> Unit,
    onProceed: () -> Unit,
) {
    val context = LocalContext.current

    // Re-checked on resume so grants made in system settings reflect here.
    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { tick++ }

    // Read live status (tick forces recomposition after grants).
    val btGranted = tick.let { OpenDashPermissions.bluetoothGranted(context) }
    val notifPost = tick.let { OpenDashPermissions.notificationsPostGranted(context) }
    val phone = tick.let { OpenDashPermissions.phoneGranted(context) }
    val notifAccess = tick.let { OpenDashPermissions.notificationAccessGranted(context) }
    val overlay = tick.let { AndroidSettings.canDrawOverlays(context) }
    val runtimeAllGranted = btGranted && notifPost && phone

    Eyebrow("Permissions", color = Ktm.Orange, fontSize = 12, letterSpacing = 2.0)
    Spacer(Modifier.height(8.dp))
    Text(
        "A few permissions.",
        color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Italic, fontSize = 34.sp, letterSpacing = (-0.4).sp,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Navigator Gen3 needs these to talk to your bike. Nothing leaves your phone.",
        color = Ktm.Muted2, fontFamily = Barlow, fontSize = 14.sp, lineHeight = 20.sp,
    )

    Spacer(Modifier.height(18.dp))
    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PermissionCard(
            icon = OpenDashIcons.Bluetooth,
            title = "Nearby devices",
            desc = "Find and connect to your bike's dash over Bluetooth. Required to pair.",
            granted = btGranted,
            onGrant = { permLauncher.launch(OpenDashPermissions.runtimePermissions()) },
        )
        PermissionCard(
            icon = OpenDashIcons.Bell,
            title = "Notification access",
            desc = "Read incoming notifications so they can be mirrored to the dash.",
            granted = notifAccess,
            onGrant = { context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        )
        PermissionCard(
            icon = OpenDashIcons.Phone,
            title = "Phone & calls",
            desc = "Answer and reject calls from the handlebar remote while riding.",
            granted = phone,
            onGrant = { permLauncher.launch(OpenDashPermissions.runtimePermissions()) },
        )
        PermissionCard(
            icon = OpenDashIcons.Navigation,
            title = "Display over other apps",
            desc = "Lets a handlebar hold open Google Assistant or Maps while the phone is in your pocket.",
            granted = overlay,
            onGrant = {
                context.startActivity(
                    Intent(
                        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}"),
                    )
                )
            },
        )
        PermissionCard(
            icon = OpenDashIcons.Navigation,
            title = "Post notifications",
            desc = "Show the persistent connection status and dash-mirroring service.",
            granted = notifPost,
            onGrant = { permLauncher.launch(OpenDashPermissions.runtimePermissions()) },
        )
    }

    Spacer(Modifier.height(12.dp))
    if (!runtimeAllGranted) {
        KtmPrimaryButton("Grant permissions") {
            permLauncher.launch(OpenDashPermissions.runtimePermissions())
        }
        Spacer(Modifier.height(10.dp))
        KtmOutlineButton(
            if (btGranted) "Proceed" else "Proceed anyway",
            onClick = onProceed,
        )
    } else {
        KtmPrimaryButton("Proceed", onClick = onProceed)
    }
    IntroFooter(onBack = onBack, onSkip = null)
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    desc: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ktm.RadiusRow))
            .background(Ktm.Surface)
            .border(1.dp, if (granted) Ktm.ConnBorder else Ktm.Border, RoundedCornerShape(Ktm.RadiusRow))
            .clickable(enabled = !granted, onClick = onGrant)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (granted) Ktm.Green else Ktm.Orange,
            modifier = Modifier.size(24.dp))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, color = Ktm.White, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                fontSize = 17.sp, letterSpacing = 0.3.sp)
            Text(desc, color = Ktm.Dim, fontFamily = Barlow, fontSize = 12.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 2.dp))
        }
        if (granted) {
            Icon(OpenDashIcons.Check, contentDescription = "Granted", tint = Ktm.Green, modifier = Modifier.size(20.dp))
        } else {
            Text("GRANT", color = Ktm.Orange, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                fontSize = 12.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
internal fun ktmFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Ktm.Orange,
    unfocusedBorderColor = Ktm.Border,
    focusedTextColor = Ktm.White,
    unfocusedTextColor = Ktm.TextPrimary,
    cursorColor = Ktm.Orange,
    focusedContainerColor = Ktm.SurfaceAlt,
    unfocusedContainerColor = Ktm.SurfaceAlt,
)
