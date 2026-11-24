package uz.osonfinans.app

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.Instant
import java.time.ZoneOffset

data class Tx(val id: Long, val kind: String, val amount: Long, val category: String, val note: String, val date: String)

class TxDb(context: Context) : SQLiteOpenHelper(context, "oson_finans.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tx(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, amount INTEGER NOT NULL, category TEXT NOT NULL, note TEXT, date TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun all(): List<Tx> {
        val out = mutableListOf<Tx>()
        readableDatabase.rawQuery("SELECT id,kind,amount,category,note,date FROM tx ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) out += Tx(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4) ?: "", c.getString(5))
        }
        return out
    }
    fun add(kind: String, amount: Long, category: String, note: String, date: String) {
        val v = ContentValues().apply {
            put("kind", kind); put("amount", amount); put("category", category); put("note", note); put("date", date)
        }
        writableDatabase.insert("tx", null, v)
    }
    fun delete(id: Long) { writableDatabase.delete("tx", "id=?", arrayOf(id.toString())) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = TxDb(this)
        val prefs = getSharedPreferences("oson_finans_settings", MODE_PRIVATE)
        setContent { MaterialTheme { FinanceApp(db, prefs) } }
    }
}

private fun money(n: Long) = "%,d".format(Locale("ru"), n).replace(',', ' ')
private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
private fun monthNow() = today().take(7)
private fun formatDay(value: String): String = try {
    val d = LocalDate.parse(value)
    val formatted = d.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru")))
    if (value == today()) "Сегодня, $formatted" else formatted.replaceFirstChar { it.uppercase(Locale("ru")) }
} catch (_: Exception) { value }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceApp(db: TxDb, prefs: android.content.SharedPreferences) {
    val context = LocalContext.current
    var txs by remember { mutableStateOf(db.all()) }
    var showAdd by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf("Расход") }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Еда") }
    var note by remember { mutableStateOf("") }
    var dayLimit by remember { mutableLongStateOf(prefs.getLong("day_limit", 100000L)) }
    var monthLimit by remember { mutableLongStateOf(prefs.getLong("month_limit", 3000000L)) }
    var dayInput by remember { mutableStateOf(dayLimit.toString()) }
    var monthInput by remember { mutableStateOf(monthLimit.toString()) }
    var voiceLanguage by remember { mutableStateOf(prefs.getString("voice_language", "ru-RU") ?: "ru-RU") }
    var recognized by remember { mutableStateOf("") }
    var pendingVoice by remember { mutableStateOf(false) }
    var historyPeriod by remember { mutableStateOf("Месяц") }
    var historySearch by remember { mutableStateOf("") }
    var selectedHistoryDate by remember { mutableStateOf(today()) }
    var showHistoryDatePicker by remember { mutableStateOf(false) }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            recognized = spoken
            val parsed = parseSpeech(spoken)
            if (parsed != null) {
                kind = parsed.first
                amount = parsed.second.toString()
                category = parsed.third
                note = spoken
                showAdd = true
            }
        }
        pendingVoice = false
    }

    val daySpent = txs.filter { it.kind == "Расход" && it.date == today() }.sumOf { it.amount }
    val monthSpent = txs.filter { it.kind == "Расход" && it.date.startsWith(monthNow()) }.sumOf { it.amount }
    val income = txs.filter { it.kind == "Доход" }.sumOf { it.amount }
    val expenses = txs.filter { it.kind == "Расход" }.sumOf { it.amount }
    val balance = income - expenses
    val dayExceeded = dayLimit > 0 && daySpent > dayLimit
    val monthExceeded = monthLimit > 0 && monthSpent > monthLimit
    var dayWasExceeded by remember { mutableStateOf(dayExceeded) }
    var monthWasExceeded by remember { mutableStateOf(monthExceeded) }
    LaunchedEffect(dayExceeded, monthExceeded) {
        if ((dayExceeded && !dayWasExceeded) || (monthExceeded && !monthWasExceeded)) {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vibrator.vibrate(100)
        }
        dayWasExceeded = dayExceeded
        monthWasExceeded = monthExceeded
    }

    Scaffold(
        topBar = {
            TopAppBarCompat(
                title = "OSON FINANS",
                onSettings = { showSettings = true }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatingActionButton(onClick = {
                    pendingVoice = true
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLanguage)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Say the amount and what it was for")
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    }
                    try { speech.launch(i) } catch (_: Exception) { pendingVoice = false; recognized = "На телефоне нет службы распознавания речи" }
                }, containerColor = MaterialTheme.colorScheme.secondary) {
                    Icon(Icons.Default.Mic, contentDescription = "Голосовой ввод")
                }
                FloatingActionButton(onClick = { kind = "Расход"; amount = ""; note = ""; showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Добавить")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("ТЕКУЩИЙ БАЛАНС", style = MaterialTheme.typography.labelMedium)
                        Text("${money(balance)} сум", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("Доходы"); Text("${money(income)} сум", color = Color(0xFF16834A), fontWeight = FontWeight.Bold) }
                            Column { Text("Расходы"); Text("${money(expenses)} сум", color = Color(0xFFC62828), fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
            item { LimitCard("Расходы сегодня", daySpent, dayLimit, dayExceeded) }
            item { LimitCard("Расходы за месяц", monthSpent, monthLimit, monthExceeded) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("История операций", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("${txs.size} записей", style = MaterialTheme.typography.labelMedium)
                }
            }
            item {
                OutlinedTextField(
                    value = historySearch,
                    onValueChange = { historySearch = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Поиск покупки или категории") },
                    placeholder = { Text("Например, продукты или такси") }
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("Сегодня", "7 дней", "Месяц", "Всё", "Дата").forEach { period ->
                        FilterChip(
                            selected = historyPeriod == period,
                            onClick = {
                                historyPeriod = period
                                if (period == "Дата") showHistoryDatePicker = true
                            },
                            label = { Text(period) }
                        )
                    }
                }
            }
            val filteredHistory = txs.filter { tx ->
                val periodMatch = when (historyPeriod) {
                    "Сегодня" -> tx.date == today()
                    "7 дней" -> tx.date >= LocalDate.now().minusDays(6).toString() && tx.date <= today()
                    "Месяц" -> tx.date.startsWith(monthNow())
                    "Дата" -> tx.date == selectedHistoryDate
                    else -> true
                }
                val q = historySearch.trim().lowercase(Locale("ru"))
                periodMatch && (q.isBlank() || tx.note.lowercase(Locale("ru")).contains(q) || tx.category.lowercase(Locale("ru")).contains(q) || tx.amount.toString().contains(q))
            }
            val shownIncome = filteredHistory.filter { it.kind == "Доход" }.sumOf { it.amount }
            val shownExpenses = filteredHistory.filter { it.kind == "Расход" }.sumOf { it.amount }
            val categoryTotals = filteredHistory.filter { it.kind == "Расход" }.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }.toList().sortedByDescending { it.second }
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (historyPeriod == "Дата") "Итог за $selectedHistoryDate" else "Итог: ${historyPeriod.lowercase(Locale("ru"))}", fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Доходы: ${money(shownIncome)} сум", color = Color(0xFF16834A), fontWeight = FontWeight.SemiBold)
                            Text("Расходы: ${money(shownExpenses)} сум", color = Color(0xFFC62828), fontWeight = FontWeight.SemiBold)
                        }
                        if (categoryTotals.isNotEmpty()) {
                            Divider()
                            Text("Расходы по категориям", fontWeight = FontWeight.SemiBold)
                            categoryTotals.take(5).forEach { (cat, total) ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(cat, style = MaterialTheme.typography.bodySmall)
                                    Text("${money(total)} сум", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
            if (filteredHistory.isEmpty()) item {
                Text("За выбранный период операций не найдено.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val groupedHistory = filteredHistory.groupBy { it.date }.toSortedMap(compareByDescending { it })
            items(groupedHistory.entries.toList(), key = { it.key }) { entry ->
                val dayItems = entry.value
                val dayExpenses = dayItems.filter { it.kind == "Расход" }.sumOf { it.amount }
                val dayIncome = dayItems.filter { it.kind == "Доход" }.sumOf { it.amount }
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(formatDay(entry.key), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Расходы: ${money(dayExpenses)} сум", color = if (dayExpenses > dayLimit && dayLimit > 0) Color(0xFFC62828) else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    }
                    if (dayIncome > 0) Text("Доходы за день: ${money(dayIncome)} сум", color = Color(0xFF16834A), style = MaterialTheme.typography.bodySmall)
                    dayItems.forEach { tx ->
                        Card {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(tx.note.ifBlank { tx.category }, fontWeight = FontWeight.SemiBold)
                                    Text(tx.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("${if (tx.kind == "Доход") "+" else "−"}${money(tx.amount)} сум",
                                    color = if (tx.kind == "Доход") Color(0xFF16834A) else Color(0xFFC62828), fontWeight = FontWeight.Bold)
                                TextButton(onClick = { db.delete(tx.id); txs = db.all() }) { Text("Удалить") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showHistoryDatePicker) {
        val initialMillis = try { LocalDate.parse(selectedHistoryDate).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() } catch (_: Exception) { null }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showHistoryDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        selectedHistoryDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        historyPeriod = "Дата"
                    }
                    showHistoryDatePicker = false
                }) { Text("Выбрать") }
            },
            dismissButton = { TextButton(onClick = { showHistoryDatePicker = false }) { Text("Отмена") } }
        ) { DatePicker(state = datePickerState) }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Новая операция") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = kind == "Расход", onClick = { kind = "Расход" }, label = { Text("Расход") })
                        FilterChip(selected = kind == "Доход", onClick = { kind = "Доход" }, label = { Text("Доход") })
                    }
                    OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Сумма, сум") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    val cats = if (kind == "Доход") listOf("Карманные деньги", "Зарплата", "Подарок", "Возврат долга", "Прочее")
                        else listOf("Еда", "Транспорт", "Покупки", "Развлечения", "Одежда", "Связь и интернет", "Здоровье", "Топливо", "Прочее")
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                        OutlinedTextField(category, {}, readOnly = true, label = { Text("Категория") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            cats.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { category = c; expanded = false }) }
                        }
                    }
                    OutlinedTextField(note, { note = it }, label = { Text("Описание") })
                    if (recognized.isNotBlank()) Text("Распознано: $recognized", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = amount.toLongOrNull()
                    if (n != null && n > 0) {
                        db.add(kind, n, category, note, today())
                        txs = db.all()
                        showAdd = false
                        recognized = ""
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Отмена") } }
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Лимиты расходов") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(dayInput, { dayInput = it.filter(Char::isDigit) }, label = { Text("Дневной лимит, сум") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(monthInput, { monthInput = it.filter(Char::isDigit) }, label = { Text("Месячный лимит, сум") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text("Укажи 0, чтобы отключить соответствующий лимит.", style = MaterialTheme.typography.bodySmall)
                    Text("Язык голосового ввода", fontWeight = FontWeight.SemiBold)
                    val voiceLanguages = listOf(
                        "Русский" to "ru-RU", "O‘zbekcha" to "uz-UZ", "English" to "en-US",
                        "中文" to "zh-CN", "Deutsch" to "de-DE", "हिन्दी" to "hi-IN",
                        "Español" to "es-ES", "Français" to "fr-FR", "العربية" to "ar-SA"
                    )
                    var languageMenu by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = languageMenu, onExpandedChange = { languageMenu = !languageMenu }) {
                        OutlinedTextField(
                            value = voiceLanguages.firstOrNull { it.second == voiceLanguage }?.first ?: "Русский",
                            onValueChange = {}, readOnly = true, label = { Text("Язык") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageMenu) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                            voiceLanguages.forEach { (label, code) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    voiceLanguage = code
                                    prefs.edit().putString("voice_language", code).apply()
                                    languageMenu = false
                                })
                            }
                        }
                    }
                    OutlinedButton(onClick = {
                        val updateIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ooolruuu-creator/OSON-FINANS/releases/latest"))
                        context.startActivity(updateIntent)
                    }, modifier = Modifier.fillMaxWidth()) { Text("Проверить обновления") }
                    Text("Проверка открывает страницу релизов приложения.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    dayLimit = dayInput.toLongOrNull() ?: 0L
                    monthLimit = monthInput.toLongOrNull() ?: 0L
                    prefs.edit().putLong("day_limit", dayLimit).putLong("month_limit", monthLimit).apply()
                    showSettings = false
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Отмена") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopAppBarCompat(title: String, onSettings: () -> Unit) {
    TopAppBar(title = { Text(title, fontWeight = FontWeight.Bold) }, actions = {
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Настройки") }
    })
}

@Composable
private fun LimitCard(title: String, spent: Long, limit: Long, exceeded: Boolean) {
    val ratio = if (limit <= 0) 0f else spent.toFloat() / limit.toFloat()
    val fraction = ratio.coerceIn(0f, 1f)
    val green = Color(0xFF16A34A)
    val orange = Color(0xFFF59E0B)
    val red = Color(0xFFDC2626)
    val barColor = when { ratio > 1f -> red; ratio >= 0.70f -> orange; else -> green }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text("${money(spent)} / ${if (limit <= 0) "лимит не задан" else money(limit) + " сум"}",
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (ratio > 1f) red else MaterialTheme.colorScheme.onSurface)
            if (limit > 0) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = barColor)
            if (ratio > 1f) Text("Лимит превышен на ${money(spent - limit)} сум", color = red, fontWeight = FontWeight.Bold)
            else if (ratio >= 0.70f) Text("Внимание: использовано ${"%.0f".format(ratio * 100)}% лимита", color = orange, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun parseSpeech(raw: String): Triple<String, Long, String>? {
    val s = raw.lowercase(Locale.ROOT)
    val incomeWords = listOf("получил", "получила", "заработал", "заработала", "дали", "подарили", "пришло",
        "oldim", "maosh", "tushdi", "berishdi", "received", "got paid", "salary", "income",
        "收到", "收入", "工资", "bekommen", "gehalt", "einnahme", "मिला", "प्राप्त", "वेतन",
        "recibí", "ingreso", "sueldo", "reçu", "salaire", "revenu", "استلمت", "راتب", "دخل")
    val kind = if (incomeWords.any { s.contains(it) }) "Доход" else "Расход"
    val category = when {
        listOf("такси", "автобус", "метро", "транспорт", "taksi", "yo'l", "taxi", "bus", "train", "transport",
            "出租车", "公交", "地铁", "verkehr", "टैक्सी", "बस", "autobús", "métro", "سيارة أجرة", "حافلة").any { s.contains(it) } -> "Транспорт"
        listOf("обед", "еда", "продукт", "магазин", "кофе", "завтрак", "ужин", "ovqat", "non", "bozor",
            "food", "lunch", "dinner", "groceries", "coffee", "restaurant", "食物", "午餐", "晚餐", "超市",
            "essen", "mittagessen", "lebensmittel", "खाना", "भोजन", "comida", "almuerzo", "cena",
            "nourriture", "déjeuner", "dîner", "طعام", "غداء").any { s.contains(it) } -> "Еда"
        listOf("бензин", "топливо", "заправ", "benzin", "gaz", "fuel", "petrol", "gasoline",
            "汽油", "加油", "kraftstoff", "ईंधन", "gasolina", "carburant", "وقود").any { s.contains(it) } -> "Топливо"
        listOf("одежд", "обув", "куртк", "kiyim", "clothes", "shoes", "kleidung", "服装", "衣服",
            "कपड़े", "ropa", "vêtements", "ملابس").any { s.contains(it) } -> "Одежда"
        listOf("интернет", "телефон", "связь", "internet", "phone", "mobile", "telefon", "电话",
            "मोबाइल", "teléfono", "téléphone", "هاتف").any { s.contains(it) } -> "Связь и интернет"
        listOf("кино", "игра", "развлеч", "kino", "movie", "game", "entertainment", "电影", "娱乐",
            "मनोरंजन", "cine", "divertissement", "ترفيه").any { s.contains(it) } -> "Развлечения"
        listOf("лекар", "врач", "аптек", "dori", "doctor", "medicine", "pharmacy", "arzt", "medizin",
            "药", "医院", "दवा", "डॉक्टर", "medicina", "médecin", "médicament", "دواء", "طبيب").any { s.contains(it) } -> "Здоровье"
        kind == "Доход" -> "Карманные деньги"
        else -> "Покупки"
    }
    val number = Regex("""\d[\d\s.,]*""").find(s)?.value?.replace(Regex("""[\s.,]"""), "")?.toLongOrNull()
    return number?.let { Triple(kind, it, category) }
}
