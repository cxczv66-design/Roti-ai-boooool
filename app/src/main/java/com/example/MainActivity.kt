package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.EntityBodyTheme
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalGradientPalette
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EntityBodyTheme {
                EntityBodyScreen()
            }
        }
    }
}

@Composable
fun EntityBodyScreen(
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val gradients = LocalGradientPalette.current

    var showHeader by remember { mutableStateOf(false) }
    var showWsCard by remember { mutableStateOf(false) }
    var showAccCard by remember { mutableStateOf(false) }
    var showTargetCard by remember { mutableStateOf(false) }
    var showLogsCard by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.initialize(context)
        showHeader = true
        delay(50)
        showWsCard = true
        delay(50)
        showAccCard = true
        delay(50)
        showTargetCard = true
        delay(50)
        showLogsCard = true
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAccessibilityStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val startConnection: () -> Unit = {
        focusManager.clearFocus()
        val url = uiState.wsUrlInput.trim()
        val intent = Intent(context, WebSocketService::class.java).apply {
            action = WebSocketService.ACTION_CONNECT
            putExtra(WebSocketService.EXTRA_WS_URL, url)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            BridgeRepository.addLog(
                LogLevel.INFO,
                "Notification permission denied; foreground link runs silently"
            )
        }
        startConnection()
    }

    val handleConnectClick: () -> Unit = {
        val isActive = uiState.connectionState == ConnectionState.CONNECTED ||
            uiState.connectionState == ConnectionState.CONNECTING
        if (isActive) {
            val intent = Intent(context, WebSocketService::class.java).apply {
                action = WebSocketService.ACTION_DISCONNECT
            }
            context.startService(intent)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startConnection()
            }
        }
    }

    val openAccessibilitySettings: () -> Unit = {
        BridgeRepository.addLog(LogLevel.INFO, "Opening Android Accessibility Settings…")
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    val ambientTransition = rememberInfiniteTransition(label = "ambient_glow")
    val driftProgress by ambientTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_drift"
    )

    val primaryGlow = gradients.primaryStart.copy(alpha = 0.16f)
    val secondaryGlow = gradients.primaryEnd.copy(alpha = 0.16f)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(primaryGlow, Color.Transparent),
                            center = Offset(
                                x = size.width * (0.12f + 0.14f * driftProgress),
                                y = size.height * (0.08f + 0.05f * driftProgress)
                            ),
                            radius = size.width * 0.85f
                        )
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(secondaryGlow, Color.Transparent),
                            center = Offset(
                                x = size.width * (0.88f - 0.14f * driftProgress),
                                y = size.height * (0.48f - 0.08f * driftProgress)
                            ),
                            radius = size.width * 0.8f
                        )
                    )
                }
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 640.dp)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AnimatedVisibility(
                    visible = showHeader,
                    enter = fadeIn(tween(240)) + slideInVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        initialOffsetY = { -it / 3 }
                    )
                ) {
                    HeaderSection(
                        connectionState = uiState.connectionState,
                        accessibilityEnabled = uiState.accessibilityEnabled,
                        shizukuStatus = uiState.shizukuStatus
                    )
                }

                AnimatedVisibility(
                    visible = showWsCard,
                    enter = fadeIn(tween(240)) + slideInVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        initialOffsetY = { it / 4 }
                    )
                ) {
                    WebSocketControlCard(
                        wsUrl = uiState.wsUrlInput,
                        connectionState = uiState.connectionState,
                        onUrlChange = viewModel::onWsUrlChanged,
                        onConnectClick = handleConnectClick
                    )
                }

                AnimatedVisibility(
                    visible = showAccCard,
                    enter = fadeIn(tween(240)) + slideInVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        initialOffsetY = { it / 4 }
                    )
                ) {
                    AccessibilityControlCard(
                        accessibilityEnabled = uiState.accessibilityEnabled,
                        foregroundPackage = uiState.foregroundPackage,
                        onOpenSettingsClick = openAccessibilitySettings,
                        onReadScreenClick = viewModel::readCurrentScreenNow
                    )
                }

                AnimatedVisibility(
                    visible = showTargetCard,
                    enter = fadeIn(tween(240)) + slideInVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        initialOffsetY = { it / 4 }
                    )
                ) {
                    TargetAiAndShizukuCard(
                        selectedPackage = uiState.targetAiPackage,
                        shizukuStatus = uiState.shizukuStatus,
                        onSelectPackage = { pkg -> viewModel.selectTargetAiApp(context, pkg) },
                        onRequestShizuku = viewModel::requestShizukuAuthorization
                    )
                }

                AnimatedVisibility(
                    visible = showLogsCard,
                    modifier = Modifier.weight(1f),
                    enter = fadeIn(tween(260)) + slideInVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        initialOffsetY = { it / 5 }
                    )
                ) {
                    TelemetryLogCard(
                        logs = uiState.logs,
                        onClearLogs = viewModel::clearLogs,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderSection(
    connectionState: ConnectionState,
    accessibilityEnabled: Boolean,
    shizukuStatus: ShizukuStatus
) {
    val gradients = LocalGradientPalette.current
    val brandGradient = Brush.linearGradient(
        colors = listOf(gradients.primaryStart, gradients.primaryEnd)
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(width = 1.5.dp, brush = brandGradient, shape = RoundedCornerShape(14.dp))
                    .padding(2.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.entity_body_icon_1795633321829),
                    contentDescription = stringResource(R.string.cd_brand_icon),
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp, bottom = 2.dp)
                        .width(56.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(brandGradient)
                )
                Text(
                    text = stringResource(R.string.app_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val (wsLabel, wsColor, wsPulsing) = when (connectionState) {
                ConnectionState.CONNECTED -> Triple("WS: Live", MaterialTheme.colorScheme.tertiary, true)
                ConnectionState.CONNECTING -> Triple("WS: Sync…", gradients.primaryStart, true)
                ConnectionState.ERROR -> Triple("WS: Error", MaterialTheme.colorScheme.error, false)
                ConnectionState.DISCONNECTED -> Triple("WS: Off", MaterialTheme.colorScheme.onSurfaceVariant, false)
            }
            StatusPill(
                label = wsLabel,
                dotColor = wsColor,
                isPulsing = wsPulsing,
                modifier = Modifier.weight(1f)
            )

            StatusPill(
                label = if (accessibilityEnabled) "Eyes: On" else "Eyes: Off",
                dotColor = if (accessibilityEnabled) gradients.primaryStart else AmberWarn,
                isPulsing = accessibilityEnabled,
                modifier = Modifier.weight(1f)
            )

            val (shizukuLabel, shizukuColor, shizukuPulse) = when (shizukuStatus) {
                ShizukuStatus.READY -> Triple("Shizuku: On", MaterialTheme.colorScheme.tertiary, true)
                ShizukuStatus.BINDING -> Triple("Shizuku: Bind", gradients.primaryStart, true)
                ShizukuStatus.NEEDS_PERMISSION -> Triple("Shizuku: Auth", AmberWarn, false)
                ShizukuStatus.UNAVAILABLE -> Triple("Shizuku: Off", MaterialTheme.colorScheme.onSurfaceVariant, false)
            }
            StatusPill(
                label = shizukuLabel,
                dotColor = shizukuColor,
                isPulsing = shizukuPulse,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatusPill(
    label: String,
    dotColor: Color,
    isPulsing: Boolean,
    modifier: Modifier = Modifier
) {
    val gradients = LocalGradientPalette.current
    val animatedDot by animateColorAsState(
        targetValue = dotColor,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "status_dot_color"
    )

    val pulseTransition = rememberInfiniteTransition(label = "pill_pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 2.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_scale"
    )
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_alpha"
    )

    val pillBorderBrush = Brush.horizontalGradient(
        colors = listOf(
            animatedDot.copy(alpha = 0.55f),
            gradients.primaryEnd.copy(alpha = 0.30f)
        )
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, pillBorderBrush)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(10.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isPulsing) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .graphicsLayer {
                                scaleX = pulseScale
                                scaleY = pulseScale
                                alpha = pulseAlpha
                            }
                            .clip(CircleShape)
                            .background(animatedDot)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(animatedDot)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun WebSocketControlCard(
    wsUrl: String,
    connectionState: ConnectionState,
    onUrlChange: (String) -> Unit,
    onConnectClick: () -> Unit
) {
    val gradients = LocalGradientPalette.current
    val isConnectedOrConnecting = connectionState == ConnectionState.CONNECTED ||
        connectionState == ConnectionState.CONNECTING

    val cardBorderBrush = Brush.linearGradient(
        colors = listOf(
            gradients.primaryStart.copy(alpha = 0.35f),
            gradients.primaryEnd.copy(alpha = 0.25f)
        )
    )

    val buttonBrush = if (isConnectedOrConnecting) {
        Brush.horizontalGradient(
            colors = listOf(gradients.dangerStart, gradients.dangerEnd)
        )
    } else {
        Brush.horizontalGradient(
            colors = listOf(gradients.primaryStart, gradients.primaryEnd)
        )
    }

    val spinTransition = rememberInfiniteTransition(label = "connecting_spin")
    val spinDegrees by spinTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin_angle"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, cardBorderBrush)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = stringResource(R.string.cd_ws_status_icon),
                    tint = gradients.primaryStart,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.ws_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            OutlinedTextField(
                value = wsUrl,
                onValueChange = onUrlChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("websocket_url_input"),
                enabled = !isConnectedOrConnecting,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = JetBrainsMonoFontFamily
                ),
                label = { Text(stringResource(R.string.ws_url_label)) },
                placeholder = {
                    Text(
                        text = stringResource(R.string.ws_url_placeholder),
                        fontFamily = JetBrainsMonoFontFamily
                    )
                },
                trailingIcon = {
                    if (wsUrl.isNotEmpty() && !isConnectedOrConnecting) {
                        IconButton(
                            onClick = { onUrlChange("") },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.cd_clear_url)
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (!isConnectedOrConnecting && wsUrl.isNotBlank()) {
                            onConnectClick()
                        }
                    }
                ),
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = gradients.primaryStart,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            GradientActionButton(
                onClick = onConnectClick,
                brush = buttonBrush,
                contentColor = if (isConnectedOrConnecting) Color.White else MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("connect_button")
            ) {
                AnimatedContent(
                    targetState = connectionState,
                    transitionSpec = {
                        (fadeIn(tween(200)) + scaleIn(initialScale = 0.92f)) togetherWith fadeOut(tween(150))
                    },
                    label = "connect_btn_content"
                ) { state ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        when (state) {
                            ConnectionState.CONNECTING -> {
                                Icon(
                                    imageVector = Icons.Filled.Sync,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .graphicsLayer { rotationZ = spinDegrees }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.btn_connecting),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }

                            ConnectionState.CONNECTED -> {
                                Icon(
                                    imageVector = Icons.Filled.LinkOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.btn_disconnect),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }

                            else -> {
                                Icon(
                                    imageVector = Icons.Filled.Link,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.btn_connect),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessibilityControlCard(
    accessibilityEnabled: Boolean,
    foregroundPackage: String?,
    onOpenSettingsClick: () -> Unit,
    onReadScreenClick: () -> Unit
) {
    val gradients = LocalGradientPalette.current

    val cardBorderBrush = Brush.linearGradient(
        colors = listOf(
            gradients.primaryEnd.copy(alpha = 0.35f),
            gradients.primaryStart.copy(alpha = 0.25f)
        )
    )

    val settingsButtonBrush = if (accessibilityEnabled) {
        Brush.horizontalGradient(
            colors = listOf(
                gradients.primaryStart.copy(alpha = 0.22f),
                gradients.primaryEnd.copy(alpha = 0.28f)
            )
        )
    } else {
        Brush.horizontalGradient(
            colors = listOf(
                gradients.primaryEnd.copy(alpha = 0.85f),
                gradients.primaryStart.copy(alpha = 0.85f)
            )
        )
    }

    val readButtonBrush = Brush.horizontalGradient(
        colors = listOf(
            gradients.primaryStart.copy(alpha = 0.14f),
            gradients.primaryEnd.copy(alpha = 0.14f)
        )
    )

    val outlineGradient = Brush.horizontalGradient(
        colors = listOf(gradients.primaryStart, gradients.primaryEnd)
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, cardBorderBrush)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AccessibilityNew,
                        contentDescription = stringResource(R.string.cd_accessibility_status_icon),
                        tint = if (accessibilityEnabled) gradients.primaryStart else AmberWarn,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.accessibility_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                AnimatedVisibility(
                    visible = !foregroundPackage.isNullOrBlank(),
                    enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.9f),
                    exit = fadeOut(tween(150))
                ) {
                    Text(
                        text = foregroundPackage.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = gradients.primaryStart,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 170.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GradientActionButton(
                    onClick = onOpenSettingsClick,
                    brush = settingsButtonBrush,
                    contentColor = if (accessibilityEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                    border = if (accessibilityEnabled) BorderStroke(1.dp, outlineGradient) else null,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("accessibility_settings_button")
                ) {
                    Text(
                        text = if (accessibilityEnabled) {
                            stringResource(R.string.btn_manage_accessibility)
                        } else {
                            stringResource(R.string.btn_start_accessibility)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                GradientActionButton(
                    onClick = onReadScreenClick,
                    brush = readButtonBrush,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, outlineGradient),
                    horizontalPadding = 14.dp,
                    modifier = Modifier.testTag("read_screen_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Visibility,
                        contentDescription = null,
                        tint = gradients.primaryStart,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.btn_read_screen),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetAiAndShizukuCard(
    selectedPackage: String,
    shizukuStatus: ShizukuStatus,
    onSelectPackage: (String) -> Unit,
    onRequestShizuku: () -> Unit
) {
    val gradients = LocalGradientPalette.current
    val cardBorderBrush = Brush.linearGradient(
        colors = listOf(
            gradients.primaryStart.copy(alpha = 0.28f),
            gradients.primaryEnd.copy(alpha = 0.35f)
        )
    )
    val outlineGradient = Brush.horizontalGradient(
        colors = listOf(gradients.primaryStart, gradients.primaryEnd)
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, cardBorderBrush)
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = gradients.primaryEnd,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.target_ai_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (shizukuStatus != ShizukuStatus.READY) {
                    GradientActionButton(
                        onClick = onRequestShizuku,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                gradients.primaryStart.copy(alpha = 0.20f),
                                gradients.primaryEnd.copy(alpha = 0.24f)
                            )
                        ),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(1.dp, outlineGradient),
                        horizontalPadding = 10.dp,
                        modifier = Modifier.testTag("grant_shizuku_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = gradients.primaryStart,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.btn_grant_shizuku),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BridgeRepository.KNOWN_AI_APPS.forEach { preset ->
                    val selected = selectedPackage == preset.packageName
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectPackage(preset.packageName) },
                        label = { Text(preset.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.testTag("target_chip_${preset.label.lowercase()}")
                    )
                }
            }
        }
    }
}

@Composable
private fun GradientActionButton(
    onClick: () -> Unit,
    brush: Brush,
    contentColor: Color,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.965f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "button_press_scale"
    )

    Button(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .heightIn(min = 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = MaterialTheme.shapes.small,
        border = border,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = contentColor
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .background(brush = brush, shape = MaterialTheme.shapes.small)
                .padding(horizontal = horizontalPadding, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                content()
            }
        }
    }
}

@Composable
private fun TelemetryLogCard(
    logs: List<TelemetryLogEntry>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gradients = LocalGradientPalette.current
    val cardBorderBrush = Brush.linearGradient(
        colors = listOf(
            gradients.primaryStart.copy(alpha = 0.25f),
            gradients.primaryEnd.copy(alpha = 0.30f)
        )
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, cardBorderBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Terminal,
                        contentDescription = null,
                        tint = gradients.primaryEnd,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.logs_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = logs.size.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onClearLogs,
                    enabled = logs.isNotEmpty(),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("clear_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteSweep,
                        contentDescription = stringResource(R.string.cd_clear_logs),
                        tint = if (logs.isNotEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        }
                    )
                }
            }

            AnimatedContent(
                targetState = logs.isEmpty(),
                transitionSpec = {
                    fadeIn(tween(220)) togetherWith fadeOut(tween(180))
                },
                modifier = Modifier.fillMaxSize(),
                label = "logs_empty_toggle"
            ) { isEmpty ->
                if (isEmpty) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .padding(18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = gradients.primaryStart.copy(alpha = 0.8f),
                                modifier = Modifier.size(26.dp)
                            )
                            Text(
                                text = stringResource(R.string.logs_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.logs_empty_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.65f))
                            .testTag("telemetry_log_list"),
                        contentPadding = PaddingValues(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(items = logs, key = { it.id }) { entry ->
                            TelemetryLogRow(
                                entry = entry,
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(220),
                                    placementSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryLogRow(
    entry: TelemetryLogEntry,
    modifier: Modifier = Modifier
) {
    val gradients = LocalGradientPalette.current
    val badgeColor = when (entry.level) {
        LogLevel.OK -> MaterialTheme.colorScheme.tertiary
        LogLevel.WS -> gradients.primaryStart
        LogLevel.CMD -> gradients.primaryEnd
        LogLevel.ERR -> MaterialTheme.colorScheme.error
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = entry.timestamp,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = badgeColor.copy(alpha = 0.16f)
        ) {
            Text(
                text = entry.level.name,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = badgeColor,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = JetBrainsMonoFontFamily
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
