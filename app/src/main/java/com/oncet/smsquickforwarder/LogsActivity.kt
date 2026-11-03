package com.oncet.smsquickforwarder

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.oncet.smsquickforwarder.data.ForwardLogStore
import com.oncet.smsquickforwarder.ui.SystemBarsInsets
import com.oncet.smsquickforwarder.ui.UiKit
import com.oncet.smsquickforwarder.util.MessagePrivacyUtils
import com.oncet.smsquickforwarder.util.PhoneMaskUtils
import org.json.JSONObject

class LogsActivity : Activity() {
    private lateinit var root: LinearLayout
    private var filter = "all"
    private var dateFilter = "all"
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = UiKit.page(this)
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        with(SystemBarsInsets) { applyStandardSystemBars(scroll, handleIme = true) }
        render()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(UiKit.title(this, getString(R.string.logs)))
        root.addView(UiKit.subtitle(this, getString(R.string.ui_historial_de_sms_procesados_los_eventos_internos_de_depuracion_se)))
        root.addView(statsCard())
        root.addView(searchCard())
        val filters = UiKit.row(this)
        listOf("all" to "Todos", "forwarded" to "Reenviado", "skipped" to "Omitido", "failed" to getString(R.string.ui_fallo)).forEach { item ->
            filters.addView(UiKit.navButton(this, item.second, filter == item.first) {
                filter = item.first
                render()
            })
        }
        root.addView(filters)
        val dates = UiKit.row(this)
        listOf("all" to "Todos", "today" to "Hoy", "7d" to getString(R.string.ui_7_dias), "30d" to getString(R.string.ui_30_dias)).forEach { item ->
            dates.addView(UiKit.navButton(this, item.second, dateFilter == item.first) {
                dateFilter = item.first
                render()
            })
        }
        root.addView(dates)

        val events = ForwardLogStore.searchUserEvents(this, query, filter, dateFilter, 200)
        var count = 0
        for (i in 0 until events.length()) {
            val obj = events.getJSONObject(i)
            addEvent(obj)
            count += 1
        }
        if (count == 0) root.addView(UiKit.card(this).apply { addView(UiKit.body(this@LogsActivity, getString(R.string.ui_no_hay_registros_que_coincidan))) })
        root.addView(clearCard())
    }

    private fun statsCard(): LinearLayout = UiKit.card(this).apply {
        addView(UiKit.body(this@LogsActivity, getString(R.string.ui_estadisticas_de_registros)))
        addView(UiKit.subtitle(this@LogsActivity, "Total ${ForwardLogStore.totalCount(this@LogsActivity)} · Reenviados hoy ${ForwardLogStore.countToday(this@LogsActivity) { isForwardSuccess(it.optString("decision")) }} · Omitidos hoy ${ForwardLogStore.countToday(this@LogsActivity) { it.optString("decision").startsWith("skipped") }} · Fallidos hoy ${ForwardLogStore.countToday(this@LogsActivity) { it.optString("decision").contains("failed", true) }}\nEspacio estimado ${ForwardLogStore.estimatedBytes(this@LogsActivity)} bytes"))
    }

    private fun searchCard(): LinearLayout = UiKit.card(this).apply {
        addView(UiKit.body(this@LogsActivity, "Buscar"))
        val input = EditText(this@LogsActivity).apply {
            hint = getString(R.string.ui_numero_resumen_del_contenido_regla_o_motivo_del_fallo)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(query)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString().orEmpty()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        addView(input)
        addView(UiKit.secondaryButton(this@LogsActivity, "Buscar") { render() })
    }

    private fun addEvent(obj: JSONObject) {
        val card = UiKit.card(this)
        card.addView(UiKit.body(this, "${obj.optString("receivedAt")}  ${decisionLabel(obj.optString("decision"))}"))
        card.addView(UiKit.subtitle(this, "${PhoneMaskUtils.mask(obj.optString("sender"))}  ${MessagePrivacyUtils.maskVerificationCodes(obj.optString("bodyPreview"))}"))
        val rule = obj.optString("primaryMatchedRuleName").ifBlank { "Ninguno" }
        card.addView(UiKit.subtitle(this, "Regla coincidente: $rule"))
        card.setOnClickListener {
            startActivity(Intent(this, LogDetailActivity::class.java).apply {
                putExtra(LogDetailActivity.EXTRA_EVENT_ID, obj.optString("eventId"))
            })
        }
        root.addView(card)
    }

    private fun clearCard(): LinearLayout = UiKit.card(this).apply {
        addView(UiKit.body(this@LogsActivity, getString(R.string.ui_administrar_registros)))
        val row1 = UiKit.row(this@LogsActivity)
        row1.addView(actionButton(getString(R.string.ui_eliminar_todo)) {
            confirm(getString(R.string.ui_eliminar_todos_los_registros_las_reglas_y_los_ajustes_se_conserva)) {
                ForwardLogStore.clear(this@LogsActivity)
                Toast.makeText(this@LogsActivity, getString(R.string.ui_registros_eliminados), Toast.LENGTH_SHORT).show()
                render()
            }
        })
        row1.addView(actionButton(getString(R.string.ui_eliminar_reenvios_correctos)) {
            confirm(getString(R.string.ui_eliminar_solo_los_registros_de_reenvios_correctos_se_conservan_lo)) {
                ForwardLogStore.clearSuccessful(this@LogsActivity)
                render()
            }
        })
        val row2 = UiKit.row(this@LogsActivity)
        row2.addView(actionButton(getString(R.string.ui_eliminar_anteriores_a_30_dias)) {
            confirm(getString(R.string.ui_eliminar_los_registros_de_hace_mas_de_30_dias_las_reglas_y_los_aj)) {
                ForwardLogStore.clearOlderThanDays(this@LogsActivity, 30)
                render()
            }
        })
        addView(row1)
        addView(row2)
    }

    private fun actionButton(text: String, onClick: () -> Unit) = UiKit.secondaryButton(this, text, onClick).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(UiKit.dp(this@LogsActivity, 4), UiKit.dp(this@LogsActivity, 4), UiKit.dp(this@LogsActivity, 4), UiKit.dp(this@LogsActivity, 4))
        }
    }

    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_confirmar_accion))
            .setMessage(message)
            .setPositiveButton("Confirmar") { _, _ -> action() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun decisionLabel(decision: String): String = when {
        isForwardSuccess(decision) -> "Reenviado"
        decision.contains("failed", ignoreCase = true) -> getString(R.string.ui_fallo)
        decision.startsWith("skipped") -> "Omitido"
        else -> decision
    }

    private fun isForwardSuccess(decision: String): Boolean =
        decision == "forwarded" || decision == "forwarded_rule_match" || decision == "forwarded_all_mode"
}
