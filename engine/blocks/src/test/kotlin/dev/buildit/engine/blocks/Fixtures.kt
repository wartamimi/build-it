package dev.buildit.engine.blocks

import dev.buildit.engine.project.AppConfig
import dev.buildit.engine.project.ProjectManifest
import dev.buildit.engine.project.SourceLanguage

class Ids {
    private var n = 0
    fun next(): String = "b${++n}"
}

fun b(id: String, type: String, fields: Map<String, String> = emptyMap(), inputs: Map<String, Block> = emptyMap(), statements: Map<String, List<Block>> = emptyMap()) =
    Block(id, type, fields, inputs, statements)

fun num(id: String, v: Double) = b(id, "math_number", mapOf("num" to v.toString()))
fun txt(id: String, v: String) = b(id, "text_literal", mapOf("text" to v))
fun get(id: String, v: String) = b(id, "var_get", mapOf("var" to v))

val views = listOf(ViewInfo("greeting", "TextView"), ViewInfo("button1", "Button"), ViewInfo("agree", "CheckBox"), ViewInfo("photo", "ImageView"))
val ctx = BlockContext(views, activities = listOf("MainActivity", "SettingsActivity"), sounds = listOf("ding"))

fun manifest(language: SourceLanguage) = ProjectManifest(
    id = "p1",
    name = "Demo",
    app = AppConfig(packageName = "com.example.demo", language = language),
    createdAt = 0L,
    updatedAt = 0L,
).let { m ->
    m.copy(modules = m.modules.map { mod -> mod.copy(activities = mod.activities + dev.buildit.engine.project.ActivityConfig("SettingsActivity", "activity_settings")) })
}

