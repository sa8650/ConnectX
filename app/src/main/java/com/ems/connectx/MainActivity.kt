package com.ems.connectx

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import com.ems.connectx.data.*
import com.ems.connectx.sms.GatewayService
import com.ems.connectx.sms.QueueProcessor
import com.ems.connectx.sms.SmsSender
import com.ems.connectx.ui.Blue
import com.ems.connectx.ui.BrandMark
import com.ems.connectx.ui.ConnectXTheme
import com.ems.connectx.ui.FrostCard
import com.ems.connectx.ui.Ice
import com.ems.connectx.ui.Mute
import com.ems.connectx.ui.Navy
import com.ems.connectx.ui.ScreenBackdrop
import com.ems.connectx.ui.frostFieldColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var api: Api

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

        when (route) {
            "start" -> GetStarted { prefs.seenGetStarted = true; route = "login" }
            "login" -> LoginScreen {
                prefs.signedIn = true
                route = if (prefs.connections().any { it.setupComplete }) "home" else "shops"
            }
            "shops" -> ShopListScreen(
                onBack = { route = "login" },
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
            "test" -> TestSmsScreen(selectedSim) { route = "home" }
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
                    onSession = { session++ }
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

    @Composable
    private fun GetStarted(onNext: () -> Unit) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                BrandMark(72.dp)
                Spacer(Modifier.height(28.dp))
                Text("CONNECTX", color = Ice, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "SMS from this phone.\nNothing else.",
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 40.sp
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "The Android SMS gateway for EMS. Sign in with your administrator account. This phone sends customer messages on the SIM you choose.",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
                Spacer(Modifier.height(28.dp))
                FrostCard(strong = true) {
                    Column(Modifier.padding(16.dp)) {
                        FeatureDark(Icons.Outlined.Store, "One administrator, many shops")
                        FeatureDark(Icons.Outlined.SimCard, "You pick which SIM sends")
                        FeatureDark(Icons.Outlined.CloudOff, "Queued SMS wait if you are offline")
                        FeatureDark(Icons.Outlined.Shield, "No EMS secrets in this app")
                    }
                }
            }
            Column {
                Button(
                    onClick = onNext,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Navy)
                ) { Text("Get started", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Powered by Dexter Studio",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    @Composable
    private fun FeatureDark(icon: ImageVector, text: String) {
        Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Ice, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, color = Color.White, fontSize = 15.sp)
        }
    }

    @Composable
    private fun LoginScreen(onOk: () -> Unit) {
        var url by remember { mutableStateOf(prefs.baseUrl) }
        var email by remember { mutableStateOf(prefs.adminEmail) }
        var password by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val already = prefs.connections()
        val fields = frostFieldColors()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            BrandMark(56.dp)
            Spacer(Modifier.height(18.dp))
            Text("Sign in", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text("Use the same administrator email and password as EMS. Do not enter a Shop ID.", color = Mute)
            if (already.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                AssistChip(
                    onClick = {},
                    label = { Text("${already.size} shop${if (already.size == 1) "" else "s"} already connected on this phone") },
                    colors = AssistChipDefaults.assistChipColors(labelColor = Color.White, containerColor = Color.White.copy(alpha = 0.12f))
                )
            }
            Spacer(Modifier.height(22.dp))
            FrostCard(strong = true) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        url, { url = it },
                        label = { Text("EMS website URL") },
                        placeholder = { Text("https://your-ems.pages.dev") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = fields
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(email, { email = it }, label = { Text("Administrator email") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp), colors = fields)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        password, { password = it },
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(14.dp),
                        colors = fields
                    )
                }
            }
            TextButton(onClick = {
                val u = url.trim().trimEnd('/')
                if (u.isBlank()) toast("Enter your EMS website URL first.")
                else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$u/#forgot")))
            }, colors = ButtonDefaults.textButtonColors(contentColor = Ice)) { Text("Forgot password?") }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        loading = true
                        try {
                            prefs.baseUrl = url
                            withContext(Dispatchers.IO) { api.adminLogin(email.trim(), password) }
                            onOk()
                        } catch (e: Exception) {
                            toast(e.message ?: "Sign in failed")
                        } finally { loading = false }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Navy)
            ) { Text(if (loading) "Signing in…" else "Sign in", fontSize = 16.sp) }
            Spacer(Modifier.height(20.dp))
            Text("Powered by Dexter Studio", color = Mute, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }

    @Composable
    private fun ShopListScreen(onBack: () -> Unit, onPick: (Shop) -> Unit) {
        var shops by remember { mutableStateOf<List<Shop>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var err by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            try {
                shops = withContext(Dispatchers.IO) { api.shops() }
            } catch (e: Exception) { err = e.message } finally { loading = false }
        }
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("Select a shop", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("One phone can serve more than one shop. Each shop keeps its own SMS queue.", color = Mute, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            if (loading) CircularProgressIndicator(color = Ice)
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                items(shops, key = { it.id }) { shop ->
                    FrostCard(onClick = { onPick(shop) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Blue.copy(alpha = 0.85f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(shop.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(shop.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.White)
                                if (shop.address.isNotBlank()) Text(shop.address, color = Mute, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            StatusChip(if (shop.connected) "Connected" else "New")
                        }
                    }
                }
            }
            TextButton(onClick = onBack, colors = ButtonDefaults.textButtonColors(contentColor = Ice)) { Text("Use a different account") }
        }
    }

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
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                BrandMark(48.dp)
                Spacer(Modifier.height(20.dp))
                Text("Allow SMS permission", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(10.dp))
                FrostCard(strong = true) {
                    Text(
                        "ConnectX needs SMS permission so this phone can send customer messages from your SIM. EMS never controls the SIM. You approve sending on this device.",
                        color = Mute,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            Column {
                Button(
                    onClick = { launcher.launch(needed) },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Navy)
                ) { Text("Continue", fontSize = 16.sp) }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = Ice)) { Text("Back") }
            }
        }
    }

    @Composable
    private fun SimScreen(onBack: () -> Unit, onPick: (SubscriptionInfo) -> Unit) {
        val sims = remember { SmsSender.sims(this) }
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Choose the sending number", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Select which SIM this shop should use. You can switch it later from Home or Settings.", color = Mute, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            if (sims.isEmpty()) Text("No SIM cards were found. Insert a SIM and grant phone permission.", color = MaterialTheme.colorScheme.error)
            sims.forEach { sim ->
                FrostCard(onClick = { onPick(sim) }, modifier = Modifier.padding(bottom = 10.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.SimCard, null, tint = Ice, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(simTitle(sim), fontWeight = FontWeight.Bold, color = Color.White)
                            Text(simNumber(sim), color = Mute, fontSize = 13.sp)
                        }
                    }
                }
            }
            TextButton(onClick = onBack, colors = ButtonDefaults.textButtonColors(contentColor = Ice)) { Text("Back") }
        }
    }

    @Composable
    private fun RegisteringScreen(shop: Shop?, sim: SubscriptionInfo?, onDone: (Boolean) -> Unit) {
        var msg by remember { mutableStateOf("Registering this device…") }
        LaunchedEffect(shop, sim) {
            if (shop == null || sim == null) { onDone(false); return@LaunchedEffect }
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
                msg = "Device registered."
                delay(400)
                onDone(true)
            } catch (e: Exception) {
                toast(e.message ?: "Could not register")
                onDone(false)
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            FrostCard(strong = true, modifier = Modifier.padding(40.dp)) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Ice)
                    Spacer(Modifier.height(16.dp))
                    Text(msg, color = Color.White)
                }
            }
        }
    }

    @Composable
    private fun TestSmsScreen(sim: SubscriptionInfo?, onDone: () -> Unit) {
        val conn = prefs.active()
        var phone by remember { mutableStateOf(sim?.number?.filter { it.isDigit() || it == '+' } ?: conn?.phoneNumber.orEmpty()) }
        var sending by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val fields = frostFieldColors()
        suspend fun finishSetup(skipped: Boolean) {
            val shopId = conn?.shopId ?: return
            withContext(Dispatchers.IO) { api.markTest(shopId, true) }
            GatewayService.start(this@MainActivity)
            toast(if (skipped) "Skipped. You can send a test SMS later from Settings." else "Test SMS sent. Setup complete.")
            onDone()
        }
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Send a test SMS", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("Confirm this phone can send for ${conn?.shopName ?: "this shop"}. You can skip and do this later.", color = Mute)
                Spacer(Modifier.height(16.dp))
                FrostCard(strong = true) {
                    OutlinedTextField(
                        phone, { phone = it },
                        label = { Text("Destination number") },
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = fields
                    )
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
                                delay(1500)
                                finishSetup(false)
                            } catch (e: Exception) {
                                toast(e.message ?: "Test failed")
                            } finally { sending = false }
                        }
                    },
                    enabled = !sending && phone.length >= 6,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Navy)
                ) { Text(if (sending) "Sending…" else "Send test SMS", fontSize = 16.sp) }
                TextButton(
                    onClick = {
                        scope.launch {
                            sending = true
                            try { finishSetup(true) } catch (e: Exception) { toast(e.message ?: "Could not skip") } finally { sending = false }
                        }
                    },
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = Ice)
                ) { Text("Skip for now") }
            }
        }
    }

    @Composable
    private fun HomeShell(tab: Int, onTab: (Int) -> Unit, onAddShop: () -> Unit, onLogout: () -> Unit, onSession: () -> Unit) {
        val conn = prefs.active()
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(
                    containerColor = Color.White.copy(alpha = 0.10f),
                    tonalElevation = 0.dp
                ) {
                    val colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Ice,
                        selectedTextColor = Ice,
                        unselectedIconColor = Color.White.copy(alpha = 0.55f),
                        unselectedTextColor = Color.White.copy(alpha = 0.55f),
                        indicatorColor = Color.White.copy(alpha = 0.12f)
                    )
                    NavigationBarItem(tab == 0, { onTab(0) }, { Icon(Icons.Outlined.Home, null) }, label = { Text("Home") }, colors = colors)
                    NavigationBarItem(tab == 1, { onTab(1) }, { Icon(Icons.Outlined.History, null) }, label = { Text("Activity") }, colors = colors)
                    NavigationBarItem(tab == 2, { onTab(2) }, { Icon(Icons.Outlined.Settings, null) }, label = { Text("Settings") }, colors = colors)
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> HomeTab(conn, onSession)
                    1 -> ActivityTab(conn)
                    else -> SettingsTab(conn, onAddShop, onLogout, onSession)
                }
            }
        }
        LaunchedEffect(Unit) {
            if (prefs.gatewayEnabled) GatewayService.start(this@MainActivity)
            while (true) {
                delay(30_000)
                runCatching { withContext(Dispatchers.IO) { QueueProcessor.drain(this@MainActivity) } }
            }
        }
    }

    @Composable
    private fun HomeTab(conn: Connection?, onSession: () -> Unit) {
        var stats by remember {
            mutableStateOf(
                HomeStats(
                    shopName = conn?.shopName.orEmpty(),
                    shopAddress = conn?.shopAddress.orEmpty(),
                    adminName = conn?.adminName.orEmpty(),
                    adminEmail = conn?.adminEmail.orEmpty()
                )
            )
        }
        var showShop by remember { mutableStateOf(false) }
        var showSim by remember { mutableStateOf(false) }
        LaunchedEffect(conn?.shopId) {
            if (conn == null) return@LaunchedEffect
            runCatching { stats = withContext(Dispatchers.IO) { api.stats(conn.shopId) } }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            FrostCard(strong = true, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("CONNECTX", color = Ice, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        TextButton(onClick = { showShop = true }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                            Text("Switch shop")
                        }
                    }
                    Text(
                        stats.shopName.ifBlank { conn?.shopName ?: "ConnectX" },
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 32.sp
                    )
                    val addr = stats.shopAddress.ifBlank { conn?.shopAddress.orEmpty() }
                    if (addr.isNotBlank()) Text(addr, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (prefs.gatewayEnabled) Ice else Color.White.copy(alpha = 0.4f)))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (prefs.gatewayEnabled) "Online · waiting for SMS jobs" else "Gateway paused",
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    FrostCard(onClick = { showSim = true }, shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.SimCard, null, tint = Ice, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Sending number", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                                Text(
                                    listOfNotNull(conn?.simCarrier?.ifBlank { null }, conn?.phoneNumber?.ifBlank { "Tap to choose SIM" }).joinToString("  "),
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Icon(Icons.Outlined.SwapHoriz, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Sent today", stats.sent.toString(), Modifier.weight(1f))
                StatCard("Failed", stats.failed.toString(), Modifier.weight(1f))
                StatCard("Pending", stats.pending.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            FrostCard {
                Column(Modifier.padding(18.dp)) {
                    Text("Administrator", color = Mute, fontSize = 12.sp)
                    Text(stats.adminName.ifBlank { conn?.adminName.orEmpty() }.ifBlank { "—" }, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                    Text(stats.adminEmail.ifBlank { conn?.adminEmail.orEmpty() }, color = Mute, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.16f))
                    Spacer(Modifier.height(12.dp))
                    Text("Last activity", color = Mute, fontSize = 12.sp)
                    Text(prettyTime(stats.lastActivity) ?: "No SMS yet today", fontSize = 14.sp, color = Color.White)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Powered by Dexter Studio", color = Mute, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        if (showShop) ShopSwitcher(onClose = { showShop = false }, onPicked = { showShop = false; onSession() })
        if (showSim) SimSwitcher(conn, onClose = { showSim = false }, onPicked = { showSim = false; onSession() })
    }

    @Composable
    private fun sheetColor() = Navy.copy(alpha = 0.92f)

    @Composable
    private fun ShopSwitcher(onClose: () -> Unit, onPicked: () -> Unit) {
        ModalBottomSheet(onDismissRequest = onClose, containerColor = sheetColor(), scrimColor = Color.Black.copy(alpha = 0.45f)) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Switch shop", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Jobs never mix across shops.", color = Mute, modifier = Modifier.padding(bottom = 12.dp))
                prefs.connections().forEach { c ->
                    val current = c.shopId == prefs.activeShopId
                    FrostCard(
                        selected = current,
                        onClick = {
                            prefs.activeShopId = c.shopId
                            onPicked()
                        },
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(c.shopName, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                if (current) "Current" else c.shopAddress.ifBlank { c.phoneNumber },
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SimSwitcher(conn: Connection?, onClose: () -> Unit, onPicked: () -> Unit) {
        val sims = remember { SmsSender.sims(this) }
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = onClose, containerColor = sheetColor(), scrimColor = Color.Black.copy(alpha = 0.45f)) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Switch sending number", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Only this phone sends. EMS never controls the SIM.", color = Mute, modifier = Modifier.padding(bottom = 12.dp))
                if (sims.isEmpty()) Text("No SIMs found. Grant phone permission and insert a SIM.", color = MaterialTheme.colorScheme.error)
                sims.forEach { sim ->
                    val current = sim.subscriptionId == conn?.simSubscriptionId
                    FrostCard(
                        selected = current,
                        onClick = {
                            val c = conn ?: return@FrostCard
                            if (busy) return@FrostCard
                            scope.launch {
                                busy = true
                                try {
                                    withContext(Dispatchers.IO) {
                                        api.updateSim(c.shopId, sim.subscriptionId, sim.carrierName?.toString().orEmpty(), sim.number.orEmpty())
                                    }
                                    toast("Sending number updated for ${c.shopName}")
                                    onPicked()
                                } catch (e: Exception) {
                                    toast(e.message ?: "Could not switch SIM")
                                } finally { busy = false }
                            }
                        },
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.SimCard, null, tint = Ice)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(simTitle(sim), fontWeight = FontWeight.Bold, color = Color.White)
                                Text(simNumber(sim) + if (current) "  ·  Current" else "", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ActivityTab(conn: Connection?) {
        var range by remember { mutableStateOf("today") }
        var items by remember { mutableStateOf<List<ActivityItem>>(emptyList()) }
        var open by remember { mutableStateOf<ActivityItem?>(null) }
        LaunchedEffect(conn?.shopId, range) {
            if (conn == null) return@LaunchedEffect
            runCatching { items = withContext(Dispatchers.IO) { api.activity(conn.shopId, range) } }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Activity", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(conn?.shopName ?: "", color = Mute)
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val chip = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color.White.copy(alpha = 0.22f),
                    selectedLabelColor = Color.White,
                    containerColor = Color.White.copy(alpha = 0.08f),
                    labelColor = Mute
                )
                FilterChip(range == "today", { range = "today" }, { Text("Today") }, colors = chip)
                FilterChip(range == "7d", { range = "7d" }, { Text("7 days") }, colors = chip)
                FilterChip(range == "30d", { range = "30d" }, { Text("30 days") }, colors = chip)
            }
            if (items.isEmpty()) {
                Text("No SMS in this range.", color = Mute, modifier = Modifier.padding(top = 24.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { row ->
                    FrostCard(onClick = { open = row }) {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(row.type.ifBlank { "SMS" }, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(
                                    row.status.uppercase(),
                                    color = when (row.status) {
                                        "sent" -> Ice
                                        "failed" -> MaterialTheme.colorScheme.error
                                        else -> Mute
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text("${row.name.ifBlank { "Customer" }}  ·  ${row.phone}", fontSize = 13.sp, color = Mute)
                            if (row.message.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(row.message, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp, color = Color.White.copy(alpha = 0.9f))
                            }
                            Text(prettyTime(row.sentAt.ifBlank { row.createdAt }) ?: "", fontSize = 11.sp, color = Mute, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
        open?.let { row ->
            ModalBottomSheet(onDismissRequest = { open = null }, containerColor = sheetColor(), scrimColor = Color.Black.copy(alpha = 0.45f)) {
                Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(row.type.ifBlank { "SMS" }, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        StatusChip(row.status)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("${row.name.ifBlank { "Customer" }}", fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(row.phone, color = Mute)
                    Text(prettyTime(row.sentAt.ifBlank { row.createdAt }) ?: row.createdAt, color = Mute, fontSize = 12.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("Message", color = Mute, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    FrostCard(strong = true) {
                        Text(
                            row.message.ifBlank { "No message body was stored for this SMS." },
                            modifier = Modifier.padding(16.dp),
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            color = Color.White
                        )
                    }
                    row.error?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsTab(conn: Connection?, onAddShop: () -> Unit, onLogout: () -> Unit, onSession: () -> Unit) {
        var enabled by remember { mutableStateOf(prefs.gatewayEnabled) }
        var showSim by remember { mutableStateOf(false) }
        var showLogout by remember { mutableStateOf(false) }
        var showTest by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(14.dp))
            FrostCard(strong = true) {
                Column(Modifier.padding(18.dp)) {
                    Text("CONNECTX", color = Ice, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(conn?.shopName ?: "No shop", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("Device ${conn?.devicePublicId ?: "—"}", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("SMS gateway", color = Color.White)
                        Switch(
                            enabled,
                            {
                                enabled = it
                                prefs.gatewayEnabled = it
                                if (it) GatewayService.start(this@MainActivity) else GatewayService.stop(this@MainActivity)
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = Ice, checkedThumbColor = Color.White)
                        )
                    }
                    Text("When off, jobs stay pending in EMS. Sales still complete.", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            SettingsRow(Icons.Outlined.SimCard, "Switch sending number", conn?.let { listOf(it.simCarrier, it.phoneNumber).filter { s -> s.isNotBlank() }.joinToString("  ").ifBlank { "Choose SIM" } } ?: "Choose SIM") { showSim = true }
            SettingsRow(Icons.Outlined.Sms, "Send a test SMS", "Optional check that this SIM can send") { showTest = true }
            SettingsRow(Icons.Outlined.Store, "Connected shops", "This phone can serve more than one shop") { onAddShop() }
            SettingsRow(Icons.Outlined.BatterySaver, "Ignore battery restrictions", "Recommended so queued SMS still send") {
                val pm = getSystemService(PowerManager::class.java)
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } else toast("Battery unrestricted.")
            }
            SettingsRow(Icons.Outlined.LinkOff, "Disconnect this shop", "Revoke the device token for ${conn?.shopName ?: "this shop"}") {
                scope.launch {
                    conn?.let {
                        withContext(Dispatchers.IO) { api.disconnect(it.shopId) }
                        if (prefs.connections().isEmpty()) GatewayService.stop(this@MainActivity)
                        toast("Disconnected")
                        onSession()
                    }
                }
            }
            SettingsRow(Icons.Outlined.Logout, "Log out", "Stop sending on this phone until you sign in again") { showLogout = true }
            Text(
                "ConnectX never stores your administrator password after setup. Revoke a device from EMS Settings → Communication at any time.\n\nPowered by Dexter Studio",
                color = Mute,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        if (showSim) SimSwitcher(conn, onClose = { showSim = false }, onPicked = { showSim = false; onSession() })
        if (showLogout) {
            AlertDialog(
                onDismissRequest = { showLogout = false },
                containerColor = Navy,
                title = { Text("Log out of ConnectX?", color = Color.White) },
                text = { Text("SMS sending will stop on this phone until you sign in again. Shops stay connected here so you will not create a duplicate device. Revoke from EMS if you want this phone removed.", color = Mute) },
                confirmButton = {
                    TextButton(onClick = { showLogout = false; onLogout() }, colors = ButtonDefaults.textButtonColors(contentColor = Ice)) { Text("Log out") }
                },
                dismissButton = { TextButton(onClick = { showLogout = false }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("Cancel") } }
            )
        }
        if (showTest) TestSheet(conn) { showTest = false }
    }

    @Composable
    private fun TestSheet(conn: Connection?, onClose: () -> Unit) {
        var phone by remember { mutableStateOf(conn?.phoneNumber.orEmpty()) }
        var sending by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val fields = frostFieldColors()
        ModalBottomSheet(onDismissRequest = onClose, containerColor = sheetColor(), scrimColor = Color.Black.copy(alpha = 0.45f)) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Send a test SMS", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(10.dp))
                FrostCard(strong = true) {
                    OutlinedTextField(
                        phone, { phone = it },
                        label = { Text("Destination number") },
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = fields
                    )
                }
                Spacer(Modifier.height(12.dp))
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
                                toast("Test SMS sent.")
                                onClose()
                            } catch (e: Exception) {
                                toast(e.message ?: "Test failed")
                            } finally { sending = false }
                        }
                    },
                    enabled = !sending && phone.length >= 6,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Navy)
                ) { Text(if (sending) "Sending…" else "Send") }
            }
        }
    }

    @Composable
    private fun SettingsRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
        FrostCard(onClick = onClick, modifier = Modifier.padding(bottom = 8.dp), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Ice, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(sub, color = Mute, fontSize = 13.sp)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = Mute)
            }
        }
    }

    @Composable
    private fun StatCard(label: String, value: String, modifier: Modifier) {
        FrostCard(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ice)
                Text(label, fontSize = 11.sp, color = Mute)
            }
        }
    }

    @Composable
    private fun StatusChip(text: String) {
        val ok = text.equals("sent", true) || text.equals("connected", true) || text.equals("current", true)
        val fail = text.equals("failed", true)
        val bg = when {
            ok -> Ice.copy(alpha = 0.18f)
            fail -> MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
            else -> Color.White.copy(alpha = 0.10f)
        }
        val fg = when {
            ok -> Ice
            fail -> MaterialTheme.colorScheme.error
            else -> Mute
        }
        Box(Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(text.replaceFirstChar { it.uppercase() }, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    private fun simTitle(sim: SubscriptionInfo): String =
        sim.carrierName?.toString()?.ifBlank { "SIM ${sim.simSlotIndex + 1}" } ?: "SIM ${sim.simSlotIndex + 1}"

    private fun simNumber(sim: SubscriptionInfo): String =
        sim.number?.ifBlank { "Number hidden by the carrier" } ?: "SIM slot ${sim.simSlotIndex + 1}"

    private fun prettyTime(iso: String?): String? {
        if (iso.isNullOrBlank() || iso == "null") return null
        return iso.replace("T", " ").take(16)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
