package com.therealcam.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.therealcam.ui.theme.RealColors

private class GuideSection(val icon: ImageVector, val title: String, val items: List<Pair<String, String>>)

private val GUIDE = listOf(
    GuideSection(
        Icons.Filled.CameraAlt, "Главный экран",
        listOf(
            "Шестерёнка" to "настройки.",
            "Часы" to "таймер: выкл → 3 с → 10 с.",
            "Решётка" to "сетка третей на превью.",
            "Молния" to "фонарик (если есть вспышка).",
            "?" to "это руководство.",
            "Красная точка и число" to "сбор датасета включён; число — сколько снимков уже полностью оценено (нужно 150).",
            "Значок мозга" to "загружена AI-модель.",
            "Тап по превью" to "фокус и замер в этой точке.",
            "Линия по центру" to "уровень горизонта; зелёная — телефон ровно.",
            "Значок штатива" to "появляется, когда выдержка длинная. Оранжевый — телефон двигается, зелёный — неподвижен, можно снимать.",
            "Круглая кнопка" to "спуск. Во время серии повторное нажатие останавливает съёмку и сводит уже снятые кадры.",
            "Кнопки громкости" to "тоже спуск (выключается в настройках).",
            "Кружок слева" to "последний снимок, открывает Real Галерею. Справа — смена фронтальной и основной камеры.",
        ),
    ),
    GuideSection(
        Icons.Filled.ZoomIn, "Объективы и зум",
        listOf(
            "Кнопки над спуском" to "объективы телефона: 0.6 — ультраширик, 1× — основной, 3 и больше — телевик, цветок — макро. Выбранная кнопка показывает текущий зум.",
            "Кнопка 2" to "цифровой зум 2× на основном объективе, если телевика нет.",
            "Два пальца по превью" to "плавный зум; когда отпускаете, камера сама переходит на подходящий объектив.",
            "Список объективов" to "Настройки → Камера. Xiaomi/Redmi/POCO часто прячут ультраширик и макро от сторонних приложений: приложение ищет их само, а с root-доступом (Про-режим) открывает гарантированно.",
        ),
    ),
    GuideSection(
        Icons.Filled.Tune, "Режимы съёмки",
        listOf(
            "Переключатель режимов" to "строка над спуском, листается пальцем.",
            "АВТО" to "обычная съёмка. Ползунок EV — светлее/темнее, ниже можно заранее выбрать категорию снимка.",
            "РУЧНОЙ" to "ISO, выдержка, фокус (авто / ∞ / дистанция), баланс белого, EV и «Кадры» — серия с выбранным способом сведения. Над шкалой видно, насколько кадр светлее или темнее нормы. В про-режиме на превью есть гистограмма (красная полоса — пересвет). «Сохранить как пресет» запоминает настройки для режима СЦЕНЫ.",
            "СЦЕНЫ" to "пресеты одной кнопкой, см. следующий раздел.",
            "АСТРО" to "звёзды со штатива, см. раздел «Звёзды».",
            "AI" to "ваша модель смотрит на сцену и показывает 1–3 значка с процентами. Нажмите значок, если ИИ ошибся (например, звёзды, а не луна). Строка под значками — что именно он снимет.",
            "ВИДЕО" to "запись видео, см. раздел «Видео».",
        ),
    ),
    GuideSection(
        Icons.Filled.Landscape, "Сцены (пресеты)",
        listOf(
            "Ночь" to "с рук: 8 коротких кадров, шум уходит при сведении. На опоре кадры длиннее.",
            "Облака" to "−0,7 EV, чтобы светлые облака не пересвечивались; 6 кадров усредняются.",
            "Закат" to "−1 EV, 4 кадра — небо и солнце без пересвета.",
            "Луна" to "сначала приблизьте луну (кнопка 2 или телевик). Много очень коротких кадров при минимальном ISO, остаются самые резкие.",
            "Звёзды" to "открывает режим АСТРО.",
            "Следы света" to "на штативе огни машин складываются в линии (максимум по кадрам).",
            "Вода" to "серия усредняется — вода становится гладкой; на опоре сильнее.",
            "Движение" to "очень короткая выдержка, 5 кадров, остаётся самый резкий.",
            "Документ" to "3 кадра, остаётся самый резкий.",
            "Строка под значками" to "план: число кадров, выдержка, ISO, ∞ — фокус на бесконечность.",
            "Свои пресеты" to "в режиме РУЧНОЙ настройте всё как нужно и нажмите «Сохранить как пресет» — он появится здесь после встроенных сцен (с жёлтой обводкой). Удалить — долгое нажатие.",
        ),
    ),
    GuideSection(
        Icons.Filled.AutoAwesome, "Звёзды (режим АСТРО)",
        listOf(
            "1" to "поставьте телефон на штатив или опору. Значок штатива должен стать зелёным.",
            "2" to "выберите время снимка: 1–30 минут. Чем дольше, тем меньше шума.",
            "3" to "нажмите спуск. Через 3 секунды начнётся серия, экран приглушится.",
            "Строка плана" to "например «15 × 16с · ISO1600 · ∞ · ≈4 мин»: выдержка кадра — самая длинная, при которой звёзды ещё точки.",
            "Динамик" to "голос каждые 30 с говорит, сколько осталось. Нужен русский голос в системном синтезаторе речи.",
            "Фары и самолёты" to "засвеченные кадры выбрасываются целиком, следы самолётов и спутников — по пикселям. Звёзды выравниваются по участкам кадра, поэтому не смазываются.",
            "Остановить раньше" to "нажмите спуск ещё раз: снятые кадры сведутся.",
        ),
    ),
    GuideSection(
        Icons.Filled.Videocam, "Видео",
        listOf(
            "Красная кнопка" to "начать и остановить запись. Во время записи вверху идёт таймер.",
            "720p / 1080p / 4K и 30 / 60" to "качество и частота кадров (только поддерживаемые телефоном).",
            "Стабилизация" to "электронная стабилизация, если телефон её умеет.",
            "Куда сохраняется" to "в галерею телефона, папка DCIM/TheRealCam.",
        ),
    ),
    GuideSection(
        Icons.Filled.PhotoLibrary, "Оценка и Real Галерея",
        listOf(
            "После снимка" to "при включённом датасете появляется панель «Как получилось?»: общая оценка 1–10, яркость, резкость, шум, цвет, смаз и категория.",
            "Только полностью оценённый снимок" to "попадает в датасет. Оценить можно и позже в Real Галерее (вторая иконка на рабочем столе).",
            "Серии" to "в галерее можно открыть каждый кадр серии и оценить отдельно.",
        ),
    ),
    GuideSection(
        Icons.Filled.Category, "Категории и значки",
        listOf(
            "Что это" to "то, что вы снимаете: «Звёзды на даче», «Луна», «Облака». Создаются в Настройках или в галерее.",
            "Значок" to "задаёт способ сведения серии: звёзды — без выбросов, луна — самые резкие кадры, следы — максимум.",
            "Зачем" to "по категориям модель учится понимать, что вы хотите снять.",
        ),
    ),
    GuideSection(
        Icons.Filled.Storage, "Датасет и обучение модели",
        listOf(
            "1" to "Настройки → «Сбор датасета» (покажет всё, что записывается).",
            "2" to "снимайте и оценивайте. Минимум 150 полностью оценённых снимков, лучше 2000+.",
            "3" to "Настройки → «Экспорт ZIP» и перенесите файл на компьютер.",
            "4" to "обучите модель программой TheRealCam Trainer (Windows / Linux): train, finetune или окно gui.",
            "5" to "скопируйте .trcm на телефон → Настройки → «Загрузить модель» → режим AI.",
        ),
    ),
    GuideSection(
        Icons.Filled.DeveloperMode, "Про-режим и root",
        listOf(
            "Шумоподавление" to "для серий лучше «Выкл»: шум и так уходит при сведении, а мелкие детали остаются.",
            "Резкость" to "«Выкл» — без искусственного повышения резкости.",
            "Оптический стабилизатор" to "на штативе выключите: при длинной выдержке он может «плыть».",
            "Root-доступ к камерам" to "с Magisk или KernelSU приложение добавит себя в список программ, которым прошивка открывает все модули камеры. Действует до перезагрузки, при запуске повторяется само.",
        ),
    ),
    GuideSection(
        Icons.Filled.Psychology, "Что делает ИИ",
        listOf(
            "Делает" to "узнаёт сцену и подбирает выдержку, ISO, число кадров и фокус так, как вы сами снимали и оценивали похожие сцены.",
            "Не делает" to "не дорисовывает и не «улучшает» пиксели: снимок — это только реальные кадры, сведённые выравниванием и усреднением.",
        ),
    ),
)

/** Step-by-step help: what each button and mode does. */
@Composable
fun GuideScreen(onBack: () -> Unit) {
    var open by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White) }
            Text("Руководство", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 12.dp)) {
            itemsIndexed(GUIDE) { i, s ->
                val expanded = open == i
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                        .background(RealColors.Surface).clickable { open = if (expanded) -1 else i }.padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(s.icon, null, tint = RealColors.Accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(s.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = RealColors.TextDim)
                    }
                    if (expanded) {
                        Spacer(Modifier.height(8.dp))
                        s.items.forEach { (term, text) ->
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append(term) }
                                    append(" — ")
                                    append(text)
                                },
                                color = RealColors.TextDim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp),
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
