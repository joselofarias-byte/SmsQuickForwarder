package com.oncet.smsquickforwarder.health

import com.oncet.smsquickforwarder.R
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import com.oncet.smsquickforwarder.ui.SystemBarsInsets
import com.oncet.smsquickforwarder.ui.UiKit

class HealthCheckActivity : Activity() {
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = UiKit.page(this)
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        with(SystemBarsInsets) { applyStandardSystemBars(scroll, handleIme = false) }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) render()
    }

    private fun render() {
        val report = HealthCheckEngine(this).run()
        root.removeAllViews()
        root.addView(UiKit.title(this, getString(R.string.ui_comprobar_funcionamiento)))
        root.addView(UiKit.card(this).apply {
            val text = when (report.overall) {
                HealthSeverity.OK -> getString(R.string.ui_funcionamiento_correcto)
                HealthSeverity.WARNING -> "Se encontraron ${report.warningCount} elementos que requieren atención"
                HealthSeverity.ACTION_REQUIRED -> "Se encontraron ${report.actionRequiredCount} elementos que requieren intervención"
            }
            addView(UiKit.body(this@HealthCheckActivity, text))
        })
        report.items.forEach { item ->
            root.addView(UiKit.card(this).apply {
                val color = when (item.severity) {
                    HealthSeverity.OK -> getColor(com.oncet.smsquickforwarder.R.color.success)
                    HealthSeverity.WARNING -> getColor(com.oncet.smsquickforwarder.R.color.warning)
                    HealthSeverity.ACTION_REQUIRED -> getColor(com.oncet.smsquickforwarder.R.color.danger)
                }
                val row = UiKit.row(this@HealthCheckActivity)
                row.addView(UiKit.body(this@HealthCheckActivity, item.name).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(UiKit.tag(this@HealthCheckActivity, severityLabel(item.severity), color))
                addView(row)
                addView(UiKit.subtitle(this@HealthCheckActivity, item.summary))
                if (item.actionLabel.isNotBlank()) {
                    addView(UiKit.secondaryButton(this@HealthCheckActivity, item.actionLabel) { openAppSettings() })
                }
            })
        }
    }

    private fun severityLabel(severity: HealthSeverity): String = when (severity) {
        HealthSeverity.OK -> "Correcto"
        HealthSeverity.WARNING -> getString(R.string.ui_atencion)
        HealthSeverity.ACTION_REQUIRED -> "Resolver"
    }

    private fun openAppSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }
}
