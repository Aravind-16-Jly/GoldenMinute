package com.goldenminute.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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

@Composable
fun GoldenMinuteHome() {
    var sosActivated by remember { mutableStateOf(false) }
    var showCprGuidance by remember { mutableStateOf(false) }
    var showOfflineSos by remember { mutableStateOf(false) }
    var currentLocation by remember { mutableStateOf("Location not captured") }
    var emergencyContact by remember {
        mutableStateOf("Emergency contact not set")
    }
    var showIncidentReport by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    var voiceText by remember { mutableStateOf("") }
    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val matches =
                result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)

            voiceText = matches?.firstOrNull() ?: ""
        }
    }
    val context = LocalContext.current

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val granted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (granted) {
                @SuppressLint("MissingPermission")
                val locationManager =
                    context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

                val location = if (
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                } else {
                    null
                }
                if (location != null) {
                    currentLocation =
                        "Lat: ${location.latitude}\nLong: ${location.longitude}"
                } else {
                    currentLocation = "GPS location unavailable"
                }
            } else {
                currentLocation = "Location permission denied"
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
        modifier = Modifier.fillMaxSize()
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "GoldenMinute",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Offline Emergency Copilot",
                fontSize = 16.sp
            )

            Spacer(modifier = Modifier.height(35.dp))

            Text(
                text = "Are you in an emergency?",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(25.dp))
            if (showCprGuidance) {
                AlertDialog(
                    onDismissRequest = {
                        showCprGuidance = false
                    },
                    title = {
                        Text("❤️ CPR Guidance")
                    },
                    text = {
                        Text(
                            "1. Check if the person responds.\n\n" +
                                    "2. Call emergency services or ask someone nearby to call.\n\n" +
                                    "3. If the person is not breathing normally, begin CPR.\n\n" +
                                    "4. Push hard and fast in the center of the chest.\n\n" +
                                    "5. Continue until help arrives or the person starts breathing normally."
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showCprGuidance = false
                            }
                        ) {
                            Text("Got it")
                        }
                    }
                )
            }
            if (countdown > 0) {
                Text(
                    text = "SOS activating in $countdown",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(15.dp))
            }

            Button(
                onClick = {
                    countdown = 5
                },
                modifier = Modifier
                    .size(180.dp),
                shape = CircleShape
            ) {
                Text(
                    text = "SOS",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(35.dp))
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
                title = "❤️ CPR Guidance",
                description = "Get step-by-step first-aid and CPR instructions.",
                onClick = {
                    showCprGuidance = true
                }
            )

            EmergencyCard(
                title = "📡 Offline SOS",
                description = "Send an emergency alert when internet is unavailable.",
                onClick = {
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                    showOfflineSos = true
                }
            )
        }
    }
    if (sosActivated) {
        AlertDialog(
            onDismissRequest = {
                sosActivated = false
            },
            title = {
                Text("🚨 SOS Activated")
            },
            text = {
                Text(
                    "Emergency mode is active.\n\n" +
                            "GoldenMinute is preparing emergency assistance."
                )
            },


            confirmButton = {
                Button(
                    onClick = {
                        sosActivated = false
                    }
                ) {
                    Text("Cancel SOS")
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
                Text("📡 Offline SOS")
            },
            text = {
                Text(
                    "✓ Emergency mode activated\n\n" +
                            "📍 Location:\n$currentLocation\n\n" +
                            "📞 Contact:\n$emergencyContact\n\n" +
                            "✓ No internet required\n\n" +
                            "🚨 SOS is ready for offline handover."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        emergencyContact = "Demo Emergency Contact"
                        showOfflineSos = false
                        sosActivated = true
                        showIncidentReport = true
                    }
                ) {
                    Text("🚨 Send SOS")
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
                            "📞 Contact:\n$emergencyContact\n\n" +
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
        shape = RoundedCornerShape(16.dp)
    ) {

        Column(
            modifier = Modifier.padding(16.dp)
        ) {

            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = description,
                fontSize = 14.sp
            )
        }
    }
}