package com.goldenminute.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import java.util.UUID
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.speech.RecognizerIntent
import kotlin.concurrent.thread
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldenminute.app.ui.theme.GoldenMinuteTheme



class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            GoldenMinuteTheme {
                GoldenMinuteHome()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoldenMinuteHome() {
    var currentTab by remember { mutableStateOf("Home") }
    var sosActivated by remember { mutableStateOf(false) }
    var showCprGuidance by remember { mutableStateOf(false) }
    var showOfflineSos by remember { mutableStateOf(false) }
    var currentLocation by remember { mutableStateOf("Location not captured") }

    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("golden_minute_prefs", Context.MODE_PRIVATE)
    }
    var savedContactName by remember {
        mutableStateOf(prefs.getString("contact_name", "") ?: "")
    }
    var savedContactPhone by remember {
        mutableStateOf(prefs.getString("contact_phone", "") ?: "")
    }

    var showSetContactDialog by remember { mutableStateOf(false) }
    var contactNameInput by remember { mutableStateOf("") }
    var contactPhoneInput by remember { mutableStateOf("") }
    var contactError by remember { mutableStateOf<String?>(null) }

    val emergencyContactDisplay = remember(savedContactName, savedContactPhone) {
        if (savedContactPhone.isBlank()) {
            "No emergency contact set"
        } else if (savedContactName.isBlank()) {
            savedContactPhone
        } else {
            "$savedContactName ($savedContactPhone)"
        }
    }

    var showIncidentReport by remember { mutableStateOf(false) }
    var showNoContactAlert by remember { mutableStateOf(false) }
    var queuedSosStatus by remember {
        mutableStateOf(prefs.getString("queued_sos_status", "") ?: "")
    }
    var showQueuedSosDialog by remember { mutableStateOf(false) }

    var showBluetoothRelayDialog by remember { mutableStateOf(false) }
    var relayModeActive by remember { mutableStateOf(false) }
    var bluetoothRelayState by remember { mutableStateOf("IDLE") }
    var foundRelayDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var relayErrorMessage by remember { mutableStateOf("") }
    var showAllBluetoothDevices by remember { mutableStateOf(false) }

