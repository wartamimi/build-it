package dev.buildit.engine.blocks

import dev.buildit.engine.blocks.BlockCategory.App
import dev.buildit.engine.blocks.BlockCategory.Components
import dev.buildit.engine.blocks.BlockCategory.Control
import dev.buildit.engine.blocks.BlockCategory.Lists
import dev.buildit.engine.blocks.BlockCategory.Logic
import dev.buildit.engine.blocks.BlockCategory.Math
import dev.buildit.engine.blocks.BlockCategory.Parts
import dev.buildit.engine.blocks.BlockCategory.Text

/**
 * The extended block catalog. Most of these blocks are lowered generically from a [CodeTemplate]
 * (one Java and one Kotlin snippet), so adding a block here needs no IR or backend changes. Number
 * values are doubles in both languages; templates convert where an API needs int, long or float.
 */
internal object Catalog {
    private fun n(name: String) = InputDef(name, ValueType.Number)
    private fun t(name: String) = InputDef(name, ValueType.Text)
    private fun b(name: String) = InputDef(name, ValueType.Boolean)
    private fun view(kind: ViewKind = ViewKind.Any) = FieldDef("view", FieldKind.View(kind))
    private fun list(type: ValueType) = FieldDef("list", FieldKind.VariableRef(type))
    private fun map() = FieldDef("map", FieldKind.VariableRef(ValueType.TextMap))

    private fun value(
        type: String, category: BlockCategory, message: String, output: ValueType, java: String, kotlin: String,
        inputs: List<InputDef> = emptyList(), fields: List<FieldDef> = emptyList(), imports: List<String> = emptyList(), helper: TemplateHelper? = null,
    ) = BlockDef(type, category, BlockShape.Value, message, fields, inputs, output = output, code = CodeTemplate(java, kotlin), imports = imports, helper = helper)

    private fun stmt(
        type: String, category: BlockCategory, message: String, java: String, kotlin: String,
        inputs: List<InputDef> = emptyList(), fields: List<FieldDef> = emptyList(), imports: List<String> = emptyList(), helper: TemplateHelper? = null,
        loopOnly: Boolean = false, terminal: Boolean = false,
    ) = BlockDef(type, category, BlockShape.Statement, message, fields, inputs, code = CodeTemplate(java, kotlin), imports = imports, helper = helper, loopOnly = loopOnly, terminal = terminal)

    private val substring = TemplateHelper(
        "substringSafe",
        java = """
            private static String substringSafe(String text, double from, double to) {
                int start = Math.max((int) from - 1, 0);
                int end = Math.min((int) to, text.length());
                return start >= end ? "" : text.substring(start, end);
            }
        """.trimIndent(),
        kotlin = """
            private fun substringSafe(text: String, from: Double, to: Double): String {
                val start = maxOf(from.toInt() - 1, 0)
                val end = minOf(to.toInt(), text.length)
                return if (start >= end) "" else text.substring(start, end)
            }
        """.trimIndent(),
    )

    private val mapGet = TemplateHelper(
        "mapGet",
        java = """
            private static String mapGet(java.util.Map<String, String> map, String key) {
                String value = map.get(key);
                return value == null ? "" : value;
            }
        """.trimIndent(),
        kotlin = null,
    )

    private val sum = TemplateHelper(
        "sumOf",
        java = """
            private static double sumOf(java.util.List<Double> list) {
                double total = 0;
                for (double value : list) total += value;
                return total;
            }
        """.trimIndent(),
        kotlin = null,
    )

    private val hideKeyboard = TemplateHelper(
        "hideKeyboard",
        java = """
            private void hideKeyboard() {
                android.view.View focus = getCurrentFocus();
                if (focus == null) return;
                ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(), 0);
            }
        """.trimIndent(),
        kotlin = """
            private fun hideKeyboard() {
                val focus = currentFocus ?: return
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(focus.windowToken, 0)
            }
        """.trimIndent(),
    )

    private val showKeyboard = TemplateHelper(
        "showKeyboard",
        java = """
            private void showKeyboard(android.view.View view) {
                view.requestFocus();
                ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(view, 0);
            }
        """.trimIndent(),
        kotlin = """
            private fun showKeyboard(view: android.view.View) {
                view.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(view, 0)
            }
        """.trimIndent(),
    )

