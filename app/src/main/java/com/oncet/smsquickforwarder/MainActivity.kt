package com.oncet.smsquickforwarder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import com.oncet.smsquickforwarder.data.ForwardLogStore
import com.oncet.smsquickforwarder.data.SettingsStore
import com.oncet.smsquickforwarder.debug.DebugInfoBuilder
import com.oncet.smsquickforwarder.backup.BackupActivity
import com.oncet.smsquickforwarder.failure.FailureNotificationPolicy
import com.oncet.smsquickforwarder.health.HealthCheckActivity
import com.oncet.smsquickforwarder.logs.LogRetentionDays
import com.oncet.smsquickforwarder.onboarding.OnboardingActivity
import com.oncet.smsquickforwarder.onboarding.OnboardingPreferences
import com.oncet.smsquickforwarder.onboarding.OnboardingSnapshot
import com.oncet.smsquickforwarder.onboarding.OnboardingState
import com.oncet.smsquickforwarder.rules.ForwardMode
import com.oncet.smsquickforwarder.rules.RuleStore
import com.oncet.smsquickforwarder.rules.RuleType
import com.oncet.smsquickforwarder.service.ForwardForegroundService
import com.oncet.smsquickforwarder.sms.SmsForwarder
import com.oncet.smsquickforwarder.ui.SystemBarsInsets
import com.oncet.smsquickforwarder.ui.UiKit
import com.oncet.smsquickforwarder.update.UpdateCheckResult
import com.oncet.smsquickforwarder.update.UpdateChecker
import com.oncet.smsquickforwarder.update.UpdateFrequency
import com.oncet.smsquickforwarder.update.UpdateInfo
import com.oncet.smsquickforwarder.update.UpdateNotifier
import com.oncet.smsquickforwarder.update.UpdatePreferences
import com.oncet.smsquickforwarder.update.UpdateScheduler
import com.oncet.smsquickforwarder.util.MessagePrivacyUtils
import com.oncet.smsquickforwarder.util.PhoneMaskUtils

