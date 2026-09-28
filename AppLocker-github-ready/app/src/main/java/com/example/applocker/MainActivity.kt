package com.example.applocker

import android.content.ActivityNotFoundException
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class Screen { SET_PIN, LAUNCHER, ADMIN }

class MainActivity : ComponentActivity() {

    private lateinit var store: SecureStore
    private val screen = mutableStateOf(Screen.LAUNCHER)
    private val refreshTick = mutableStateOf(0)

    // Not persisted on purpose: admin access never survives the activity leaving the screen.
    private var suppressReset = false
    private var failedAttempts = 0
    private var lockedUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureStore(applicationContext)
        screen.value = if (store.hasPin()) Screen.LAUNCHER else Screen.SET_PIN

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) { AppRoot() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button pressed while we are the launcher: always fall back to the (locked) grid.
        if (intent.hasCategory(Intent.CATEGORY_HOME) && store.hasPin()) screen.value = Screen.LAUNCHER
    }

    override fun onResume() {
        super.onResume()
        suppressReset = false
        refreshTick.value++
    }

    override fun onStop() {
        super.onStop()
        if (!suppressReset && screen.value == Screen.ADMIN) screen.value = Screen.LAUNCHER
    }

    // ---------------------------------------------------------------- root

    @Composable
    private fun AppRoot() {
        val ctx = LocalContext.current
        val current by screen
        val tick by refreshTick
        var restricted by remember { mutableStateOf(store.restricted) }
        var allowed by remember { mutableStateOf(store.allowedPackages) }
        var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
        var askPin by remember { mutableStateOf(false) }

        LaunchedEffect(tick) {
            apps = withContext(Dispatchers.Default) { AppRepository.loadApps(ctx) }
        }
        // Device owner only: pin the task while the restricted launcher is showing, release it for admin.
        LaunchedEffect(current, restricted) { setLockTask(current == Screen.LAUNCHER && restricted) }

        when (current) {
            Screen.SET_PIN -> {
                val changing = remember { store.hasPin() }
                PinSetupScreen(
                    changing = changing,
                    onSaved = { pin ->
                        store.setPin(pin)
                        screen.value = if (changing) Screen.ADMIN else Screen.LAUNCHER
                    },
                    onCancel = { screen.value = Screen.ADMIN }
                )
            }

            Screen.LAUNCHER -> {
                val visible = if (restricted) apps.filter { it.pkg in allowed } else apps
                LauncherScreen(visible, restricted, onAdmin = { askPin = true }, onLaunch = { launchApp(it) })
                if (askPin) {
                    PinDialog(
                        onDismiss = { askPin = false },
                        onSuccess = { askPin = false; screen.value = Screen.ADMIN }
                    )
                }
            }

            Screen.ADMIN -> AdminScreen(
                apps = apps,
                refresh = tick,
                restricted = restricted,
                allowed = allowed,
                onRestricted = { restricted = it; store.restricted = it },
                onToggleApp = { pkg, on ->
                    allowed = if (on) allowed + pkg else allowed - pkg
                    store.allowedPackages = allowed
                },
                onChangePin = { screen.value = Screen.SET_PIN },
                onClose = { screen.value = Screen.LAUNCHER },
                onApplyPolicy = { applyPolicy(allowed, apps) },
                onClearPolicy = { clearPolicy() },
                onReleaseOwner = { PolicyManager.releaseDeviceOwner(this); refreshTick.value++ }
            )
        }
    }

    // ---------------------------------------------------------------- actions

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ?: return
        try { startActivity(intent) } catch (e: Exception) { toast("Cannot open this app") }
    }

    private fun setLockTask(enable: Boolean) {
        if (!PolicyManager.isDeviceOwner(this) || !PolicyManager.isLockTaskPermitted(this)) return
        try { if (enable) startLockTask() else stopLockTask() } catch (_: Exception) { }
    }

    private fun openHomeSettings() {
        suppressReset = true
        try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        catch (e: ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun activateDeviceAdmin() {
        suppressReset = true
        startActivity(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, AppDeviceAdminReceiver.component(this))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets App Locker apply device policies.")
        )
    }

    private fun applyPolicy(allowed: Set<String>, apps: List<AppEntry>) {
        try {
            val ok = PolicyManager.apply(this, store, allowed, apps.map { it.pkg })
            toast(if (ok) "Device-owner policy applied" else "Not device owner - see instructions")
        } catch (e: SecurityException) { toast("Policy failed: ${e.message}") }
    }

    private fun clearPolicy() {
        try { PolicyManager.clear(this, store); toast("Policy cleared") }
        catch (e: SecurityException) { toast("Clear failed: ${e.message}") }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- screens

    @Composable
    private fun PinSetupScreen(changing: Boolean, onSaved: (String) -> Unit, onCancel: () -> Unit) {
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        if (changing) BackHandler(onBack = onCancel)

        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(
                if (changing) "Change admin password" else "Set admin password",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(8.dp))
            Text("Required to open settings or leave restricted mode.")
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = p1, onValueChange = { p1 = it; error = null }, singleLine = true,
                label = { Text("New password") }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = p2, onValueChange = { p2 = it; error = null }, singleLine = true,
                label = { Text("Confirm password") }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    when {
                        p1.length < 4 -> error = "Use at least 4 characters"
                        p1 != p2 -> error = "Passwords do not match"
                        else -> onSaved(p1)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
            if (changing) TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }

    @Composable
    private fun PinDialog(onDismiss: () -> Unit, onSuccess: () -> Unit) {
        var pin by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Admin password") },
            text = {
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it; error = null }, singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = error != null,
                    supportingText = error?.let { msg -> @Composable { Text(msg) } }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val now = SystemClock.elapsedRealtime()
                    if (now < lockedUntil) {
                        error = "Too many attempts. Wait ${(lockedUntil - now) / 1000 + 1}s"
                        return@TextButton
                    }
                    if (store.verifyPin(pin)) {
                        failedAttempts = 0
                        onSuccess()
                    } else {
                        failedAttempts++
                        if (failedAttempts >= 5) { lockedUntil = now + 30_000; failedAttempts = 0 }
                        error = "Incorrect password"
                        pin = ""
                    }
                }) { Text("Unlock") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }

    @Composable
    private fun LauncherScreen(
        apps: List<AppEntry>, restricted: Boolean,
        onAdmin: () -> Unit, onLaunch: (String) -> Unit
    ) {
        BackHandler(enabled = true) { /* swallow Back on the home screen */ }
        Box(Modifier.fillMaxSize()) {
            if (apps.isEmpty()) {
                Text(
                    if (restricted) "No apps allowed yet.\nTap the gear to configure." else "No apps found.",
                    modifier = Modifier.align(Alignment.Center), textAlign = TextAlign.Center
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(88.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 64.dp, bottom = 24.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(apps, key = { it.pkg }) { app ->
                        Column(
                            Modifier.clickable { onLaunch(app.pkg) }.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Image(app.icon, contentDescription = null, modifier = Modifier.size(56.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(
                                app.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onAdmin, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "Admin settings")
            }
        }
    }

    @Composable
    private fun AdminScreen(
        apps: List<AppEntry>, refresh: Int, restricted: Boolean, allowed: Set<String>,
        onRestricted: (Boolean) -> Unit, onToggleApp: (String, Boolean) -> Unit,
        onChangePin: () -> Unit, onClose: () -> Unit,
        onApplyPolicy: () -> Unit, onClearPolicy: () -> Unit, onReleaseOwner: () -> Unit
    ) {
        val ctx = LocalContext.current
        var query by remember { mutableStateOf("") }
        var showSystem by remember { mutableStateOf(true) }
        val isOwner = remember(refresh) { PolicyManager.isDeviceOwner(ctx) }
        val isAdmin = remember(refresh) { PolicyManager.isAdminActive(ctx) }
        val filtered = remember(apps, query, showSystem) {
            apps.filter { (showSystem || !it.isSystem) && it.label.contains(query, ignoreCase = true) }
        }
        BackHandler(onBack = onClose)

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Admin settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Button(onClick = onClose) { Text("Done") }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Restricted mode", style = MaterialTheme.typography.titleMedium)
                        Text("ON: home shows only allowed apps. OFF: all apps.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = restricted, onCheckedChange = onRestricted)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { openHomeSettings() }, modifier = Modifier.fillMaxWidth()) { Text("Set as default Home app / switch launcher") }
                OutlinedButton(onClick = onChangePin, modifier = Modifier.fillMaxWidth()) { Text("Change admin password") }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text("Managed-device policy", style = MaterialTheme.typography.titleMedium)
                Text("Device admin: ${if (isAdmin) "active" else "inactive"}  |  Device owner: ${if (isOwner) "yes" else "no"}",
                    style = MaterialTheme.typography.bodySmall)
                Text("Lock-task, package suspension and forced default-home need device-owner provisioning:\n" +
                    "adb shell dpm set-device-owner ${ctx.packageName}/.AppDeviceAdminReceiver",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { activateDeviceAdmin() }, enabled = !isAdmin, modifier = Modifier.fillMaxWidth()) { Text("Activate device admin") }
                Button(onClick = onApplyPolicy, enabled = isOwner, modifier = Modifier.fillMaxWidth()) { Text("Apply policy to allowed apps") }
                OutlinedButton(onClick = onClearPolicy, enabled = isOwner, modifier = Modifier.fillMaxWidth()) { Text("Clear policy") }
                OutlinedButton(onClick = onReleaseOwner, enabled = isOwner, modifier = Modifier.fillMaxWidth()) { Text("Release device owner (testing)") }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text("Allowed apps (${allowed.size})", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Search apps") }, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showSystem, onCheckedChange = { showSystem = it })
                    Text("Include system apps")
                }
            }
            items(filtered, key = { it.pkg }) { app ->
                val checked = app.pkg in allowed
                Row(
                    Modifier.fillMaxWidth().clickable { onToggleApp(app.pkg, !checked) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked, onCheckedChange = { onToggleApp(app.pkg, it) })
                    Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.pkg, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
