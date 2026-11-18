package dev.buildit.feature.blocks

import androidx.annotation.StringRes
import dev.buildit.engine.blocks.BlockCategory

// Explicit resource mappings (no getIdentifier) so resource shrinking keeps every string.

@StringRes
internal fun blockMessageRes(type: String): Int? = when (type) {
    "event_create" -> R.string.blk_event_create
    "event_resume" -> R.string.blk_event_resume
    "event_pause" -> R.string.blk_event_pause
    "event_destroy" -> R.string.blk_event_destroy
    "event_click" -> R.string.blk_event_click
    "event_checked" -> R.string.blk_event_checked
    "proc_define" -> R.string.blk_proc_define
    "ctrl_if" -> R.string.blk_ctrl_if
    "ctrl_if_else" -> R.string.blk_ctrl_if_else
    "ctrl_repeat" -> R.string.blk_ctrl_repeat
    "ctrl_while" -> R.string.blk_ctrl_while
    "ctrl_for" -> R.string.blk_ctrl_for
    "proc_call" -> R.string.blk_proc_call
    "logic_bool" -> R.string.blk_logic_bool
    "logic_compare" -> R.string.blk_logic_compare
    "logic_text_equals" -> R.string.blk_logic_text_equals
    "logic_op" -> R.string.blk_logic_op
    "logic_not" -> R.string.blk_logic_not
    "math_number" -> R.string.blk_math_number
    "math_arith" -> R.string.blk_math_arith
    "math_random" -> R.string.blk_math_random
    "math_round" -> R.string.blk_math_round
    "math_parse" -> R.string.blk_math_parse
    "text_literal" -> R.string.blk_text_literal
    "text_join" -> R.string.blk_text_join
    "text_length" -> R.string.blk_text_length
    "var_get" -> R.string.blk_var_get
    "var_set" -> R.string.blk_var_set
    "var_change" -> R.string.blk_var_change
    "list_add" -> R.string.blk_list_add
    "list_get" -> R.string.blk_list_get
    "list_size" -> R.string.blk_list_size
    "list_clear" -> R.string.blk_list_clear
    "view_set_text" -> R.string.blk_view_set_text
    "view_get_text" -> R.string.blk_view_get_text
    "view_set_visibility" -> R.string.blk_view_set_visibility
    "view_set_enabled" -> R.string.blk_view_set_enabled
    "view_is_checked" -> R.string.blk_view_is_checked
    "view_set_checked" -> R.string.blk_view_set_checked
    "view_set_text_color" -> R.string.blk_view_set_text_color
    "app_toast" -> R.string.blk_app_toast
    "app_log" -> R.string.blk_app_log
    "app_start" -> R.string.blk_app_start
    "app_finish" -> R.string.blk_app_finish
    "code_stmt" -> R.string.blk_code_stmt
    "code_expr" -> R.string.blk_code_expr
    "event_timer" -> R.string.blk_event_timer
    "event_dialog_ok" -> R.string.blk_event_dialog_ok
    "event_sound_done" -> R.string.blk_event_sound_done
    "timer_start" -> R.string.blk_timer_start
    "timer_stop" -> R.string.blk_timer_stop
    "storage_set" -> R.string.blk_storage_set
    "storage_get" -> R.string.blk_storage_get
    "storage_remove" -> R.string.blk_storage_remove
    "dialog_show" -> R.string.blk_dialog_show
    "sound_play" -> R.string.blk_sound_play
    "sound_stop" -> R.string.blk_sound_stop
    "app_open_link" -> R.string.blk_app_open_link
    else -> null
}

@StringRes
internal fun statementLabelRes(label: String): Int? = when (label) {
    "else" -> R.string.blk_label_else
    "then" -> R.string.blk_label_then
    "do" -> R.string.blk_label_do
    else -> null
}

@StringRes
internal fun categoryRes(c: BlockCategory): Int = when (c) {
    BlockCategory.Events -> R.string.bk_cat_Events
    BlockCategory.Control -> R.string.bk_cat_Control
    BlockCategory.Logic -> R.string.bk_cat_Logic
    BlockCategory.Math -> R.string.bk_cat_Math
    BlockCategory.Text -> R.string.bk_cat_Text
    BlockCategory.Variables -> R.string.bk_cat_Variables
    BlockCategory.Lists -> R.string.bk_cat_Lists
    BlockCategory.Components -> R.string.bk_cat_Components
    BlockCategory.Parts -> R.string.bk_cat_Parts
    BlockCategory.App -> R.string.bk_cat_App
    BlockCategory.Code -> R.string.bk_cat_Code
}

@StringRes
internal fun diagnosticRes(code: String): Int? = when (code) {
    "not_in_event" -> R.string.bk_diag_not_in_event
    "after_event" -> R.string.bk_diag_after_event
    "duplicate_event" -> R.string.bk_diag_duplicate_event
    "duplicate_procedure" -> R.string.bk_diag_duplicate_procedure
    "event_not_on_top" -> R.string.bk_diag_event_not_on_top
    "unknown_block" -> R.string.bk_diag_unknown_block
    "missing_input" -> R.string.bk_diag_missing_input
    "wrong_shape" -> R.string.bk_diag_wrong_shape
    "type_mismatch" -> R.string.bk_diag_type_mismatch
    "missing_view" -> R.string.bk_diag_missing_view
    "unknown_view" -> R.string.bk_diag_unknown_view
    "view_kind" -> R.string.bk_diag_view_kind
    "missing_variable" -> R.string.bk_diag_missing_variable
    "unknown_variable" -> R.string.bk_diag_unknown_variable
    "variable_type" -> R.string.bk_diag_variable_type
    "invalid_number" -> R.string.bk_diag_invalid_number
    "invalid_option" -> R.string.bk_diag_invalid_option
    "invalid_procedure" -> R.string.bk_diag_invalid_procedure
    "unknown_procedure" -> R.string.bk_diag_unknown_procedure
    "unknown_activity" -> R.string.bk_diag_unknown_activity
    "empty_code" -> R.string.bk_diag_empty_code
    "invalid_color" -> R.string.bk_diag_invalid_color
    "invalid_variable" -> R.string.bk_diag_invalid_variable
    "duplicate_variable" -> R.string.bk_diag_duplicate_variable
    "variable_clashes_view" -> R.string.bk_diag_variable_clashes_view
    "invalid_component" -> R.string.bk_diag_invalid_component
    "duplicate_component" -> R.string.bk_diag_duplicate_component
    "component_clashes_view" -> R.string.bk_diag_component_clashes_view
    "missing_component" -> R.string.bk_diag_missing_component
    "unknown_component" -> R.string.bk_diag_unknown_component
    "missing_sound" -> R.string.bk_diag_missing_sound
    "unknown_sound" -> R.string.bk_diag_unknown_sound
    else -> null
}
