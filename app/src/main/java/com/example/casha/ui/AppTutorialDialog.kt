package com.example.casha.ui

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal class TutorialSpotlights {
    val bounds = mutableStateMapOf<String, Rect>()
}

internal val LocalTutorialSpotlights = compositionLocalOf { TutorialSpotlights() }

@Composable
internal fun Modifier.tutorialTarget(id: String): Modifier {
    val registry = LocalTutorialSpotlights.current
    DisposableEffect(id) {
        onDispose { registry.bounds.remove(id) }
    }
    return this.onGloballyPositioned { coordinates ->
        if (coordinates.isAttached) {
            registry.bounds[id] = coordinates.boundsInRoot()
        }
    }
}

private data class SpotlightStep(
    val page: String,
    val targetId: String,
    val badge: String,
    val title: String,
    val message: String,
    val mood: PetotMood
)

private val SPOTLIGHT_STEPS = listOf(
    SpotlightStep(
        page = "Loans",
        targetId = "ledger-mode",
        badge = "STEP 1 OF 11 · DUAL LEDGERS",
        title = "Dual Debt & Collectible Ledgers",
        message = "Hi, I'm Petot! Casha manages both sides of your money: 'I Owe' tracks debts you pay to lenders, while 'Owed to Me' tracks money people owe you. Each keeps independent totals and payment schedules.",
        mood = PetotMood.HELLO
    ),
    SpotlightStep(
        page = "Loans",
        targetId = "add-loan",
        badge = "STEP 2 OF 11 · ADD LOANS & INTEREST",
        title = "Create Loans & Estimate Interest",
        message = "Tap 'Add loan' to record a lender or borrower with flexible installments (monthly, weekly, bi-weekly). Use the built-in interest estimator to compute flat or reducing-balance totals before saving.",
        mood = PetotMood.CHEER
    ),
    SpotlightStep(
        page = "Loans",
        targetId = "open-calendar",
        badge = "STEP 3 OF 11 · STATEMENTS & REMINDERS",
        title = "Statements & Borrower Reminders",
        message = "Tap any loan card to view installments, record payments, or reverse mistakes with reasons. You can also export a Statement of Account (SOA) or draft a polite SMS payment reminder for borrowers.",
        mood = PetotMood.HELLO
    ),
    SpotlightStep(
        page = "Calendar",
        targetId = "calendar-card",
        badge = "STEP 4 OF 11 · PAYMENT CALENDAR",
        title = "Visual Payment Calendar",
        message = "See your entire month at a glance! Visual indicators highlight dues within 3 days (red), dues in 4-7 days (yellow), and paydays (green). Use the Appearance icon in the top header to customize colors for each loan.",
        mood = PetotMood.CHEER
    ),
    SpotlightStep(
        page = "Payday",
        targetId = "safe-spend",
        badge = "STEP 5 OF 11 · PAYDAY PLAN",
        title = "Payday Plan & Safe-to-Spend",
        message = "Casha reserves your essential living costs, emergency buffer, and upcoming loan payments first. What remains is your real Safe-to-Spend balance and daily spending allowance until your next payday.",
        mood = PetotMood.CARE
    ),
    SpotlightStep(
        page = "Payday",
        targetId = "manage-accounts",
        badge = "STEP 6 OF 11 · ACCOUNTS & SAVINGS",
        title = "Wallets & Protected Savings Vault",
        message = "Manage Cash on hand, E-Wallets (GCash, Maya), Banks, and custom accounts. Any money deposited into your Savings Vault is strictly protected and never counted into Safe-to-Spend!",
        mood = PetotMood.CHEER
    ),
    SpotlightStep(
        page = "Payday",
        targetId = "cutoff-activity",
        badge = "STEP 7 OF 11 · ACTIVITY & AUDIT",
        title = "Activity Ledger & Audit Reversals",
        message = "Monitor all expenses, income, and adjustments recorded in this cutoff. Made an accidental entry? Tap the refresh icon on any transaction to safely reverse it while keeping a transparent audit record.",
        mood = PetotMood.HELLO
    ),
    SpotlightStep(
        page = "Payday",
        targetId = "payoff-strategies",
        badge = "STEP 8 OF 11 · DEBT STRATEGIES",
        title = "Avalanche & Snowball Payoff",
        message = "Pick between Avalanche (tackle highest APR first to minimize interest) or Snowball (knock out smallest debts first for momentum). Open the Payoff Simulator to see how extra payments shave months off debt!",
        mood = PetotMood.CHEER
    ),
    SpotlightStep(
        page = "Payday",
        targetId = "nav-Expense",
        badge = "STEP 9 OF 11 · EXPENSES & OCR",
        title = "Log Cash & Scan Receipts",
        message = "Tap + on the bottom bar anytime to log daily expenses or capture paper receipts with your camera. Text recognition runs 100% on your device with zero cloud upload, sending scanned drafts to your inbox.",
        mood = PetotMood.CHEER
    ),
    SpotlightStep(
        page = "AI",
        targetId = "chat-composer",
        badge = "STEP 10 OF 11 · ASK PETOT AI",
        title = "Petot Financial Assistant",
        message = "Chat with me anytime in English or Taglish! Ask about yesterday's expenses, calendar due dates, savings vault status, or draft a payment. Every action is a proposal you review before anything is saved.",
        mood = PetotMood.CHEER
    ),
    SpotlightStep(
        page = "Settings",
        targetId = "settings-security",
        badge = "STEP 11 OF 11 · PRIVACY & SYNC",
        title = "Security, Backups & Cloud Sync",
        message = "Protect your financial data with biometric or PIN App Lock. Export encrypted JSON backups locally, or connect your Google account for multi-device Cloud Sync!",
        mood = PetotMood.CARE
    )
)

