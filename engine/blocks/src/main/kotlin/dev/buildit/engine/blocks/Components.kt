package dev.buildit.engine.blocks

/** An event a component raises: a hat block of type [hat] whose body becomes `on<Name><suffix>(params)`. */
data class ComponentEvent(val hat: String, val suffix: String, val params: List<ProcParam> = emptyList())

/**
 * Generated code of a component in one language. `{name}` is the component's name, `{Name}` the
 * same capitalised, `{REQ}` a request code unique in the screen. [result] runs inside
 * onActivityResult (with `requestCode`, `resultCode` and `data` in scope).
 */
data class ComponentCode(
    val field: String? = null,
    val init: String? = null,
    val members: String? = null,
    val cleanup: String? = null,
    val result: String? = null,
)

/**
 * What a component type needs: the field its blocks use to name it, its events, and (for
 * components declared here rather than printed by the backends directly) its code, imports and
 * manifest permissions.
 */
data class ComponentSpec(
    val type: ComponentType,
    /** Name of the [FieldKind.ComponentRef] field in this component's blocks and event hats. */
    val field: String,
    val events: List<ComponentEvent> = emptyList(),
    val java: ComponentCode? = null,
    val kotlin: ComponentCode? = null,
    val imports: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
)

object Components {
    private fun n(name: String) = ProcParam(name, ValueType.Number)
    private fun t(name: String) = ProcParam(name, ValueType.Text)

