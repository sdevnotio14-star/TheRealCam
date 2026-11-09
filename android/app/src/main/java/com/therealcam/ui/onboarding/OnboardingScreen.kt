package com.therealcam.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.therealcam.R
import com.therealcam.dataset.MIN_DATASET_SAMPLES
import com.therealcam.ui.common.DatasetConsentDialog
import com.therealcam.ui.theme.RealColors

/**
 * First run: what the app is, then the two ways to get an AI model — collect an own dataset
 * (with the full list of recorded data) and/or import a `.trcm` shared by another user.
 */
@Composable
fun OnboardingScreen(
    importedModel: String?,
    importError: String?,
    importBusy: Boolean,
    onImportModel: () -> Unit,
    onFinish: (enableDataset: Boolean) -> Unit,
    startPage: Int = 0,
    datasetInitially: Boolean = false,
) {
    var page by rememberSaveable { mutableIntStateOf(startPage) }
    var dataset by rememberSaveable { mutableStateOf(datasetInitially) }
    var consent by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = page > 0) { page = 0 }

    Column(
        Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (page == 0) WelcomePage() else ChoicePage(
                dataset = dataset,
                onDataset = { on -> if (on) consent = true else dataset = false },
                importedModel = importedModel, importError = importError, importBusy = importBusy,
                onImportModel = onImportModel,
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { if (page == 0) page = 1 else onFinish(dataset) },
            enabled = !importBusy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RealColors.Accent, contentColor = Color.Black),
        ) {
            Text(if (page == 0) "Далее" else "Начать съёмку", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        if (page == 1) {
            Text(
                "Всё это можно изменить позже в настройках.",
                color = RealColors.TextDim, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
            )
        }
    }

    if (consent) {
        DatasetConsentDialog(
            onAccept = { consent = false; dataset = true },
            onDismiss = { consent = false },
        )
    }
}

@Composable
private fun WelcomePage() {
    Spacer(Modifier.height(28.dp))
    Box(
        Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF0E1116))
            .border(1.dp, RealColors.SurfaceHigh, RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_launcher_camera_fg), null, tint = Color.Unspecified, modifier = Modifier.size(84.dp))
    }
    Spacer(Modifier.height(18.dp))
    Text("TheRealCam", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text("Камера со своим ИИ, который учится только на ваших снимках", color = RealColors.TextDim, fontSize = 16.sp)
    Spacer(Modifier.height(26.dp))
    Feature(Icons.Filled.Psychology, "Своя модель с нуля", "Без чужих весов и интернета. Учится на ваших снимках, настройках и оценках.")
    Feature(Icons.Filled.Tune, "ИИ выбирает параметры, а не пиксели", "Выдержку, ISO, фокус и число кадров. Снимок он не перерисовывает.")
    Feature(Icons.Filled.BurstMode, "Честные серии", "Кадры выравниваются, засвеченные фарами и смазанные отбрасываются, следы самолётов убираются по пикселям.")
    Feature(Icons.Filled.PhotoLibrary, "Real Галерея", "Вторая иконка на рабочем столе: серии, оценки 1–10 по критериям, категории.")
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(RealColors.SurfaceHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = RealColors.Accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(text, color = RealColors.TextDim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ChoicePage(
    dataset: Boolean,
    onDataset: (Boolean) -> Unit,
    importedModel: String?,
    importError: String?,
    importBusy: Boolean,
    onImportModel: () -> Unit,
) {
    Spacer(Modifier.height(20.dp))
    Text("С чего начнём?", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text(
        "Режиму AI нужна модель. Её можно обучить на своих снимках или взять у другого пользователя. Можно и то и другое.",
        color = RealColors.TextDim, fontSize = 14.sp,
    )
    Spacer(Modifier.height(20.dp))

    OptionCard(
        icon = Icons.Filled.AddAPhoto,
        title = "Собирать свой датасет",
        text = "Снимайте как обычно и оценивайте снимки. После $MIN_DATASET_SAMPLES полностью оценённых снимков " +
            "датасет можно выгрузить и обучить модель на ПК (Linux или Windows).",
        selected = dataset,
        onClick = { onDataset(!dataset) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (dataset) "Сбор включится после «Начать съёмку»" else "Перед включением покажем всё, что записывается",
                color = if (dataset) RealColors.Ok else RealColors.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            Switch(
                dataset, onDataset,
                colors = SwitchDefaults.colors(checkedTrackColor = RealColors.Accent, checkedThumbColor = Color.Black),
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    OptionCard(
        icon = Icons.Filled.UploadFile,
        title = "Загрузить модель от другого пользователя",
        text = "Файл .trcm. Позже её можно дообучить на своём датасете.",
        selected = importedModel != null,
        onClick = { if (!importBusy) onImportModel() },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                importBusy -> {
                    CircularProgressIndicator(Modifier.size(18.dp), color = RealColors.Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Проверяю модель…", color = RealColors.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                }
                importedModel != null -> {
                    Icon(Icons.Filled.CheckCircle, null, tint = RealColors.Ok, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(importedModel, color = RealColors.Ok, fontSize = 12.sp, modifier = Modifier.weight(1f))
                }
                else -> Text(importError ?: "Модель сразу станет активной", color = if (importError != null) RealColors.Bad else RealColors.TextDim,
                    fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
            OutlinedButton(onClick = onImportModel, enabled = !importBusy) { Text(if (importedModel != null) "Другая" else "Выбрать файл") }
        }
    }
}

@Composable
private fun OptionCard(
    icon: ImageVector,
    title: String,
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    footer: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(RealColors.Surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) RealColors.Accent else RealColors.SurfaceHigh, shape)
            .clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) RealColors.Accent else Color.White, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(text, color = RealColors.TextDim, fontSize = 13.sp)
        footer()
    }
}