    private val mathFunctions = listOf("abs", "sqrt", "cbrt", "log", "log10", "exp", "sin", "cos", "tan", "asin", "acos", "atan", "floor", "ceil", "toRadians", "toDegrees")

    private fun comp(type: ComponentType) = dev.buildit.engine.blocks.Components.spec(type).let { FieldDef(it.field, FieldKind.ComponentRef(type)) }

    private fun hat(type: String, message: String, component: ComponentType) =
        BlockDef(type, BlockCategory.Events, BlockShape.Hat, message, fields = listOf(comp(component)), statements = listOf(StatementDef("do", "")))

    private val readTextFile = TemplateHelper(
        "readTextFile",
        java = """
            private String readTextFile(String uri) {
                try (java.io.InputStream in = getContentResolver().openInputStream(android.net.Uri.parse(uri))) {
                    if (in == null) return "";
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return "";
                }
            }
        """.trimIndent(),
        kotlin = """
            private fun readTextFile(uri: String): String = try {
                contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            } catch (e: Exception) {
                ""
            }
        """.trimIndent(),
    )

    private val textExtra = TemplateHelper(
        "textExtra",
        java = """
            private String textExtra(String key) {
                String value = getIntent().getStringExtra(key);
                return value == null ? "" : value;
            }
        """.trimIndent(),
        kotlin = null,
    )

    private val calendarFields = listOf("YEAR", "MONTH", "DAY_OF_MONTH", "HOUR_OF_DAY", "MINUTE", "SECOND")

