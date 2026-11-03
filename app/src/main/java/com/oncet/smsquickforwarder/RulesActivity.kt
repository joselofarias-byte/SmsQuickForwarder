package com.oncet.smsquickforwarder

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.oncet.smsquickforwarder.rules.ForwardMode
import com.oncet.smsquickforwarder.rules.RuleStore
import com.oncet.smsquickforwarder.rules.RuleType
import com.oncet.smsquickforwarder.rules.SmsRule
import com.oncet.smsquickforwarder.ui.SystemBarsInsets

class RulesActivity : Activity() {
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        with(SystemBarsInsets) { applyStandardSystemBars(scroll, handleIme = false) }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        root.removeAllViews()
        val rules = RuleStore.rules(this)
        val mode = RuleStore.forwardMode(this)
        root.addView(TextView(this).apply {
            text = getString(R.string.rule_settings)
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "Modo actual: ${modeLabel(mode)}\nActivadas: ${rules.count { it.enabled }}  Inclusión: ${rules.count { it.type == RuleType.INCLUDE }}  Exclusión: ${rules.count { it.type == RuleType.EXCLUDE }}"
            setPadding(0, 12, 0, 12)
        })
        root.addView(Button(this).apply {
            text = if (mode == ForwardMode.ALL) getString(R.string.ui_cambiar_a_reenviar_solo_los_sms_que_coincidan_con_las_reglas) else getString(R.string.ui_cambiar_a_reenviar_todos_los_sms)
            setOnClickListener {
                val next = if (mode == ForwardMode.ALL) ForwardMode.MATCH_ONLY else ForwardMode.ALL
                RuleStore.setForwardMode(this@RulesActivity, next)
                if (next == ForwardMode.MATCH_ONLY && rules.none { it.enabled && it.type == RuleType.INCLUDE }) {
                    Toast.makeText(this@RulesActivity, getString(R.string.ui_no_hay_reglas_de_inclusion_se_omitiran_todos_los_sms), Toast.LENGTH_LONG).show()
                }
                render()
            }
        })
        root.addView(Button(this).apply {
            text = getString(R.string.ui_nueva_regla_de_inclusion)
            setOnClickListener { openEditor(RuleType.INCLUDE) }
        })
        root.addView(Button(this).apply {
            text = getString(R.string.ui_nueva_regla_de_exclusion)
            setOnClickListener { openEditor(RuleType.EXCLUDE) }
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.ui_reglas_rapidas)
            textSize = 16f
            setPadding(0, 18, 0, 6)
        })
        addTemplateButton(getString(R.string.ui_codigo_de_verificacion), "verification", RuleType.INCLUDE)
        addTemplateButton("Banco", "bank", RuleType.INCLUDE)
        addTemplateButton(getString(R.string.ui_envios), "delivery", RuleType.INCLUDE)
        addTemplateButton("Facturas", "bill", RuleType.INCLUDE)
        addTemplateButton(getString(R.string.ui_avisos_de_inicio_de_sesion), "login", RuleType.INCLUDE)
        addTemplateButton(getString(R.string.ui_cancelar_publicidad), "unsubscribe", RuleType.EXCLUDE)
        addTemplateButton(getString(R.string.ui_numero_especifico), "sender", RuleType.EXCLUDE)
        root.addView(Button(this).apply {
            text = getString(R.string.ui_copiar_reglas_json)
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("rules", RuleStore.exportRules(this@RulesActivity, includePatterns = true)))
                Toast.makeText(this@RulesActivity, getString(R.string.ui_reglas_json_copiado), Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = getString(R.string.ui_pegar_e_importar_reglas_json)
            setOnClickListener { showImportDialog() }
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.ui_lista_de_reglas)
            textSize = 16f
            setPadding(0, 18, 0, 6)
        })
        if (rules.isEmpty()) {
            root.addView(TextView(this).apply { text = getString(R.string.ui_todavia_no_hay_reglas_de_forma_predeterminada_se_reenviaran_todos) })
        } else {
            rules.forEach { addRuleRow(it) }
        }
    }

    private fun addRuleRow(rule: SmsRule) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
        }
        box.addView(TextView(this).apply {
            text = "${rule.priority}. ${rule.name}  ${if (rule.type == RuleType.INCLUDE) "Incluir" else "Excluir"}\n${fieldLabel(rule)} / ${matchLabel(rule)} / ${rule.pattern}\nEstado: ${if (rule.enabled) "Activada" else "Desactivada"}"
        })
        box.addView(Switch(this).apply {
            text = "Activar"
            isChecked = rule.enabled
            setOnCheckedChangeListener { _, checked -> RuleStore.setEnabled(this@RulesActivity, rule.id, checked); render() }
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply { text = "Editar"; setOnClickListener { openEditor(rule.type, rule.id) } })
        actions.addView(Button(this).apply { text = "Subir"; setOnClickListener { RuleStore.move(this@RulesActivity, rule.id, -1); render() } })
        actions.addView(Button(this).apply { text = "Bajar"; setOnClickListener { RuleStore.move(this@RulesActivity, rule.id, 1); render() } })
        actions.addView(Button(this).apply { text = "Eliminar"; setOnClickListener { confirmDelete(rule) } })
        box.addView(actions)
        root.addView(box)
    }

    private fun showImportDialog() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.ui_pegar_reglas_json)
            minLines = 5
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_importar_reglas))
            .setView(input)
            .setPositiveButton(getString(R.string.ui_importar_y_combinar)) { _, _ ->
                val result = RuleStore.importRules(this, input.text.toString(), replace = false)
                Toast.makeText(this, "Agregadas ${result.added}, Duplicadas ${result.duplicate}, No válidas ${result.invalid}", Toast.LENGTH_LONG).show()
                render()
            }
            .setNeutralButton(getString(R.string.ui_reemplazar_todo)) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ui_confirmar_reemplazo_completo))
                    .setMessage(getString(R.string.ui_se_eliminaran_las_reglas_actuales_y_se_importara_el_contenido_peg))
                    .setPositiveButton("Reemplazar") { _, _ ->
                        val result = RuleStore.importRules(this, input.text.toString(), replace = true)
                        Toast.makeText(this, "Agregadas ${result.added}, No válidas ${result.invalid}", Toast.LENGTH_LONG).show()
                        render()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDelete(rule: SmsRule) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_eliminar_regla))
            .setMessage("¿Eliminar «${rule.name}»?")
            .setPositiveButton("Eliminar") { _, _ -> RuleStore.deleteRule(this, rule.id); render() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun addTemplateButton(label: String, template: String, type: RuleType) {
        root.addView(Button(this).apply {
            text = label
            setOnClickListener { openEditor(type, template = template) }
        })
    }

    private fun openEditor(type: RuleType, id: String? = null, template: String? = null) {
        startActivity(Intent(this, RuleEditActivity::class.java).apply {
            putExtra(RuleEditActivity.EXTRA_TYPE, type.name)
            if (id != null) putExtra(RuleEditActivity.EXTRA_RULE_ID, id)
            if (template != null) putExtra(RuleEditActivity.EXTRA_TEMPLATE, template)
        })
    }

    private fun modeLabel(mode: ForwardMode) = if (mode == ForwardMode.ALL) getString(R.string.forward_all_sms) else getString(R.string.forward_matched_sms_only)
    private fun fieldLabel(rule: SmsRule) = when (rule.field.name) { "SENDER" -> getString(R.string.ui_numero); "BODY" -> "Contenido"; else -> "Cualquiera" }
    private fun matchLabel(rule: SmsRule) = when (rule.matchMode.name) { "EQUALS" -> getString(R.string.ui_es_igual_a); "STARTS_WITH" -> getString(R.string.ui_empieza_por); "REGEX" -> getString(R.string.ui_expresion_regular); else -> "Incluir" }
}
