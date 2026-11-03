package com.oncet.smsquickforwarder

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.oncet.smsquickforwarder.data.SettingsStore
import com.oncet.smsquickforwarder.rules.RuleEngine
import com.oncet.smsquickforwarder.rules.RuleStore
import com.oncet.smsquickforwarder.ui.SystemBarsInsets

class RuleTestActivity : Activity() {
    private lateinit var senderInput: EditText
    private lateinit var bodyInput: EditText
    private lateinit var resultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.test_rules)
            textSize = 22f
        })
        senderInput = EditText(this).apply { hint = getString(R.string.ui_numero_de_remitente_para_la_prueba) }
        bodyInput = EditText(this).apply { hint = getString(R.string.ui_contenido_del_sms_de_prueba); minLines = 4 }
        resultText = TextView(this).apply { setPadding(0, 16, 0, 0) }
        root.addView(senderInput)
        root.addView(bodyInput)
        root.addView(Button(this).apply { text = getString(R.string.ui_probar_coincidencia); setOnClickListener { test() } })
        root.addView(Button(this).apply {
            text = getString(R.string.ui_cargar_ejemplo_de_codigo_de_verificacion)
            setOnClickListener {
                senderInput.setText("+15550106666")
                bodyInput.setText(getString(R.string.ui_tu_codigo_de_verificacion_es_123456))
            }
        })
        root.addView(Button(this).apply {
            text = getString(R.string.ui_cargar_ejemplo_de_publicidad)
            setOnClickListener {
                senderInput.setText("12345")
                bodyInput.setText(getString(R.string.ui_ofertas_de_hoy_responde_stop_para_cancelar_la_suscripcion))
            }
        })
        root.addView(Button(this).apply {
            text = "Vaciar"
            setOnClickListener { senderInput.setText(""); bodyInput.setText(""); resultText.text = "" }
        })
        root.addView(resultText)
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        with(SystemBarsInsets) { applyStandardSystemBars(scroll, handleIme = true) }
    }

    private fun test() {
        val mode = RuleStore.forwardMode(this)
        val result = RuleEngine.evaluate(
            sender = senderInput.text.toString(),
            body = bodyInput.text.toString(),
            targetPhone = SettingsStore.targetPhone(this),
            mode = mode,
            rules = RuleStore.rules(this),
            applyLoopGuard = true
        )
        resultText.text = buildString {
            append(getString(R.string.ui_resultado_final)).append(if (result.shouldForward) getString(R.string.will_forward) else getString(R.string.will_skip)).append("\n")
            append(getString(R.string.ui_motivo)).append(result.reason).append("\n")
            append(getString(R.string.ui_modo_de_reenvio_actual)).append(DisplayLabels.mode(mode.name)).append("\n")
            append(getString(R.string.ui_proteccion_contra_bucles_activada)).append(if (result.loopGuardTriggered) getString(R.string.ui_si) else "No").append("\n")
            append(getString(R.string.ui_reglas_de_inclusion_coincidentes)).append(result.includeMatches.joinToString { it.name }.ifBlank { "Ninguno" }).append("\n")
            append(getString(R.string.ui_reglas_de_exclusion_coincidentes)).append(result.excludeMatches.joinToString { it.name }.ifBlank { "Ninguno" })
        }
    }
}
