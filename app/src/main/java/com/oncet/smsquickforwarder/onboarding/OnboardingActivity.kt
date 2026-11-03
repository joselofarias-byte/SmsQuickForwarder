package com.oncet.smsquickforwarder.onboarding

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.oncet.smsquickforwarder.R
import com.oncet.smsquickforwarder.data.SettingsStore
import com.oncet.smsquickforwarder.service.ForwardForegroundService
import com.oncet.smsquickforwarder.sms.SmsForwarder
import com.oncet.smsquickforwarder.ui.SystemBarsInsets
import com.oncet.smsquickforwarder.ui.UiKit
import com.oncet.smsquickforwarder.util.PhoneMaskUtils

class OnboardingActivity : Activity() {
    private lateinit var root: LinearLayout
    private var step = 0
    private var testEventId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        step = OnboardingPreferences.step(this)
        root = UiKit.page(this)
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        with(SystemBarsInsets) { applyStandardSystemBars(scroll, handleIme = true) }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) render()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(UiKit.title(this, getString(R.string.ui_asistente_de_configuracion)))
        root.addView(UiKit.subtitle(this, "Paso ${step + 1} de 6"))
        when (step) {
            0 -> welcomeStep()
            1 -> targetStep()
            2 -> permissionStep()
            3 -> backgroundStep()
            4 -> testStep()
            else -> completeStep()
        }
    }

    private fun welcomeStep() {
        root.addView(UiKit.card(this).apply {
            addView(UiKit.body(this@OnboardingActivity, getString(R.string.ui_bienvenido_a_sms_quick_forwarder)))
            addView(UiKit.subtitle(this@OnboardingActivity, getString(R.string.ui_esta_aplicacion_recibe_sms_y_los_reenvia_al_numero_indicado_segun)))
            addView(UiKit.primaryButton(this@OnboardingActivity, getString(R.string.ui_iniciar_configuracion)) { next() })
            addView(UiKit.secondaryButton(this@OnboardingActivity, getString(R.string.ui_configurar_mas_tarde)) { finish() })
        })
    }

    private fun targetStep() {
        val input = EditText(this).apply {
            hint = "+1 602-555-0108"
            inputType = InputType.TYPE_CLASS_PHONE
            setText(SettingsStore.targetPhone(this@OnboardingActivity))
        }
        root.addView(UiKit.card(this).apply {
            addView(UiKit.body(this@OnboardingActivity, getString(R.string.ui_configurar_numero_de_destino)))
            addView(input)
            addView(UiKit.subtitle(this@OnboardingActivity, getString(R.string.ui_se_pedira_confirmacion_antes_de_guardar_no_se_accede_a_los_contac)))
            addView(UiKit.primaryButton(this@OnboardingActivity, getString(R.string.ui_guardar_y_continuar)) {
                val value = input.text.toString().trim()
                if (!isValidPhone(value)) {
                    Toast.makeText(this@OnboardingActivity, getString(R.string.ui_ingresa_un_numero_de_telefono_valido), Toast.LENGTH_SHORT).show()
                    return@primaryButton
                }
                AlertDialog.Builder(this@OnboardingActivity)
                    .setTitle(getString(R.string.ui_confirmar_numero_de_destino))
                    .setMessage("Los SMS se reenviarán a: ${PhoneMaskUtils.mask(value)}")
                    .setPositiveButton("Confirmar") { _, _ ->
                        SettingsStore.setTargetPhone(this@OnboardingActivity, value)
                        next()
                    }
                    .setNegativeButton(getString(R.string.ui_volver_y_editar), null)
                    .show()
            })
        })
    }

    private fun permissionStep() {
        root.addView(UiKit.card(this).apply {
            addView(UiKit.body(this@OnboardingActivity, getString(R.string.ui_configurar_permisos)))
            permissionRow(this, getString(R.string.ui_recibir_sms), Manifest.permission.RECEIVE_SMS, 401)
            permissionRow(this, getString(R.string.ui_enviar_sms), Manifest.permission.SEND_SMS, 402)
            if (Build.VERSION.SDK_INT >= 33) permissionRow(this, "Notificaciones", Manifest.permission.POST_NOTIFICATIONS, 403)
            addView(UiKit.primaryButton(this@OnboardingActivity, "Siguiente") { next() })
        })
    }

    private fun permissionRow(container: LinearLayout, label: String, permission: String, code: Int) {
        val row = UiKit.row(this)
        row.addView(UiKit.body(this, "$label: ${if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) "Permitido" else "No permitido"}").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(UiKit.secondaryButton(this, getString(R.string.ui_conceder_permiso)) { requestPermissions(arrayOf(permission), code) })
        container.addView(row)
    }

    private fun backgroundStep() {
        root.addView(UiKit.card(this).apply {
            addView(UiKit.body(this@OnboardingActivity, getString(R.string.ui_funcionamiento_en_segundo_plano)))
            addView(UiKit.subtitle(this@OnboardingActivity, getString(R.string.ui_para_evitar_que_se_interrumpan_los_reenvios_al_bloquear_la_pantal)))
            addView(UiKit.body(this@OnboardingActivity, "Servicio en primer plano: ${if (SettingsStore.isServiceRunning(this@OnboardingActivity)) "En ejecución" else "Detenido"}"))
            addView(UiKit.body(this@OnboardingActivity, "Optimización de batería: ${if (batteryOk()) "Sin restricciones" else "Puede estar restringido"}"))
            addView(UiKit.secondaryButton(this@OnboardingActivity, getString(R.string.ui_abrir_ajustes_de_bateria)) { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } })
            addView(UiKit.secondaryButton(this@OnboardingActivity, getString(R.string.ui_volver_a_comprobar)) { render() })
            addView(UiKit.primaryButton(this@OnboardingActivity, "Siguiente") { next() })
        })
    }

    private fun testStep() {
        root.addView(UiKit.card(this).apply {
            addView(UiKit.body(this@OnboardingActivity, getString(R.string.ui_envio_de_prueba)))
            addView(UiKit.subtitle(this@OnboardingActivity, if (testEventId.isBlank()) getString(R.string.ui_enviar_un_sms_de_prueba_al_numero_de_destino) else "Evento de prueba: $testEventId"))
            addView(UiKit.primaryButton(this@OnboardingActivity, getString(R.string.ui_enviar_sms_de_prueba)) {
                testEventId = SmsForwarder.sendTestMessage(this@OnboardingActivity)
                Toast.makeText(this@OnboardingActivity, getString(R.string.ui_envio_de_prueba_solicitado), Toast.LENGTH_SHORT).show()
                render()
            })
            addView(UiKit.secondaryButton(this@OnboardingActivity, getString(R.string.ui_comprobar_permisos_nuevamente)) { render() })
            addView(UiKit.primaryButton(this@OnboardingActivity, "Siguiente") { next() })
        })
    }

    private fun completeStep() {
        root.addView(UiKit.card(this).apply {
            addView(UiKit.body(this@OnboardingActivity, getString(R.string.ui_configuracion_completada)))
            addView(UiKit.subtitle(this@OnboardingActivity, getString(R.string.ui_todo_listo_para_reenviar_sms)))
            addView(UiKit.body(this@OnboardingActivity, "Número de destino: ${PhoneMaskUtils.mask(SettingsStore.targetPhone(this@OnboardingActivity))}"))
            addView(UiKit.body(this@OnboardingActivity, "Permiso de recepción: ${perm(Manifest.permission.RECEIVE_SMS)}"))
            addView(UiKit.body(this@OnboardingActivity, "Permiso de envío: ${perm(Manifest.permission.SEND_SMS)}"))
            addView(UiKit.body(this@OnboardingActivity, "Permiso de notificaciones: ${if (Build.VERSION.SDK_INT < 33) "No requiere permiso" else perm(Manifest.permission.POST_NOTIFICATIONS)}"))
            addView(UiKit.body(this@OnboardingActivity, "Funcionamiento en segundo plano: ${if (batteryOk()) "Sin restricciones" else "Se recomienda completar los ajustes"}"))
            addView(UiKit.body(this@OnboardingActivity, "Envío de prueba: ${if (testEventId.isBlank()) "Sin probar" else "Solicitado"}"))
            addView(UiKit.primaryButton(this@OnboardingActivity, getString(R.string.ui_ir_al_inicio)) {
                OnboardingPreferences.markCompleted(this@OnboardingActivity)
                if (SettingsStore.isEnabled(this@OnboardingActivity)) startForegroundService(Intent(this@OnboardingActivity, ForwardForegroundService::class.java))
                finish()
            })
        })
    }

    private fun next() {
        step = (step + 1).coerceAtMost(5)
        OnboardingPreferences.setStep(this, step)
        render()
    }

    private fun perm(permission: String): String =
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) "Permitido" else getString(R.string.ui_no_permitido)

    private fun batteryOk(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun isValidPhone(value: String): Boolean =
        value.filter { it.isDigit() }.length >= 7 && value.length <= 32
}
