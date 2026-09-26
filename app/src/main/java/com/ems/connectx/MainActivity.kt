package com.ems.connectx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.ems.connectx.data.*
import com.ems.connectx.sms.GatewayService
import com.ems.connectx.sms.QueueProcessor
import com.ems.connectx.sms.SmsSender
import com.ems.connectx.sms.SimUssdClient
import com.ems.connectx.ui.*
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// Gradle is the single source of truth (also used by Android's package manager).
val APP_VERSION_NAME get() = BuildConfig.VERSION_NAME
val APP_VERSION_CODE get() = BuildConfig.VERSION_CODE

fun Double.format(digits: Int): String = String.format(Locale.US, "%.${digits}f", this)

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var api: Api
    private var resumeToken by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeToken++ // Re-check immediately when returning from background or installer.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        api = Api(prefs)
        setContent {
            ConnectXTheme {
                ScreenBackdrop { AppRoot() }
            }
        }
    }

    @Composable
    private fun AppRoot() {
        var route by remember { mutableStateOf(initialRoute()) }
        var selectedShop by remember { mutableStateOf<Shop?>(null) }
        var selectedSim by remember { mutableStateOf<SubscriptionInfo?>(null) }
        var tab by remember { mutableIntStateOf(0) }
        var session by remember { mutableIntStateOf(0) }
        var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
        var checkingUpdates by remember { mutableStateOf(false) }
        var updateChecked by remember { mutableStateOf(false) }
        var updateCheckError by remember { mutableStateOf<String?>(null) }
        var dismissedBuild by remember { mutableIntStateOf(0) }
        var lastBackPressTime by remember { mutableLongStateOf(0L) }
        val scope = rememberCoroutineScope()

        // In-App Automatic Installation Wizard States
        var showInstallWizard by remember { mutableStateOf(false) }
        var wizardTargetUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
        var wizardProgress by remember { mutableFloatStateOf(0f) }
        var wizardDownloadedBytes by remember { mutableLongStateOf(0L) }
        var wizardTotalBytes by remember { mutableLongStateOf(0L) }
        var wizardStatus by remember { mutableStateOf("idle") } // "idle", "downloading", "ready", "error"
        var wizardError by remember { mutableStateOf("") }
        var downloadedApkFile by remember { mutableStateOf<File?>(null) }
        var downloadJob by remember { mutableStateOf<Job?>(null) }

        fun launchWizard(info: AppUpdateInfo) {
            downloadJob?.cancel()
            wizardTargetUpdate = info
            showInstallWizard = true
            downloadedApkFile = null
            wizardProgress = 0f
            wizardDownloadedBytes = 0L
            wizardTotalBytes = info.apkSizeBytes.coerceAtLeast(0L)
            wizardStatus = "downloading"
            wizardError = ""

            downloadJob = scope.launch {
                try {
                    val apk = withContext(Dispatchers.IO) {
                        downloadUpdateApk(
                            downloadUrl = info.downloadUrl,
                            fileName = info.apkFilename.ifBlank { "ConnectX-${info.latestVersion}.apk" },
                            expectedVersionCode = info.versionCode,
                            expectedSize = info.apkSizeBytes
                        ) { progress, downloaded, total ->
                            wizardProgress = progress
                            wizardDownloadedBytes = downloaded
                            if (total > 0) wizardTotalBytes = total
                        }
                    }
                    if (!showInstallWizard) {
                        apk.delete()
                        return@launch
                    }
                    downloadedApkFile = apk
                    wizardStatus = "ready"
                    // Automatically trigger the Android system installation wizard!
                    installDownloadedApk(apk)
                } catch (_: CancellationException) {
                    // User closed the wizard; the partial APK is deleted below.
                } catch (e: Exception) {
                    wizardStatus = "error"
                    wizardError = e.message ?: "Failed to download update"
                }
            }
        }

        // Public App Store: check on launch, after EMS URL is saved, on resume,
        // and hourly while the UI is active. WorkManager checks in background.
        LaunchedEffect(route, session, resumeToken) {
            if (prefs.baseUrl.isBlank()) return@LaunchedEffect
            while (true) {
                try {
                    val latest = withContext(Dispatchers.IO) { api.checkUpdate(packageName, APP_VERSION_CODE) }
                    updateInfo = latest.takeIf { it.versionCode > APP_VERSION_CODE }
                    updateChecked = true
                    updateCheckError = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    updateChecked = false
                    updateCheckError = e.message ?: "Could not reach the EMS App Store."
                }
                delay(60 * 60 * 1000L)
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // SYSTEM BACK GESTURE / NAVIGATION BACK HANDLER
        // Fixes: Prevents app from closing on system back gesture across sub-screens
        // ═══════════════════════════════════════════════════════════════════
        BackHandler(enabled = true) {
            if (showInstallWizard && wizardStatus == "downloading") {
                downloadJob?.cancel()
                showInstallWizard = false
                return@BackHandler
            }
            if (updateInfo?.mandatory == true && updateInfo!!.versionCode > APP_VERSION_CODE) {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000) {
                    finish()
                } else {
                    lastBackPressTime = now
                    toast("Press back again to exit ConnectX")
                }
                return@BackHandler
            }

            when (route) {
                "about" -> {
                    tab = 3
                    route = "home"
                }
                "shops" -> {
                    if (prefs.connections().any { it.setupComplete }) {
                        tab = 3
                        route = "home"
                    } else {
                        route = "login"
                    }
                }
                "permission" -> route = "shops"
                "sim" -> route = "permission"
                "register" -> route = "sim"
                "test" -> {
                    tab = 0
                    route = "home"
                }
                "home" -> {
                    if (tab != 0) {
                        tab = 0
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPressTime < 2000) {
                            finish()
                        } else {
                            lastBackPressTime = now
                            toast("Press back again to exit ConnectX")
                        }
                    }
                }
                "login" -> {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressTime < 2000) {
                        finish()
                    } else {
                        lastBackPressTime = now
                        toast("Press back again to exit ConnectX")
                    }
                }
                else -> {
                    route = "home"
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // IN-APP AUTOMATIC INSTALLATION WIZARD MODAL
        // ═══════════════════════════════════════════════════════════════════
        if (showInstallWizard) {
            ModalBottomSheet(
                onDismissRequest = {
                    if (wizardStatus != "downloading") {
                        showInstallWizard = false
                    }
                },
                containerColor = PureWhite,
                scrimColor = Color.Black.copy(alpha = 0.5f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(
                                when (wizardStatus) {
                                    "error" -> RoseText.copy(alpha = 0.12f)
                                    "ready" -> AccentEmerald.copy(alpha = 0.12f)
                                    else -> PrimaryBlue.copy(alpha = 0.12f)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            when (wizardStatus) {
                                "ready" -> Icons.Outlined.CheckCircle
                                "error" -> Icons.Outlined.ErrorOutline
                                else -> Icons.Outlined.Download
                            },
                            contentDescription = null,
                            tint = when (wizardStatus) {
                                "error" -> RoseText
                                "ready" -> AccentEmerald
                                else -> PrimaryBlue
                            },
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        when (wizardStatus) {
                            "ready" -> "Ready to Install"
                            "error" -> "Download Interrupted"
                            else -> "Downloading ConnectX Update"
                        },
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        when (wizardStatus) {
                            "ready" -> "Download complete. Android will check the APK signature. If it says ‘App not installed,’ ask the EMS owner for an APK signed with the same key as your current app."
                            "error" -> wizardError.ifBlank { "Could not download APK. Please check your network connection." }
                            else -> "Downloading v${wizardTargetUpdate?.latestVersion ?: APP_VERSION_NAME} (Build ${wizardTargetUpdate?.versionCode ?: APP_VERSION_CODE}) directly from EMS App Store…"
                        },
                        fontSize = 13.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(20.dp))

                    if (wizardStatus == "downloading") {
                        if (wizardTotalBytes > 0) {
                            LinearProgressIndicator(
                                progress = { wizardProgress },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = PrimaryBlue, trackColor = PearlSurface
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = PrimaryBlue, trackColor = PearlSurface
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                if (wizardTotalBytes > 0) "${(wizardProgress * 100).toInt()}% Downloaded" else "Downloading…",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PrimaryBlue
                            )
                            Text(
                                if (wizardTotalBytes > 0) "${(wizardDownloadedBytes / (1024 * 1024.0)).format(1)} / ${(wizardTotalBytes / (1024 * 1024.0)).format(1)} MB"
                                else "${(wizardDownloadedBytes / (1024 * 1024.0)).format(1)} MB",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }
                    } else if (wizardStatus == "ready") {
                        Button(
                            onClick = {
                                downloadedApkFile?.let { installDownloadedApk(it) }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryBlue,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Outlined.InstallMobile, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Launch System Installer", fontWeight = FontWeight.Bold)
                        }
                    } else if (wizardStatus == "error") {
                        Button(
                            onClick = {
                                wizardTargetUpdate?.let { launchWizard(it) }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryBlue,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Retry Download", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // FULLSCREEN MANDATORY UPDATE LOCK SCREEN
        // ═══════════════════════════════════════════════════════════════════
        if (updateInfo?.mandatory == true && updateInfo!!.versionCode > APP_VERSION_CODE) {
            MandatoryUpdateScreen(
                update = updateInfo!!,
                onStartInstall = { launchWizard(updateInfo!!) }
            )
            return
        }

        // Optional Update Dialog (if update available and not mandatory)
        if (updateInfo != null && !updateInfo!!.mandatory && updateInfo!!.versionCode > APP_VERSION_CODE && route != "about" && dismissedBuild != updateInfo!!.versionCode) {
            AlertDialog(
                onDismissRequest = { dismissedBuild = updateInfo!!.versionCode },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(PrimaryBlue.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.SystemUpdate,
                                contentDescription = null,
                                tint = PrimaryBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "New Update Available",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = TextPrimary
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = PearlBg,
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("New Version Available", fontSize = 12.sp, color = TextSecondary)
                                Text("v${updateInfo!!.latestVersion} (Build ${updateInfo!!.versionCode})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PrimaryBlue)
                            }
                        }

                        if (updateInfo!!.releaseNotes.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text("Release Notes", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = updateInfo!!.releaseNotes,
                                fontSize = 13.sp,
                                color = TextSecondary,
                                lineHeight = 18.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            dismissedBuild = updateInfo!!.versionCode
                            launchWizard(updateInfo!!)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Outlined.Download, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Update Now", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { dismissedBuild = updateInfo!!.versionCode },
                        colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                    ) {
                        Text("Later")
                    }
                },
                containerColor = PureWhite,
                shape = RoundedCornerShape(18.dp)
            )
        }

        when (route) {
            "start" -> OnboardingScreen(
                onFinish = {
                    prefs.seenGetStarted = true
                    route = "login"
                }
            )
            "login" -> LoginScreen {
                prefs.signedIn = true
                route = if (prefs.connections().any { it.setupComplete }) "home" else "shops"
            }
            "shops" -> ShopListScreen(
                onBack = {
                    if (prefs.connections().any { it.setupComplete }) {
                        tab = 3
                        route = "home"
                    } else {
                        route = "login"
                    }
                },
                onPick = { shop -> selectedShop = shop; route = "permission" }
            )
            "permission" -> PermissionScreen(
                onContinue = { route = "sim" },
                onBack = { route = "shops" }
            )
            "sim" -> SimScreen(
                onBack = { route = "permission" },
                onPick = { sim ->
                    selectedSim = sim
                    route = "register"
                }
            )
            "register" -> RegisteringScreen(selectedShop, selectedSim) { ok ->
                route = if (ok) "test" else "sim"
            }
            "test" -> TestSmsScreen(selectedSim) {
                tab = 0
                route = "home"
            }
            "about" -> AboutScreen(
                updateInfo = updateInfo,
                onBack = {
                    tab = 3
                    route = "home"
                },
                onStartUpdate = { info -> launchWizard(info) },
                onCheckUpdate = {
                    scope.launch {
                        checkingUpdates = true
                        try {
                            val latest = withContext(Dispatchers.IO) { api.checkUpdate(packageName, APP_VERSION_CODE) }
                            updateInfo = latest.takeIf { it.versionCode > APP_VERSION_CODE }
                            updateChecked = true
                            updateCheckError = null
                            if (updateInfo != null) toast("New build v${latest.latestVersion} found!")
                            else toast("ConnectX is up to date (v$APP_VERSION_NAME)")
                        } catch (e: Exception) {
                            updateChecked = false
                            updateCheckError = e.message ?: "Could not reach the EMS App Store."
                            toast("Update check failed: $updateCheckError")
                        } finally {
                            checkingUpdates = false
                        }
                    }
                },
                checkingUpdates = checkingUpdates,
                updateChecked = updateChecked,
                updateCheckError = updateCheckError
            )
            else -> key(session) {
                HomeShell(
                    tab = tab,
                    onTab = { tab = it },
                    onAddShop = { route = if (prefs.adminToken.isNotBlank()) "shops" else "login" },
                    onLogout = {
                        GatewayService.stop(this@MainActivity)
                        prefs.signedIn = false
                        prefs.adminToken = ""
                        tab = 0
                        route = "login"
                    },
                    onSession = { session++ },
                    updateInfo = updateInfo,
                    onOpenUpdate = { updateInfo?.let { launchWizard(it) } },
                    onOpenAbout = { route = "about" }
                )
            }
        }
    }

    private fun initialRoute(): String {
        if (!prefs.seenGetStarted && prefs.connections().isEmpty()) return "start"
        if (prefs.signedIn && prefs.connections().any { it.setupComplete }) return "home"
        if (prefs.signedIn && prefs.adminToken.isNotBlank()) return "shops"
        return "login"
    }

    /* =====================================================================
     * MOBBIN-INSPIRED LIGHT ONBOARDING EXPERIENCE
     * ===================================================================== */
    // Keep manual balance state on the SMS page, not inside a LazyColumn item.
    // Scrolling history must not discard an in-progress USSD result.
    private class BalanceUiState {
        val catalog = mutableStateOf<CarrierBalanceConfig?>(null)
        val pending = mutableStateOf<CarrierBalanceConfig?>(null)
        val balance = mutableStateOf<String?>(null)
        val status = mutableStateOf("Tap Refresh to check balance.")
        val busy = mutableStateOf(false)
    }

    data class OnboardingStep(
        val badge: String,
        val title: String,
        val subtitle: String,
        val icon: ImageVector,
        val features: List<Pair<ImageVector, String>>
    )

    @Composable
    private fun OnboardingScreen(onFinish: () -> Unit) {
        val steps = remember {
            listOf(
                OnboardingStep(
                    badge = "CENTRAL COMMUNICATION",
                    title = "SMS dispatch and EMS email history",
                    subtitle = "ConnectX: Central Communication Gateway powered by Dexter Studio. Send SMS from your SIM and review outgoing EMS emails for your selected shop.",
                    icon = Icons.Outlined.Sensors,
                    features = listOf(
                        Icons.Outlined.Bolt to "Direct cellular SMS from your device's SIM",
                        Icons.Outlined.Email to "Read-only EMS outgoing email history",
                        Icons.Outlined.Sync to "Background SMS queue while the screen is off"
                    )
                ),
                OnboardingStep(
                    badge = "MULTI-STORE ROUTING",
                    title = "One phone, multiple shop branches",
                    subtitle = "Switch shops for both SMS and email history. Choose each shop’s sending SIM for SMS only; EMS handles outgoing email independently.",
                    icon = Icons.Outlined.Store,
                    features = listOf(
                        Icons.Outlined.SimCard to "Assign dedicated SIM carriers per shop location",
                        Icons.Outlined.Layers to "Isolated message queues per retail branch",
                        Icons.Outlined.SwapHoriz to "Seamless SIM switching directly from the app"
                    )
                ),
                OnboardingStep(
                    badge = "TRANSACTION AUTOMATION",
                    title = "Instant transaction notifications",
                    subtitle = "Automated customer SMS for sales invoices, due reminders, returns, exchanges, and custom announcements.",
                    icon = Icons.Outlined.MarkChatRead,
                    features = listOf(
                        Icons.Outlined.Receipt to "Sales Invoice Confirmation & digital bill receipts",
                        Icons.Outlined.Alarm to "Due Invoice Reminders with outstanding balances",
                        Icons.Outlined.Autorenew to "Exchange & Return Invoice Confirmations with settlement"
                    )
                ),
                OnboardingStep(
                    badge = "ENTERPRISE PRIVACY",
                    title = "Zero passwords stored on device",
                    subtitle = "Secure cryptographic device token pairing. Manage and revoke hardware access anytime directly from your EMS console.",
                    icon = Icons.Outlined.Security,
                    features = listOf(
                        Icons.Outlined.Lock to "Encrypted token storage via Android KeyStore",
                        Icons.Outlined.CloudOff to "Queued messages wait safely if phone goes offline",
                        Icons.Outlined.PowerSettingsNew to "One-tap remote device token revocation from EMS"
                    )
                )
            )
        }

        var currentStep by remember { mutableIntStateOf(0) }
        val step = steps[currentStep]

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PureWhite)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar: Brand Mark & Skip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "ConnectX",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            getString(R.string.tagline),
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }

                if (currentStep < steps.size - 1) {
                    TextButton(
                        onClick = onFinish,
                        colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                    ) {
                        Text("Skip", fontSize = 14.sp)
                    }
                }
            }

            // Main Content Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                // Badge Tag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(PrimarySubtle)
                        .border(1.dp, Color(0xFFBFDBFE), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        step.badge,
                        color = PrimaryBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = step.title,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 34.sp,
                    color = TextPrimary
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    text = step.subtitle,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = TextSecondary
                )

                Spacer(Modifier.height(20.dp))

                // Feature Highlights Card
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = PearlBg,
                    borderColor = BorderSubtle,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        step.features.forEachIndexed { index, (icon, text) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(PureWhite)
                                        .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        tint = PrimaryBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = text,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    color = TextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (index < step.features.size - 1) {
                                HorizontalDivider(
                                    color = BorderSubtle,
                                    thickness = 0.8.dp,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Controls
            Column(modifier = Modifier.fillMaxWidth()) {
                // Step Indicator Pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    steps.indices.forEach { index ->
                        val active = index == currentStep
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .height(6.dp)
                                .width(if (active) 26.dp else 8.dp)
                                .clip(CircleShape)
                                .background(if (active) PrimaryBlue else BorderMedium)
                        )
                    }
                }

                // Next / Get Started Button
                Button(
                    onClick = {
                        if (currentStep < steps.size - 1) {
                            currentStep++
                        } else {
                            onFinish()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryBlue,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        if (currentStep < steps.size - 1) "Continue" else "Get started",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Outlined.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    "Powered by Dexter Studio",
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    /* =====================================================================
     * SIGN IN SCREEN (Light Theme)
     * ===================================================================== */
    @Composable
    private fun LoginScreen(onOk: () -> Unit) {
        var url by remember { mutableStateOf(prefs.baseUrl) }
        var urlError by remember { mutableStateOf<String?>(null) }
        var email by remember { mutableStateOf(prefs.adminEmail) }
        var password by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val already = prefs.connections()
        val fields = modernFieldColors()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PureWhite)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            BrandMark(56.dp)
            Spacer(Modifier.height(18.dp))
            Text("Sign in", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "Sign in with your EMS administrator account. Your password is used only for device registration and is never stored on this phone.",
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )

            if (already.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = PearlBg,
                    borderColor = BorderSubtle,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = AccentEmerald, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${already.size} shop${if (already.size == 1) "" else "s"} already connected on this device",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            AppCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                containerColor = PureWhite,
                borderColor = BorderSubtle
            ) {
                Column(Modifier.padding(18.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; urlError = null },
                        label = { Text("EMS Website URL") },
                        placeholder = { Text("https://your-ems.pages.dev") },
                        supportingText = { Text(urlError ?: "Use the complete deployed EMS domain, not just https:/ or GitHub.",
                            color = if (urlError != null) RoseText else TextMuted, fontSize = 11.sp) },
                        isError = urlError != null,
                        leadingIcon = { Icon(Icons.Outlined.Language, null, tint = PrimaryBlue) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = fields
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Administrator Email") },
                        placeholder = { Text("admin@example.com") },
                        leadingIcon = { Icon(Icons.Outlined.Email, null, tint = PrimaryBlue) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = fields
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        leadingIcon = { Icon(Icons.Outlined.Lock, null, tint = PrimaryBlue) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(12.dp),
                        colors = fields
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = {
                        val checked = runCatching { EmsSiteUrl.normalize(url) }
                        val u = checked.getOrNull()
                        if (u == null) urlError = checked.exceptionOrNull()?.message ?: EmsSiteUrl.HELP
                        if (u != null) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$u/#forgot")))
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = PrimaryBlue)
                ) {
                    Text("Forgot password?", fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = {
                    val checked = runCatching { EmsSiteUrl.normalize(url) }
                    val site = checked.getOrNull()
                    if (site == null) urlError = checked.exceptionOrNull()?.message ?: EmsSiteUrl.HELP
                    if (site != null) scope.launch {
                        loading = true
                        try {
                            prefs.baseUrl = site
                            withContext(Dispatchers.IO) { api.adminLogin(email.trim(), password) }
                            onOk()
                        } catch (e: Exception) {
                            if (e is UnknownHostException || e.cause is UnknownHostException)
                                urlError = e.message ?: "EMS website not found. Check the full deployed domain."
                            toast(e.message ?: "Sign in failed")
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = !loading && url.isNotBlank() && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryBlue,
                    contentColor = Color.White,
                    disabledContainerColor = PearlSurface,
                    disabledContentColor = TextMuted
                )
            ) {
                if (loading) {
                    ConnectXLoader(size = 20.dp, strokeWidth = 2.5.dp, color = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text("Signing in…", fontSize = 15.sp)
                } else {
                    Text("Sign in", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Powered by Dexter Studio",
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }

    /* =====================================================================
     * SHOP LIST SCREEN
     * ===================================================================== */
    @Composable
    private fun ShopListScreen(onBack: () -> Unit, onPick: (Shop) -> Unit) {
        var shops by remember { mutableStateOf<List<Shop>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var err by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            try {
                shops = withContext(Dispatchers.IO) { api.shops() }
            } catch (e: Exception) {
                err = e.message
            } finally {
                loading = false
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PureWhite)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(20.dp)
        ) {
            Text("Select a shop", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(
                "One device can serve multiple shops. Each shop maintains its own isolated SMS queue.",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )

            if (loading) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(3) {
                        ShimmerBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                }
            }

            err?.let {
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = AccentRoseBg,
                    borderColor = AccentRoseBorder,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(it, color = AccentRose, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
                }
                Spacer(Modifier.height(12.dp))
            }

            if (!loading && shops.isEmpty() && err == null) {
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = PearlBg,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Outlined.Storefront, null, tint = TextMuted, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("No shops found for this administrator.", color = TextSecondary, fontSize = 14.sp)
                    }
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(shops, key = { it.id }) { shop ->
                    AppCard(
                        onClick = { onPick(shop) },
                        containerColor = PureWhite,
                        borderColor = if (shop.connected) PrimaryBlue else BorderSubtle,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(PrimarySubtle)
                                    .border(1.dp, Color(0xFFBFDBFE), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    shop.name.take(1).uppercase(),
                                    color = PrimaryBlue,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    shop.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = TextPrimary
                                )
                                if (shop.address.isNotBlank()) {
                                    Text(
                                        shop.address,
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            StatusPill(if (shop.connected) "Connected" else "New")
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = PrimaryBlue)
            ) {
                Text("Use a different administrator account")
            }
        }
    }

    /* =====================================================================
     * PERMISSION SCREEN
     * ===================================================================== */
    @Composable
    private fun PermissionScreen(onContinue: () -> Unit, onBack: () -> Unit) {
        val needed = buildList {
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 26) add(Manifest.permission.READ_PHONE_NUMBERS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
            if (map[Manifest.permission.SEND_SMS] == true) onContinue()
            else toast("SMS permission is required. ConnectX cannot send messages without it.")
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PureWhite)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                BrandMark(48.dp)
                Spacer(Modifier.height(18.dp))
                Text("Allow SMS permission", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Text(
                    "ConnectX requires SMS and telephony permissions so this phone can send customer messages from your local SIM card. EMS never controls your SIM card directly.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )

                Spacer(Modifier.height(20.dp))

                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = PearlBg,
                    borderColor = BorderSubtle,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        PermissionItem(Icons.Outlined.Sms, "Send SMS Messages", "Dispatches customer invoices and payment receipts.")
                        Spacer(Modifier.height(12.dp))
                        PermissionItem(Icons.Outlined.SimCard, "Read Phone & SIM State", "Detects active carrier and SIM slot configuration.")
                        Spacer(Modifier.height(12.dp))
                        PermissionItem(Icons.Outlined.Notifications, "Post Notifications", "Keeps gateway active in foreground with zero sleep.")
                    }
                }
            }

            Column {
                Button(
                    onClick = { launcher.launch(needed) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryBlue,
                        contentColor = Color.White
                    )
                ) {
                    Text("Grant permissions", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Back")
                }
            }
        }
    }

    @Composable
    private fun PermissionItem(icon: ImageVector, title: String, desc: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PrimarySubtle),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = PrimaryBlue, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(desc, color = TextMuted, fontSize = 11.sp)
            }
        }
    }

    /* =====================================================================
     * SIM SELECTION SCREEN
     * ===================================================================== */
    @Composable
    private fun SimScreen(onBack: () -> Unit, onPick: (SubscriptionInfo) -> Unit) {
        val sims = remember { SmsSender.sims(this) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PureWhite)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp)
        ) {
            Text("Choose sending SIM", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(
                "Select which SIM card this shop should use to send SMS messages. You can switch SIMs anytime from Settings.",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
            )

            if (sims.isEmpty()) {
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = AccentRoseBg,
                    borderColor = AccentRoseBorder,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        "No SIM cards were detected. Please insert a SIM card and ensure phone permissions are granted.",
                        color = AccentRose,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            sims.forEach { sim ->
                AppCard(
                    onClick = { onPick(sim) },
                    modifier = Modifier.padding(bottom = 12.dp),
                    containerColor = PureWhite,
                    borderColor = BorderSubtle,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(PrimarySubtle)
                                .border(1.dp, Color(0xFFBFDBFE), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.SimCard, null, tint = PrimaryBlue, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(simTitle(sim), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                            Text(simNumber(sim), color = TextSecondary, fontSize = 13.sp)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            TextButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = PrimaryBlue)
            ) {
                Text("Back")
            }
        }
    }

    /* =====================================================================
     * REGISTERING SCREEN
     * ===================================================================== */
    @Composable
    private fun RegisteringScreen(shop: Shop?, sim: SubscriptionInfo?, onDone: (Boolean) -> Unit) {
        var msg by remember { mutableStateOf("Registering this device…") }

        LaunchedEffect(shop, sim) {
            if (shop == null || sim == null) {
                onDone(false)
                return@LaunchedEffect
            }
            try {
                withContext(Dispatchers.IO) {
                    api.registerDevice(
                        shopId = shop.id,
                        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
                        androidVersion = Build.VERSION.RELEASE,
                        simId = sim.subscriptionId,
                        carrier = sim.carrierName?.toString().orEmpty(),
                        phone = sim.number.orEmpty()
                    )
                }
                msg = "Device paired successfully."
                delay(400)
                onDone(true)
            } catch (e: Exception) {
                toast(e.message ?: "Could not register device")
                onDone(false)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PureWhite),
            contentAlignment = Alignment.Center
        ) {
            AppCard(
                modifier = Modifier.padding(32.dp),
                shape = RoundedCornerShape(20.dp),
                containerColor = PureWhite,
                borderColor = BorderSubtle
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ConnectXLoader(size = 48.dp, strokeWidth = 3.5.dp)
                    Spacer(Modifier.height(18.dp))
                    Text(msg, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    /* =====================================================================
     * TEST SMS SCREEN
     * ===================================================================== */
    @Composable
    private fun TestSmsScreen(sim: SubscriptionInfo?, onDone: () -> Unit) {
        val conn = prefs.active()
        var phone by remember { mutableStateOf(sim?.number?.filter { it.isDigit() || it == '+' } ?: conn?.phoneNumber.orEmpty()) }
        var sending by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val fields = modernFieldColors()

        suspend fun finishSetup(skipped: Boolean) {
            val shopId = conn?.shopId ?: return
            withContext(Dispatchers.IO) { api.markTest(shopId, true) }
            GatewayService.start(this@MainActivity)
            toast(if (skipped) "Skipped. You can send a test SMS later from Settings." else "Test SMS sent. Setup complete.")
            onDone()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PureWhite)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Send a test SMS", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Confirm that this device can send customer SMS messages for ${conn?.shopName ?: "this shop"}. You can skip this step and test later.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )

                Spacer(Modifier.height(20.dp))

                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    containerColor = PureWhite,
                    borderColor = BorderSubtle
                ) {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text("Destination phone number") },
                            placeholder = { Text("+8801XXXXXXXXX") },
                            leadingIcon = { Icon(Icons.Outlined.Phone, null, tint = PrimaryBlue) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = fields
                        )
                    }
                }
            }

            Column {
                Button(
                    onClick = {
                        scope.launch {
                            sending = true
                            try {
                                val c = conn ?: return@launch
                                val jobId = "test-${System.currentTimeMillis()}"
                                withContext(Dispatchers.IO) {
                                    SmsSender.send(
                                        this@MainActivity,
                                        c.simSubscriptionId,
                                        c.shopId,
                                        jobId,
                                        phone,
                                        "ConnectX test from ${c.shopName}. Your SMS gateway is working."
                                    )
                                }
                                delay(1200)
                                finishSetup(false)
                            } catch (e: Exception) {
                                toast(e.message ?: "Test failed")
                            } finally {
                                sending = false
                            }
                        }
                    },
                    enabled = !sending && phone.length >= 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryBlue,
                        contentColor = Color.White
                    )
                ) {
                    if (sending) {
                        ConnectXLoader(size = 20.dp, strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Sending…")
                    } else {
                        Text("Send test SMS", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = {
                        scope.launch {
                            sending = true
                            try {
                                finishSetup(true)
                            } catch (e: Exception) {
                                toast(e.message ?: "Could not skip")
                            } finally {
                                sending = false
                            }
                        }
                    },
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = PrimaryBlue)
                ) {
                    Text("Skip for now")
                }
            }
        }
    }

    /* =====================================================================
     * HOME SHELL & NAVIGATION (Light Theme)
     * ===================================================================== */
    @Composable
    private fun HomeShell(
        tab: Int,
        onTab: (Int) -> Unit,
        onAddShop: () -> Unit,
        onLogout: () -> Unit,
        onSession: () -> Unit,
        updateInfo: AppUpdateInfo? = null,
        onOpenUpdate: () -> Unit = {},
        onOpenAbout: () -> Unit = {}
    ) {
        val conn = prefs.active()

        Scaffold(
            containerColor = PureWhite,
            bottomBar = {
                NavigationBar(
                    containerColor = PureWhite,
                    tonalElevation = 0.dp,
                    modifier = Modifier.border(0.8.dp, BorderSubtle, RoundedCornerShape(0.dp))
                ) {
                    val colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = PrimaryBlue,
                        selectedTextColor = PrimaryBlue,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = PrimarySubtle
                    )
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { onTab(0) },
                        icon = { Icon(Icons.Outlined.Dashboard, contentDescription = "Dashboard") },
                        label = { Text("Dashboard") },
                        colors = colors
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { onTab(1) },
                        icon = { Icon(Icons.Outlined.Sms, contentDescription = "SMS") },
                        label = { Text("SMS") },
                        colors = colors
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { onTab(2) },
                        icon = { Icon(Icons.Outlined.Email, contentDescription = "Email") },
                        label = { Text("Email") },
                        colors = colors
                    )
                    NavigationBarItem(
                        selected = tab == 3,
                        onClick = { onTab(3) },
                        icon = { Icon(Icons.Outlined.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        colors = colors
                    )
                }
            }
        ) { pad ->
            Box(
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .background(PureWhite)
            ) {
                when (tab) {
                    0 -> HomeTab(conn, updateInfo, onOpenUpdate, onSession,
                        onOpenSms = { onTab(1) }, onOpenEmail = { onTab(2) })
                    1 -> SmsTab(conn, onSession)
                    2 -> EmailTab(conn)
                    else -> SettingsTab(conn, onAddShop, onLogout, onSession, updateInfo, onOpenAbout)
                }
            }
        }

        LaunchedEffect(Unit) {
            if (prefs.gatewayEnabled) GatewayService.start(this@MainActivity)
            while (true) {
                delay(25_000)
                runCatching { withContext(Dispatchers.IO) { QueueProcessor.drain(this@MainActivity) } }
            }
        }
    }

    /* =====================================================================
     * HOME / DASHBOARD TAB (Light Theme)
     * ===================================================================== */
    @Composable
    private fun HomeTab(
        conn: Connection?,
        updateInfo: AppUpdateInfo? = null,
        onOpenUpdate: () -> Unit = {},
        onSession: () -> Unit,
        onOpenSms: () -> Unit,
        onOpenEmail: () -> Unit
    ) {
        var stats by remember(conn?.shopId) {
            mutableStateOf(HomeStats(shopName = conn?.shopName.orEmpty(),
                shopAddress = conn?.shopAddress.orEmpty()))
        }
        var emailStats by remember(conn?.shopId) { mutableStateOf<EmailStats?>(null) }
        var loadingStats by remember(conn?.shopId) { mutableStateOf(true) }
        var loadingEmail by remember(conn?.shopId) { mutableStateOf(true) }
        var smsError by remember(conn?.shopId) { mutableStateOf<String?>(null) }
        var emailError by remember(conn?.shopId) { mutableStateOf<String?>(null) }
        var retry by remember { mutableIntStateOf(0) }
        var showShop by remember { mutableStateOf(false) }

        LaunchedEffect(conn?.shopId, retry) {
            if (conn == null) return@LaunchedEffect
            loadingStats = true
            loadingEmail = true
            smsError = null
            emailError = null
            launch {
                try { stats = withContext(Dispatchers.IO) { api.stats(conn.shopId) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { smsError = e.message ?: "SMS statistics unavailable." }
                finally { loadingStats = false }
            }
            launch {
                try { emailStats = withContext(Dispatchers.IO) { api.emailStats(conn.shopId) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { emailError = e.message ?: "Email statistics unavailable. Update EMS." }
                finally { loadingEmail = false }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PureWhite)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            // Update Banner if new version is available
            if (updateInfo != null) {
                AppCard(
                    onClick = onOpenUpdate,
                    containerColor = if (updateInfo.mandatory) RoseText.copy(alpha = 0.08f) else PrimarySubtle,
                    borderColor = if (updateInfo.mandatory) RoseText.copy(alpha = 0.35f) else PrimaryBlue.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.padding(bottom = 14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (updateInfo.mandatory) RoseText else PrimaryBlue),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.Download,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    if (updateInfo.mandatory) "Important Update Required (v${updateInfo.latestVersion})" else "New Update Available (v${updateInfo.latestVersion})",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = if (updateInfo.mandatory) RoseText else PrimaryBlue
                                )
                                Text(
                                    if (updateInfo.mandatory) "Tap to install mandatory build ${updateInfo.versionCode}" else "Build ${updateInfo.versionCode} is now available in App Store",
                                    fontSize = 11.5.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = if (updateInfo.mandatory) RoseText else PrimaryBlue
                        )
                    }
                }
            }

            // Top Shop Hero Card
            AppCard(
                containerColor = PureWhite,
                borderColor = BorderSubtle,
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(PrimarySubtle)
                                .border(1.dp, Color(0xFFBFDBFE), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                "CONNECTX · COMMUNICATION",
                                color = PrimaryBlue,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
                            )
                        }

                        TextButton(
                            onClick = { showShop = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = PrimaryBlue)
                        ) {
                            Icon(Icons.Outlined.SwapHoriz, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Switch shop", fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(getString(R.string.brand_full), color = TextSecondary,
                        fontSize = 12.sp, lineHeight = 17.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Active shop", color = TextMuted, fontSize = 11.sp)

                    Text(
                        text = stats.shopName.ifBlank { conn?.shopName ?: "ConnectX" },
                        color = TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 28.sp
                    )

                    val addr = stats.shopAddress.ifBlank { conn?.shopAddress.orEmpty() }
                    if (addr.isNotBlank()) {
                        Text(
                            text = addr,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // Gateway Status Pill
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(if (prefs.gatewayEnabled) AccentEmerald else TextMuted)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (prefs.gatewayEnabled) "SMS dispatch active" else "SMS dispatch paused · Email history remains available",
                            color = if (prefs.gatewayEnabled) AccentEmerald else TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }


                }
            }

            Spacer(Modifier.height(14.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Sms, null, tint = PrimaryBlue)
                    Spacer(Modifier.width(8.dp))
                    Text("SMS · Local SIM", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        color = TextPrimary)
                }
                TextButton(onClick = onOpenSms) { Text("Open SMS →") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Sent today", if (loadingStats || smsError != null) "—" else stats.sent.toString(),
                    AccentEmerald, Modifier.weight(1f))
                MetricCard("Queued", if (loadingStats || smsError != null) "—" else stats.pending.toString(),
                    AccentAmber, Modifier.weight(1f))
                MetricCard("Failed", if (loadingStats || smsError != null) "—" else stats.failed.toString(),
                    AccentRose, Modifier.weight(1f))
            }
            if (smsError != null) Text("SMS: $smsError", color = RoseText, fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp))

            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = BorderSubtle)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Email, null, tint = AccentPurple)
                    Spacer(Modifier.width(8.dp))
                    Text("Email · EMS ConnectX", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        color = TextPrimary)
                }
                TextButton(onClick = onOpenEmail) { Text("Open Email →") }
            }
            Text("Outgoing history for this shop · read-only", color = TextSecondary,
                fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Sent today", if (loadingEmail || emailError != null) "—" else (emailStats?.sent?.toString() ?: "—"),
                    AccentEmerald, Modifier.weight(1f))
                MetricCard("Pending", if (loadingEmail || emailError != null) "—" else (emailStats?.pending?.toString() ?: "—"),
                    AccentAmber, Modifier.weight(1f))
                MetricCard("Failed", if (loadingEmail || emailError != null) "—" else (emailStats?.failed?.toString() ?: "—"),
                    AccentRose, Modifier.weight(1f))
            }
            if (emailError != null) {
                Text("Email: $emailError · Check connection and deploy the matching EMS API.",
                    color = RoseText, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            } else if (emailStats?.latest != null) {
                val latest = emailStats!!.latest!!
                AppCard(onClick = onOpenEmail, containerColor = PearlBg,
                    borderColor = BorderSubtle, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(top = 12.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Latest outgoing email", fontSize = 11.sp, color = TextMuted)
                            StatusPill(latest.status)
                        }
                        Text(latest.subject, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("To: ${latest.toEmails.joinToString().ifBlank { "—" }} · ${prettyTime(latest.sentAt.ifBlank { latest.createdAt }) ?: ""}",
                            color = TextSecondary, fontSize = 11.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            } else if (!loadingEmail) {
                Text("No outgoing email history for this shop.", color = TextMuted,
                    fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }

            if (smsError != null || emailError != null) {
                TextButton(onClick = { retry++ }, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry statistics")
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(getString(R.string.powered_by), color = TextMuted, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }

        if (showShop) ShopSwitcher(onClose = { showShop = false }, onPicked = { showShop = false; onSession() })
    }

    @Composable
    private fun SendingSimCard(conn: Connection?, onSwitch: () -> Unit) {
        AppCard(onClick = onSwitch, containerColor = PureWhite, borderColor = BorderSubtle,
            shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SimCard, null, tint = PrimaryBlue, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Sending SIM · Tap to switch", fontSize = 12.sp, color = TextSecondary)
                    Text(listOfNotNull(conn?.simCarrier?.ifBlank { null },
                        conn?.phoneNumber?.ifBlank { null }).joinToString(" · ")
                        .ifBlank { "Select a sending SIM" },
                        fontWeight = FontWeight.SemiBold, color = TextPrimary,
                        fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted)
            }
        }
    }

    /** Only the selected sending SIM may be queried. Never call USSD on entry
     * or in a background worker: the user explicitly taps Refresh. */
    @Composable
    private fun SimBalanceCard(conn: Connection?, state: BalanceUiState,
        scope: kotlinx.coroutines.CoroutineScope) {
        val selectedSim = SmsSender.sims(this@MainActivity)
            .firstOrNull { it.subscriptionId == conn?.simSubscriptionId }
        val mccMnc = selectedSim?.let { sim ->
            runCatching {
                getSystemService(TelephonyManager::class.java)
                    ?.createForSubscriptionId(sim.subscriptionId)?.simOperator.orEmpty()
            }.getOrDefault("")
        }.orEmpty().takeIf { it.matches(Regex("[0-9]{5,6}")) }.orEmpty()
        val simName = selectedSim?.carrierName?.toString().orEmpty().ifBlank {
            if (selectedSim == null) "No active sending SIM"
            else conn?.simCarrier.orEmpty().ifBlank { "Unknown carrier" }
        }
        val phone = if (selectedSim == null) "" else
            (runCatching { selectedSim.number.takeIf { it.isNotBlank() } }.getOrNull()
                ?: conn?.phoneNumber.orEmpty())
        val masked = remember(phone) {
            val digits = phone.filter { it.isDigit() }
            if (digits.length < 8) "Not available"
            else digits.take(3) + "X".repeat((digits.length - 3).coerceAtMost(12))
        }
        var catalog by state.catalog
        var pending by state.pending
        var balance by state.balance
        var status by state.status
        var busy by state.busy

        suspend fun querySelectedSim(config: CarrierBalanceConfig) {
            val shop = conn ?: return
            val sim = selectedSim ?: return
            if (prefs.active()?.shopId != shop.shopId ||
                prefs.active()?.simSubscriptionId != sim.subscriptionId) {
                status = "Balance unavailable"
                return
            }
            status = "Checking SIM balance…"
            val reply = SimUssdClient.request(this@MainActivity, sim.subscriptionId, config.balanceCode)
            if (prefs.active()?.shopId != shop.shopId ||
                prefs.active()?.simSubscriptionId != sim.subscriptionId) {
                status = "Balance unavailable"
                return
            }
            balance = withContext(Dispatchers.Default) {
                BalanceReplyParser.balance(reply, config.balancePattern)
            }
            status = if (balance == null) "Balance unavailable" else "Updated from SIM"
        }

        val permissionRequest = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val config = pending
            pending = null
            if (granted && config != null) {
                busy = true
                scope.launch {
                    try { querySelectedSim(config) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { status = "Balance unavailable" }
                    finally { busy = false }
                }
            } else status = "Balance unavailable"
        }

        fun refresh() {
            val shop = conn
            if (busy || shop == null || selectedSim == null) {
                if (selectedSim == null) status = "Balance unavailable"
                return
            }
            busy = true
            balance = null
            catalog = null
            scope.launch {
                try {
                    // Re-read the owner catalog on every tap; no bundled USSD code.
                    val config = withContext(Dispatchers.IO) {
                        api.simCarrier(shop.shopId, mccMnc, simName)
                    }
                    catalog = config
                    if (config == null) {
                        status = "Balance unavailable"
                    } else if (ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.CALL_PHONE
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pending = config
                        status = "Allow Phone permission to check balance."
                        permissionRequest.launch(Manifest.permission.CALL_PHONE)
                    } else {
                        querySelectedSim(config)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { status = "Balance unavailable" }
                finally { busy = false }
            }
        }

        AppCard(
            containerColor = PureWhite,
            borderColor = BorderSubtle,
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.SimCard, contentDescription = null, tint = PrimaryBlue,
                        modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("SIM Balance", fontWeight = FontWeight.Bold,
                        fontSize = 16.sp, color = TextPrimary)
                }
                Spacer(Modifier.height(12.dp))
                Text("Carrier: ${catalog?.name ?: simName}", fontSize = 13.sp, color = TextPrimary)
                Text("Number: $masked", fontSize = 13.sp, color = TextSecondary)
                if (catalog == null && mccMnc.isNotBlank())
                    Text("SIM MCC/MNC: $mccMnc", fontSize = 11.sp, color = TextMuted)
                Spacer(Modifier.height(12.dp))
                if (balance != null) {
                    Text("Balance: ৳$balance", fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, color = TextPrimary)
                }
                Text(status, fontSize = 12.sp,
                    color = if (status == "Balance unavailable") RoseText else TextMuted,
                    modifier = Modifier.padding(top = 5.dp, bottom = 12.dp))
                Button(onClick = { refresh() }, enabled = !busy && selectedSim != null,
                    shape = RoundedCornerShape(10.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp,
                        color = Color.White)
                    else Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (busy) "Checking…" else "Refresh")
                }
            }
        }
    }

    @Composable
    private fun MetricCard(label: String, value: String, accentColor: Color, modifier: Modifier = Modifier) {
        AppCard(
            modifier = modifier,
            containerColor = PureWhite,
            borderColor = BorderSubtle,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = value,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    /* =====================================================================
     * SMS TAB — manual SIM controls and outgoing SMS history
     * ===================================================================== */
    @Composable
    private fun SmsTab(conn: Connection?, onSession: () -> Unit) {
        var range by remember { mutableStateOf("today") }
        var items by remember(conn?.shopId) { mutableStateOf<List<ActivityItem>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var reload by remember { mutableIntStateOf(0) }
        var showSim by remember { mutableStateOf(false) }
        var open by remember { mutableStateOf<ActivityItem?>(null) }
        var cancelling by remember { mutableStateOf(false) }
        var jobToCancel by remember { mutableStateOf<ActivityItem?>(null) }
        val scope = rememberCoroutineScope()
        val currentCarrier = SmsSender.sims(this@MainActivity)
            .firstOrNull { it.subscriptionId == conn?.simSubscriptionId }
            ?.carrierName?.toString().orEmpty()
        val balanceState = remember(conn?.shopId, conn?.simSubscriptionId,
            conn?.simCarrier, currentCarrier) { BalanceUiState() }

        LaunchedEffect(conn?.shopId, range, reload) {
            if (conn == null) return@LaunchedEffect
            loading = true
            error = null
            items = emptyList()
            try { items = withContext(Dispatchers.IO) { api.activity(conn.shopId, range) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "SMS history is unavailable." }
            finally { loading = false }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(PureWhite).statusBarsPadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("SMS", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("${conn?.shopName ?: "No paired shop"} · SIM dispatch and history",
                        color = TextSecondary, fontSize = 12.sp)
                }

                IconButton(
                    onClick = { reload++ },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(PearlSurface)
                        .border(1.dp, BorderSubtle, CircleShape)
                ) {
                    Icon(Icons.Outlined.Refresh, "Refresh", tint = PrimaryBlue, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            SendingSimCard(conn, onSwitch = { showSim = true })
            Spacer(Modifier.height(10.dp))
            SimBalanceCard(conn, balanceState, scope)
            Spacer(Modifier.height(16.dp))
            Text("SMS history", color = TextPrimary, fontWeight = FontWeight.Bold,
                fontSize = 16.sp)

            // Filter Chips Bar
            Row(
                modifier = Modifier.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val chipColors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PrimaryBlue,
                    selectedLabelColor = Color.White,
                    containerColor = PearlBg,
                    labelColor = TextSecondary
                )
                FilterChip(
                    selected = range == "today",
                    onClick = { range = "today" },
                    label = { Text("Today", fontSize = 12.sp) },
                    colors = chipColors,
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = BorderSubtle,
                        selectedBorderColor = PrimaryBlue,
                        enabled = true,
                        selected = range == "today"
                    )
                )
                FilterChip(
                    selected = range == "7d",
                    onClick = { range = "7d" },
                    label = { Text("7 Days", fontSize = 12.sp) },
                    colors = chipColors,
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = BorderSubtle,
                        selectedBorderColor = PrimaryBlue,
                        enabled = true,
                        selected = range == "7d"
                    )
                )
                FilterChip(
                    selected = range == "30d",
                    onClick = { range = "30d" },
                    label = { Text("30 Days", fontSize = 12.sp) },
                    colors = chipColors,
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = BorderSubtle,
                        selectedBorderColor = PrimaryBlue,
                        enabled = true,
                        selected = range == "30d"
                    )
                )
            }

            if (loading) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(4) {
                        ShimmerBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(95.dp),
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                }
            } else if (error != null) {
                Text("Could not load SMS history: $error", color = RoseText, fontSize = 13.sp)
                TextButton(onClick = { reload++ }) { Text("Try again") }
            } else if (items.isEmpty()) {
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    containerColor = PearlBg,
                    borderColor = BorderSubtle,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Outlined.ChatBubbleOutline, null, tint = TextMuted, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("No SMS messages in this date range.", color = TextSecondary, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Messages will appear here once dispatched.", color = TextMuted, fontSize = 12.sp)
                    }
                }
            }
            } // Header and SIM controls scroll together with SMS history.

            items(items, key = { it.id }) { row ->
                    val isQueued = row.status.equals("queued", ignoreCase = true)

                    AppCard(
                        onClick = { open = row },
                        containerColor = PureWhite,
                        borderColor = BorderSubtle,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            // Header: Message Type Title & Status
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MessageTypeBadge(row.type)

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusPill(row.status)

                                    // Cancel Button if SMS is Queued / Pending
                                    if (isQueued) {
                                        Spacer(Modifier.width(8.dp))
                                        IconButton(
                                            onClick = { jobToCancel = row },
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(AccentRoseBg)
                                                .border(1.dp, AccentRoseBorder, CircleShape)
                                        ) {
                                            Icon(
                                                Icons.Outlined.Close,
                                                contentDescription = "Cancel SMS",
                                                tint = AccentRose,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // Recipient & Phone
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    row.name.ifBlank { "Customer" },
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    fontSize = 14.sp
                                )
                                Text(
                                    row.phone,
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }

                            if (row.message.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    row.message,
                                    fontSize = 13.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 18.sp,
                                    color = TextSecondary
                                )
                            }

                            Spacer(Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    prettyTime(row.sentAt.ifBlank { row.createdAt }) ?: "",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )

                                if (isQueued) {
                                    Text(
                                        "Tap to view or cancel",
                                        color = AccentAmber,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
        }

        if (showSim) SimSwitcher(conn, onClose = { showSim = false },
            onPicked = { showSim = false; onSession() })

        // Cancel Confirmation Dialog
        jobToCancel?.let { target ->
            AlertDialog(
                onDismissRequest = { jobToCancel = null },
                containerColor = PureWhite,
                icon = { Icon(Icons.Outlined.Cancel, null, tint = AccentRose, modifier = Modifier.size(28.dp)) },
                title = { Text("Cancel Queued SMS?", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "Are you sure you want to cancel this ${target.type} to ${target.name} (${target.phone})? It will be removed from the gateway queue and will not be sent.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val c = conn ?: return@Button
                            scope.launch {
                                cancelling = true
                                try {
                                    val ok = withContext(Dispatchers.IO) { api.cancelJob(c.shopId, target.id) }
                                    if (ok) {
                                        items = items.filterNot { it.id == target.id }
                                        if (open?.id == target.id) open = null
                                        toast("Queued SMS cancelled.")
                                        reload++
                                    } else toast("SMS cancellation was not confirmed. Refresh and try again.")
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) {
                                    toast(e.message ?: "SMS cancellation failed. It may already be sending.")
                                    reload++
                                } finally {
                                    cancelling = false
                                    jobToCancel = null
                                }
                            }
                        },
                        enabled = !cancelling,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentRose,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel SMS", fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { jobToCancel = null },
                        colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                    ) {
                        Text("Keep SMS")
                    }
                }
            )
        }

        // Detail Bottom Sheet
        open?.let { row ->
            val isQueued = row.status.equals("queued", ignoreCase = true)

            ModalBottomSheet(
                onDismissRequest = { open = null },
                containerColor = PureWhite,
                scrimColor = Color.Black.copy(alpha = 0.45f)
            ) {
                Column(
                    Modifier
                        .padding(horizontal = 22.dp)
                        .padding(bottom = 36.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MessageTypeBadge(row.type)
                        StatusPill(row.status)
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = row.type,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${row.name.ifBlank { "Customer" }} · ${row.phone}",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )

                    Text(
                        prettyTime(row.sentAt.ifBlank { row.createdAt }) ?: row.createdAt,
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    Spacer(Modifier.height(18.dp))

                    Text("Message Content", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))

                    AppCard(
                        containerColor = PearlBg,
                        borderColor = BorderSubtle,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = row.message.ifBlank { "No message body was recorded." },
                            modifier = Modifier.padding(16.dp),
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            color = TextPrimary
                        )
                    }

                    row.error?.let {
                        Spacer(Modifier.height(12.dp))
                        AppCard(
                            containerColor = AccentRoseBg,
                            borderColor = AccentRoseBorder,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Error: $it",
                                color = AccentRose,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    if (isQueued) {
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = {
                                jobToCancel = row
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentRose,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Outlined.Cancel, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Cancel this queued SMS", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    /* =====================================================================
     * EMAIL TAB — read-only, paginated EMS outgoing history for active shop
     * ===================================================================== */
    @Composable
    private fun EmailTab(conn: Connection?) {
        var emails by remember(conn?.shopId) { mutableStateOf<List<EmailItem>>(emptyList()) }
        var nextPage by remember(conn?.shopId) { mutableIntStateOf(0) }
        var snapshot by remember(conn?.shopId) { mutableStateOf("") }
        var hasMore by remember(conn?.shopId) { mutableStateOf(false) }
        var loading by remember(conn?.shopId) { mutableStateOf(false) }
        var error by remember(conn?.shopId) { mutableStateOf<String?>(null) }
        var selectedId by remember(conn?.shopId) { mutableStateOf<String?>(null) }
        var detail by remember(conn?.shopId) { mutableStateOf<EmailItem?>(null) }
        var detailLoading by remember(conn?.shopId) { mutableStateOf(false) }
        var detailError by remember(conn?.shopId) { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        suspend fun load(reset: Boolean) {
            val shop = conn ?: return
            if (loading || (!reset && !hasMore)) return
            val target = if (reset) 0 else nextPage
            val anchor = if (reset) "" else snapshot
            loading = true
            error = null
            if (reset) {
                emails = emptyList()
                nextPage = 0
                hasMore = false
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    api.emailPage(shop.shopId, target, anchor)
                }
                if (prefs.active()?.shopId != shop.shopId) return
                emails = if (reset) result.items else (emails + result.items).distinctBy { it.id }
                nextPage = result.page + 1
                hasMore = result.hasMore
                snapshot = result.snapshot
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = e.message ?: "Could not load email history. Check EMS and retry."
            } finally { loading = false }
        }

        LaunchedEffect(conn?.shopId) { load(reset = true) }
        LaunchedEffect(conn?.shopId, selectedId) {
            val id = selectedId ?: return@LaunchedEffect
            val shop = conn ?: return@LaunchedEffect
            detail = null
            detailError = null
            detailLoading = true
            try { detail = withContext(Dispatchers.IO) { api.emailDetail(shop.shopId, id) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { detailError = e.message ?: "Email details unavailable." }
            finally { detailLoading = false }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(PureWhite).statusBarsPadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Email", fontWeight = FontWeight.Bold, fontSize = 26.sp,
                            color = TextPrimary)
                        Text(conn?.shopName ?: "No paired shop", fontSize = 12.sp,
                            color = TextSecondary)
                    }
                    IconButton(onClick = { scope.launch { load(reset = true) } },
                        enabled = !loading && conn != null,
                        modifier = Modifier.size(36.dp).clip(CircleShape)
                            .background(PearlSurface).border(1.dp, BorderSubtle, CircleShape)) {
                        Icon(Icons.Outlined.Refresh, "Refresh email", tint = PrimaryBlue,
                            modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                AppCard(containerColor = PrimarySubtle, borderColor = Color(0xFFBFDBFE),
                    shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.MarkEmailRead, null, tint = PrimaryBlue)
                        Spacer(Modifier.width(10.dp))
                        Text("EMS ConnectX outgoing email history for this shop. View sent, failed and pending messages here; send new email from EMS.",
                            color = TextPrimary, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Messages", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = TextPrimary)
            }

            items(emails, key = { it.id }) { row ->
                AppCard(onClick = { selectedId = row.id }, containerColor = PureWhite,
                    borderColor = BorderSubtle, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(15.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(row.subject, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            StatusPill(row.status)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("To: ${row.toEmails.joinToString().ifBlank { "—" }}",
                            fontSize = 12.sp, color = TextSecondary, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text(prettyTime(row.sentAt.ifBlank { row.createdAt }) ?: "",
                            fontSize = 11.sp, color = TextMuted,
                            modifier = Modifier.padding(top = 6.dp))
                        if (row.error != null) Text(row.error, color = RoseText, fontSize = 11.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            item {
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
                        ConnectXLoader(size = 28.dp)
                    }
                } else if (error != null) {
                    AppCard(containerColor = AccentRoseBg, borderColor = AccentRoseBorder) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("Email history unavailable: $error", color = RoseText, fontSize = 13.sp)
                            Text("Check EMS connectivity; deploy the v1.6 device API if needed.",
                                color = TextSecondary, fontSize = 12.sp)
                            TextButton(onClick = { scope.launch { load(reset = emails.isEmpty()) } }) {
                                Text("Try again")
                            }
                        }
                    }
                } else if (emails.isEmpty()) {
                    AppCard(containerColor = PearlBg, borderColor = BorderSubtle) {
                        Column(Modifier.fillMaxWidth().padding(30.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Email, null, tint = TextMuted,
                                modifier = Modifier.size(36.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("No outgoing emails for this shop yet.", color = TextSecondary)
                        }
                    }
                } else if (hasMore) {
                    OutlinedButton(onClick = { scope.launch { load(reset = false) } },
                        modifier = Modifier.fillMaxWidth()) {
                        Text("Load older emails")
                    }
                } else {
                    Text("All available emails loaded", color = TextMuted, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
        }

        if (selectedId != null) {
            ModalBottomSheet(onDismissRequest = { selectedId = null },
                containerColor = PureWhite,
                scrimColor = Color.Black.copy(alpha = 0.45f)) {
                Column(Modifier.fillMaxWidth().fillMaxHeight(0.83f)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)
                    .padding(bottom = 32.dp)) {
                    Text("Outgoing email", fontSize = 12.sp, color = PrimaryBlue,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    if (detailLoading) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            ConnectXLoader(size = 30.dp)
                        }
                    } else if (detailError != null) {
                        Text("Could not open email: $detailError", color = RoseText)
                        TextButton(onClick = { selectedId = null }) { Text("Close") }
                    } else if (detail != null) {
                        val mail = detail!!
                        Text(mail.subject, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                            color = TextPrimary, lineHeight = 26.sp)
                        Spacer(Modifier.height(10.dp))
                        StatusPill(mail.status)
                        Spacer(Modifier.height(14.dp))
                        Text("From: ${mail.fromEmail}", color = TextSecondary, fontSize = 13.sp)
                        Text("To: ${mail.toEmails.joinToString().ifBlank { "—" }}",
                            color = TextSecondary, fontSize = 13.sp)
                        if (mail.ccEmails.isNotEmpty()) Text("CC: ${mail.ccEmails.joinToString()}",
                            color = TextSecondary, fontSize = 13.sp)
                        if (mail.bccEmails.isNotEmpty()) Text("BCC: ${mail.bccEmails.joinToString()}",
                            color = TextSecondary, fontSize = 13.sp)
                        Text("${if (mail.status == "sent") "Sent" else "Created"}: ${prettyTime(mail.sentAt.ifBlank { mail.createdAt }) ?: "—"}",
                            color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        if (mail.error != null) {
                            Spacer(Modifier.height(12.dp))
                            Text("Provider error: ${mail.error}", color = RoseText, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(18.dp))
                        HorizontalDivider(color = BorderSubtle)
                        Spacer(Modifier.height(14.dp))
                        Text("Message and document (plain-text view)", color = TextMuted,
                            fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Spacer(Modifier.height(7.dp))
                        AppCard(containerColor = PearlBg, borderColor = BorderSubtle,
                            shape = RoundedCornerShape(12.dp)) {
                            Text(readableEmailBody(mail), Modifier.fillMaxWidth().padding(16.dp),
                                fontSize = 14.sp, color = TextPrimary, lineHeight = 21.sp)
                        }
                    }
                }
            }
        }
    }

    /** Render EMS-generated HTML as inert text; never execute remote email HTML,
     * JavaScript, or image URLs inside the Android app. */
    private fun readableEmailBody(mail: EmailItem): String {
        if (mail.bodyHtml.isBlank()) return mail.customBody.ifBlank { "No email body recorded." }
        val html = mail.bodyHtml
            .replace(Regex("(?i)</t[dh]>"), " | ")
            .replace(Regex("(?i)</tr>"), "<br>")
        return android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT)
            .toString().replace(Regex("\n{3,}"), "\n\n").trim()
            .ifBlank { mail.customBody.ifBlank { "No email body recorded." } }
    }

    /* =====================================================================
     * SETTINGS TAB WITH ADMINISTRATOR PROFILE & ABOUT / UPDATES ROW
     * ===================================================================== */
    @Composable
    private fun SettingsTab(
        conn: Connection?,
        onAddShop: () -> Unit,
        onLogout: () -> Unit,
        onSession: () -> Unit,
        updateInfo: AppUpdateInfo? = null,
        onOpenAbout: () -> Unit = {}
    ) {
        var enabled by remember { mutableStateOf(prefs.gatewayEnabled) }
        var showSim by remember { mutableStateOf(false) }
        var showLogout by remember { mutableStateOf(false) }
        var showTest by remember { mutableStateOf(false) }
        var showAdminProfile by remember { mutableStateOf(false) }
        var adminProfile by remember { mutableStateOf(prefs.getAdminProfile()) }
        var refreshingProfile by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        // Fetch fresh administrator profile
        LaunchedEffect(Unit) {
            runCatching {
                adminProfile = withContext(Dispatchers.IO) { api.fetchAdminProfile(conn?.shopId) }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PureWhite)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(14.dp))

            // =============================================================
            // TOP ADMINISTRATOR PROFILE HERO CARD
            // Tapping opens the comprehensive Administrator Profile sheet
            // =============================================================
            AppCard(
                onClick = { showAdminProfile = true },
                containerColor = PureWhite,
                borderColor = BorderSubtle,
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(PrimarySubtle)
                                    .border(1.5.dp, PrimaryBlue, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    (adminProfile.name.ifBlank { prefs.adminName }).take(1).uppercase().ifBlank { "A" },
                                    color = PrimaryBlue,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        adminProfile.name.ifBlank { prefs.adminName }.ifBlank { "Administrator" },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp,
                                        color = TextPrimary
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = "Verified Administrator",
                                        tint = AccentEmerald,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    adminProfile.email.ifBlank { prefs.adminEmail },
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted)
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = BorderSubtle)
                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val code = adminProfile.adminCode.ifBlank { prefs.adminCode }
                        val formattedId = when {
                            code.isNotBlank() && (code.startsWith("ADMIN-") || code.startsWith("#")) -> code
                            code.isNotBlank() -> "#$code"
                            adminProfile.id.isNotBlank() && adminProfile.id.length <= 6 -> "#${adminProfile.id}"
                            else -> "#1001"
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(PrimarySubtle)
                                .border(1.dp, Color(0xFFBFDBFE), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                formattedId,
                                color = PrimaryBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Text(
                            "View Profile Details →",
                            color = PrimaryBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // =============================================================
            // GATEWAY CONTROLS CARD
            // =============================================================
            AppCard(
                containerColor = PureWhite,
                borderColor = BorderSubtle,
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        "GATEWAY CONTROLS",
                        color = PrimaryBlue,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        conn?.shopName ?: "No shop connected",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        "Hardware Device: ${conn?.devicePublicId ?: "—"}",
                        color = TextMuted,
                        fontSize = 12.sp
                    )

                    Spacer(Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("SMS Gateway Active", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                "When paused, customer SMS jobs stay queued safely in EMS.",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }

                        Switch(
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                prefs.gatewayEnabled = it
                                if (it) GatewayService.start(this@MainActivity) else GatewayService.stop(this@MainActivity)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = PrimaryBlue,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = PearlSurface
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Settings Rows with Clean "About & Updates" navigation
            SettingsRow(
                icon = Icons.Outlined.Info,
                title = "About & Updates",
                sub = if (updateInfo != null && updateInfo.versionCode > APP_VERSION_CODE) "Update available (v${updateInfo.latestVersion})" else "Version $APP_VERSION_NAME · Build $APP_VERSION_CODE · Check for updates",
                accentColor = if (updateInfo != null && updateInfo.versionCode > APP_VERSION_CODE) (if (updateInfo.mandatory) RoseText else PrimaryBlue) else TextPrimary,
                onClick = onOpenAbout
            )

            SettingsRow(
                icon = Icons.Outlined.SimCard,
                title = "Switch Sending Number",
                sub = conn?.let {
                    listOf(it.simCarrier, it.phoneNumber).filter { s -> s.isNotBlank() }.joinToString(" · ").ifBlank { "Choose active SIM" }
                } ?: "Choose active SIM",
                onClick = { showSim = true }
            )

            SettingsRow(
                icon = Icons.Outlined.Send,
                title = "Send a Test SMS",
                sub = "Verify that this device's SIM card can dispatch messages",
                onClick = { showTest = true }
            )

            SettingsRow(
                icon = Icons.Outlined.Store,
                title = "Connected Shops (${prefs.connections().size})",
                sub = "Manage retail outlets paired with this device",
                onClick = { onAddShop() }
            )

            SettingsRow(
                icon = Icons.Outlined.BatteryChargingFull,
                title = "Disable Battery Restrictions",
                sub = "Recommended so queued SMS dispatch instantly in background",
                onClick = {
                    val pm = getSystemService(PowerManager::class.java)
                    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                    } else toast("Battery restrictions already disabled.")
                }
            )

            SettingsRow(
                icon = Icons.Outlined.LinkOff,
                title = "Disconnect this Shop",
                sub = "Revoke device gateway authorization for ${conn?.shopName ?: "this shop"}",
                accentColor = AccentRose,
                onClick = {
                    scope.launch {
                        conn?.let {
                            withContext(Dispatchers.IO) { api.disconnect(it.shopId) }
                            if (prefs.connections().isEmpty()) GatewayService.stop(this@MainActivity)
                            toast("Shop disconnected.")
                            onSession()
                        }
                    }
                }
            )

            SettingsRow(
                icon = Icons.Outlined.Logout,
                title = "Log Out",
                sub = "Stop background sending until you sign in again",
                onClick = { showLogout = true }
            )

            Spacer(Modifier.height(14.dp))

            Text(
                "ConnectX never retains your administrator password after pairing.\nRevoke gateway devices from EMS Settings → Communication anytime.\n\nPowered by Dexter Studio",
                color = TextMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // =============================================================
        // ADMINISTRATOR PROFILE DETAILS MODAL SHEET
        // =============================================================
        if (showAdminProfile) {
            val code = adminProfile.adminCode.ifBlank { prefs.adminCode }
            val formattedShortId = when {
                code.isNotBlank() && (code.startsWith("ADMIN-") || code.startsWith("#")) -> code
                code.isNotBlank() -> "#$code"
                adminProfile.id.isNotBlank() && adminProfile.id.length <= 6 -> "#${adminProfile.id}"
                adminProfile.id.isNotBlank() -> "#${adminProfile.id.take(4).uppercase()}"
                else -> "#1001"
            }

            // Fallback for phone and address from active connection / shop if not in profile
            val displayPhone = adminProfile.phone.ifBlank { prefs.adminPhone }.ifBlank { conn?.phoneNumber.orEmpty() }.ifBlank { "Not provided" }
            val displayAddress = adminProfile.address.ifBlank { prefs.adminAddress }.ifBlank { conn?.shopAddress.orEmpty() }.ifBlank { "Not provided" }

            ModalBottomSheet(
                onDismissRequest = { showAdminProfile = false },
                containerColor = PureWhite,
                scrimColor = Color.Black.copy(alpha = 0.45f)
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 22.dp)
                        .padding(bottom = 36.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Administrator Profile", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        IconButton(
                            onClick = {
                                scope.launch {
                                    refreshingProfile = true
                                    try {
                                        adminProfile = withContext(Dispatchers.IO) { api.fetchAdminProfile(conn?.shopId) }
                                        toast("Profile refreshed.")
                                    } catch (e: Exception) {
                                        toast(e.message ?: "Failed to refresh profile")
                                    } finally {
                                        refreshingProfile = false
                                    }
                                }
                            }
                        ) {
                            if (refreshingProfile) {
                                ConnectXLoader(size = 18.dp, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Refresh, "Refresh", tint = PrimaryBlue)
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Profile Summary Card
                    AppCard(
                        containerColor = PearlBg,
                        borderColor = BorderSubtle,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            ProfileDetailRow("Full Name", adminProfile.name.ifBlank { prefs.adminName }.ifBlank { "Administrator" })
                            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))

                            ProfileDetailRow("Administrator ID", formattedShortId)
                            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))

                            ProfileDetailRow("Email Address", adminProfile.email.ifBlank { prefs.adminEmail })
                            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))

                            ProfileDetailRow("Phone Number", displayPhone)
                            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))

                            ProfileDetailRow("Address", displayAddress)
                            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))

                            ProfileDetailRow("Account Status", if (adminProfile.active) "Active Administrator" else "Suspended")
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text("Gateway Infrastructure", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))

                    AppCard(
                        containerColor = PearlBg,
                        borderColor = BorderSubtle,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            ProfileDetailRow("EMS Server URL", prefs.baseUrl)
                            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))

                            ProfileDetailRow("Connected Shops", "${prefs.connections().size} Shop(s)")
                            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))

                            ProfileDetailRow("Active Device ID", conn?.devicePublicId ?: "—")
                            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))

                            ProfileDetailRow("Active Sending SIM", "${conn?.simCarrier ?: "SIM"} · ${conn?.phoneNumber ?: "—"}")
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    Button(
                        onClick = { showAdminProfile = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PearlSurface,
                            contentColor = TextPrimary
                        )
                    ) {
                        Text("Close Profile")
                    }
                }
            }
        }

        if (showSim) SimSwitcher(conn, onClose = { showSim = false }, onPicked = { showSim = false; onSession() })

        if (showLogout) {
            AlertDialog(
                onDismissRequest = { showLogout = false },
                containerColor = PureWhite,
                title = { Text("Log out of ConnectX?", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "SMS sending will pause on this phone until you sign in again. Connected shops remain registered so duplicate devices are avoided.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { showLogout = false; onLogout() },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRose, contentColor = Color.White),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Log out")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showLogout = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showTest) TestSheet(conn) { showTest = false }
    }

    @Composable
    private fun ProfileDetailRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = TextMuted, fontSize = 12.sp)
            Text(
                value,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
    }

    @Composable
    private fun SettingsRow(
        icon: ImageVector,
        title: String,
        sub: String,
        accentColor: Color = PrimaryBlue,
        onClick: () -> Unit
    ) {
        AppCard(
            onClick = onClick,
            modifier = Modifier.padding(bottom = 8.dp),
            containerColor = PureWhite,
            borderColor = BorderSubtle,
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(PrimarySubtle)
                        .border(1.dp, Color(0xFFBFDBFE), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = accentColor, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 14.sp)
                    Text(sub, color = TextMuted, fontSize = 12.sp)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(18.dp))
            }
        }
    }

    /* =====================================================================
     * TEST SMS SHEET
     * ===================================================================== */
    @Composable
    private fun TestSheet(conn: Connection?, onClose: () -> Unit) {
        var phone by remember { mutableStateOf(conn?.phoneNumber.orEmpty()) }
        var sending by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val fields = modernFieldColors()

        ModalBottomSheet(
            onDismissRequest = onClose,
            containerColor = PureWhite,
            scrimColor = Color.Black.copy(alpha = 0.45f)
        ) {
            Column(
                Modifier
                    .padding(horizontal = 22.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text("Send a test SMS", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Confirm message dispatch for ${conn?.shopName ?: "this shop"} via SIM ${conn?.simCarrier ?: ""}.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )

                Spacer(Modifier.height(16.dp))

                AppCard(
                    containerColor = PearlBg,
                    borderColor = BorderSubtle,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Destination Phone Number") },
                        placeholder = { Text("+8801XXXXXXXXX") },
                        leadingIcon = { Icon(Icons.Outlined.Phone, null, tint = PrimaryBlue) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = fields
                    )
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        val c = conn ?: return@Button
                        scope.launch {
                            sending = true
                            try {
                                withContext(Dispatchers.IO) {
                                    SmsSender.send(
                                        this@MainActivity,
                                        c.simSubscriptionId,
                                        c.shopId,
                                        "test-${System.currentTimeMillis()}",
                                        phone,
                                        "ConnectX test from ${c.shopName}. Your SMS gateway is working."
                                    )
                                }
                                toast("Test SMS sent successfully.")
                                onClose()
                            } catch (e: Exception) {
                                toast(e.message ?: "Test failed")
                            } finally {
                                sending = false
                            }
                        }
                    },
                    enabled = !sending && phone.length >= 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryBlue,
                        contentColor = Color.White
                    )
                ) {
                    if (sending) {
                        ConnectXLoader(size = 20.dp, strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Sending test SMS…")
                    } else {
                        Text("Send Test Message", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    /* =====================================================================
     * SHOP SWITCHER MODAL
     * ===================================================================== */
    @Composable
    private fun ShopSwitcher(onClose: () -> Unit, onPicked: () -> Unit) {
        ModalBottomSheet(
            onDismissRequest = onClose,
            containerColor = PureWhite,
            scrimColor = Color.Black.copy(alpha = 0.45f)
        ) {
            Column(
                Modifier
                    .padding(horizontal = 22.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text("Switch active shop", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(
                    "Messages and queues are kept completely isolated per shop.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                prefs.connections().forEach { c ->
                    val current = c.shopId == prefs.activeShopId
                    AppCard(
                        selected = current,
                        onClick = {
                            prefs.activeShopId = c.shopId
                            onPicked()
                        },
                        modifier = Modifier.padding(bottom = 8.dp),
                        containerColor = if (current) PrimarySubtle else PureWhite,
                        borderColor = if (current) PrimaryBlue else BorderSubtle,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (current) PrimaryBlue.copy(alpha = 0.15f) else PearlBg)
                                    .border(1.dp, if (current) PrimaryBlue else BorderSubtle, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    c.shopName.take(1).uppercase(),
                                    color = if (current) PrimaryBlue else TextSecondary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.shopName, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                                Text(
                                    if (current) "Currently active" else c.shopAddress.ifBlank { c.phoneNumber },
                                    color = if (current) PrimaryBlue else TextMuted,
                                    fontSize = 12.sp
                                )
                            }
                            if (current) {
                                StatusPill("Active")
                            }
                        }
                    }
                }
            }
        }
    }

    /* =====================================================================
     * SIM SWITCHER MODAL
     * ===================================================================== */
    @Composable
    private fun SimSwitcher(conn: Connection?, onClose: () -> Unit, onPicked: () -> Unit) {
        val sims = remember { SmsSender.sims(this) }
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }

        ModalBottomSheet(
            onDismissRequest = onClose,
            containerColor = PureWhite,
            scrimColor = Color.Black.copy(alpha = 0.45f)
        ) {
            Column(
                Modifier
                    .padding(horizontal = 22.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text("Switch sending SIM", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(
                    "Select which SIM sends messages for ${conn?.shopName ?: "this shop"}.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                if (sims.isEmpty()) {
                    Text(
                        "No SIM cards detected. Ensure phone permissions are granted.",
                        color = AccentRose,
                        fontSize = 13.sp
                    )
                }

                sims.forEach { sim ->
                    val current = sim.subscriptionId == conn?.simSubscriptionId
                    AppCard(
                        selected = current,
                        onClick = {
                            val c = conn ?: return@AppCard
                            if (busy) return@AppCard
                            scope.launch {
                                busy = true
                                try {
                                    withContext(Dispatchers.IO) {
                                        api.updateSim(
                                            c.shopId,
                                            sim.subscriptionId,
                                            sim.carrierName?.toString().orEmpty(),
                                            sim.number.orEmpty()
                                        )
                                    }
                                    toast("Sending SIM updated for ${c.shopName}")
                                    onPicked()
                                } catch (e: Exception) {
                                    toast(e.message ?: "Could not switch SIM")
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.padding(bottom = 8.dp),
                        containerColor = if (current) PrimarySubtle else PureWhite,
                        borderColor = if (current) PrimaryBlue else BorderSubtle,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (current) PrimaryBlue.copy(alpha = 0.15f) else PearlBg)
                                    .border(1.dp, if (current) PrimaryBlue else BorderSubtle, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.SimCard,
                                    null,
                                    tint = if (current) PrimaryBlue else TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(simTitle(sim), fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                                Text(
                                    simNumber(sim) + if (current) " · Currently active" else "",
                                    color = if (current) PrimaryBlue else TextMuted,
                                    fontSize = 12.sp
                                )
                            }
                            if (current) {
                                StatusPill("Active")
                            }
                        }
                    }
                }
            }
        }
    }

    /* =====================================================================
     * DEDICATED ABOUT & UPDATES SCREEN (Light Theme)
     * ===================================================================== */
    @Composable
    private fun AboutScreen(
        updateInfo: AppUpdateInfo?,
        onBack: () -> Unit,
        onStartUpdate: (AppUpdateInfo) -> Unit,
        onCheckUpdate: () -> Unit,
        checkingUpdates: Boolean,
        updateChecked: Boolean,
        updateCheckError: String?
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PureWhite)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            // Top Navigation Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(PearlSurface)
                        .border(1.dp, BorderSubtle, CircleShape)
                ) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    "About & Updates",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Spacer(Modifier.height(20.dp))

            // App Identity & Hero Card
            AppCard(
                containerColor = PureWhite,
                borderColor = BorderSubtle,
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(PrimaryBlue)
                            .border(1.5.dp, PrimaryBlue, RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Sensors,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // Branding comes from this installed build, not possibly stale
                    // App Store metadata for an older server release.
                    Text(
                        getString(R.string.brand_full),
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        "v$APP_VERSION_NAME (Build $APP_VERSION_CODE)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PrimaryBlue
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "SMS delivery from your selected SIM, plus secure read-only access to the selected shop’s outgoing EMS ConnectX emails. Compose and send email on the EMS website.",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Version Details & App Store Status Card
            AppCard(
                containerColor = PureWhite,
                borderColor = if (updateInfo != null && updateInfo.versionCode > APP_VERSION_CODE) (if (updateInfo.mandatory) RoseText.copy(alpha = 0.5f) else PrimaryBlue.copy(alpha = 0.5f)) else BorderSubtle,
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "VERSION & SYSTEM METADATA",
                            color = PrimaryBlue,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )

                        if (updateInfo != null && updateInfo.versionCode > APP_VERSION_CODE) {
                            StatusPill(
                                if (updateInfo.mandatory) "Update Required" else "Update Available",
                                if (updateInfo.mandatory) RoseText else PrimaryBlue
                            )
                        } else if (updateCheckError != null) {
                            StatusPill("Check failed", RoseText)
                        } else if (updateChecked) {
                            StatusPill("Up to date", AccentEmerald)
                        } else {
                            StatusPill("Not checked", TextMuted)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = BorderSubtle)
                    Spacer(Modifier.height(12.dp))

                    ProfileDetailRow("Installed Version", "v$APP_VERSION_NAME")
                    ProfileDetailRow("Installed Build", "$APP_VERSION_CODE")
                    ProfileDetailRow("Target Platform", "Android 8.0+ (Oreo to 15)")

                    if (updateInfo != null && updateInfo.versionCode > APP_VERSION_CODE) {
                        ProfileDetailRow("Latest Available", "v${updateInfo.latestVersion} (Build ${updateInfo.versionCode})")
                        if (updateInfo.apkSizeBytes > 0) {
                            ProfileDetailRow("Package Size", "${(updateInfo.apkSizeBytes / (1024 * 1024.0)).format(1)} MB")
                        }
                        if (updateInfo.updatedAt.isNotBlank()) {
                            ProfileDetailRow("Release Date", prettyTime(updateInfo.updatedAt) ?: updateInfo.updatedAt.take(10))
                        }
                    }
                }
            }

            // Release Notes Card
            Spacer(Modifier.height(16.dp))

            AppCard(
                containerColor = PureWhite,
                borderColor = BorderSubtle,
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        if (updateInfo != null && updateInfo.versionCode > APP_VERSION_CODE) "WHAT'S NEW IN V${updateInfo.latestVersion}" else "RELEASE NOTES (V$APP_VERSION_NAME)",
                        color = PrimaryBlue,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )

                    Spacer(Modifier.height(10.dp))

                    val notes = if (updateInfo != null && updateInfo.versionCode > APP_VERSION_CODE && updateInfo.releaseNotes.isNotBlank()) {
                        updateInfo.releaseNotes
                    } else {
                        "• Separate SMS and read-only Email pages for the selected shop’s outgoing messages.\n• Dashboard shows SMS and email activity; SIM switching and manual SIM Balance live on SMS.\n• Administrator Profile and Test SMS remain in Settings; email is sent from EMS."
                    }

                    Text(
                        notes,
                        fontSize = 13.sp,
                        color = TextSecondary,
                        lineHeight = 20.sp
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Action Buttons
            if (updateInfo != null && updateInfo.versionCode > APP_VERSION_CODE) {
                Button(
                    onClick = { onStartUpdate(updateInfo) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (updateInfo.mandatory) RoseText else PrimaryBlue,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Outlined.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Download & Install v${updateInfo.latestVersion}", fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(10.dp))
            }

            OutlinedButton(
                onClick = onCheckUpdate,
                enabled = !checkingUpdates,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = PureWhite,
                    contentColor = PrimaryBlue
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBFDBFE))
            ) {
                if (checkingUpdates) {
                    ConnectXLoader(size = 18.dp, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Checking EMS App Store…", fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Check for Updates", fontWeight = FontWeight.SemiBold)
                }
            }

            if (updateCheckError != null) {
                Spacer(Modifier.height(12.dp))
                Text(updateCheckError, fontSize = 12.sp, color = RoseText,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))

            Text(
                getString(R.string.brand_full),
                color = TextMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    /* =====================================================================
     * MANDATORY UPDATE FULLSCREEN LOCK SCREEN
     * ===================================================================== */
    @Composable
    private fun MandatoryUpdateScreen(
        update: AppUpdateInfo,
        onStartInstall: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PureWhite)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(24.dp))

                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(RoseText.copy(alpha = 0.12f))
                        .border(1.5.dp, RoseText.copy(alpha = 0.3f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.SecurityUpdateWarning,
                        contentDescription = null,
                        tint = RoseText,
                        modifier = Modifier.size(38.dp)
                    )
                }

                Spacer(Modifier.height(18.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(RoseText.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        "MANDATORY UPDATE REQUIRED",
                        color = RoseText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    "Update to v${update.latestVersion}",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    "This update is required to continue using ConnectX and keep shop communication secure and reliable.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp
                )

                Spacer(Modifier.height(24.dp))

                AppCard(
                    containerColor = PearlBg,
                    borderColor = BorderSubtle,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Current Version", fontSize = 12.sp, color = TextSecondary)
                            Text("v$APP_VERSION_NAME (Build $APP_VERSION_CODE)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextMuted)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Required Version", fontSize = 12.sp, color = TextSecondary)
                            Text("v${update.latestVersion} (Build ${update.versionCode})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RoseText)
                        }

                        if (update.releaseNotes.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = BorderSubtle)
                            Spacer(Modifier.height(10.dp))
                            Text("Release Highlights:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                update.releaseNotes,
                                fontSize = 12.sp,
                                color = TextSecondary,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
            }

            Column(Modifier.fillMaxWidth()) {
                Button(
                    onClick = onStartInstall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RoseText,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Outlined.Download, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Install Update Now", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    "Press back twice to exit the application.",
                    fontSize = 11.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    private fun simTitle(sim: SubscriptionInfo): String =
        sim.carrierName?.toString()?.ifBlank { "SIM Slot ${sim.simSlotIndex + 1}" } ?: "SIM Slot ${sim.simSlotIndex + 1}"

    private fun simNumber(sim: SubscriptionInfo): String =
        sim.number?.ifBlank { "Carrier hidden (Slot ${sim.simSlotIndex + 1})" } ?: "SIM Slot ${sim.simSlotIndex + 1}"

    private suspend fun downloadUpdateApk(
        downloadUrl: String,
        fileName: String,
        expectedVersionCode: Int,
        expectedSize: Long,
        onProgress: suspend (Float, Long, Long) -> Unit
    ): File {
        val dir = File(cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!safeName.endsWith(".apk", ignoreCase = true))
            throw IllegalStateException("The update filename is not an APK.")
        val destFile = File(dir, safeName)
        val limit = 100L * 1024 * 1024

        try {
            val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
            val request = Request.Builder().url(downloadUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful)
                    throw IllegalStateException("APK download failed (HTTP ${response.code}). Check EMS App Store storage.")
                val body = response.body ?: throw IllegalStateException("EMS returned an empty APK download.")
                val mime = response.header("content-type").orEmpty()
                if (mime.contains("text/html", true) || mime.contains("application/json", true))
                    throw IllegalStateException("EMS returned a page instead of an APK. Ask the owner to re-upload the update.")
                val total = body.contentLength()
                if (total > limit) throw IllegalStateException("Update APK exceeds 100 MB.")
                val displayTotal = if (total > 0) total else expectedSize.coerceAtLeast(0L)
                var downloaded = 0L
                var lastProgress = 0L
                body.byteStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            currentCoroutineContext().ensureActive()
                            if (count < 0) break
                            downloaded += count
                            if (downloaded > limit) throw IllegalStateException("Update APK exceeds 100 MB.")
                            output.write(buffer, 0, count)
                            if (System.currentTimeMillis() - lastProgress > 180) {
                                withContext(Dispatchers.Main) {
                                    onProgress(if (displayTotal > 0) (downloaded.toFloat() / displayTotal).coerceIn(0f, 1f) else 0f,
                                        downloaded, displayTotal)
                                }
                                lastProgress = System.currentTimeMillis()
                            }
                        }
                    }
                }
                if (downloaded == 0L || (total > 0 && downloaded != total) ||
                    (expectedSize > 0 && downloaded != expectedSize))
                    throw IllegalStateException("The downloaded APK is incomplete or its size does not match the published release.")
                withContext(Dispatchers.Main) { onProgress(1f, downloaded, displayTotal) }
            }
            val zip = destFile.inputStream().use { input -> byteArrayOf(input.read().toByte(), input.read().toByte()) }
            if (zip[0] != 0x50.toByte() || zip[1] != 0x4b.toByte())
                throw IllegalStateException("The download is not an Android APK.")
            @Suppress("DEPRECATION")
            val archive = packageManager.getPackageArchiveInfo(destFile.absolutePath, 0)
                ?: throw IllegalStateException("Android cannot read this APK. Ask the owner to upload a valid signed build.")
            @Suppress("DEPRECATION")
            val build = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode
                else archive.versionCode.toLong()
            if (archive.packageName != packageName || build != expectedVersionCode.toLong() || build <= APP_VERSION_CODE)
                throw IllegalStateException("APK package/build does not match the EMS release (${packageName}, build $expectedVersionCode).")
            // Android's package installer also checks the signing certificate.
            return destFile
        } catch (error: Exception) {
            destFile.delete() // Never offer a truncated or unrelated file to the installer.
            throw error
        }
    }

    private fun installDownloadedApk(apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                    toast("Please allow ConnectX to install updates, then return.")
                    return
                }
            }
            val apkUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(installIntent)
        } catch (e: Exception) {
            toast("Could not start installer: ${e.message}")
        }
    }

    private fun prettyTime(iso: String?): String? {
        if (iso.isNullOrBlank() || iso == "null") return null
        return runCatching {
            java.time.OffsetDateTime.parse(iso)
                .atZoneSameInstant(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm", Locale.getDefault()))
        }.getOrElse { iso.replace("T", " ").take(16) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