/** A program exercising every statement and expression kind. */
fun sampleDocument(): BlockDocument = BlockDocument(
    activity = "MainActivity",
    variables = listOf(Variable("count", ValueType.Number), Variable("name", ValueType.Text), Variable("done", ValueType.Boolean), Variable("items", ValueType.TextList)),
    stacks = listOf(
        BlockStack(0f, 0f, listOf(b("e1", "event_create", statements = mapOf("do" to listOf(
            b("s1", "var_set", mapOf("var" to "name"), mapOf("value" to txt("t1", "Ada \"$\" \\ \n"))),
            b("s2", "view_set_text", mapOf("view" to "greeting"), mapOf("text" to b("j1", "text_join", inputs = mapOf("a" to txt("t2", "Count: "), "b" to get("g1", "count"))))),
            b("s3", "ctrl_repeat", inputs = mapOf("times" to num("n1", 3.0)), statements = mapOf("do" to listOf(
                b("s4", "list_add", mapOf("list" to "items"), mapOf("item" to b("r1", "math_random", inputs = mapOf("from" to num("n2", 1.0), "to" to num("n3", 6.0))))),
            ))),
            b("s5", "ctrl_for", mapOf("var" to "count"), mapOf("from" to num("n4", 1.0), "to" to b("ls", "list_size", mapOf("list" to "items"))), mapOf("do" to listOf(
                b("s6", "app_log", inputs = mapOf("text" to b("lg", "list_get", mapOf("list" to "items"), mapOf("index" to get("g2", "count"))))),
            ))),
            b("s7", "var_set", mapOf("var" to "count"), mapOf("value" to b("p1", "math_parse", inputs = mapOf("text" to b("vt", "view_get_text", mapOf("view" to "greeting")))))),
            b("s8", "view_set_text_color", mapOf("view" to "greeting", "color" to "#FF336699")),
        ))))),
        BlockStack(0f, 300f, listOf(b("e2", "event_click", mapOf("view" to "button1"), statements = mapOf("do" to listOf(
            b("s9", "var_change", mapOf("var" to "count"), mapOf("by" to num("n5", 1.5))),
            b("s10", "ctrl_if_else", inputs = mapOf("cond" to b("c1", "logic_op", mapOf("op" to "and"), mapOf(
                "a" to b("c2", "logic_compare", mapOf("op" to "≥"), mapOf("a" to get("g3", "count"), "b" to num("n6", 5.0))),
                "b" to b("c3", "logic_not", inputs = mapOf("a" to get("g4", "done"))),
            ))), statements = mapOf(
                "then" to listOf(
                    b("s11", "app_toast", inputs = mapOf("text" to b("rd", "math_round", inputs = mapOf("a" to b("ar", "math_arith", mapOf("op" to "÷"), mapOf("a" to get("g5", "count"), "b" to num("n7", 2.0))))))),
                    b("s12", "proc_call", mapOf("name" to "reset")),
                ),
                "else" to listOf(b("s13", "app_start", mapOf("activity" to "SettingsActivity"))),
            )),
        ))))),
        BlockStack(0f, 600f, listOf(b("e3", "event_checked", mapOf("view" to "agree"), statements = mapOf("do" to listOf(
            b("s14", "var_set", mapOf("var" to "done"), mapOf("value" to b("ic", "view_is_checked", mapOf("view" to "agree")))),
            b("s15", "view_set_enabled", mapOf("view" to "button1"), mapOf("enabled" to get("g6", "done"))),
            b("s16", "view_set_visibility", mapOf("view" to "photo", "visibility" to "gone")),
            b("s17", "ctrl_while", inputs = mapOf("cond" to b("te", "logic_text_equals", inputs = mapOf("a" to get("g7", "name"), "b" to b("ce", "code_expr", mapOf("code" to "\"x\""))))), statements = mapOf("do" to listOf(
                b("s18", "code_stmt", mapOf("code" to "name = \"y\";")),
            ))),
        ))))),
        BlockStack(0f, 900f, listOf(b("e4", "proc_define", mapOf("name" to "reset"), statements = mapOf("do" to listOf(
            b("s19", "list_clear", mapOf("list" to "items")),
            b("s20", "view_set_checked", mapOf("view" to "agree"), mapOf("checked" to b("bf", "logic_bool", mapOf("value" to "false")))),
            b("s21", "var_set", mapOf("var" to "count"), mapOf("value" to b("tl", "text_length", inputs = mapOf("text" to get("g8", "name"))))),
        ))))),
        BlockStack(0f, 1200f, listOf(b("e5", "event_pause", statements = mapOf("do" to listOf(b("s22", "app_finish")))))),
        // Components
        BlockStack(0f, 1500f, listOf(b("e6", "event_resume", statements = mapOf("do" to listOf(
            b("s23", "timer_start", mapOf("timer" to "ticker", "mode" to "repeating"), mapOf("ms" to num("n8", 1000.0))),
            b("s24", "dialog_show", mapOf("dialog" to "confirm"), mapOf("title" to txt("t3", "Hi"), "message" to b("sg", "storage_get", mapOf("store" to "prefs"), mapOf("key" to txt("t4", "last"))))),
        ))))),
        BlockStack(0f, 1800f, listOf(b("e7", "event_timer", mapOf("timer" to "ticker"), statements = mapOf("do" to listOf(
            b("s25", "timer_stop", mapOf("timer" to "ticker")),
            b("s26", "storage_set", mapOf("store" to "prefs"), mapOf("value" to get("g9", "count"), "key" to txt("t5", "last"))),
            b("s27", "storage_remove", mapOf("store" to "prefs"), mapOf("key" to txt("t6", "old"))),
        ))))),
        BlockStack(0f, 2100f, listOf(b("e8", "event_dialog_ok", mapOf("dialog" to "confirm"), statements = mapOf("do" to listOf(
            b("s28", "sound_play", mapOf("sound" to "player", "file" to "ding")),
        ))))),
        BlockStack(0f, 2400f, listOf(b("e9", "event_sound_done", mapOf("sound" to "player"), statements = mapOf("do" to listOf(
            b("s29", "sound_stop", mapOf("sound" to "player")),
            b("s30", "app_open_link", inputs = mapOf("url" to txt("t7", "https://example.com"))),
        ))))),
    ),
    components = listOf(
        Component("ticker", ComponentType.Timer), Component("prefs", ComponentType.Storage),
        Component("confirm", ComponentType.Dialog), Component("player", ComponentType.Sound),
    ),
)