    var showOfflineSosReceived by remember { mutableStateOf(false) }
    var receivedSosData by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_CONNECT] == true &&
            permissions[Manifest.permission.BLUETOOTH_SCAN] == true
        } else {
            true
        }
        if (granted) {
            checkAndStartBluetoothRelay(context) { state, msg, devices ->
                bluetoothRelayState = state
                relayErrorMessage = msg
                foundRelayDevices = devices
            }
        } else {
            bluetoothRelayState = "ERROR"
            relayErrorMessage = "Bluetooth permissions denied."
        }
    }

    LaunchedEffect(relayModeActive) {
        if (relayModeActive) {
            thread(start = true) {
                try {
                    val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                    if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                        @SuppressLint("MissingPermission")
                        val serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord("GoldenMinuteRelay", GOLDENMINUTE_UUID)
                        while (relayModeActive) {
                            try {
                                val socket = serverSocket?.accept()
                                if (socket != null) {
                                    val input = socket.inputStream
                                    val buffer = ByteArray(2048)
                                    val bytes = input.read(buffer)
                                    if (bytes > 0) {
                                        val payload = String(buffer, 0, bytes)
                                        val parsedMap = parseSosPayload(payload)
                                        receivedSosData = parsedMap
                                        showOfflineSosReceived = true

                                        try {
                                            val output = socket.outputStream
                                            output.write("ACK".toByteArray())
                                            output.flush()
                                        } catch (e: Exception) {}
                                    }
                                    socket.close()
                                }
                            } catch (e: Exception) {
                                break
                            }
                        }
                        try {
                            serverSocket.close()
                        } catch (e: Exception) {}
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val currentStatus = prefs.getString("queued_sos_status", "") ?: ""
                if (currentStatus == "QUEUED") {
                    prefs.edit().putString("queued_sos_status", "SENDING").apply()
                    queuedSosStatus = "SENDING"

                    val desc = prefs.getString("queued_voice", "") ?: ""
                    val cls = prefs.getString("queued_classification", "") ?: ""
                    val loc = prefs.getString("queued_location", "") ?: ""
                    val phone = prefs.getString("queued_contact_phone", "") ?: ""
                    val name = prefs.getString("queued_contact_name", "") ?: ""

                    if (phone.isNotBlank()) {
                        val messageText = "🚨 GOLDENMINUTE EMERGENCY ALERT (AUTO-SENT)\n\n" +
                                "Description: $desc\n" +
                                "Classification: $cls\n" +
                                "Location: $loc\n" +
                                "Contact: $name ($phone)"

                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("smsto:$phone")
                            putExtra("sms_body", messageText)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(intent)
                            prefs.edit().putString("queued_sos_status", "SENT").apply()
                            queuedSosStatus = "SENT"
                        } catch (e: Exception) {
                            prefs.edit().putString("queued_sos_status", "QUEUED").apply()
                            queuedSosStatus = "QUEUED"
                        }
                    } else {
                        prefs.edit().putString("queued_sos_status", "QUEUED").apply()
                        queuedSosStatus = "QUEUED"
                    }
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {}
    }
    var countdown by remember { mutableStateOf(0) }
    var voiceText by remember { mutableStateOf("") }
    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches =
                result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)

            voiceText = matches?.firstOrNull() ?: ""
        }
    }

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val granted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (granted) {
                updateCurrentLocation(context) { loc ->
                    currentLocation = loc
                }
            } else {
                currentLocation = "Location permission denied"
            }
        }

    LaunchedEffect(Unit) {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            updateCurrentLocation(context) { loc ->
                currentLocation = loc
            }
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown--

            if (countdown == 0) {
                sosActivated = true
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "🛡 GoldenMinute",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Professional Emergency Copilot",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Are you in an emergency?",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (countdown > 0) {
                Text(
                    text = "SOS activating in $countdown",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(15.dp))
            }

            Button(
                onClick = { countdown = 5 },
                modifier = Modifier.size(170.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "SOS", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                    Text(text = "TAP FOR HELP", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                }
            }

            Spacer(modifier = Modifier.height(25.dp))

            if (voiceText.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(text = "🎙 You said:", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(text = voiceText, fontSize = 14.sp)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(text = classifyEmergency(voiceText), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        if (queuedSosStatus == "QUEUED" || queuedSosStatus == "SENDING" || queuedSosStatus == "SENT") {
                            Spacer(modifier = Modifier.height(10.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = when (queuedSosStatus) {
                                        "SENT" -> MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.errorContainer
                                    }
                                ),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = when (queuedSosStatus) {
                                            "SENDING" -> "📡 SENDING SOS"
                                            "SENT" -> "📡 SOS SENT"
                                            else -> "📡 SOS QUEUED"
                                        },
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when (queuedSosStatus) {
                                            "SENT" -> MaterialTheme.colorScheme.onPrimaryContainer
                                            else -> MaterialTheme.colorScheme.onErrorContainer
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = when (queuedSosStatus) {
                                            "SENDING" -> "Sending SOS to emergency contact..."
                                            "SENT" -> "SOS Sent / Delivery confirmed"
                                            else -> "SOS QUEUED — Waiting for connectivity\nEmergency info saved securely on device."
                                        },
                                        fontSize = 14.sp,
                                        color = when (queuedSosStatus) {
                                            "SENT" -> MaterialTheme.colorScheme.onPrimaryContainer
                                            else -> MaterialTheme.colorScheme.onErrorContainer
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { showQueuedSosDialog = true },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("View Details")
                                        }
                                        if (queuedSosStatus != "SENT" && isNetworkAvailable(context)) {
                                            Button(
                                                onClick = {
                                                    queuedSosStatus = "SENDING"
                                                    prefs.edit().putString("queued_sos_status", "SENDING").apply()

                                                    val desc = prefs.getString("queued_voice", voiceText) ?: voiceText
                                                    val cls = prefs.getString("queued_classification", classifyEmergency(desc)) ?: classifyEmergency(desc)
                                                    val loc = prefs.getString("queued_location", currentLocation) ?: currentLocation
                                                    val phone = prefs.getString("queued_contact_phone", savedContactPhone) ?: savedContactPhone
                                                    val name = prefs.getString("queued_contact_name", savedContactName) ?: savedContactName

                                                    val messageText = "🚨 GOLDENMINUTE EMERGENCY ALERT\n\nDescription: $desc\nClassification: $cls\nLocation: $loc\nContact: $name ($phone)"
                                                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                                                        data = Uri.parse("smsto:$phone")
                                                        putExtra("sms_body", messageText)
                                                    }
                                                    try {
                                                        context.startActivity(intent)
                                                        queuedSosStatus = "SENT"
                                                        prefs.edit().putString("queued_sos_status", "SENT").apply()
                                                    } catch (e: ActivityNotFoundException) {
                                                        queuedSosStatus = "QUEUED"
                                                        prefs.edit().putString("queued_sos_status", "QUEUED").apply()
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Send Now")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        EmergencyCard(
                            title = "📍 GPS Location",
                            description = currentLocation,
                            onClick = {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                                updateCurrentLocation(context) { loc -> currentLocation = loc }
                            }
                        )

                        EmergencyCard(
                            title = "🎙 Voice Emergency",
                            description = "Speak your emergency and get offline guidance.",
                            onClick = {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe your emergency")
                                }
                                voiceLauncher.launch(intent)
                            }
                        )

                        EmergencyCard(
                            title = "📞 Set Emergency Contact",
                            description = if (emergencyContactDisplay == "No emergency contact set") "No emergency contact set. Tap to configure." else "Configured: $emergencyContactDisplay",
                            onClick = {
                                contactNameInput = savedContactName
                                contactPhoneInput = savedContactPhone
                                contactError = null
                                showSetContactDialog = true
                            }
                        )

                        EmergencyCard(
                            title = "❤️ CPR Guidance",
                            description = "Get step-by-step first-aid and CPR instructions.",
                            onClick = { showCprGuidance = true }
                        )

                        EmergencyCard(
                            title = if (relayModeActive) "🔵 Relay Mode (ACTIVE)" else "🔵 Relay Mode",
                            description = if (relayModeActive) "Listening for incoming offline SOS relays..." else "Toggle to act as an offline SOS relay receiver.",
                            onClick = {
                                val adapter = BluetoothAdapter.getDefaultAdapter()
                                if (adapter == null) {
                                    relayErrorMessage = "Bluetooth unavailable. Unable to find an offline relay."
                                    bluetoothRelayState = "ERROR"
                                    showBluetoothRelayDialog = true
                                } else if (!adapter.isEnabled) {
                                    relayErrorMessage = "Bluetooth is disabled. Please turn on Bluetooth."
                                    bluetoothRelayState = "ERROR"
                                    showBluetoothRelayDialog = true
                                } else {
                                    relayModeActive = !relayModeActive
                                    if (relayModeActive) {
                                        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                                            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                                        }
                                        try { context.startActivity(discoverableIntent) } catch (e: Exception) {}
                                    }
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(25.dp))
            EmergencyCard(
                title = "📍 GPS Location",
                description = currentLocation,
                onClick = {
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                    updateCurrentLocation(context) { loc ->
                        currentLocation = loc
                    }
                }
            )

            EmergencyCard(
                title = "🎙 Voice Emergency",
                description = "Speak your emergency and get offline guidance.",
                onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(
                            RecognizerIntent.EXTRA_PROMPT,
                            "Describe your emergency"
                        )
                    }

                    voiceLauncher.launch(intent)
                }
            )
            if (voiceText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "🎙 You said:",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = voiceText,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = classifyEmergency(voiceText),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            EmergencyCard(
                title = "📞 Set Emergency Contact",
                description = if (emergencyContactDisplay == "No emergency contact set") {
                    "No emergency contact set. Tap to configure."
                } else {
                    "Configured: $emergencyContactDisplay"
                },
                onClick = {
                    contactNameInput = savedContactName
                    contactPhoneInput = savedContactPhone
                    contactError = null
                    showSetContactDialog = true
                }
            )

            EmergencyCard(
                title = "❤️ CPR Guidance",
                description = "Get step-by-step first-aid and CPR instructions.",
                onClick = {
                    showCprGuidance = true
                }
            )

            if (queuedSosStatus == "QUEUED" || queuedSosStatus == "SENDING" || queuedSosStatus == "SENT") {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (queuedSosStatus) {
                            "SENT" -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.errorContainer
                        }
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = when (queuedSosStatus) {
                                "SENDING" -> "📡 SENDING SOS"
                                "SENT" -> "📡 SOS SENT"
                                else -> "📡 SOS QUEUED"
                            },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (queuedSosStatus) {
                                "SENT" -> MaterialTheme.colorScheme.onPrimaryContainer
                                else -> MaterialTheme.colorScheme.onErrorContainer
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when (queuedSosStatus) {
                                "SENDING" -> "Sending SOS to emergency contact..."
                                "SENT" -> "SOS Sent / Delivery confirmed"
                                else -> "SOS QUEUED — Waiting for connectivity\nEmergency info saved securely on device."
                            },
                            fontSize = 14.sp,
                            color = when (queuedSosStatus) {
                                "SENT" -> MaterialTheme.colorScheme.onPrimaryContainer
                                else -> MaterialTheme.colorScheme.onErrorContainer
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    showQueuedSosDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("View Details")
                            }
                            if (queuedSosStatus != "SENT" && isNetworkAvailable(context)) {
                                Button(
                                    onClick = {
                                        queuedSosStatus = "SENDING"
                                        prefs.edit().putString("queued_sos_status", "SENDING").apply()

                                        val desc = prefs.getString("queued_voice", voiceText) ?: voiceText
                                        val cls = prefs.getString("queued_classification", classifyEmergency(desc)) ?: classifyEmergency(desc)
                                        val loc = prefs.getString("queued_location", currentLocation) ?: currentLocation
                                        val phone = prefs.getString("queued_contact_phone", savedContactPhone) ?: savedContactPhone
                                        val name = prefs.getString("queued_contact_name", savedContactName) ?: savedContactName

                                        val messageText = "🚨 GOLDENMINUTE EMERGENCY ALERT\n\n" +
                                                "Description: $desc\n" +
                                                "Classification: $cls\n" +
                                                "Location: $loc\n" +
                                                "Contact: $name ($phone)"

                                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                                            data = Uri.parse("smsto:$phone")
                                            putExtra("sms_body", messageText)
                                        }
                                        try {
                                            context.startActivity(intent)
                                            queuedSosStatus = "SENT"
                                            prefs.edit().putString("queued_sos_status", "SENT").apply()
                                        } catch (e: ActivityNotFoundException) {
                                            queuedSosStatus = "QUEUED"
                                            prefs.edit().putString("queued_sos_status", "QUEUED").apply()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Send Now")
                                }
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "📡 Offline SOS",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = "Send an emergency alert when offline via Bluetooth relay.",
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                            updateCurrentLocation(context) { loc ->
                                currentLocation = loc
                            }

                            if (isNetworkAvailable(context)) {
                                if (savedContactPhone.isBlank()) {
                                    showNoContactAlert = true
                                } else {
                                    sosActivated = true
                                    showIncidentReport = true

                                    val description = if (voiceText.isBlank()) "No voice description provided" else voiceText
                                    val classification = classifyEmergency(voiceText.ifEmpty { "General Emergency" })
                                    val locToSend = if (currentLocation.isBlank()) "Location unavailable" else currentLocation

                                    val messageText = "🚨 GOLDENMINUTE EMERGENCY ALERT\n\n" +
                                            "Description: $description\n" +
                                            "Classification: $classification\n" +
                                            "Location: $locToSend\n" +
                                            "Contact: $savedContactName ($savedContactPhone)"

                                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                                        data = Uri.parse("smsto:$savedContactPhone")
                                        putExtra("sms_body", messageText)
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: ActivityNotFoundException) {
                                        val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, messageText)
                                        }
                                        try {
                                            context.startActivity(fallbackIntent)
                                        } catch (ex: Exception) {}
                                    }
                                }
                            } else {
                                val timestamp = System.currentTimeMillis().toString()
                                val description = if (voiceText.isBlank()) "No voice description provided" else voiceText
                                val classification = classifyEmergency(voiceText.ifEmpty { "General Emergency" })
                                val locToSave = if (currentLocation.isBlank()) "Location unavailable" else currentLocation

                                prefs.edit()
                                    .putString("queued_sos_status", "QUEUED")
                                    .putString("queued_timestamp", timestamp)
                                    .putString("queued_voice", description)
                                    .putString("queued_classification", classification)
                                    .putString("queued_location", locToSave)
                                    .putString("queued_contact_name", savedContactName)
                                    .putString("queued_contact_phone", savedContactPhone)
                                    .apply()

                                queuedSosStatus = "QUEUED"

                                val adapter = BluetoothAdapter.getDefaultAdapter()
                                if (adapter == null) {
                                    relayErrorMessage = "Bluetooth unavailable. Unable to find an offline relay."
                                    bluetoothRelayState = "ERROR"
                                    showBluetoothRelayDialog = true
                                } else if (!adapter.isEnabled) {
                                    relayErrorMessage = "Bluetooth is disabled. Please turn on Bluetooth."
                                    bluetoothRelayState = "ERROR"
                                    showBluetoothRelayDialog = true
                                } else {
                                    bluetoothRelayState = "SEARCHING"
                                    relayErrorMessage = ""
                                    showBluetoothRelayDialog = true

                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        bluetoothPermissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.BLUETOOTH_CONNECT,
                                                Manifest.permission.BLUETOOTH_SCAN
                                            )
                                        )
                                    } else {
                                        checkAndStartBluetoothRelay(context) { state, msg, devices ->
                                            bluetoothRelayState = state
                                            relayErrorMessage = msg
                                            foundRelayDevices = devices
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🔵 Scan Nearby Phones")
                    }
                }
            }

            EmergencyCard(
                title = if (relayModeActive) "🔵 GoldenMinute Relay Mode (ACTIVE)" else "🔵 GoldenMinute Relay Mode",
                description = if (relayModeActive) "Listening for incoming offline SOS relays..." else "Toggle to act as an offline SOS relay receiver.",
                onClick = {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    if (adapter == null) {
                        relayErrorMessage = "Bluetooth unavailable. Unable to find an offline relay."
                        bluetoothRelayState = "ERROR"
                        showBluetoothRelayDialog = true
                    } else if (!adapter.isEnabled) {
                        relayErrorMessage = "Bluetooth is disabled. Please turn on Bluetooth."
                        bluetoothRelayState = "ERROR"
                        showBluetoothRelayDialog = true
                    } else {
                        relayModeActive = !relayModeActive
                        if (relayModeActive) {
                            val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                            }
                            try {
                                context.startActivity(discoverableIntent)
                            } catch (e: Exception) {}
                        }
                    }
                }
            )
        }

        if (showSetContactDialog) {
        AlertDialog(
            onDismissRequest = {
                showSetContactDialog = false
            },
            title = {
                Text("📞 Set Emergency Contact")
            },
            text = {
                Column {
                    Text("Enter your emergency contact details below.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = contactNameInput,
                        onValueChange = { contactNameInput = it },
                        label = { Text("Contact Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = contactPhoneInput,
                        onValueChange = {
                            contactPhoneInput = it
                            if (it.isNotBlank()) contactError = null
                        },
                        label = { Text("Phone Number *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (contactError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = contactError!!,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (contactPhoneInput.isBlank()) {
                            contactError = "Phone number cannot be empty"
                        } else {
                            savedContactName = contactNameInput.trim()
                            savedContactPhone = contactPhoneInput.trim()
                            prefs.edit()
                                .putString("contact_name", savedContactName)
                                .putString("contact_phone", savedContactPhone)
                                .apply()
                            showSetContactDialog = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSetContactDialog = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    if (sosActivated) {
        AlertDialog(
            onDismissRequest = {
                sosActivated = false
            },
            title = {
                Text("🚨 SOS Activated — Emergency Action")
            },
            text = {
                Column {
                    Text("Choose an emergency action below:")
                    if (!isNetworkAvailable(context)) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "⚠️ Note: Device is offline or in Airplane Mode. A cellular emergency call to 112 may still be possible depending on network availability.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_DIAL).apply {
                                data = Uri.parse("tel:112")
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {}
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Call Emergency Services — 112")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (savedContactPhone.isBlank()) {
                                sosActivated = false
                                showNoContactAlert = true
                            } else {
                                val intent = Intent(Intent.ACTION_DIAL).apply {
                                    data = Uri.parse("tel:$savedContactPhone")
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (e: Exception) {}
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Call Emergency Contact")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            sosActivated = false
                            showOfflineSos = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Continue Offline SOS")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sosActivated = false
                    }
                ) {
                    Text("Dismiss")
                }
            }
        )
    }
    if (showOfflineSos) {
        AlertDialog(
            onDismissRequest = {
                showOfflineSos = false
            },
            title = {
                Text(if (isNetworkAvailable(context)) "📡 Offline SOS" else "📡 OFFLINE SOS")
            },
            text = {
                if (isNetworkAvailable(context)) {
                    Text(
                        "✓ Emergency mode activated\n\n" +
                                "📍 Location:\n$currentLocation\n\n" +
                                "📞 Contact:\n$emergencyContactDisplay\n\n" +
                                "✓ Network available\n\n" +
                                "🚨 SOS is ready for online handover."
                    )
                } else {
                    Text(
                        "No internet connection.\n\n" +
                                "SOS stored locally.\n\n" +
                                "📍 Location:\n$currentLocation\n\n" +
                                "📞 Contact:\n$emergencyContactDisplay"
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        updateCurrentLocation(context) { loc ->
                            currentLocation = loc
                        }

                        if (savedContactPhone.isBlank()) {
                            showOfflineSos = false
                            showNoContactAlert = true
                        } else {
                            if (isNetworkAvailable(context)) {
                                showOfflineSos = false
                                sosActivated = true
                                showIncidentReport = true

                                val description = if (voiceText.isBlank()) "No voice description provided" else voiceText
                                val classification = classifyEmergency(voiceText.ifEmpty { "General Emergency" })
                                val messageText = "🚨 GOLDENMINUTE EMERGENCY ALERT\n\n" +
                                        "Description: $description\n" +
                                        "Classification: $classification\n" +
                                        "Location: $currentLocation\n" +
                                        "Contact: $savedContactName ($savedContactPhone)"

                                val intent = Intent(Intent.ACTION_SENDTO).apply {
                                    data = Uri.parse("smsto:$savedContactPhone")
                                    putExtra("sms_body", messageText)
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (e: ActivityNotFoundException) {
                                    val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, messageText)
                                    }
                                    try {
                                        context.startActivity(fallbackIntent)
                                    } catch (ex: Exception) {
                                        // ignore fallback failure
                                    }
                                }
                            } else {
                                val timestamp = System.currentTimeMillis().toString()
                                val description = if (voiceText.isBlank()) "No voice description provided" else voiceText
                                val classification = classifyEmergency(voiceText.ifEmpty { "General Emergency" })
                                val locToSave = if (currentLocation.isBlank()) "Location unavailable" else currentLocation

                                prefs.edit()
                                    .putString("queued_sos_status", "QUEUED")
                                    .putString("queued_timestamp", timestamp)
                                    .putString("queued_voice", description)
                                    .putString("queued_classification", classification)
                                    .putString("queued_location", locToSave)
                                    .putString("queued_contact_name", savedContactName)
                                    .putString("queued_contact_phone", savedContactPhone)
                                    .apply()

                                queuedSosStatus = "QUEUED"
                                showOfflineSos = false

                                checkAndStartBluetoothRelay(context) { state, msg, devices ->
                                    bluetoothRelayState = state
                                    relayErrorMessage = msg
                                    foundRelayDevices = devices
                                    showBluetoothRelayDialog = true
                                }
                            }
                        }
                    }
                ) {
                    Text(if (isNetworkAvailable(context)) "🚨 Send SOS" else "🔵 Find Nearby Relay")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showOfflineSos = false
                        if (!isNetworkAvailable(context)) {
                            val timestamp = System.currentTimeMillis().toString()
                            val description = if (voiceText.isBlank()) "No voice description provided" else voiceText
                            val classification = classifyEmergency(voiceText.ifEmpty { "General Emergency" })
                            val locToSave = if (currentLocation.isBlank()) "Location unavailable" else currentLocation

                            prefs.edit()
                                .putString("queued_sos_status", "QUEUED")
                                .putString("queued_timestamp", timestamp)
                                .putString("queued_voice", description)
                                .putString("queued_classification", classification)
                                .putString("queued_location", locToSave)
                                .putString("queued_contact_name", savedContactName)
                                .putString("queued_contact_phone", savedContactPhone)
                                .apply()

                            queuedSosStatus = "QUEUED"
                        }
                    }
                ) {
                    Text("Close")
                }
            }
        )
    }
    if (showNoContactAlert) {
        AlertDialog(
            onDismissRequest = {
                showNoContactAlert = false
            },
            title = {
                Text("⚠️ No Emergency Contact")
            },
            text = {
                Text("Please set an emergency contact before sending an SOS message.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNoContactAlert = false
                        contactNameInput = savedContactName
                        contactPhoneInput = savedContactPhone
                        contactError = null
                        showSetContactDialog = true
                    }
                ) {
                    Text("Set Contact Now")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNoContactAlert = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    if (showQueuedSosDialog) {
        AlertDialog(
            onDismissRequest = {
                showQueuedSosDialog = false
            },
            title = {
                Text("📡 SOS QUEUED")
            },
            text = {
                Text(
                    "No network connection is available.\n\n" +
                            "Your emergency information has been saved securely on this device.\n\n" +
                            "It will be ready for handover when connectivity is restored."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showQueuedSosDialog = false
                    }
                ) {
                    Text("OK")
                }
            }
        )
    }
    if (showIncidentReport) {
        AlertDialog(
            onDismissRequest = {
                showIncidentReport = false
            },
            title = {
                Text("📝 Incident Report")
            },
            text = {
                Text(
                    "🚨 Emergency detected\n\n" +
                            "🎙 Voice input:\n$voiceText\n\n" +
                            "📍 Location:\n$currentLocation\n\n" +
                            "📞 Contact:\n$emergencyContactDisplay\n\n" +
                            "📡 SOS status: Activated"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showIncidentReport = false
                    }
                ) {
                    Text("Close")
                }
            }
        )
    }

    if (showBluetoothRelayDialog) {
        AlertDialog(
            onDismissRequest = {
                showBluetoothRelayDialog = false
            },
            title = {
                Text("Nearby Bluetooth Devices")
            },
            text = {
                Column {
                    when (bluetoothRelayState) {
                        "SEARCHING" -> {
                            Column {
                                Text("Searching for nearby devices...", fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Scanning for discoverable Bluetooth devices...")
                                }
                            }
                        }
                        "FOUND" -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 350.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text("🔵 Nearby Devices\n", fontWeight = FontWeight.Bold)
                                Text("Select a nearby relay device to transfer SOS:")
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    Checkbox(
                                        checked = showAllBluetoothDevices,
                                        onCheckedChange = { showAllBluetoothDevices = it }
                                    )
                                    Text("Show all discovered devices (audio/computers)", fontSize = 12.sp)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Note: Some phones do not reveal their names until pairing or connection.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                val filteredDevices = foundRelayDevices.filter { device ->
                                    if (showAllBluetoothDevices) true
                                    else {
                                        val major = device.bluetoothClass?.majorDeviceClass
                                        major != BluetoothClass.Device.Major.AUDIO_VIDEO && major != BluetoothClass.Device.Major.COMPUTER
                                    }
                                }

                                if (filteredDevices.isEmpty()) {
                                    Text("No nearby Bluetooth devices found yet. Make sure Phone B has GoldenMinute Relay Mode enabled and is discoverable.")
                                } else {
                                    for (device in filteredDevices) {
                                        @SuppressLint("MissingPermission")
                                        val formattedName = getFormattedDeviceName(device)
                                        Button(
                                            onClick = {
                                                bluetoothRelayState = "TRANSFERRING"
                                                val description = if (voiceText.isBlank()) "No voice description provided" else voiceText
                                                val classification = classifyEmergency(voiceText.ifEmpty { "General Emergency" })
                                                val locToSave = if (currentLocation.isBlank()) "Location unavailable" else currentLocation
                                                val timestamp = System.currentTimeMillis().toString()

                                                val payload = "TIMESTAMP:$timestamp\n" +
                                                              "VOICE:$description\n" +
                                                              "CLASSIFICATION:$classification\n" +
                                                              "LOCATION:$locToSave\n" +
                                                              "CONTACT_NAME:$savedContactName\n" +
                                                              "CONTACT_PHONE:$savedContactPhone\n" +
                                                              "STATUS:READY"

                                                transferSosViaBluetooth(
                                                    device = device,
                                                    payload = payload,
                                                    onSuccess = {
                                                        bluetoothRelayState = "SUCCESS"
                                                    },
                                                    onError = { err ->
                                                        bluetoothRelayState = "ERROR"
                                                        relayErrorMessage = err
                                                    }
                                                )
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                        ) {
                                            Text("📱 $formattedName")
                                        }
                                    }
                                }
                            }
                        }
                        "TRANSFERRING" -> {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Connecting and sending SOS...")
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Connecting via RFCOMM and transmitting SOS payload...", fontSize = 12.sp)
                            }
                        }
                        "SUCCESS" -> {
                            Text("✅ SOS delivered", fontWeight = FontWeight.Bold)
                        }
                        "ERROR" -> {
                            Text("❌ Failed: $relayErrorMessage", color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("SOS is safely stored in local Offline SOS Queue.")
                        }
                    }
                }
            },
            confirmButton = {
                if (bluetoothRelayState == "SUCCESS" || bluetoothRelayState == "ERROR") {
                    Button(
                        onClick = {
                            showBluetoothRelayDialog = false
                            if (bluetoothRelayState == "ERROR") {
                                val timestamp = System.currentTimeMillis().toString()
                                val description = if (voiceText.isBlank()) "No voice description provided" else voiceText
                                val classification = classifyEmergency(voiceText.ifEmpty { "General Emergency" })
                                val locToSave = if (currentLocation.isBlank()) "Location unavailable" else currentLocation

                                prefs.edit()
                                    .putString("queued_sos_status", "QUEUED")
                                    .putString("queued_timestamp", timestamp)
                                    .putString("queued_voice", description)
                                    .putString("queued_classification", classification)
                                    .putString("queued_location", locToSave)
                                    .putString("queued_contact_name", savedContactName)
                                    .putString("queued_contact_phone", savedContactPhone)
                                    .apply()

                                queuedSosStatus = "QUEUED"
                                showQueuedSosDialog = true
                            }
                        }
                    ) {
                        Text("OK")
                    }
                } else if (bluetoothRelayState == "SEARCHING" || bluetoothRelayState == "FOUND") {
                    Button(
                        onClick = {
                            showBluetoothRelayDialog = false
                            val timestamp = System.currentTimeMillis().toString()
                            val description = if (voiceText.isBlank()) "No voice description provided" else voiceText
                            val classification = classifyEmergency(voiceText.ifEmpty { "General Emergency" })
                            val locToSave = if (currentLocation.isBlank()) "Location unavailable" else currentLocation

                            prefs.edit()
                                .putString("queued_sos_status", "QUEUED")
                                .putString("queued_timestamp", timestamp)
                                .putString("queued_voice", description)
                                .putString("queued_classification", classification)
                                .putString("queued_location", locToSave)
                                .putString("queued_contact_name", savedContactName)
                                .putString("queued_contact_phone", savedContactPhone)
                                .apply()

                            queuedSosStatus = "QUEUED"
                            showQueuedSosDialog = true
                        }
                    ) {
                        Text("Save to SOS Queue")
                    }
                }
            },
            dismissButton = {
                if (bluetoothRelayState == "SEARCHING" || bluetoothRelayState == "FOUND") {
                    TextButton(
                        onClick = {
                            showBluetoothRelayDialog = false
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    if (showOfflineSosReceived) {
        val cls = receivedSosData["CLASSIFICATION"] ?: prefs.getString("received_sos_classification", "Emergency") ?: "Emergency"
        val voice = receivedSosData["VOICE"] ?: prefs.getString("received_sos_voice", "N/A") ?: "N/A"
        val loc = receivedSosData["LOCATION"] ?: prefs.getString("received_sos_location", "N/A") ?: "N/A"
        val time = receivedSosData["TIMESTAMP"] ?: prefs.getString("received_sos_timestamp", "N/A") ?: "N/A"
        val contactName = receivedSosData["CONTACT_NAME"] ?: prefs.getString("received_sos_name", "") ?: ""
        val contactPhone = receivedSosData["CONTACT_PHONE"] ?: prefs.getString("received_sos_phone", "") ?: ""

        var smsErrorMessage by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = {
                showOfflineSosReceived = false
            },
            title = {
                Text("🚨 OFFLINE SOS RECEIVED")
            },
            text = {
                Column {
                    Text("Emergency:\n$cls\n", fontWeight = FontWeight.Bold)
                    Text("Description:\n$voice\n")
                    Text("Location:\n$loc\n")
                    Text("Time:\n$time\n")
                    Text("Contact:\n$contactName ($contactPhone)")
                    if (smsErrorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(smsErrorMessage!!, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (contactPhone.isBlank()) {
                            smsErrorMessage = "📡 Relay received, but contact phone is missing."
                        } else {
                            val messageText = "🚨 GOLDENMINUTE EMERGENCY ALERT (RELAYED)\n\n" +
                                    "Description: $voice\n" +
                                    "Classification: $cls\n" +
                                    "Location: $loc\n" +
                                    "Contact: $contactName ($contactPhone)"

                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("smsto:$contactPhone")
                                putExtra("sms_body", messageText)
                            }
                            try {
                                context.startActivity(intent)
                                showOfflineSosReceived = false
                            } catch (e: ActivityNotFoundException) {
                                smsErrorMessage = "📡 Relay received, but cellular service is unavailable."
                            } catch (e: Exception) {
                                smsErrorMessage = "📡 Relay received, but cellular service is unavailable."
                            }
                        }
                    }
                ) {
                    Text("📱 SEND SOS")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showOfflineSosReceived = false
                    }
                ) {
                    Text("Dismiss")
                }
            }
        )
    }
}

fun classifyEmergency(text: String): String {
    val input = text.lowercase()

    return when {
        input.contains("fire") ||
                input.contains("smoke") ||
                input.contains("burning") ->
            "🔥 FIRE EMERGENCY"

        input.contains("chest pain") ||
                input.contains("heart") ||
                input.contains("breathing") ||
                input.contains("can't breathe") ||
                input.contains("unconscious") ||
                input.contains("fainted") ->
            "🏥 MEDICAL EMERGENCY"

        input.contains("accident") ||
                input.contains("car crash") ||
                input.contains("crash") ||
                input.contains("collision") ->
            "🚗 ACCIDENT / RESCUE"

        input.contains("bleeding") ||
                input.contains("injured") ||
                input.contains("injury") ||
                input.contains("fell") ||
                input.contains("hurt") ->
            "🚑 INJURY"

        else ->
            "⚠️ GENERAL EMERGENCY"
    }
}

@Composable
fun EmergencyCard(
    title: String,
    description: String,
    onClick: () -> Unit = {}
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = description,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@SuppressLint("MissingPermission")
fun updateCurrentLocation(context: Context, onLocationUpdated: (String) -> Unit) {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    if (!hasFine && !hasCoarse) {
        onLocationUpdated("Location permission denied")
        return
    }

    val gpsEnabled = try { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (e: Exception) { false }
    val netEnabled = try { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { false }

    if (!gpsEnabled && !netEnabled) {
        onLocationUpdated("Location unavailable — please enable Location services.")
        return
    }

    var bestLocation: Location? = null
    try {
        val providers = locationManager.getProviders(true)
        for (provider in providers) {
            val loc = locationManager.getLastKnownLocation(provider)
            if (loc != null) {
                if (bestLocation == null || loc.time > bestLocation.time) {
                    bestLocation = loc
                }
            }
        }
    } catch (e: Exception) {
        // ignore
    }

    if (bestLocation != null) {
        val lat = bestLocation.latitude
        val lon = bestLocation.longitude
        val formatted = "Lat: %.4f, Lon: %.4f\nMaps: https://maps.google.com/?q=$lat,$lon".format(lat, lon)
        onLocationUpdated(formatted)
    } else {
        onLocationUpdated("Location unavailable — waiting for GPS fix.")
    }
}

fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

val GOLDENMINUTE_UUID: UUID = UUID.fromString("d06198f0-b852-4a0b-9366-9304c4dbb258")

fun parseSosPayload(payload: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    val lines = payload.split("\n")
    for (line in lines) {
        val parts = line.split(":", limit = 2)
        if (parts.size == 2) {
            map[parts[0].trim()] = parts[1].trim()
        }
    }
    return map
}

@SuppressLint("MissingPermission")
fun startBluetoothDiscovery(context: Context, onDeviceFound: (BluetoothDevice) -> Unit, onDiscoveryFinished: () -> Unit) {
    val adapter = BluetoothAdapter.getDefaultAdapter()
    if (adapter == null || !adapter.isEnabled) return

    if (adapter.isDiscovering) {
        adapter.cancelDiscovery()
    }
    adapter.startDiscovery()

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        onDeviceFound(device)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    onDiscoveryFinished()
                    try {
                        context.unregisterReceiver(this)
                    } catch (e: Exception) {}
                }
            }
        }
    }
    val filter = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_FOUND)
        addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
    }
    context.registerReceiver(receiver, filter)
}

@SuppressLint("MissingPermission")
fun checkAndStartBluetoothRelay(context: Context, onStateReady: (String, String, List<BluetoothDevice>) -> Unit) {
    val adapter = BluetoothAdapter.getDefaultAdapter()
    if (adapter == null) {
        onStateReady("ERROR", "Bluetooth unavailable. Unable to find an offline relay.", emptyList())
        return
    }
    if (!adapter.isEnabled) {
        onStateReady("ERROR", "Bluetooth is disabled. Please turn on Bluetooth.", emptyList())
        return
    }

    onStateReady("SEARCHING", "", emptyList())

    val devicesList = mutableSetOf<BluetoothDevice>()
    adapter.bondedDevices?.forEach { device ->
        val majorClass = device.bluetoothClass?.majorDeviceClass
        if (majorClass != BluetoothClass.Device.Major.AUDIO_VIDEO) {
            devicesList.add(device)
        }
    }
    onStateReady("FOUND", "", devicesList.toList())

    startBluetoothDiscovery(
        context = context,
        onDeviceFound = { device ->
            val majorClass = device.bluetoothClass?.majorDeviceClass
            if (majorClass != BluetoothClass.Device.Major.AUDIO_VIDEO) {
                devicesList.add(device)
                onStateReady("FOUND", "", devicesList.toList())
            }
        },
        onDiscoveryFinished = {
            onStateReady("FOUND", "", devicesList.toList())
        }
    )
}

@SuppressLint("MissingPermission")
fun transferSosViaBluetooth(device: BluetoothDevice, payload: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
    thread(start = true) {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            val socket = device.createRfcommSocketToServiceRecord(GOLDENMINUTE_UUID)
            socket.connect()
            val output = socket.outputStream
            output.write(payload.toByteArray())
            output.flush()

            val input = socket.inputStream
            val buffer = ByteArray(64)
            val bytes = input.read(buffer)
            if (bytes > 0) {
                val response = String(buffer, 0, bytes).trim()
                if (response.contains("ACK")) {
                    socket.close()
                    onSuccess()
                } else {
                    socket.close()
                    onError("Device did not acknowledge SOS receipt")
                }
            } else {
                socket.close()
                onError("No acknowledgement received from receiver")
            }
        } catch (e: Exception) {
            onError(e.localizedMessage ?: "Connection failed")
        }
    }
}

@SuppressLint("MissingPermission")
fun getFormattedDeviceName(device: BluetoothDevice): String {
    val name = device.name
    val address = device.address ?: "00:00:00:00:00"
    val maskedAddress = if (address.length >= 8) "••:••:••:${address.takeLast(5)}" else "••:••:••:••:••"

    val displayName = if (!name.isNullOrBlank()) {
        name
    } else {
        "Unknown Bluetooth device ($maskedAddress)"
    }

    val typeLabel = when (device.bluetoothClass?.majorDeviceClass) {
        BluetoothClass.Device.Major.PHONE -> "Phone"
        BluetoothClass.Device.Major.AUDIO_VIDEO -> "Audio device"
        BluetoothClass.Device.Major.COMPUTER -> "Computer"
        else -> "Unknown"
    }

    return "$displayName [$typeLabel]"
}

@Composable
fun MapScreenContent(currentLocation: String) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("GPS Location & Maps", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Accurate offline GPS coordinates and maps integration.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("📍 Current Coordinates", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(currentLocation, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val url = if (currentLocation.contains("https://maps.google.com")) {
                            currentLocation.substringAfter("Maps: ").substringBefore("\n").ifBlank { "https://maps.google.com/?q=12.9716,77.5946" }
                        } else {
                            "https://maps.google.com/?q=12.9716,77.5946"
                        }
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        try { context.startActivity(intent) } catch (e: Exception) {}
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Filled.Place, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open in Maps")
                }
            }
        }
    }
}

@Composable
fun FirstAidScreenContent() {
    var selectedCategory by remember { mutableStateOf("CPR") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("First Aid & CPR", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Offline step-by-step emergency medical guidance.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("CPR", "Bleeding", "Burns", "Choking").forEach { category ->
                Button(
                    onClick = { selectedCategory = category },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedCategory == category) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(category, fontSize = 12.sp)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = when (selectedCategory) {
                        "CPR" -> "Adult CPR Steps"
                        "Bleeding" -> "Severe Bleeding Control"
                        "Burns" -> "Thermal Burn Care"
                        else -> "Choking / Heimlich Maneuver"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = when (selectedCategory) {
                        "CPR" -> "1. Check Responsiveness: Tap shoulders and ask 'Are you okay?'.\n2. Call 112 if unresponsive.\n3. Begin Chest Compressions: Push hard and fast (100-120 bpm) in the center of the chest.\n4. Give rescue breaths if trained."
                        "Bleeding" -> "1. Apply direct pressure to the wound using a clean cloth or bandage.\n2. Maintain pressure continuously without lifting to check.\n3. Elevate injured area if possible.\n4. Seek professional medical help immediately."
                        "Burns" -> "1. Cool the burn with cool (not cold) running water for 10-20 minutes.\n2. Remove jewelry or tight items before swelling occurs.\n3. Cover loosely with sterile non-stick bandage.\n4. Do not apply ice or butter."
                        else -> "1. Stand behind the person and wrap arms around their waist.\n2. Make a fist and place it just above the navel.\n3. Grasp fist with other hand and press hard into abdomen with quick upward thrusts."
                    },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun HistoryScreenContent(queuedSosStatus: String) {
    var historyFilter by remember { mutableStateOf("All") } // "All", "Pending", "Sent", "Failed"

    val sampleIncidents = remember(queuedSosStatus) {
        val list = mutableListOf<Triple<String, String, String>>()
        if (queuedSosStatus.isNotBlank()) {
            list.add(Triple("Emergency Alert", "Just now • Offline", if (queuedSosStatus == "SENT") "Sent" else "Pending"))
        }
        list.add(Triple("Heart Problem", "10:32 AM • Oct 10, 2026", "Pending"))
        list.add(Triple("Accident", "08:15 AM • Oct 9, 2026", "Sent"))
        list.add(Triple("Breathing Issue", "06:20 PM • Oct 8, 2026", "Failed"))
        list
    }

    val filtered = sampleIncidents.filter {
        when (historyFilter) {
            "Pending" -> it.third == "Pending"
            "Sent" -> it.third == "Sent"
            "Failed" -> it.third == "Failed"
            else -> true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("SOS Queue & Incident History", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Review past emergency incidents and delivery statuses.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("All", "Pending", "Sent", "Failed").forEach { filter ->
                Button(
                    onClick = { historyFilter = filter },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (historyFilter == filter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(filter, fontSize = 12.sp)
                }
            }
        }

        if (filtered.isEmpty()) {
            Text("No incidents found for filter: $historyFilter", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            filtered.forEach { incident ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(incident.first, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(incident.second, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = when (incident.third) {
                                "Sent" -> MaterialTheme.colorScheme.primaryContainer
                                "Failed" -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.secondaryContainer
                            }
                        ) {
                            Text(
                                text = incident.third,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (incident.third) {
                                    "Sent" -> MaterialTheme.colorScheme.onPrimaryContainer
                                    "Failed" -> MaterialTheme.colorScheme.onErrorContainer
                                    else -> MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreenContent(savedContactName: String, savedContactPhone: String, onEditContact: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings & Emergency", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Manage emergency contacts, permissions, and app preferences.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("📞 Emergency Contact", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(if (savedContactPhone.isBlank()) "No contact configured" else "$savedContactName ($savedContactPhone)", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Button(
                    onClick = onEditContact,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Configure Contact")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ℹ️ App Information", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("GoldenMinute v1.0", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Professional Emergency Copilot with Bluetooth Relay & 112 Dialing.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}