@Composable
internal fun InteractiveTutorialOverlay(
    currentPage: String,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val step = SPOTLIGHT_STEPS[stepIndex]
    val registry = LocalTutorialSpotlights.current
    val targetRect = registry.bounds[step.targetId]
    val companionOptions by rememberCompanionOptions()
    val animateMotion = companionOptions.second && ValueAnimator.areAnimatorsEnabled()

    LaunchedEffect(stepIndex) {
        val desiredPage = SPOTLIGHT_STEPS[stepIndex].page
        if (currentPage != desiredPage) {
            onNavigate(desiredPage)
        }
    }

    val infinite = rememberInfiniteTransition(label = "spotlightPulse")
    val pulse by if (animateMotion) {
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseVal"
        )
    } else {
        remember { mutableFloatStateOf(0.5f) }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val goldColor = Color(0xFFFFC75D)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .testTag("tutorial-overlay")
            .pointerInput(stepIndex) {
                // Consume background taps so underlying buttons aren't triggered accidentally during the tour
                detectTapGestures { }
            }
    ) {
        val screenHeightPx = constraints.maxHeight.toFloat()
        val density = LocalDensity.current

        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            // 1. Darken the entire screen
            drawRect(Color.Black.copy(alpha = 0.74f))

            // 2. Cut out and encircle the target UI element
            if (targetRect != null && targetRect.width > 0f && targetRect.height > 0f) {
                val pad = 10.dp.toPx()
                val extraPulse = (6.dp.toPx()) * pulse
                val holeTopLeft = Offset(
                    (targetRect.left - pad).coerceAtLeast(4f),
                    (targetRect.top - pad).coerceAtLeast(4f)
                )
                val holeSize = Size(
                    targetRect.width + pad * 2f,
                    targetRect.height + pad * 2f
                )
                val radius = CornerRadius(18.dp.toPx(), 18.dp.toPx())

                // Clear cutout so real button shines through brightly
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = holeTopLeft,
                    size = holeSize,
                    cornerRadius = radius,
                    blendMode = BlendMode.Clear
                )

                // Outer glowing animated ring
                drawRoundRect(
                    color = goldColor.copy(alpha = 0.45f + 0.35f * pulse),
                    topLeft = Offset(holeTopLeft.x - extraPulse, holeTopLeft.y - extraPulse),
                    size = Size(holeSize.width + extraPulse * 2f, holeSize.height + extraPulse * 2f),
                    cornerRadius = CornerRadius(22.dp.toPx(), 22.dp.toPx()),
                    style = Stroke(
                        width = 3.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(22f, 12f), phase = pulse * 34f)
                    )
                )

                // Crisp inner ring encircling the element
                drawRoundRect(
                    color = Color.White,
                    topLeft = holeTopLeft,
                    size = holeSize,
                    cornerRadius = radius,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }
        }

        val placeAtBottom = targetRect == null || targetRect.center.y < screenHeightPx * 0.52f
        val cardAlignment = if (placeAtBottom) Alignment.BottomCenter else Alignment.TopCenter

        Box(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = cardAlignment
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 500.dp).fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(1.5.dp, primaryColor.copy(alpha = 0.5f))
            ) {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            step.badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(3.5.dp), verticalAlignment = Alignment.CenterVertically) {
                            SPOTLIGHT_STEPS.indices.forEach { idx ->
                                val active = idx == stepIndex
                                Box(
                                    Modifier
                                        .height(5.dp)
                                        .width(if (active) 14.dp else 5.dp)
                                        .clip(CircleShape)
                                        .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                                )
                            }
                        }
                    }

                    AnimatedContent(
                        targetState = step,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                        label = "pipSpotlightStep"
                    ) { current ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            PetotCharacter(mood = current.mood, motion = animateMotion, modifier = Modifier.size(64.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(current.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(current.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (stepIndex < SPOTLIGHT_STEPS.lastIndex) {
                            TextButton(onClick = onDismiss, shape = MaterialTheme.shapes.small) {
                                Text("Skip tutorial")
                            }
                        } else {
                            Spacer(Modifier.width(8.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (stepIndex > 0) {
                                OutlinedButton(
                                    onClick = { stepIndex-- },
                                    shape = MaterialTheme.shapes.medium,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    PilotIcon("ChevronLeft", Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Back")
                                }
                            }
                            Button(
                                onClick = {
                                    if (stepIndex < SPOTLIGHT_STEPS.lastIndex) {
                                        stepIndex++
                                    } else {
                                        onNavigate("Loans")
                                        onDismiss()
                                    }
                                },
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Text(if (stepIndex < SPOTLIGHT_STEPS.lastIndex) "Next" else "Finish tour")
                                Spacer(Modifier.width(4.dp))
                                PilotIcon(if (stepIndex < SPOTLIGHT_STEPS.lastIndex) "ChevronRight" else "Check", Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