    /** Blocks and event hats of the Phase 12 components. */
    private val componentBlocks: List<BlockDef> = listOf(
        // Intent
        stmt("intent_set_screen", Parts, "set {intent} to open {activity}", "{intent}.setClass(this, {activity}.class);", "{intent}.setClass(this, {activity}::class.java)",
            fields = listOf(comp(ComponentType.Intent), FieldDef("activity", FieldKind.Activity))),
        stmt("intent_set_action", Parts, "set {intent} to {action} {data}", "{intent}.setAction(Intent.ACTION_{action});\n{intent}.setData(Uri.parse({data}));",
            "{intent}.action = Intent.ACTION_{action}\n{intent}.data = Uri.parse({data})",
            inputs = listOf(t("data")), fields = listOf(comp(ComponentType.Intent), FieldDef("action", FieldKind.Dropdown(listOf("VIEW", "DIAL", "SENDTO", "EDIT")))),
            imports = listOf("android.content.Intent", "android.net.Uri")),
        stmt("intent_put_extra", Parts, "in {intent} put {key} = {value}", "{intent}.putExtra({key}, {value});", "{intent}.putExtra({key}, {value})",
            inputs = listOf(t("key"), t("value")), fields = listOf(comp(ComponentType.Intent))),
        stmt("intent_start", Parts, "start {intent}", "startActivity({intent});", "startActivity({intent})", fields = listOf(comp(ComponentType.Intent))),
        stmt("intent_clear", Parts, "clear {intent}", "{intent} = new Intent();", "{intent} = Intent()", fields = listOf(comp(ComponentType.Intent))),
        value("app_get_extra", App, "value {key} given to this screen", ValueType.Text, "textExtra({key})", "(intent.getStringExtra({key}) ?: \"\")",
            inputs = listOf(t("key")), helper = textExtra),

        // Vibrator
        stmt("vibrator_vibrate", Parts, "vibrate {vibrator} for {ms} ms", "{vibrator}.vibrate((long) {ms});", "{vibrator}.vibrate({ms}.toLong())",
            inputs = listOf(n("ms")), fields = listOf(comp(ComponentType.Vibrator))),
        stmt("vibrator_cancel", Parts, "stop {vibrator}", "{vibrator}.cancel();", "{vibrator}.cancel()", fields = listOf(comp(ComponentType.Vibrator))),

        // Calendar
        stmt("calendar_now", Parts, "set {calendar} to now", "{calendar} = Calendar.getInstance();", "{calendar} = Calendar.getInstance()", fields = listOf(comp(ComponentType.Calendar))),
        stmt("calendar_set_ms", Parts, "set {calendar} to time {ms}", "{calendar}.setTimeInMillis((long) {ms});", "{calendar}.timeInMillis = {ms}.toLong()",
            inputs = listOf(n("ms")), fields = listOf(comp(ComponentType.Calendar))),
        stmt("calendar_add", Parts, "add {amount} {unit} to {calendar}", "{calendar}.add(Calendar.{unit}, (int) {amount});", "{calendar}.add(Calendar.{unit}, {amount}.toInt())",
            inputs = listOf(n("amount")), fields = listOf(comp(ComponentType.Calendar), FieldDef("unit", FieldKind.Dropdown(calendarFields)))),
        stmt("calendar_set", Parts, "set {unit} of {calendar} to {value}", "{calendar}.set(Calendar.{unit}, (int) {value});", "{calendar}.set(Calendar.{unit}, {value}.toInt())",
            inputs = listOf(n("value")), fields = listOf(comp(ComponentType.Calendar), FieldDef("unit", FieldKind.Dropdown(calendarFields)))),
        value("calendar_get", Parts, "{unit} of {calendar}", ValueType.Number, "((double) {calendar}.get(Calendar.{unit}))", "{calendar}.get(Calendar.{unit}).toDouble()",
            fields = listOf(comp(ComponentType.Calendar), FieldDef("unit", FieldKind.Dropdown(calendarFields + "DAY_OF_WEEK")))),
        value("calendar_ms", Parts, "time in ms of {calendar}", ValueType.Number, "((double) {calendar}.getTimeInMillis())", "{calendar}.timeInMillis.toDouble()",
            fields = listOf(comp(ComponentType.Calendar))),
        value("calendar_format", Parts, "{calendar} as {pattern}", ValueType.Text, "new SimpleDateFormat({pattern}, Locale.getDefault()).format({calendar}.getTime())",
            "SimpleDateFormat({pattern}, Locale.getDefault()).format({calendar}.time)",
            inputs = listOf(t("pattern")), fields = listOf(comp(ComponentType.Calendar)), imports = listOf("java.text.SimpleDateFormat", "java.util.Locale")),

        // Animator
        stmt("animator_start", Parts, "with {animator} animate {property} of {view} from {from} to {to} in {ms} ms {mode}",
            "animate{animator^}({view}, \"{property}\", {from}, {to}, {ms}, \"{mode}\".equals(\"repeat\"));",
            "animate{animator^}({view}, \"{property}\", {from}, {to}, {ms}, \"{mode}\" == \"repeat\")",
            inputs = listOf(n("from"), n("to"), n("ms")),
            fields = listOf(comp(ComponentType.Animator), FieldDef("property", FieldKind.Dropdown(listOf("alpha", "translationX", "translationY", "rotation", "scaleX", "scaleY"))),
                view(), FieldDef("mode", FieldKind.Dropdown(listOf("once", "repeat"))))),
        stmt("animator_stop", Parts, "stop {animator}", "stop{animator^}();", "stop{animator^}()", fields = listOf(comp(ComponentType.Animator))),
        hat("event_animator_end", "when {animator} finishes", ComponentType.Animator),

        // Sensor
        stmt("sensor_start", Parts, "start {sensor} reading {kind}", "start{sensor^}(Sensor.TYPE_{kind});", "start{sensor^}(Sensor.TYPE_{kind})",
            fields = listOf(comp(ComponentType.Sensor), FieldDef("kind", FieldKind.Dropdown(listOf("ACCELEROMETER", "GYROSCOPE", "LIGHT", "PROXIMITY", "MAGNETIC_FIELD"))))),
        stmt("sensor_stop", Parts, "stop {sensor}", "stop{sensor^}();", "stop{sensor^}()", fields = listOf(comp(ComponentType.Sensor))),
        hat("event_sensor_change", "when {sensor} changes", ComponentType.Sensor),

        // Text to speech
        stmt("tts_speak", Parts, "say {text} with {speech}", "{speech}.speak({text}, TextToSpeech.QUEUE_FLUSH, null, null);", "{speech}.speak({text}, TextToSpeech.QUEUE_FLUSH, null, null)",
            inputs = listOf(t("text")), fields = listOf(comp(ComponentType.TextToSpeech))),
        stmt("tts_set", Parts, "set {setting} of {speech} to {value}", "{speech}.set{setting}((float) {value});", "{speech}.set{setting}({value}.toFloat())",
            inputs = listOf(n("value")), fields = listOf(comp(ComponentType.TextToSpeech), FieldDef("setting", FieldKind.Dropdown(listOf("Pitch", "SpeechRate"))))),
        stmt("tts_stop", Parts, "stop {speech}", "{speech}.stop();", "{speech}.stop()", fields = listOf(comp(ComponentType.TextToSpeech))),
        value("tts_is_speaking", Parts, "{speech} is speaking", ValueType.Boolean, "{speech}.isSpeaking()", "{speech}.isSpeaking", fields = listOf(comp(ComponentType.TextToSpeech))),

        // Speech to text
        stmt("stt_listen", Parts, "listen with {recognizer} showing {prompt}", "listen{recognizer^}({prompt});", "listen{recognizer^}({prompt})",
            inputs = listOf(t("prompt")), fields = listOf(comp(ComponentType.SpeechToText))),
        hat("event_recognizer_result", "when {recognizer} hears speech", ComponentType.SpeechToText),
        hat("event_recognizer_error", "when {recognizer} fails", ComponentType.SpeechToText),

        // File picker
        stmt("picker_pick", Parts, "pick a file with {picker} of type {type}", "pick{picker^}({type});", "pick{picker^}({type})",
            inputs = listOf(t("type")), fields = listOf(comp(ComponentType.FilePicker))),
        hat("event_picker_picked", "when a file is picked in {picker}", ComponentType.FilePicker),
        value("file_read_text", App, "text of file {file}", ValueType.Text, "readTextFile({file})", "readTextFile({file})", inputs = listOf(t("file")), helper = readTextFile),
        stmt("view_set_image_file", Components, "set image of {view} to file {file}", "{view}.setImageURI(Uri.parse({file}));", "{view}.setImageURI(Uri.parse({file}))",
            inputs = listOf(t("file")), fields = listOf(view(ViewKind.Image)), imports = listOf("android.net.Uri")),

        // Network
        stmt("network_request", Parts, "with {network} send {method} to {url} with body {body}", "request{network^}(\"{method}\", {url}, {body});",
            "request{network^}(\"{method}\", {url}, {body})",
            inputs = listOf(t("url"), t("body")), fields = listOf(comp(ComponentType.Network), FieldDef("method", FieldKind.Dropdown(listOf("GET", "POST", "PUT", "DELETE"))))),
        hat("event_network_response", "when {network} gets a response", ComponentType.Network),
        hat("event_network_error", "when {network} fails", ComponentType.Network),

        // Notification
        stmt("notification_show", Parts, "with {notifier} show notification {title} {text}", "notify{notifier^}({title}, {text});", "notify{notifier^}({title}, {text})",
            inputs = listOf(t("title"), t("text")), fields = listOf(comp(ComponentType.Notification))),

        // Date and time pickers
        stmt("date_picker_show", Parts, "show {datePicker}", "show{datePicker^}();", "show{datePicker^}()", fields = listOf(comp(ComponentType.DatePicker))),
        hat("event_date_picked", "when a date is picked in {datePicker}", ComponentType.DatePicker),
        stmt("time_picker_show", Parts, "show {timePicker}", "show{timePicker^}();", "show{timePicker^}()", fields = listOf(comp(ComponentType.TimePicker))),
        hat("event_time_picked", "when a time is picked in {timePicker}", ComponentType.TimePicker),
    )

