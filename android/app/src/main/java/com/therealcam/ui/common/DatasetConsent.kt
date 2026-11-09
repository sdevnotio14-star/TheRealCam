package com.therealcam.ui.common

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp

/** Shown before dataset collection is enabled: lists everything that is recorded. */
const val CONSENT_TEXT = """В режиме сбора датасета для каждого снимка сохраняется:

• геометка (если разрешена геопозиция);
• положение телефона: наклон, крен, азимут;
• гироскоп и акселерометр за 1,5 с до кадра, во время выдержки и 0,75 с после;
• параметры кадра: выдержка, ISO, диафрагма, фокус, баланс белого, режимы автоматики;
• то, что вы выставили вручную (или выбрал ИИ), число кадров серии, способ сведения;
• освещённость, давление, температура батареи, высота Солнца и Луны, фаза Луны, время;
• маленькое превью сцены и числовые признаки, которые видит модель;
• ваша оценка 1–10, оценка по критериям и категория.

Всё хранится только на телефоне, в папке приложения (Android/data/com.therealcam/files/TheRealCam). У приложения нет доступа к интернету, поэтому данные никуда не отправляются. Наружу они попадают только при экспорте ZIP, который делаете вы.

В обучение идут только снимки, оценённые по всем критериям. Минимум для обучения — 150 снимков."""

@Composable
fun DatasetConsentDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сбор датасета") },
        text = { Text(CONSENT_TEXT, fontSize = 13.sp, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onAccept) { Text("Включить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
