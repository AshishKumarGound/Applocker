package com.example.applocker

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * This app is a settings utility, NOT a launcher. It never takes over the Home screen —
 * the phone's own launcher (folders, wallpaper, icon layout) is left completely untouched.
 * Blocking an app means suspending it via DevicePolicyManager: its icon stays exactly where
 * it is in the real launcher, greyed out, and tapping it fails instead of opening.
 */
enum class Screen { SET_PIN, PIN_ENTRY, ADMIN }

class MainActivity : ComponentActivity() {

    private lateinit var store: SecureStore
    private val screen = mutableStateOf(Screen.PIN_ENTRY)
    private val refreshTick = mutableStateOf(0)

    private var failedAttempts = 0
    private var lockedUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureStore(applicationContext)
        screen.value = if (store.hasPin()) Screen.PIN_ENTRY else Screen.SET_PIN

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) { AppRoot() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.value++
    }

    /** Re-lock behind the password every time the app leaves the foreground. */
    override fun onStop() {
        super.onStop()
        if (store.hasPin() && screen.value == Screen.ADMIN) screen.value = Screen.PIN_ENTRY
    }

    @Composable
    private fun AppRoot() {
        val ctx = LocalContext.current
        val current by screen
        val tick by refreshTick
        var restricted by remember { mutableStateOf(store.restricted) }
        var allowed by remember { mutableStateOf(store.allowedPackages) }
        var blockMode by remember {
            mutableStateOf(runCatching { PolicyManager.BlockMode.valueOf(store.blockMode) }
                .getOrDefault(PolicyManager.BlockMode.GREY_OUT))
        }
        var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }

        LaunchedEffect(tick) {
            apps = withContext(Dispatchers.Default) { AppRepository.loadApps(ctx) }
        }

        when (current) {
            Screen.SET_PIN -> {
                val changing = remember { store.hasPin() }
                PinSetupScreen(
                    changing = changing,
                    onSaved = { pin -> store.setPin(pin); screen.value = Screen.ADMIN },
                    onCancel = { screen.value = Screen.ADMIN }
                )
            }

            Screen.PIN_ENTRY -> PinEntryScreen(
                onSuccess = { screen.value = Screen.ADMIN },
                onCancel = { finish() }
            )

            Screen.ADMIN -> AdminScreen(
                apps = apps,
                refresh = tick,
                restricted = restricted,
                allowed = allowed,
                blockMode = blockMode,
                onBlockMode = { blockMode = it; store.blockMode = it.name },
                onRestricted = { restricted = it; store.restricted = it },
                onToggleApp = { pkg, on ->
                    allowed = if (on) allowed + pkg else allowed - pkg
                    store.allowedPackages = allowed
                },
                onChangePin = { screen.value = Screen.SET_PIN },
                onClose = { finish() },
                onApplyPolicy = { applyPolicy(allowed, apps, blockMode) },
                onClearPolicy = { clearPolicy() },
                onReleaseOwner = { PolicyManager.releaseDeviceOwner(this); refreshTick.value++ }
            )
        }
    }

    // ---------------------------------------------------------------- actions

    private fun applyPolicy(allowed: Set<String>, apps: List<AppEntry>, mode: PolicyManager.BlockMode) {
        try {
            val ok = PolicyManager.apply(this, store, allowed, apps.map { it.pkg }, mode)
            toast(if (ok) "Blocked apps updated" else "Not device owner - see instructions")
        } catch (e: SecurityException) { toast("Failed: ${e.message}") }
    }

    private fun clearPolicy() {
        try { PolicyManager.clear(this, store); toast("All apps unblocked") }
        catch (e: SecurityException) { toast("Failed: ${e.message}") }
    }

    private fun activateDeviceAdmin() {
        startActivity(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, AppDeviceAdminReceiver.component(this))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets App Locker block apps.")
        )
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
            Text("Required every time you reopen this app to change what's blocked.")
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
    private fun PinEntryScreen(onSuccess: () -> Unit, onCancel: () -> Unit) {
        var pin by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        BackHandler(onBack = onCancel)

        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("App Locker", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text("Enter the admin password to continue.")
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = pin, onValueChange = { pin = it; error = null }, singleLine = true,
                label = { Text("Password") }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = error != null,
                supportingText = error?.let { msg -> @Composable { Text(msg) } }
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val now = SystemClock.elapsedRealtime()
                    if (now < lockedUntil) {
                        error = "Too many attempts. Wait ${(lockedUntil - now) / 1000 + 1}s"
                        return@Button
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
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Unlock") }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }

    @Composable
    private fun AdminScreen(
        apps: List<AppEntry>, refresh: Int, restricted: Boolean, allowed: Set<String>,
        blockMode: PolicyManager.BlockMode, onBlockMode: (PolicyManager.BlockMode) -> Unit,
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
        var showReleaseConfirm by remember { mutableStateOf(false) }
        BackHandler(onBack = onClose)

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("App Locker", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Button(onClick = onClose) { Text("Done") }
                }
                Text(
                    "Blocking an app leaves your home screen exactly as it is; the app's icon " +
                        "just stops opening. No launcher is changed.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Enforce blocking", style = MaterialTheme.typography.titleMedium)
                        Text("Turn on, then tap \"Block selected apps\" below to apply it.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = restricted, onCheckedChange = onRestricted)
                }
                OutlinedButton(onClick = onChangePin, modifier = Modifier.fillMaxWidth()) { Text("Change admin password") }
                Spacer(Modifier.height(12.dp))

                Text("Blocked apps should:", style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.fillMaxWidth().clickable { onBlockMode(PolicyManager.BlockMode.GREY_OUT) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = blockMode == PolicyManager.BlockMode.GREY_OUT,
                        onClick = { onBlockMode(PolicyManager.BlockMode.GREY_OUT) })
                    Column {
                        Text("Grey out")
                        Text("Icon stays where it is, tapping it fails.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { onBlockMode(PolicyManager.BlockMode.HIDE) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = blockMode == PolicyManager.BlockMode.HIDE,
                        onClick = { onBlockMode(PolicyManager.BlockMode.HIDE) })
                    Column {
                        Text("Hide completely")
                        Text("Icon disappears from the launcher, as if uninstalled.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "Changing this and tapping \"Block selected apps\" again switches every blocked app to the new mode.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text("Office device lock", style = MaterialTheme.typography.titleMedium)
                Text("Device admin: ${if (isAdmin) "active" else "inactive"}  |  Device owner: ${if (isOwner) "yes" else "no"}",
                    style = MaterialTheme.typography.bodySmall)
                if (!isOwner) {
                    Text("Blocking needs device-owner status. Provision with:\n" +
                        "adb shell dpm set-device-owner ${ctx.packageName}/.AppDeviceAdminReceiver\n" +
                        "on a factory-reset phone with no Google account added yet, before handing it to the employee.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Device owner is active: uninstalling this app, factory reset, safe mode, USB " +
                        "debugging removal and adding a second user are all blocked once you tap Block selected apps.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { activateDeviceAdmin() }, enabled = !isAdmin, modifier = Modifier.fillMaxWidth()) { Text("Activate device admin") }
                Button(onClick = onApplyPolicy, enabled = isOwner && restricted, modifier = Modifier.fillMaxWidth()) { Text("Block selected apps") }
                OutlinedButton(onClick = onClearPolicy, enabled = isOwner, modifier = Modifier.fillMaxWidth()) { Text("Unblock all apps") }
                OutlinedButton(
                    onClick = { showReleaseConfirm = true }, enabled = isOwner,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Give up device owner permanently") }
                if (showReleaseConfirm) {
                    AlertDialog(
                        onDismissRequest = { showReleaseConfirm = false },
                        title = { Text("Give up device owner?") },
                        text = { Text("This permanently removes every restriction above. The employee will be able to " +
                            "uninstall the app, factory reset and bypass the lock afterward. You would need to factory " +
                            "reset and re-provision to lock the device again. This cannot be undone from here.") },
                        confirmButton = {
                            TextButton(onClick = { showReleaseConfirm = false; onReleaseOwner() }) {
                                Text("Give up device owner", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = { TextButton(onClick = { showReleaseConfirm = false }) { Text("Cancel") } }
                    )
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text("Blocked apps (${allowed.let { apps.size - it.size - 1 }.coerceAtLeast(0)} of ${apps.size})",
                    style = MaterialTheme.typography.titleMedium)
                Text("Tick the apps to ALLOW. Everything left unticked gets blocked.", style = MaterialTheme.typography.bodySmall)
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
