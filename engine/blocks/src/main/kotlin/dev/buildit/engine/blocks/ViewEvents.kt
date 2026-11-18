package dev.buildit.engine.blocks

/**
 * An event a layout view raises, beyond click and checked change (Phase 13). The hat block names a
 * view of [kind]; its body becomes `on<View><suffix>(params)`, and [java]/[kotlin] register the
 * listener in onCreate. In the listener snippets `{view}` is the view field and `{handler}` the
 * handler method; values are converted to the block value types (numbers are doubles).
 */
data class ViewEventSpec(
    val hat: String,
    val message: String,
    val kind: ViewKind,
    val suffix: String,
    val params: List<ProcParam>,
    val java: String,
    val kotlin: String,
    val imports: List<String> = emptyList(),
)

object ViewEvents {
    private fun n(name: String) = ProcParam(name, ValueType.Number)
    private fun t(name: String) = ProcParam(name, ValueType.Text)
    private fun b(name: String) = ProcParam(name, ValueType.Boolean)

    val specs: List<ViewEventSpec> = listOf(
        ViewEventSpec(
            "event_long_click", "when {view} is long clicked", ViewKind.Any, "LongClick", emptyList(),
            java = "{view}.setOnLongClickListener(v -> { {handler}(); return true; });",
            kotlin = "{view}.setOnLongClickListener { {handler}(); true }",
        ),
        ViewEventSpec(
            "event_text_changed", "when the text of {view} changes", ViewKind.Edit, "TextChanged", listOf(t("text")),
            java = """
                {view}.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                    @Override public void afterTextChanged(Editable s) { {handler}(s.toString()); }
                });
            """.trimIndent(),
            kotlin = """
                {view}.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) { {handler}(s?.toString() ?: "") }
                })
            """.trimIndent(),
            imports = listOf("android.text.Editable", "android.text.TextWatcher"),
        ),
        ViewEventSpec(
            "event_item_selected", "when an item of {view} is selected", ViewKind.Spinner, "ItemSelected", listOf(n("position"), t("item")),
            java = """
                {view}.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { {handler}(position + 1, String.valueOf(parent.getItemAtPosition(position))); }
                    @Override public void onNothingSelected(AdapterView<?> parent) {}
                });
            """.trimIndent(),
            kotlin = """
                {view}.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) { {handler}((position + 1).toDouble(), parent.getItemAtPosition(position).toString()) }
                    override fun onNothingSelected(parent: AdapterView<*>) {}
                }
            """.trimIndent(),
            imports = listOf("android.view.View", "android.widget.AdapterView"),
        ),
        ViewEventSpec(
            "event_item_clicked", "when an item of {view} is clicked", ViewKind.ItemList, "ItemClick", listOf(n("position"), t("item")),
            java = "{view}.setOnItemClickListener((parent, view, position, id) -> {handler}(position + 1, String.valueOf(parent.getItemAtPosition(position))));",
            kotlin = "{view}.setOnItemClickListener { parent, _, position, _ -> {handler}((position + 1).toDouble(), parent.getItemAtPosition(position).toString()) }",
        ),
        ViewEventSpec(
            "event_item_long_clicked", "when an item of {view} is long clicked", ViewKind.ItemList, "ItemLongClick", listOf(n("position"), t("item")),
            java = "{view}.setOnItemLongClickListener((parent, view, position, id) -> { {handler}(position + 1, String.valueOf(parent.getItemAtPosition(position))); return true; });",
            kotlin = "{view}.setOnItemLongClickListener { parent, _, position, _ -> {handler}((position + 1).toDouble(), parent.getItemAtPosition(position).toString()); true }",
        ),
        ViewEventSpec(
            "event_seekbar_changed", "when {view} is moved", ViewKind.SeekBar, "ProgressChanged", listOf(n("progress"), b("fromUser")),
            java = """
                {view}.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { {handler}(progress, fromUser); }
                    @Override public void onStartTrackingTouch(SeekBar bar) {}
                    @Override public void onStopTrackingTouch(SeekBar bar) {}
                });
            """.trimIndent(),
            kotlin = """
                {view}.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { {handler}(progress.toDouble(), fromUser) }
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                })
            """.trimIndent(),
            imports = listOf("android.widget.SeekBar"),
        ),
        ViewEventSpec(
            "event_rating_changed", "when the rating of {view} changes", ViewKind.Rating, "RatingChanged", listOf(n("rating")),
            java = "{view}.setOnRatingBarChangeListener((bar, rating, fromUser) -> {handler}(rating));",
            kotlin = "{view}.setOnRatingBarChangeListener { _, rating, _ -> {handler}(rating.toDouble()) }",
        ),
        ViewEventSpec(
            "event_video_done", "when {view} finishes playing", ViewKind.Video, "Completion", emptyList(),
            java = "{view}.setOnCompletionListener(player -> {handler}());",
            kotlin = "{view}.setOnCompletionListener { {handler}() }",
        ),
        ViewEventSpec(
            "event_date_changed", "when a date is picked in {view}", ViewKind.CalendarView, "DateChanged", listOf(n("year"), n("month"), n("day")),
            java = "{view}.setOnDateChangeListener((calendarView, year, month, day) -> {handler}(year, month + 1, day));",
            kotlin = "{view}.setOnDateChangeListener { _, year, month, day -> {handler}(year.toDouble(), (month + 1).toDouble(), day.toDouble()) }",
        ),
    )

    private val byHat = specs.associateBy { it.hat }

    fun spec(hat: String): ViewEventSpec? = byHat[hat]

    val blocks: List<BlockDef> = specs.map { s ->
        BlockDef(s.hat, BlockCategory.Events, BlockShape.Hat, s.message, fields = listOf(FieldDef("view", FieldKind.View(s.kind))), statements = listOf(StatementDef("do", "")))
    }

    /** Fills a listener snippet for [view] and its handler method. */
    fun fill(code: String, view: String, handler: String) = code.replace("{view}", view).replace("{handler}", handler)
}