    val blocks: List<BlockDef> = componentBlocks + listOf(
        // Control
        BlockDef("ctrl_foreach", Control, BlockShape.Statement, "for each {var} in {list}",
            fields = listOf(FieldDef("var", FieldKind.VariableRef(ValueType.Text)), list(ValueType.TextList)), statements = listOf(StatementDef("do", "do"))),
        BlockDef("ctrl_foreach_number", Control, BlockShape.Statement, "for each number {var} in {list}",
            fields = listOf(FieldDef("var", FieldKind.VariableRef(ValueType.Number)), list(ValueType.NumberList)), statements = listOf(StatementDef("do", "do"))),
        stmt("ctrl_break", Control, "stop the loop", "break;", "break", loopOnly = true, terminal = true),
        stmt("ctrl_continue", Control, "skip to the next round", "continue;", "continue", loopOnly = true, terminal = true),
        stmt("ctrl_return", Control, "stop this event", "return;", "return", terminal = true),

        // Logic
        value("logic_choose", Logic, "if {cond} then {a} else {b}", ValueType.Text, "({cond} ? {a} : {b})", "(if ({cond}) {a} else {b})",
            inputs = listOf(b("cond"), t("a"), t("b"))),
        value("logic_choose_number", Logic, "if {cond} then number {a} else {b}", ValueType.Number, "({cond} ? {a} : {b})", "(if ({cond}) {a} else {b})",
            inputs = listOf(b("cond"), n("a"), n("b"))),
        value("logic_is_empty", Logic, "{text} is empty", ValueType.Boolean, "{text}.isEmpty()", "{text}.isEmpty()", inputs = listOf(t("text"))),
        value("logic_is_number", Logic, "{text} is a number", ValueType.Boolean, "{text}.trim().matches(\"[-+]?(\\\\d+\\\\.?\\\\d*|\\\\.\\\\d+)\")",
            "({text}.trim().toDoubleOrNull() != null)", inputs = listOf(t("text"))),

        // Math
        value("math_function", Math, "{op} of {a}", ValueType.Number, "Math.{op}({a})", "Math.{op}({a})",
            inputs = listOf(n("a")), fields = listOf(FieldDef("op", FieldKind.Dropdown(mathFunctions)))),
        value("math_power", Math, "{a} to the power of {b}", ValueType.Number, "Math.pow({a}, {b})", "Math.pow({a}, {b})", inputs = listOf(n("a"), n("b"))),
        value("math_min_max", Math, "{op} of {a} and {b}", ValueType.Number, "Math.{op}({a}, {b})", "Math.{op}({a}, {b})",
            inputs = listOf(n("a"), n("b")), fields = listOf(FieldDef("op", FieldKind.Dropdown(listOf("min", "max"))))),
        value("math_constant", Math, "{name}", ValueType.Number, "Math.{name}", "Math.{name}", fields = listOf(FieldDef("name", FieldKind.Dropdown(listOf("PI", "E"))))),
        value("math_random_fraction", Math, "random fraction", ValueType.Number, "Math.random()", "Math.random()"),
        value("math_whole", Math, "whole part of {a}", ValueType.Number, "((double) (long) {a})", "{a}.toLong().toDouble()", inputs = listOf(n("a"))),
        value("math_clamp", Math, "{a} kept between {low} and {high}", ValueType.Number, "Math.max({low}, Math.min({high}, {a}))", "{a}.coerceIn(minOf({low}, {high}), maxOf({low}, {high}))",
            inputs = listOf(n("a"), n("low"), n("high"))),
        value("math_format", Math, "{a} with {digits} decimals", ValueType.Text,
            "String.format(Locale.US, \"%.\" + Math.max(0, (int) {digits}) + \"f\", {a})", "String.format(Locale.US, \"%.\" + maxOf(0, {digits}.toInt()) + \"f\", {a})",
            inputs = listOf(n("a"), n("digits")), imports = listOf("java.util.Locale")),

        // Text
        value("text_contains", Text, "{text} contains {part}", ValueType.Boolean, "{text}.contains({part})", "{text}.contains({part})", inputs = listOf(t("text"), t("part"))),
        value("text_starts_with", Text, "{text} starts with {part}", ValueType.Boolean, "{text}.startsWith({part})", "{text}.startsWith({part})", inputs = listOf(t("text"), t("part"))),
        value("text_ends_with", Text, "{text} ends with {part}", ValueType.Boolean, "{text}.endsWith({part})", "{text}.endsWith({part})", inputs = listOf(t("text"), t("part"))),
        value("text_index_of", Text, "position of {part} in {text}", ValueType.Number, "((double) ({text}.indexOf({part}) + 1))", "({text}.indexOf({part}) + 1).toDouble()",
            inputs = listOf(t("part"), t("text"))),
        value("text_substring", Text, "letters # {from} to # {to} of {text}", ValueType.Text, "substringSafe({text}, {from}, {to})", "substringSafe({text}, {from}, {to})",
            inputs = listOf(n("from"), n("to"), t("text")), helper = substring),
        value("text_char_at", Text, "letter # {index} of {text}", ValueType.Text, "substringSafe({text}, {index}, {index})", "substringSafe({text}, {index}, {index})",
            inputs = listOf(n("index"), t("text")), helper = substring),
        value("text_replace", Text, "in {text} replace {find} with {with}", ValueType.Text, "{text}.replace({find}, {with})", "{text}.replace({find}, {with})",
            inputs = listOf(t("text"), t("find"), t("with"))),
        value("text_upper", Text, "{text} in upper case", ValueType.Text, "{text}.toUpperCase()", "{text}.uppercase()", inputs = listOf(t("text"))),
        value("text_lower", Text, "{text} in lower case", ValueType.Text, "{text}.toLowerCase()", "{text}.lowercase()", inputs = listOf(t("text"))),
        value("text_trim", Text, "{text} without outer spaces", ValueType.Text, "{text}.trim()", "{text}.trim()", inputs = listOf(t("text"))),
        value("text_reverse", Text, "{text} reversed", ValueType.Text, "new StringBuilder({text}).reverse().toString()", "{text}.reversed()", inputs = listOf(t("text"))),
        value("text_repeat", Text, "{text} repeated {times} times", ValueType.Text, "repeatText({text}, {times})", "{text}.repeat(maxOf(0, {times}.toInt()))",
            inputs = listOf(t("text"), n("times")),
            helper = TemplateHelper("repeatText", java = """
                private static String repeatText(String text, double times) {
                    StringBuilder out = new StringBuilder();
                    for (int i = 0; i < (int) times; i++) out.append(text);
                    return out.toString();
                }
            """.trimIndent(), kotlin = null)),
        value("text_split", Text, "split {text} at {separator}", ValueType.TextList,
            "new ArrayList<>(Arrays.asList({text}.split(Pattern.quote({separator}), -1)))", "ArrayList({text}.split({separator}))",
            inputs = listOf(t("text"), t("separator")), imports = listOf("java.util.ArrayList", "java.util.Arrays", "java.util.regex.Pattern")),
        value("text_join_list", Text, "items of {list} joined with {separator}", ValueType.Text, "TextUtils.join({separator}, {list})", "{list}.joinToString({separator})",
            inputs = listOf(t("separator")), fields = listOf(list(ValueType.TextList)), imports = listOf("android.text.TextUtils")),
        value("text_new_line", Text, "new line", ValueType.Text, "\"\\n\"", "\"\\n\""),

        // Lists of text
        stmt("list_insert", Lists, "insert {item} at # {index} of {list}", "{list}.add((int) {index} - 1, {item});", "{list}.add({index}.toInt() - 1, {item})",
            inputs = listOf(t("item"), n("index")), fields = listOf(list(ValueType.TextList))),
        stmt("list_set", Lists, "set item # {index} of {list} to {item}", "{list}.set((int) {index} - 1, {item});", "{list}[{index}.toInt() - 1] = {item}",
            inputs = listOf(n("index"), t("item")), fields = listOf(list(ValueType.TextList))),
        stmt("list_remove", Lists, "remove item # {index} of {list}", "{list}.remove((int) {index} - 1);", "{list}.removeAt({index}.toInt() - 1)",
            inputs = listOf(n("index")), fields = listOf(list(ValueType.TextList))),
        value("list_contains", Lists, "{list} contains {item}", ValueType.Boolean, "{list}.contains({item})", "{list}.contains({item})",
            inputs = listOf(t("item")), fields = listOf(list(ValueType.TextList))),
        value("list_index_of", Lists, "position of {item} in {list}", ValueType.Number, "((double) ({list}.indexOf({item}) + 1))", "({list}.indexOf({item}) + 1).toDouble()",
            inputs = listOf(t("item")), fields = listOf(list(ValueType.TextList))),
        stmt("list_sort", Lists, "sort {list}", "Collections.sort({list});", "{list}.sort()", fields = listOf(list(ValueType.TextList)), imports = listOf("java.util.Collections")),
        stmt("list_shuffle", Lists, "shuffle {list}", "Collections.shuffle({list});", "{list}.shuffle()", fields = listOf(list(ValueType.TextList)), imports = listOf("java.util.Collections")),
        stmt("list_set_all", Lists, "set {list} to {items}", "{list}.clear();\n{list}.addAll({items});", "{list}.clear()\n{list}.addAll({items})",
            inputs = listOf(InputDef("items", ValueType.TextList)), fields = listOf(list(ValueType.TextList))),

        // Lists of numbers
        stmt("nlist_add", Lists, "add number {item} to {list}", "{list}.add({item});", "{list}.add({item})", inputs = listOf(n("item")), fields = listOf(list(ValueType.NumberList))),
        value("nlist_get", Lists, "number # {index} of {list}", ValueType.Number, "{list}.get((int) {index} - 1).doubleValue()", "{list}[{index}.toInt() - 1]",
            inputs = listOf(n("index")), fields = listOf(list(ValueType.NumberList))),
        stmt("nlist_set", Lists, "set number # {index} of {list} to {item}", "{list}.set((int) {index} - 1, {item});", "{list}[{index}.toInt() - 1] = {item}",
            inputs = listOf(n("index"), n("item")), fields = listOf(list(ValueType.NumberList))),
        stmt("nlist_remove", Lists, "remove number # {index} of {list}", "{list}.remove((int) {index} - 1);", "{list}.removeAt({index}.toInt() - 1)",
            inputs = listOf(n("index")), fields = listOf(list(ValueType.NumberList))),
        value("nlist_size", Lists, "count of {list}", ValueType.Number, "((double) {list}.size())", "{list}.size.toDouble()", fields = listOf(list(ValueType.NumberList))),
        value("nlist_contains", Lists, "{list} contains number {item}", ValueType.Boolean, "{list}.contains({item})", "{list}.contains({item})",
            inputs = listOf(n("item")), fields = listOf(list(ValueType.NumberList))),
        value("nlist_sum", Lists, "sum of {list}", ValueType.Number, "sumOf({list})", "{list}.sum()", fields = listOf(list(ValueType.NumberList)), helper = sum),
        stmt("nlist_sort", Lists, "sort numbers {list}", "Collections.sort({list});", "{list}.sort()", fields = listOf(list(ValueType.NumberList)), imports = listOf("java.util.Collections")),
        stmt("nlist_clear", Lists, "clear numbers {list}", "{list}.clear();", "{list}.clear()", fields = listOf(list(ValueType.NumberList))),

        // Maps
        stmt("map_put", Lists, "in {map} set {key} to {value}", "{map}.put({key}, {value});", "{map}[{key}] = {value}", inputs = listOf(t("key"), t("value")), fields = listOf(map())),
        value("map_get", Lists, "{key} of {map}", ValueType.Text, "mapGet({map}, {key})", "({map}[{key}] ?: \"\")", inputs = listOf(t("key")), fields = listOf(map()), helper = mapGet),
        value("map_has", Lists, "{map} has key {key}", ValueType.Boolean, "{map}.containsKey({key})", "{map}.containsKey({key})", inputs = listOf(t("key")), fields = listOf(map())),
        stmt("map_remove", Lists, "in {map} remove {key}", "{map}.remove({key});", "{map}.remove({key})", inputs = listOf(t("key")), fields = listOf(map())),
        value("map_size", Lists, "number of keys in {map}", ValueType.Number, "((double) {map}.size())", "{map}.size.toDouble()", fields = listOf(map())),
        value("map_keys", Lists, "keys of {map}", ValueType.TextList, "new ArrayList<>({map}.keySet())", "ArrayList({map}.keys)", fields = listOf(map()), imports = listOf("java.util.ArrayList")),
        stmt("map_clear", Lists, "clear {map}", "{map}.clear();", "{map}.clear()", fields = listOf(map())),

        // Views
        stmt("view_set_background", Components, "set background of {view} to {color}", "{view}.setBackgroundColor(Color.parseColor({color}));",
            "{view}.setBackgroundColor(Color.parseColor({color}))", fields = listOf(view(), FieldDef("color", FieldKind.Color("#FFFFFFFF"))), imports = listOf("android.graphics.Color")),
        stmt("view_set_text_size", Components, "set text size of {view} to {size}", "{view}.setTextSize((float) {size});", "{view}.setTextSize({size}.toFloat())",
            inputs = listOf(n("size")), fields = listOf(view(ViewKind.Text))),
        stmt("view_set_hint", Components, "set hint of {view} to {text}", "{view}.setHint({text});", "{view}.hint = {text}", inputs = listOf(t("text")), fields = listOf(view(ViewKind.Text))),
        stmt("view_set_image", Components, "set image of {view} to {image}", "{view}.setImageResource(R.drawable.{image});", "{view}.setImageResource(R.drawable.{image})",
            fields = listOf(view(ViewKind.Image), FieldDef("image", FieldKind.DrawableResource))),
        stmt("view_set_alpha", Components, "set opacity of {view} to {alpha}", "{view}.setAlpha((float) {alpha});", "{view}.alpha = {alpha}.toFloat()",
            inputs = listOf(n("alpha")), fields = listOf(view())),
        stmt("view_set_rotation", Components, "rotate {view} to {degrees} degrees", "{view}.setRotation((float) {degrees});", "{view}.rotation = {degrees}.toFloat()",
            inputs = listOf(n("degrees")), fields = listOf(view())),
        stmt("view_set_progress", Components, "set progress of {view} to {value}", "{view}.setProgress((int) {value});", "{view}.progress = {value}.toInt()",
            inputs = listOf(n("value")), fields = listOf(view(ViewKind.Progress))),
        value("view_get_progress", Components, "progress of {view}", ValueType.Number, "((double) {view}.getProgress())", "{view}.progress.toDouble()", fields = listOf(view(ViewKind.Progress))),
        value("view_is_visible", Components, "{view} is visible", ValueType.Boolean, "({view}.getVisibility() == View.VISIBLE)", "({view}.visibility == View.VISIBLE)",
            fields = listOf(view()), imports = listOf("android.view.View")),
        stmt("view_focus", Components, "focus {view} and show keyboard", "showKeyboard({view});", "showKeyboard({view})", fields = listOf(view()), helper = showKeyboard),
        stmt("view_hide_keyboard", Components, "hide the keyboard", "hideKeyboard();", "hideKeyboard()", helper = hideKeyboard),

        // App
        stmt("app_toast_long", App, "show long message {text}", "Toast.makeText(this, {text}, Toast.LENGTH_LONG).show();", "Toast.makeText(this, {text}, Toast.LENGTH_LONG).show()",
            inputs = listOf(t("text")), imports = listOf("android.widget.Toast")),
        stmt("app_set_title", App, "set screen title to {text}", "setTitle({text});", "title = {text}", inputs = listOf(t("text"))),
        stmt("app_copy", App, "copy {text} to the clipboard",
            "((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(\"text\", {text}));",
            "(getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(\"text\", {text}))",
            inputs = listOf(t("text")), imports = listOf("android.content.ClipData", "android.content.ClipboardManager")),
        stmt("app_share", App, "share text {text}",
            "startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType(\"text/plain\").putExtra(Intent.EXTRA_TEXT, {text}), null));",
            "startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(\"text/plain\").putExtra(Intent.EXTRA_TEXT, {text}), null))",
            inputs = listOf(t("text")), imports = listOf("android.content.Intent")),
        value("app_now", App, "current time in ms", ValueType.Number, "((double) System.currentTimeMillis())", "System.currentTimeMillis().toDouble()"),
        value("app_format_time", App, "time {ms} as {pattern}", ValueType.Text,
            "new SimpleDateFormat({pattern}, Locale.getDefault()).format(new Date((long) {ms}))", "SimpleDateFormat({pattern}, Locale.getDefault()).format(Date({ms}.toLong()))",
            inputs = listOf(n("ms"), t("pattern")), imports = listOf("java.text.SimpleDateFormat", "java.util.Date", "java.util.Locale")),
    )
}
