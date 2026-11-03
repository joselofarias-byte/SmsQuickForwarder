package com.oncet.smsquickforwarder

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import com.oncet.smsquickforwarder.data.ForwardLogStore
import com.oncet.smsquickforwarder.ui.SystemBarsInsets
import com.oncet.smsquickforwarder.ui.UiKit
import com.oncet.smsquickforwarder.util.PhoneMaskUtils

class LogDetailActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID).orEmpty()
        val obj = ForwardLogStore.eventById(this, eventId)
        val root = UiKit.page(this)
        root.addView(UiKit.title(this, getString(R.string.ui_detalles_del_registro)))
        if (obj == null) {
            root.addView(UiKit.card(this).apply { addView(UiKit.body(this@LogDetailActivity, getString(R.string.ui_el_registro_no_existe))) })
        } else {
            root.addView(UiKit.card(this).apply {
                addView(UiKit.body(this@LogDetailActivity, "Fecha: ${obj.optString("receivedAt")}"))
                addView(UiKit.body(this@LogDetailActivity, "Remitente: ${PhoneMaskUtils.mask(obj.optString("sender"))}"))
                addView(UiKit.body(this@LogDetailActivity, "Número de destino: ${PhoneMaskUtils.mask(obj.optString("targetPhone"))}"))
                addView(UiKit.body(this@LogDetailActivity, "Resultado: ${DisplayLabels.decision(obj.optString("decision"))}"))
                addView(UiKit.subtitle(this@LogDetailActivity, "Resumen del contenido: ${obj.optString("bodyPreview")}"))
            })
            root.addView(UiKit.card(this).apply {
                addView(UiKit.body(this@LogDetailActivity, getString(R.string.ui_evaluacion_de_reglas)))
                addView(UiKit.divider(this@LogDetailActivity))
                addView(UiKit.body(this@LogDetailActivity, "Modo: ${DisplayLabels.mode(obj.optString("forwardMode"))}"))
                addView(UiKit.body(this@LogDetailActivity, "Coincidencias: ${obj.optJSONArray("matchedRuleNames")?.join(", ").orEmpty().ifBlank { "Ninguno" }}"))
                addView(UiKit.body(this@LogDetailActivity, "Resultado: ${obj.optString("finalRuleReason")}"))
                addView(UiKit.body(this@LogDetailActivity, "Duración: ${obj.optLong("ruleEvaluationDurationMs")}ms"))
            })
            root.addView(UiKit.card(this).apply {
                addView(UiKit.body(this@LogDetailActivity, "Resultado del envío: ${DisplayLabels.sendResult(obj.optString("sendResult"))}"))
                addView(UiKit.body(this@LogDetailActivity, "Motivo del fallo: ${obj.optString("errorMessage").ifBlank { obj.optString("skipReason").ifBlank { "Ninguno" } }}"))
            })
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        with(SystemBarsInsets) { applyStandardSystemBars(scroll, handleIme = false) }
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
    }
}
