package com.therealcam.ui.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.therealcam.ui.gallery.GalleryActivity
import com.therealcam.ui.onboarding.OnboardingScreen
import com.therealcam.ui.onboarding.OnboardingViewModel
import com.therealcam.ui.settings.SettingsActivity
import com.therealcam.ui.theme.RealTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CameraActivity : ComponentActivity() {
    private val vm: CameraViewModel by viewModels()
    private val onboarding: OnboardingViewModel by viewModels()
    private var hasCamera by mutableStateOf(false)
    private var askedOnce = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasCamera = granted(Manifest.permission.CAMERA)
        if (hasCamera && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.onResume()
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            vm.startLocation()
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hasCamera = granted(Manifest.permission.CAMERA)
        // Permissions are asked after the first-run screen, not on top of it.
        lifecycleScope.launch {
            onboarding.needed.first { it == false }
            requestMissing()
        }
        setContent {
            RealTheme {
                val onboardingNeeded by onboarding.needed.collectAsState()
                when {
                    onboardingNeeded == null -> Box(Modifier.fillMaxSize().background(ComposeColor.Black))
                    onboardingNeeded == true -> {
                        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                            uri?.let(onboarding::importModel)
                        }
                        OnboardingScreen(
                            importedModel = onboarding.imported.collectAsState().value,
                            importError = onboarding.error.collectAsState().value,
                            importBusy = onboarding.busy.collectAsState().value,
                            onImportModel = { picker.launch(arrayOf("*/*")) },
                            onFinish = { onboarding.finish(it) },
                        )
                    }
                    hasCamera -> CameraScreen(
                        vm,
                        onGallery = { id -> startActivity(GalleryActivity.intent(this, id)) },
                        onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                        onGuide = { startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_GUIDE, true)) },
                    )
                    else -> PermissionScreen()
                }
            }
        }
    }

    private fun requestMissing() {
        val wanted = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filterNot(::granted)
        if (wanted.isNotEmpty()) {
            askedOnce = true
            permissions.launch(wanted.toTypedArray())
        }
    }

    @androidx.compose.runtime.Composable
    private fun PermissionScreen() {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("TheRealCam нужен доступ к камере.", textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Геопозиция нужна только для геометок и данных сцены (высота Солнца и Луны). Её можно не давать.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = {
                if (askedOnce && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                } else {
                    requestMissing()
                }
            }) { Text("Разрешить") }
        }
    }

    override fun onResume() {
        super.onResume()
        hasCamera = granted(Manifest.permission.CAMERA)
        if (hasCamera) vm.onResume()
    }

    override fun onPause() {
        vm.onPause()
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_CAMERA) &&
            hasCamera && onboarding.needed.value == false && (vm.settings.value.volumeShutter || keyCode == KeyEvent.KEYCODE_CAMERA)
        ) {
            if (event?.repeatCount == 0) vm.onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