    val specs: List<ComponentSpec> = listOf(
        // Printed by the backends directly (Phase 10)
        ComponentSpec(ComponentType.Timer, "timer", listOf(ComponentEvent("event_timer", "Tick"))),
        ComponentSpec(ComponentType.Storage, "store"),
        ComponentSpec(ComponentType.Dialog, "dialog", listOf(ComponentEvent("event_dialog_ok", "Ok"))),
        ComponentSpec(ComponentType.Sound, "sound", listOf(ComponentEvent("event_sound_done", "Done"))),

        ComponentSpec(
            ComponentType.Intent, "intent",
            java = ComponentCode(field = "private Intent {name} = new Intent();"),
            kotlin = ComponentCode(field = "private var {name} = Intent()"),
            imports = listOf("android.content.Intent"),
        ),
        ComponentSpec(
            ComponentType.Vibrator, "vibrator",
            java = ComponentCode(field = "private Vibrator {name};", init = "{name} = (Vibrator) getSystemService(VIBRATOR_SERVICE);", cleanup = "{name}.cancel();"),
            kotlin = ComponentCode(field = "private lateinit var {name}: Vibrator", init = "{name} = getSystemService(VIBRATOR_SERVICE) as Vibrator", cleanup = "{name}.cancel()"),
            imports = listOf("android.os.Vibrator"),
            permissions = listOf("android.permission.VIBRATE"),
        ),
        ComponentSpec(
            ComponentType.Calendar, "calendar",
            java = ComponentCode(field = "private Calendar {name} = Calendar.getInstance();"),
            kotlin = ComponentCode(field = "private var {name}: Calendar = Calendar.getInstance()"),
            imports = listOf("java.util.Calendar"),
        ),
        ComponentSpec(
            ComponentType.Animator, "animator", listOf(ComponentEvent("event_animator_end", "End")),
            java = ComponentCode(
                field = "private ObjectAnimator {name};",
                members = """
                    private void animate{Name}(View view, String property, double from, double to, double ms, boolean repeat) {
                        stop{Name}();
                        final ObjectAnimator animator = ObjectAnimator.ofFloat(view, property, (float) from, (float) to);
                        animator.setDuration((long) Math.max(ms, 0));
                        animator.setRepeatCount(repeat ? ObjectAnimator.INFINITE : 0);
                        animator.setRepeatMode(ObjectAnimator.REVERSE);
                        animator.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                if (animation == {name}) {
                                    {name} = null;
                                    on{Name}End();
                                }
                            }
                        });
                        {name} = animator;
                        animator.start();
                    }

                    private void stop{Name}() {
                        ObjectAnimator running = {name};
                        {name} = null;
                        if (running != null) running.cancel();
                    }
                """.trimIndent(),
                cleanup = "stop{Name}();",
            ),
            kotlin = ComponentCode(
                field = "private var {name}: ObjectAnimator? = null",
                members = """
                    private fun animate{Name}(view: View, property: String, from: Double, to: Double, ms: Double, repeat: Boolean) {
                        stop{Name}()
                        val animator = ObjectAnimator.ofFloat(view, property, from.toFloat(), to.toFloat())
                        animator.duration = maxOf(ms, 0.0).toLong()
                        animator.repeatCount = if (repeat) ObjectAnimator.INFINITE else 0
                        animator.repeatMode = ObjectAnimator.REVERSE
                        animator.addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                if (animation === {name}) {
                                    {name} = null
                                    on{Name}End()
                                }
                            }
                        })
                        {name} = animator
                        animator.start()
                    }

                    private fun stop{Name}() {
                        val running = {name}
                        {name} = null
                        running?.cancel()
                    }
                """.trimIndent(),
                cleanup = "stop{Name}()",
            ),
            imports = listOf("android.animation.Animator", "android.animation.AnimatorListenerAdapter", "android.animation.ObjectAnimator", "android.view.View"),
        ),
        ComponentSpec(
            ComponentType.Sensor, "sensor", listOf(ComponentEvent("event_sensor_change", "Change", listOf(n("x"), n("y"), n("z")))),
            java = ComponentCode(
                field = "private SensorManager {name}Manager;\nprivate SensorEventListener {name};",
                init = "{name}Manager = (SensorManager) getSystemService(SENSOR_SERVICE);",
                members = """
                    private void start{Name}(int type) {
                        stop{Name}();
                        Sensor sensor = {name}Manager.getDefaultSensor(type);
                        if (sensor == null) return;
                        {name} = new SensorEventListener() {
                            @Override
                            public void onSensorChanged(SensorEvent event) {
                                float[] v = event.values;
                                on{Name}Change(v[0], v.length > 1 ? v[1] : 0, v.length > 2 ? v[2] : 0);
                            }

                            @Override
                            public void onAccuracyChanged(Sensor s, int accuracy) {
                            }
                        };
                        {name}Manager.registerListener({name}, sensor, SensorManager.SENSOR_DELAY_UI);
                    }

                    private void stop{Name}() {
                        if ({name} != null) {
                            {name}Manager.unregisterListener({name});
                            {name} = null;
                        }
                    }
                """.trimIndent(),
                cleanup = "stop{Name}();",
            ),
            kotlin = ComponentCode(
                field = "private lateinit var {name}Manager: SensorManager\nprivate var {name}: SensorEventListener? = null",
                init = "{name}Manager = getSystemService(SENSOR_SERVICE) as SensorManager",
                members = """
                    private fun start{Name}(type: Int) {
                        stop{Name}()
                        val sensor = {name}Manager.getDefaultSensor(type) ?: return
                        val listener = object : SensorEventListener {
                            override fun onSensorChanged(event: SensorEvent) {
                                val v = event.values
                                on{Name}Change(v[0].toDouble(), v.getOrElse(1) { 0f }.toDouble(), v.getOrElse(2) { 0f }.toDouble())
                            }

                            override fun onAccuracyChanged(s: Sensor, accuracy: Int) = Unit
                        }
                        {name} = listener
                        {name}Manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                    }

                    private fun stop{Name}() {
                        {name}?.let { {name}Manager.unregisterListener(it) }
                        {name} = null
                    }
                """.trimIndent(),
                cleanup = "stop{Name}()",
            ),
            imports = listOf("android.hardware.Sensor", "android.hardware.SensorEvent", "android.hardware.SensorEventListener", "android.hardware.SensorManager"),
        ),
        ComponentSpec(
            ComponentType.TextToSpeech, "speech",
            java = ComponentCode(field = "private TextToSpeech {name};", init = "{name} = new TextToSpeech(this, status -> { });", cleanup = "{name}.shutdown();"),
            kotlin = ComponentCode(field = "private lateinit var {name}: TextToSpeech", init = "{name} = TextToSpeech(this) { }", cleanup = "{name}.shutdown()"),
            imports = listOf("android.speech.tts.TextToSpeech"),
        ),
        ComponentSpec(
            ComponentType.SpeechToText, "recognizer",
            listOf(ComponentEvent("event_recognizer_result", "Result", listOf(t("text"))), ComponentEvent("event_recognizer_error", "Error", listOf(t("message")))),
            java = ComponentCode(
                members = """
                    private void listen{Name}(String prompt) {
                        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt);
                        try {
                            startActivityForResult(intent, {REQ});
                        } catch (ActivityNotFoundException e) {
                            on{Name}Error("Speech input is not available on this device");
                        }
                    }
                """.trimIndent(),
                result = """
                    if (requestCode == {REQ}) {
                        java.util.ArrayList<String> results = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                        if (resultCode == RESULT_OK && results != null && !results.isEmpty()) on{Name}Result(results.get(0));
                        else on{Name}Error("No speech recognised");
                    }
                """.trimIndent(),
            ),
            kotlin = ComponentCode(
                members = """
                    private fun listen{Name}(prompt: String) {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                        try {
                            startActivityForResult(intent, {REQ})
                        } catch (e: ActivityNotFoundException) {
                            on{Name}Error("Speech input is not available on this device")
                        }
                    }
                """.trimIndent(),
                result = """
                    if (requestCode == {REQ}) {
                        val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                        if (resultCode == RESULT_OK && !results.isNullOrEmpty()) on{Name}Result(results[0]) else on{Name}Error("No speech recognised")
                    }
                """.trimIndent(),
            ),
            imports = listOf("android.content.ActivityNotFoundException", "android.content.Intent", "android.speech.RecognizerIntent"),
        ),
        ComponentSpec(
            ComponentType.FilePicker, "picker", listOf(ComponentEvent("event_picker_picked", "Picked", listOf(t("file")))),
            java = ComponentCode(
                members = """
                    private void pick{Name}(String type) {
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.setType(type.isEmpty() ? "*/*" : type);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        try {
                            startActivityForResult(intent, {REQ});
                        } catch (ActivityNotFoundException e) {
                            Log.w("BuildIt", "No app can pick files", e);
                        }
                    }
                """.trimIndent(),
                result = "if (requestCode == {REQ} && resultCode == RESULT_OK && data != null && data.getData() != null) on{Name}Picked(data.getData().toString());",
            ),
            kotlin = ComponentCode(
                members = """
                    private fun pick{Name}(type: String) {
                        val intent = Intent(Intent.ACTION_GET_CONTENT)
                        intent.type = type.ifEmpty { "*/*" }
                        intent.addCategory(Intent.CATEGORY_OPENABLE)
                        try {
                            startActivityForResult(intent, {REQ})
                        } catch (e: ActivityNotFoundException) {
                            Log.w("BuildIt", "No app can pick files", e)
                        }
                    }
                """.trimIndent(),
                result = "if (requestCode == {REQ} && resultCode == RESULT_OK) data?.data?.let { on{Name}Picked(it.toString()) }",
            ),
            imports = listOf("android.content.ActivityNotFoundException", "android.content.Intent", "android.util.Log"),
        ),
        ComponentSpec(
            ComponentType.Network, "network",
            listOf(ComponentEvent("event_network_response", "Response", listOf(t("response"), n("code"))), ComponentEvent("event_network_error", "Error", listOf(t("message")))),
            java = ComponentCode(
                members = """
                    private void request{Name}(final String method, final String url, final String body) {
                        new Thread(() -> {
                            try {
                                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                                connection.setRequestMethod(method);
                                connection.setConnectTimeout(15000);
                                connection.setReadTimeout(15000);
                                if (!body.isEmpty() && !method.equals("GET")) {
                                    connection.setDoOutput(true);
                                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                                    try (OutputStream out = connection.getOutputStream()) {
                                        out.write(body.getBytes(StandardCharsets.UTF_8));
                                    }
                                }
                                final int code = connection.getResponseCode();
                                InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                                final String text = in == null ? "" : readAll{Name}(in);
                                runOnUiThread(() -> on{Name}Response(text, code));
                            } catch (final Exception e) {
                                runOnUiThread(() -> on{Name}Error(String.valueOf(e.getMessage())));
                            }
                        }).start();
                    }

                    private static String readAll{Name}(InputStream in) throws java.io.IOException {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                        in.close();
                        return new String(out.toByteArray(), StandardCharsets.UTF_8);
                    }
                """.trimIndent(),
            ),
            kotlin = ComponentCode(
                members = """
                    private fun request{Name}(method: String, url: String, body: String) {
                        Thread {
                            try {
                                val connection = URL(url).openConnection() as HttpURLConnection
                                connection.requestMethod = method
                                connection.connectTimeout = 15000
                                connection.readTimeout = 15000
                                if (body.isNotEmpty() && method != "GET") {
                                    connection.doOutput = true
                                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                                    connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                                }
                                val code = connection.responseCode
                                val stream: InputStream? = if (code >= 400) connection.errorStream else connection.inputStream
                                val text = stream?.use { String(it.readBytes(), StandardCharsets.UTF_8) } ?: ""
                                runOnUiThread { on{Name}Response(text, code.toDouble()) }
                            } catch (e: Exception) {
                                runOnUiThread { on{Name}Error(e.message ?: e.toString()) }
                            }
                        }.start()
                    }
                """.trimIndent(),
            ),
            imports = listOf("java.io.ByteArrayOutputStream", "java.io.InputStream", "java.io.OutputStream", "java.net.HttpURLConnection", "java.net.URL", "java.nio.charset.StandardCharsets"),
            permissions = listOf("android.permission.INTERNET"),
        ),
        ComponentSpec(
            ComponentType.Notification, "notifier",
            java = ComponentCode(
                members = """
                    private void notify{Name}(String title, String text) {
                        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, {REQ});
                            return;
                        }
                        Notification.Builder builder;
                        if (Build.VERSION.SDK_INT >= 26) {
                            manager.createNotificationChannel(new NotificationChannel("{name}", getTitle(), NotificationManager.IMPORTANCE_DEFAULT));
                            builder = new Notification.Builder(this, "{name}");
                        } else {
                            builder = new Notification.Builder(this);
                        }
                        builder.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text).setAutoCancel(true);
                        manager.notify({REQ}, builder.build());
                    }
                """.trimIndent(),
            ),
            kotlin = ComponentCode(
                members = """
                    @Suppress("DEPRECATION")
                    private fun notify{Name}(title: String, text: String) {
                        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), {REQ})
                            return
                        }
                        val builder = if (Build.VERSION.SDK_INT >= 26) {
                            manager.createNotificationChannel(NotificationChannel("{name}", title, NotificationManager.IMPORTANCE_DEFAULT))
                            Notification.Builder(this, "{name}")
                        } else {
                            Notification.Builder(this)
                        }
                        builder.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text).setAutoCancel(true)
                        manager.notify({REQ}, builder.build())
                    }
                """.trimIndent(),
            ),
            imports = listOf("android.app.Notification", "android.app.NotificationChannel", "android.app.NotificationManager", "android.content.pm.PackageManager", "android.os.Build"),
            permissions = listOf("android.permission.POST_NOTIFICATIONS"),
        ),
        ComponentSpec(
            ComponentType.DatePicker, "datePicker", listOf(ComponentEvent("event_date_picked", "Picked", listOf(n("year"), n("month"), n("day")))),
            java = ComponentCode(
                members = """
                    private void show{Name}() {
                        Calendar now = Calendar.getInstance();
                        new DatePickerDialog(this, (view, year, month, day) -> on{Name}Picked(year, month + 1, day),
                            now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show();
                    }
                """.trimIndent(),
            ),
            kotlin = ComponentCode(
                members = """
                    private fun show{Name}() {
                        val now = Calendar.getInstance()
                        DatePickerDialog(this, { _, year, month, day -> on{Name}Picked(year.toDouble(), (month + 1).toDouble(), day.toDouble()) },
                            now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
                    }
                """.trimIndent(),
            ),
            imports = listOf("android.app.DatePickerDialog", "java.util.Calendar"),
        ),
        ComponentSpec(
            ComponentType.TimePicker, "timePicker", listOf(ComponentEvent("event_time_picked", "Picked", listOf(n("hour"), n("minute")))),
            java = ComponentCode(
                members = """
                    private void show{Name}() {
                        Calendar now = Calendar.getInstance();
                        new TimePickerDialog(this, (view, hour, minute) -> on{Name}Picked(hour, minute),
                            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show();
                    }
                """.trimIndent(),
            ),
            kotlin = ComponentCode(
                members = """
                    private fun show{Name}() {
                        val now = Calendar.getInstance()
                        TimePickerDialog(this, { _, hour, minute -> on{Name}Picked(hour.toDouble(), minute.toDouble()) },
                            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
                    }
                """.trimIndent(),
            ),
            imports = listOf("android.app.TimePickerDialog", "java.util.Calendar"),
        ),
    )

    private val byType = specs.associateBy { it.type }
    private val byHat = specs.flatMap { s -> s.events.map { it.hat to (s to it) } }.toMap()

    init { require(byType.keys == ComponentType.entries.toSet()) { "Every component type needs a spec" } }

    fun spec(type: ComponentType): ComponentSpec = byType.getValue(type)

    /** The component and event behind an event hat type, or null for other blocks. */
    fun event(hat: String): Pair<ComponentSpec, ComponentEvent>? = byHat[hat]

    /** Manifest permissions the components of [doc] need. */
    fun permissions(doc: BlockDocument): Set<String> = doc.components.flatMap { spec(it.type).permissions }.toSet()

    fun capitalized(name: String) = name.replaceFirstChar { it.uppercase() }

    /** Fills `{name}`, `{Name}` and `{REQ}` in component code. */
    fun fill(code: String, name: String, requestCode: Int) =
        code.replace("{name}", name).replace("{Name}", capitalized(name)).replace("{REQ}", requestCode.toString())
}