class MainActivity : Activity() {
    private lateinit var contentRoot: LinearLayout
    private lateinit var navRoot: LinearLayout
    private var currentTab = Tab.HOME
    private var refreshing = false
    private var updateResult: UpdateCheckResult? = null
    private var updateChecking = false
    private var sessionHiddenUpdateVersion = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildShell()
        render()
        maybeStartOnboarding()
        UpdateScheduler.configure(this)
        scheduleStartupUpdateCheck()
    }

    override fun onResume() {
        super.onResume()
        if (::contentRoot.isInitialized) render()
    }

    private fun buildShell() {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.app_background))
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        contentRoot = UiKit.page(this)
        scroll.addView(contentRoot)
        navRoot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(UiKit.dp(this@MainActivity, 12), UiKit.dp(this@MainActivity, 8), UiKit.dp(this@MainActivity, 12), UiKit.dp(this@MainActivity, 10))
            setBackgroundColor(getColor(R.color.surface_card))
        }
        shell.addView(scroll)
        shell.addView(navRoot)
        setContentView(shell)
        with(SystemBarsInsets) { applyMainSystemBars(shell, scroll, navRoot) }
    }

    private fun render() {
        contentRoot.removeAllViews()
        when (currentTab) {
            Tab.HOME -> renderHome()
            Tab.RULES -> renderRules()
            Tab.LOGS -> renderLogsPreview()
            Tab.ABOUT -> renderAbout()
        }
        renderNav()
    }

    private fun renderNav() {
        navRoot.removeAllViews()
        navRoot.addView(UiKit.navButton(this, getString(R.string.home), currentTab == Tab.HOME) { currentTab = Tab.HOME; render() })
        navRoot.addView(UiKit.navButton(this, getString(R.string.rules), currentTab == Tab.RULES) { currentTab = Tab.RULES; render() })
        navRoot.addView(UiKit.navButton(this, getString(R.string.logs), currentTab == Tab.LOGS) { currentTab = Tab.LOGS; render() })
        navRoot.addView(UiKit.navButton(this, getString(R.string.about), currentTab == Tab.ABOUT) { currentTab = Tab.ABOUT; render() })
    }

    private fun renderHome() {
        contentRoot.addView(UiKit.title(this, getString(R.string.app_name)))
        contentRoot.addView(UiKit.subtitle(this, getString(R.string.ui_solo_procesa_los_mensajes_recibidos_cuando_activas_el_reenvio_de_)))
        contentRoot.addView(statusCard())
        updatePromptCard()?.let { contentRoot.addView(it) }
        failureWarningCard()?.let { contentRoot.addView(it) }
        contentRoot.addView(targetCard())
        contentRoot.addView(switchCard())
        contentRoot.addView(quickActionsCard())
        contentRoot.addView(permissionCard())
        contentRoot.addView(recentPreviewCard(3))
        contentRoot.addView(UiKit.subtitle(this, "${getString(R.string.current_version)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
    }

    private fun statusCard(): LinearLayout {
        val target = SettingsStore.targetPhone(this)
        val lastSuccess = ForwardLogStore.findLatestTime(this) { isForwardSuccess(it.optString("decision")) }.ifBlank { getString(R.string.ui_sin_datos) }
        val todaySuccess = ForwardLogStore.countToday(this) { isForwardSuccess(it.optString("decision")) }
        val todayFailed = ForwardLogStore.countToday(this) { it.optString("decision").contains("failed", ignoreCase = true) }
        val state = currentState()
        return UiKit.card(this).apply {
            val row = UiKit.row(this@MainActivity)
            row.addView(UiKit.body(this@MainActivity, getString(R.string.ui_estado_actual)).apply {
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(UiKit.tag(this@MainActivity, state.label, state.color))
            addView(row)
            addView(UiKit.subtitle(this@MainActivity, if (SettingsStore.isEnabled(this@MainActivity) && target.isNotBlank()) "Los SMS que coincidan con las reglas se reenviarán automáticamente a ${PhoneMaskUtils.mask(target)}" else getString(R.string.ui_reenvio_de_sms_pausado_o_configuracion_incompleta)))
            addView(UiKit.divider(this@MainActivity))
            addView(UiKit.body(this@MainActivity, "Destino del reenvío: ${if (target.isNotBlank()) PhoneMaskUtils.mask(target) else "Sin configurar"}"))
            addView(UiKit.body(this@MainActivity, "Último envío correcto: $lastSuccess"))
            addView(UiKit.body(this@MainActivity, "Correctos / fallidos hoy: $todaySuccess / $todayFailed"))
            addView(UiKit.body(this@MainActivity, "Modo de reenvío: ${modeLabel(RuleStore.forwardMode(this@MainActivity))}"))
        }
    }

    private fun updatePromptCard(): LinearLayout? {
        val result = updateResult as? UpdateCheckResult.UpdateAvailable ?: return null
        val info = result.info
        if (sessionHiddenUpdateVersion == info.latestVersionName || UpdatePreferences.ignoredVersion(this) == info.latestVersionName) return null
        return UiKit.card(this).apply {
            val row = UiKit.row(this@MainActivity)
            row.addView(UiKit.body(this@MainActivity, "${getString(R.string.update_available)} ${info.latestVersionName}").apply {
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(UiKit.tag(this@MainActivity, "NUEVA", getColor(R.color.primary)))
            addView(row)
            addView(UiKit.subtitle(this@MainActivity, "Versión actual ${BuildConfig.VERSION_NAME}\n\nCambios de esta versión: \n${releaseNotesPreview(info.releaseNotes)}"))
            val actions = UiKit.row(this@MainActivity)
            actions.addView(gridButton(getString(R.string.view_update)) { openUrl(info.releaseUrl) })
            actions.addView(gridButton(getString(R.string.ignore_this_version)) {
                UpdatePreferences.ignoreVersion(this@MainActivity, info.latestVersionName)
                render()
            })
            actions.addView(gridButton(getString(R.string.later)) {
                sessionHiddenUpdateVersion = info.latestVersionName
                render()
            })
            addView(actions)
        }
    }

    private fun targetCard(): LinearLayout {
        val input = EditText(this).apply {
            hint = getString(R.string.ui_numero_de_telefono_de_destino)
            inputType = InputType.TYPE_CLASS_PHONE
            setText(SettingsStore.targetPhone(this@MainActivity))
        }
        return UiKit.card(this).apply {
            addView(UiKit.body(this@MainActivity, getString(R.string.ui_destino_del_reenvio)))
            addView(input)
            addView(UiKit.primaryButton(this@MainActivity, getString(R.string.ui_guardar_numero_de_destino)) {
                SettingsStore.setTargetPhone(this@MainActivity, input.text.toString())
                render()
            })
        }
    }

    private fun switchCard(): LinearLayout {
        val enabledSwitch = Switch(this).apply {
            text = getString(R.string.ui_activar_reenvio_de_sms)
            refreshing = true
            isChecked = SettingsStore.isEnabled(this@MainActivity)
            refreshing = false
            setOnCheckedChangeListener { _, checked ->
                if (refreshing) return@setOnCheckedChangeListener
                if (checked && !canEnableForwarding()) {
                    SettingsStore.setEnabled(this@MainActivity, false)
                    isChecked = false
                    Toast.makeText(this@MainActivity, missingEnableMessage(), Toast.LENGTH_SHORT).show()
                    requestMissingCorePermissions()
                    render()
                    return@setOnCheckedChangeListener
                }
                SettingsStore.setEnabled(this@MainActivity, checked)
                if (checked) startForegroundService(Intent(this@MainActivity, ForwardForegroundService::class.java)) else {
                    stopService(Intent(this@MainActivity, ForwardForegroundService::class.java))
                    SettingsStore.setServiceRunning(this@MainActivity, false)
                }
                render()
            }
        }
        return UiKit.card(this).apply {
            addView(enabledSwitch)
            addView(UiKit.subtitle(this@MainActivity, getString(R.string.ui_al_desactivar_se_conservan_los_ajustes_y_los_registros_pero_no_se)))
        }
    }

    private fun quickActionsCard(): LinearLayout = UiKit.card(this).apply {
        addView(UiKit.body(this@MainActivity, getString(R.string.ui_acciones_rapidas)))
        val row1 = UiKit.row(this@MainActivity)
        row1.addView(gridButton(getString(R.string.ui_envio_de_prueba)) {
            SmsForwarder.sendTestMessage(this@MainActivity)
            render()
        })
        row1.addView(gridButton(getString(R.string.ui_ajustes_de_reglas)) { currentTab = Tab.RULES; render() })
        val row2 = UiKit.row(this@MainActivity)
        row2.addView(gridButton(getString(R.string.ui_registros_recientes)) { currentTab = Tab.LOGS; render() })
        row2.addView(gridButton(getString(R.string.debug_info)) { startActivity(Intent(this@MainActivity, DebugActivity::class.java)) })
        addView(row1)
        addView(row2)
    }

    private fun permissionCard(): LinearLayout = UiKit.card(this).apply {
        val row = UiKit.row(this@MainActivity)
        row.addView(UiKit.body(this@MainActivity, getString(R.string.ui_permisos_y_condiciones_de_funcionamiento)).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        row.addView(UiKit.tag(this@MainActivity, if (canForwardNormally()) getString(R.string.ready_to_forward) else getString(R.string.setup_required), if (canForwardNormally()) getColor(R.color.success) else getColor(R.color.warning)))
        addView(row)
        addView(UiKit.divider(this@MainActivity))
        addView(UiKit.body(this@MainActivity, "Recibir SMS: ${if (hasReceiveSmsPermission()) "Permitido" else "No permitido"}"))
        addView(UiKit.body(this@MainActivity, "Enviar SMS: ${if (hasSendSmsPermission()) "Permitido" else "No permitido"}"))
        addView(UiKit.body(this@MainActivity, "Notificaciones: ${if (hasNotificationPermission()) "Permitido" else "No permitido"}"))
        addView(UiKit.body(this@MainActivity, "Batería en segundo plano: ${if (isBatteryUnrestricted()) "Sin restricciones" else "Puede estar restringido"}"))
        if (!hasSendSmsPermission()) {
            addView(UiKit.subtitle(this@MainActivity, getString(R.string.ui_el_permiso_para_enviar_sms_no_esta_habilitado_este_permiso_permit)))
            addView(UiKit.primaryButton(this@MainActivity, getString(R.string.ui_habilitar_permiso_para_enviar_sms)) { requestSendSmsPermission() })
        }
        val actions = UiKit.row(this@MainActivity)
        actions.addView(gridButton(getString(R.string.setup_help)) { showPermissionHelpDialog() })
        actions.addView(gridButton(getString(R.string.ui_volver_a_comprobar)) {
            render()
            Toast.makeText(this@MainActivity, if (canForwardNormally()) getString(R.string.ui_estado_de_permisos_actualizado) else getString(R.string.ui_aun_faltan_ajustes_por_completar), Toast.LENGTH_SHORT).show()
        })
        addView(actions)
        addView(UiKit.secondaryButton(this@MainActivity, getString(R.string.ui_comprobar_funcionamiento)) { startActivity(Intent(this@MainActivity, HealthCheckActivity::class.java)) })
    }

    private fun recentPreviewCard(limit: Int): LinearLayout = UiKit.card(this).apply {
        val row = UiKit.row(this@MainActivity)
        row.addView(UiKit.body(this@MainActivity, getString(R.string.recent_logs)).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        row.addView(UiKit.secondaryButton(this@MainActivity, getString(R.string.view_all)) { currentTab = Tab.LOGS; render() })
        addView(row)
        val events = ForwardLogStore.recentUserEvents(this@MainActivity, limit)
        if (events.length() == 0) {
            addView(UiKit.subtitle(this@MainActivity, getString(R.string.ui_todavia_no_hay_registros_de_sms_validos)))
        } else {
            for (i in 0 until events.length()) {
                val obj = events.getJSONObject(i)
                addView(UiKit.divider(this@MainActivity))
                addView(UiKit.body(this@MainActivity, "${obj.optString("receivedAt")}  ${decisionLabel(obj.optString("decision"))}"))
                addView(UiKit.subtitle(this@MainActivity, "${PhoneMaskUtils.mask(obj.optString("sender"))}  ${MessagePrivacyUtils.maskVerificationCodes(obj.optString("bodyPreview"))}\nReglas: ${obj.optString("primaryMatchedRuleName").ifBlank { "Ninguno" }}"))
            }
        }
    }

    private fun renderRules() {
        contentRoot.addView(UiKit.title(this, getString(R.string.rules)))
        val rules = RuleStore.rules(this)
        val mode = RuleStore.forwardMode(this)
        contentRoot.addView(UiKit.card(this).apply {
            addView(UiKit.body(this@MainActivity, "${getString(R.string.forward_mode)}: ${modeLabel(mode)}"))
            addView(UiKit.subtitle(this@MainActivity, "Activadas ${rules.count { it.enabled }} · Inclusión ${rules.count { it.type == RuleType.INCLUDE }} · Exclusión ${rules.count { it.type == RuleType.EXCLUDE }}"))
            addView(UiKit.primaryButton(this@MainActivity, if (mode == ForwardMode.ALL) getString(R.string.ui_reenviar_solo_coincidencias) else getString(R.string.ui_reenviar_todos_los_sms)) {
                val next = if (mode == ForwardMode.ALL) ForwardMode.MATCH_ONLY else ForwardMode.ALL
                RuleStore.setForwardMode(this@MainActivity, next)
                if (next == ForwardMode.MATCH_ONLY && rules.none { it.enabled && it.type == RuleType.INCLUDE }) {
                    Toast.makeText(this@MainActivity, getString(R.string.ui_no_hay_reglas_de_inclusion_se_omitiran_todos_los_sms), Toast.LENGTH_LONG).show()
                }
                render()
            })
        })
        contentRoot.addView(UiKit.section(this, getString(R.string.ui_acciones_principales)))
        val actions = UiKit.card(this)
        val row1 = UiKit.row(this)
        row1.addView(gridButton(getString(R.string.ui_nueva_regla_de_inclusion)) { startActivity(Intent(this, RuleEditActivity::class.java).putExtra(RuleEditActivity.EXTRA_TYPE, RuleType.INCLUDE.name)) })
        row1.addView(gridButton(getString(R.string.ui_nueva_regla_de_exclusion)) { startActivity(Intent(this, RuleEditActivity::class.java).putExtra(RuleEditActivity.EXTRA_TYPE, RuleType.EXCLUDE.name)) })
        val row2 = UiKit.row(this)
        row2.addView(gridButton(getString(R.string.test_rules)) { startActivity(Intent(this, RuleTestActivity::class.java)) })
        row2.addView(gridButton(getString(R.string.ui_administrar_todas_las_reglas)) { startActivity(Intent(this, RulesActivity::class.java)) })
        actions.addView(row1)
        actions.addView(row2)
        contentRoot.addView(actions)
        contentRoot.addView(UiKit.section(this, getString(R.string.ui_lista_de_reglas)))
        if (rules.isEmpty()) {
            contentRoot.addView(UiKit.card(this).apply { addView(UiKit.body(this@MainActivity, getString(R.string.ui_todavia_no_hay_reglas_de_forma_predeterminada_se_reenviaran_todos))) })
        } else {
            rules.take(20).forEach { rule ->
                contentRoot.addView(UiKit.card(this).apply {
                    val row = UiKit.row(this@MainActivity)
                    row.addView(UiKit.body(this@MainActivity, "${rule.priority}. ${rule.name}").apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                    row.addView(UiKit.tag(this@MainActivity, if (rule.type == RuleType.INCLUDE) "Incluir" else "Excluir", if (rule.type == RuleType.INCLUDE) getColor(R.color.primary) else getColor(R.color.warning)))
                    addView(row)
                    addView(UiKit.subtitle(this@MainActivity, "${DisplayLabels.field(rule.field.name)} / ${DisplayLabels.match(rule.matchMode.name)} / ${rule.pattern.take(48)}\n${if (rule.enabled) "Activada" else "Desactivada"}"))
                })
            }
        }
        contentRoot.addView(UiKit.section(this, getString(R.string.ui_acciones_avanzadas)))
        contentRoot.addView(UiKit.card(this).apply {
            val row = UiKit.row(this@MainActivity)
            row.addView(gridButton(getString(R.string.ui_copiar_reglas_json)) {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("rules", RuleStore.exportRules(this@MainActivity, includePatterns = true)))
                Toast.makeText(this@MainActivity, getString(R.string.ui_reglas_json_copiado), Toast.LENGTH_SHORT).show()
            })
            row.addView(gridButton(getString(R.string.ui_pegar_e_importar_reglas)) { startActivity(Intent(this@MainActivity, RulesActivity::class.java)) })
            addView(row)
        })
    }

    private fun renderLogsPreview() {
        contentRoot.addView(UiKit.title(this, getString(R.string.logs)))
        contentRoot.addView(UiKit.subtitle(this, getString(R.string.ui_ultimos_sms_procesados)))
        contentRoot.addView(recentPreviewCard(20))
        contentRoot.addView(UiKit.primaryButton(this, getString(R.string.ui_abrir_filtros_de_registros)) { startActivity(Intent(this, LogsActivity::class.java)) })
    }

    private fun renderAbout() {
        contentRoot.addView(UiKit.title(this, getString(R.string.about)))
        contentRoot.addView(UiKit.card(this).apply {
            addView(UiKit.body(this@MainActivity, getString(R.string.app_name)).apply { textSize = 18f })
            addView(UiKit.subtitle(this@MainActivity, getString(R.string.ui_sms_quick_forwarder)))
            addView(UiKit.body(this@MainActivity, "${getString(R.string.current_version)}: ${BuildConfig.VERSION_NAME}"))
            addView(UiKit.body(this@MainActivity, "${getString(R.string.build_number)}: ${BuildConfig.VERSION_CODE}"))
            addView(UiKit.body(this@MainActivity, "applicationId: ${BuildConfig.APPLICATION_ID}"))
        })
        contentRoot.addView(updateStatusCard())
        contentRoot.addView(UiKit.card(this).apply {
            addView(UiKit.body(this@MainActivity, getString(R.string.ui_ajustes_de_la_aplicacion)))
            addView(UiKit.primaryButton(this@MainActivity, getString(R.string.ui_repetir_asistente_de_configuracion)) {
                OnboardingPreferences.setStep(this@MainActivity, 0)
                startActivity(Intent(this@MainActivity, OnboardingActivity::class.java))
            })
            val failureSwitch = Switch(this@MainActivity).apply {
                text = getString(R.string.ui_avisos_de_reenvio_fallido)
                isChecked = SettingsStore.failureNotificationsEnabled(this@MainActivity)
                setOnCheckedChangeListener { _, checked -> SettingsStore.setFailureNotificationsEnabled(this@MainActivity, checked) }
            }
            addView(failureSwitch)
            addView(UiKit.secondaryButton(this@MainActivity, "Conservación de registros: ${retentionLabel(LogRetentionDays.fromName(SettingsStore.logRetention(this@MainActivity)))}") { showLogRetentionDialog() })
        })
        contentRoot.addView(UiKit.card(this).apply {
            addView(UiKit.body(this@MainActivity, getString(R.string.ui_datos_y_copias_de_seguridad)))
            addView(UiKit.primaryButton(this@MainActivity, getString(R.string.ui_copia_de_seguridad_y_restauracion)) { startActivity(Intent(this@MainActivity, BackupActivity::class.java)) })
        })
        contentRoot.addView(UiKit.card(this).apply {
            addView(UiKit.body(this@MainActivity, getString(R.string.ui_funcionamiento_y_diagnostico)))
            addView(UiKit.primaryButton(this@MainActivity, getString(R.string.ui_comprobar_funcionamiento)) { startActivity(Intent(this@MainActivity, HealthCheckActivity::class.java)) })
            addView(UiKit.secondaryButton(this@MainActivity, getString(R.string.debug_info)) { startActivity(Intent(this@MainActivity, DebugActivity::class.java)) })
            addView(UiKit.secondaryButton(this@MainActivity, getString(R.string.copy_diagnostic_summary)) {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("diagnostics", DebugInfoBuilder.diagnosticSummary(this@MainActivity)))
                Toast.makeText(this@MainActivity, getString(R.string.ui_resumen_de_diagnostico_copiado), Toast.LENGTH_SHORT).show()
            })
        })
        contentRoot.addView(UiKit.card(this).apply {
            addView(UiKit.body(this@MainActivity, getString(R.string.ui_version_y_codigo_abierto)))
            addView(UiKit.primaryButton(this@MainActivity, getString(R.string.changelog)) { startActivity(Intent(this@MainActivity, ChangelogActivity::class.java)) })
            addView(UiKit.secondaryButton(this@MainActivity, getString(R.string.github_repository)) { openUrl("https://github.com/oncet886/SmsQuickForwarder") })
            addView(UiKit.secondaryButton(this@MainActivity, getString(R.string.github_releases)) { openUrl("https://github.com/oncet886/SmsQuickForwarder/releases") })
        })
        contentRoot.addView(UiKit.card(this).apply {
            addView(UiKit.body(this@MainActivity, getString(R.string.ui_privacidad_e_informacion)))
            addView(UiKit.subtitle(this@MainActivity, getString(R.string.ui_el_permiso_de_internet_solo_se_usa_para_buscar_nuevas_versiones_e)))
        })
    }

    private fun failureWarningCard(): LinearLayout? {
        val failureAt = ForwardLogStore.findLatestTime(this) { it.optString("decision").contains("failed", ignoreCase = true) }
        val successAt = ForwardLogStore.findLatestTime(this) { isForwardSuccess(it.optString("decision")) }
        if (!FailureNotificationPolicy.hasActiveFailure(failureAt, successAt)) return null
        val event = ForwardLogStore.latestFailureEvent(this)
        return UiKit.card(this).apply {
            addView(UiKit.body(this@MainActivity, getString(R.string.ui_ultimo_reenvio_fallido)))
            addView(UiKit.subtitle(this@MainActivity, "Motivo: ${ForwardLogStore.latestFailureReason(this@MainActivity).ifBlank { "Fallo desconocido" }}\nFecha: $failureAt"))
            val row = UiKit.row(this@MainActivity)
            row.addView(gridButton(getString(R.string.ui_ver_detalles)) {
                event?.optString("eventId")?.takeIf { it.isNotBlank() }?.let {
                    startActivity(Intent(this@MainActivity, LogDetailActivity::class.java).putExtra(LogDetailActivity.EXTRA_EVENT_ID, it))
                }
            })
            row.addView(gridButton(getString(R.string.ui_volver_a_comprobar)) { render() })
            row.addView(gridButton("Corregir") { startActivity(Intent(this@MainActivity, HealthCheckActivity::class.java)) })
            addView(row)
        }
    }

    private fun updateStatusCard(): LinearLayout = UiKit.card(this).apply {
        addView(UiKit.body(this@MainActivity, getString(R.string.version_updates)).apply { textSize = 16f })
        addView(UiKit.body(this@MainActivity, "Versión actual: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
        addView(UiKit.body(this@MainActivity, "Buscar actualizaciones automáticamente: ${if (UpdatePreferences.autoCheckEnabled(this@MainActivity)) "Activado" else "Desactivado"}"))
        addView(UiKit.body(this@MainActivity, "Frecuencia de comprobación: ${frequencyLabel(UpdatePreferences.frequency(this@MainActivity))}"))
        addView(UiKit.body(this@MainActivity, "Última comprobación: ${formatCheckTime(UpdatePreferences.lastCheckAt(this@MainActivity))}"))
        addView(UiKit.body(this@MainActivity, "Estado actual: ${updateStateLabel()}"))
        val ignored = UpdatePreferences.ignoredVersion(this@MainActivity)
        if (ignored.isNotBlank()) addView(UiKit.subtitle(this@MainActivity, "Versión ignorada: $ignored"))
        val row1 = UiKit.row(this@MainActivity)
        row1.addView(gridButton(if (updateChecking) "Comprobando..." else getString(R.string.check_now)) { manualCheckUpdates() })
        row1.addView(gridButton(getString(R.string.github_releases)) { openUrl("https://github.com/oncet886/SmsQuickForwarder/releases") })
        val row2 = UiKit.row(this@MainActivity)
        row2.addView(gridButton(getString(R.string.update_settings)) { showUpdateSettingsDialog() })
        val latestUrl = (updateResult as? UpdateCheckResult.UpdateAvailable)?.info?.releaseUrl ?: UpdatePreferences.lastKnownReleaseUrl(this@MainActivity)
        row2.addView(gridButton(getString(R.string.view_update)) {
            openUrl(latestUrl.ifBlank { "https://github.com/oncet886/SmsQuickForwarder/releases" })
        })
        addView(row1)
        addView(row2)
    }

    private fun gridButton(text: String, onClick: () -> Unit) = UiKit.secondaryButton(this, text, onClick).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(UiKit.dp(this@MainActivity, 4), UiKit.dp(this@MainActivity, 5), UiKit.dp(this@MainActivity, 4), UiKit.dp(this@MainActivity, 5))
        }
    }

    private fun currentState(): State = when {
        !hasReceiveSmsPermission() || !hasSendSmsPermission() -> State(getString(R.string.permission_required), getColor(R.color.warning))
        SettingsStore.targetPhone(this).isBlank() -> State(getString(R.string.setup_incomplete), getColor(R.color.warning))
        SettingsStore.isEnabled(this) -> State(getString(R.string.running), getColor(R.color.success))
        else -> State(getString(R.string.paused), getColor(R.color.muted))
    }

    private fun decisionLabel(decision: String): String = when {
        isForwardSuccess(decision) -> "Reenviado"
        decision.contains("failed", ignoreCase = true) -> getString(R.string.ui_fallo)
        decision.startsWith("skipped") -> "Omitido"
        else -> decision
    }

    private fun isForwardSuccess(decision: String): Boolean =
        decision == "forwarded" || decision == "forwarded_rule_match" || decision == "forwarded_all_mode"

    private fun modeLabel(mode: ForwardMode): String =
        if (mode == ForwardMode.ALL) getString(R.string.forward_all_sms) else getString(R.string.forward_matched_sms_only)

    private fun maybeStartOnboarding() {
        val snapshot = OnboardingSnapshot(
            completed = OnboardingPreferences.completed(this),
            hasTarget = SettingsStore.targetPhone(this).isNotBlank(),
            hasExistingLogs = ForwardLogStore.totalCount(this) > 0,
            hasExistingRules = RuleStore.rules(this).isNotEmpty(),
            legacyGuideShown = SettingsStore.isSetupGuideShown(this)
        )
        if (!snapshot.completed && !OnboardingState.shouldShow(snapshot)) {
            OnboardingPreferences.markUpgradeCompleted(this)
            return
        }
        if (OnboardingState.shouldShow(snapshot)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            return
        }
        maybeShowFirstRunGuide()
    }

    private fun maybeShowFirstRunGuide() {
        if (SettingsStore.isSetupGuideShown(this)) return
        SettingsStore.setSetupGuideShown(this, true)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_primeros_pasos))
            .setMessage(getString(R.string.ui_1_permitir_recibir_sms_npermite_detectar_los_sms_recibidos_en_est))
            .setPositiveButton("Configurar") { _, _ -> requestNeededPermissions() }
            .show()
    }

    private fun requestSendSmsPermission() {
        if (hasSendSmsPermission()) {
            render()
            return
        }
        val permanentlyDenied = SettingsStore.wasSendSmsRequested(this) && !shouldShowRequestPermissionRationale(Manifest.permission.SEND_SMS)
        if (permanentlyDenied) {
            showSendSmsSettingsDialog()
            return
        }
        SettingsStore.setSendSmsRequested(this, true)
        requestPermissions(arrayOf(Manifest.permission.SEND_SMS), REQUEST_SEND_SMS)
    }

    private fun requestMissingCorePermissions() {
        val missing = mutableListOf<String>()
        if (!hasReceiveSmsPermission()) missing.add(Manifest.permission.RECEIVE_SMS)
        if (!hasSendSmsPermission()) missing.add(Manifest.permission.SEND_SMS)
        if (missing.isEmpty()) return
        if (missing.contains(Manifest.permission.SEND_SMS)) SettingsStore.setSendSmsRequested(this, true)
        requestPermissions(missing.toTypedArray(), REQUEST_CORE_PERMISSIONS)
    }

    private fun requestNeededPermissions() {
        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.contains(Manifest.permission.SEND_SMS)) SettingsStore.setSendSmsRequested(this, true)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_ALL_PERMISSIONS)
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
        val sendIndex = permissions.indexOf(Manifest.permission.SEND_SMS)
        if (sendIndex >= 0 && !hasSendSmsPermission() && SettingsStore.wasSendSmsRequested(this) && !shouldShowRequestPermissionRationale(Manifest.permission.SEND_SMS)) {
            showSendSmsSettingsDialog()
        }
    }

    private fun showSendSmsSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_es_necesario_habilitar_el_permiso_para_enviar_sms))
            .setMessage(getString(R.string.ui_sms_quick_forwarder_requiere_permiso_para_enviar_sms_para_reenvia))
            .setPositiveButton(getString(R.string.ui_abrir_ajustes_de_la_aplicacion)) { _, _ -> openAppSettings() }
            .setNegativeButton(getString(R.string.ui_ahora_no), null)
            .show()
    }

    private fun showPermissionHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_ajustes_de_permisos_de_sms))
            .setMessage(getString(R.string.ui_samsung_najustes_aplicaciones_sms_quick_forwarder_permisos_sms_pe))
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, getString(R.string.ui_no_se_pudieron_abrir_los_ajustes_automaticamente_abri_manualmente), Toast.LENGTH_LONG).show()
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure {
            Toast.makeText(this, getString(R.string.ui_no_se_pudo_abrir_el_enlace), Toast.LENGTH_SHORT).show()
        }
    }

    private fun scheduleStartupUpdateCheck() {
        if (!UpdatePreferences.shouldCheckNow(this)) return
        Thread {
            runCatching {
                Thread.sleep(2_000)
                val result = UpdateChecker.create(this).check()
                UpdatePreferences.recordResult(this, result)
                if (result is UpdateCheckResult.UpdateAvailable) UpdateNotifier.notifyIfNeeded(this, result.info)
                runOnUiThread {
                    updateResult = result
                    if (::contentRoot.isInitialized) render()
                }
            }
        }.start()
    }

    private fun manualCheckUpdates() {
        if (updateChecking) return
        updateChecking = true
        render()
        Thread {
            val result = UpdateChecker.create(this).check()
            UpdatePreferences.recordResult(this, result)
            if (result is UpdateCheckResult.UpdateAvailable) UpdateNotifier.notifyIfNeeded(this, result.info)
            runOnUiThread {
                updateChecking = false
                updateResult = result
                Toast.makeText(this, updateStateLabel(result), Toast.LENGTH_SHORT).show()
                render()
            }
        }.start()
    }

    private fun showUpdateSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@MainActivity, 8), UiKit.dp(this@MainActivity, 6), UiKit.dp(this@MainActivity, 8), 0)
        }
        val autoSwitch = Switch(this).apply {
            text = getString(R.string.ui_buscar_actualizaciones_automaticamente)
            isChecked = UpdatePreferences.autoCheckEnabled(this@MainActivity)
        }
        val notificationSwitch = Switch(this).apply {
            text = getString(R.string.ui_notificaciones_en_segundo_plano)
            isChecked = UpdatePreferences.notificationsEnabled(this@MainActivity)
        }
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val daily = frequencyRadio(getString(R.string.ui_cada_dia), UpdateFrequency.DAILY)
        val weekly = frequencyRadio(getString(R.string.ui_cada_semana), UpdateFrequency.WEEKLY)
        val startup = frequencyRadio(getString(R.string.ui_solo_al_iniciar), UpdateFrequency.ON_APP_START_ONLY)
        group.addView(daily)
        group.addView(weekly)
        group.addView(startup)
        when (UpdatePreferences.frequency(this)) {
            UpdateFrequency.DAILY -> daily.isChecked = true
            UpdateFrequency.WEEKLY -> weekly.isChecked = true
            UpdateFrequency.ON_APP_START_ONLY -> startup.isChecked = true
        }
        container.addView(autoSwitch)
        container.addView(UiKit.subtitle(this, getString(R.string.ui_comprobar_github_releases_solo_consulta_la_informacion_publica_de)))
        container.addView(notificationSwitch)
        if (!hasNotificationPermission()) container.addView(UiKit.subtitle(this, getString(R.string.ui_si_no_se_concedio_el_permiso_de_notificaciones_solo_se_muestran_a)))
        container.addView(UiKit.body(this, getString(R.string.ui_frecuencia_de_comprobacion)))
        container.addView(group)
        val ignored = UpdatePreferences.ignoredVersion(this)
        container.addView(UiKit.subtitle(this, "Versión ignorada: ${ignored.ifBlank { "Ninguno" }}"))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_settings))
            .setView(container)
            .setPositiveButton("Guardar") { _, _ ->
                UpdatePreferences.setAutoCheckEnabled(this, autoSwitch.isChecked)
                UpdatePreferences.setNotificationsEnabled(this, notificationSwitch.isChecked)
                UpdatePreferences.setFrequency(this, selectedFrequency(group.checkedRadioButtonId))
                UpdateScheduler.configure(this)
                render()
            }
            .setNeutralButton(getString(R.string.ui_restablecer_version_ignorada)) { _, _ ->
                UpdatePreferences.clearIgnoredVersion(this)
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun frequencyRadio(label: String, frequency: UpdateFrequency): RadioButton =
        RadioButton(this).apply {
            text = label
            id = frequency.ordinal + 10_000
        }

    private fun selectedFrequency(checkedId: Int): UpdateFrequency =
        UpdateFrequency.values().firstOrNull { it.ordinal + 10_000 == checkedId } ?: UpdateFrequency.DAILY

    private fun frequencyLabel(frequency: UpdateFrequency): String = when (frequency) {
        UpdateFrequency.DAILY -> getString(R.string.ui_cada_dia)
        UpdateFrequency.WEEKLY -> getString(R.string.ui_cada_semana)
        UpdateFrequency.ON_APP_START_ONLY -> getString(R.string.ui_solo_al_iniciar)
    }

    private fun updateStateLabel(result: UpdateCheckResult? = updateResult): String = when (result) {
        is UpdateCheckResult.UpdateAvailable -> "Nueva versión disponible ${result.info.latestVersionName}"
        is UpdateCheckResult.UpToDate -> getString(R.string.up_to_date)
        is UpdateCheckResult.Error -> "No se pudo comprobar: ${result.message}"
        null -> if (UpdatePreferences.lastKnownLatestVersion(this).isBlank()) getString(R.string.ui_sin_comprobar) else "Última versión detectada ${UpdatePreferences.lastKnownLatestVersion(this)}"
    }

    private fun releaseNotesPreview(notes: String): String {
        val lines = notes.lines().map { it.trim().trimStart('-', '*').trim() }.filter { it.isNotBlank() }
        return lines.take(4).joinToString("\n") { "- $it" }.ifBlank { getString(R.string.ui_ver_github_release_para_ver_los_detalles) }
    }

    private fun formatCheckTime(timestamp: Long): String =
        if (timestamp <= 0L) getString(R.string.ui_sin_comprobar) else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(timestamp))

    private fun showLogRetentionDialog() {
        val labels = LogRetentionDays.values().map { retentionLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_conservacion_de_registros))
            .setItems(labels) { _, which ->
                val selected = LogRetentionDays.values()[which]
                SettingsStore.setLogRetention(this, selected.name)
                if (selected != LogRetentionDays.FOREVER) ForwardLogStore.pruneByRetention(this, selected)
                render()
            }
            .show()
    }

    private fun retentionLabel(value: LogRetentionDays): String = when (value) {
        LogRetentionDays.DAYS_7 -> getString(R.string.ui_7_dias)
        LogRetentionDays.DAYS_30 -> getString(R.string.ui_30_dias)
        LogRetentionDays.DAYS_90 -> getString(R.string.ui_90_dias)
        LogRetentionDays.FOREVER -> getString(R.string.ui_conservar_siempre)
    }

    private fun canEnableForwarding(): Boolean =
        SettingsStore.targetPhone(this).isNotBlank() && hasReceiveSmsPermission() && hasSendSmsPermission()

    private fun canForwardNormally(): Boolean =
        SettingsStore.targetPhone(this).isNotBlank() && hasReceiveSmsPermission() && hasSendSmsPermission() && hasNotificationPermission()

    private fun missingEnableMessage(): String = when {
        SettingsStore.targetPhone(this).isBlank() -> getString(R.string.ui_primero_configura_el_numero_de_destino)
        !hasReceiveSmsPermission() && !hasSendSmsPermission() -> getString(R.string.ui_primero_habilita_los_permisos_para_recibir_y_enviar_sms)
        !hasSendSmsPermission() -> getString(R.string.ui_primero_habilita_el_permiso_para_enviar_sms)
        !hasReceiveSmsPermission() -> getString(R.string.ui_primero_habilita_el_permiso_para_recibir_sms)
        else -> getString(R.string.ui_aun_faltan_ajustes_por_completar)
    }

    private fun neededPermissions(): List<String> {
        val list = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list
    }

    private fun hasReceiveSmsPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    private fun hasSendSmsPermission(): Boolean =
        checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun isBatteryUnrestricted(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private enum class Tab { HOME, RULES, LOGS, ABOUT }
    private data class State(val label: String, val color: Int)

    companion object {
        private const val REQUEST_SEND_SMS = 301
        private const val REQUEST_CORE_PERMISSIONS = 302
        private const val REQUEST_ALL_PERMISSIONS = 303
    }
}
