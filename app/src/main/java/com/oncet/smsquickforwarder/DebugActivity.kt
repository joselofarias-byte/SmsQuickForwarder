package com.oncet.smsquickforwarder

import android.app.Activity
import android.content.ActivityNotFoundException
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.oncet.smsquickforwarder.data.ForwardLogStore
import com.oncet.smsquickforwarder.debug.DebugInfoBuilder
import com.oncet.smsquickforwarder.ui.SystemBarsInsets
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugActivity : Activity() {
    private lateinit var includeBodySwitch: android.widget.Switch
    private lateinit var includePhoneSwitch: android.widget.Switch
    private lateinit var debugText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.debug_info)
            textSize = 22f
        })

        includeBodySwitch = android.widget.Switch(this).apply {
            text = getString(R.string.ui_incluir_el_contenido_completo_de_los_sms)
            isChecked = false
            setOnCheckedChangeListener { _: CompoundButton, _: Boolean -> refresh() }
        }
        root.addView(includeBodySwitch)

        includePhoneSwitch = android.widget.Switch(this).apply {
            text = getString(R.string.ui_incluir_los_numeros_completos_al_compartir)
            isChecked = false
        }
        root.addView(includePhoneSwitch)

        root.addView(Button(this).apply {
            text = getString(R.string.copy_diagnostic_summary)
            setOnClickListener { copyDiagnosticSummary() }
        })

        root.addView(Button(this).apply {
            text = getString(R.string.copy_full_json)
            setOnClickListener { copyFullJson() }
        })

        root.addView(Button(this).apply {
            text = getString(R.string.share_as_text)
            setOnClickListener { shareJsonAsText() }
        })

        root.addView(Button(this).apply {
            text = getString(R.string.share_json_file)
            setOnClickListener { shareJsonFile() }
        })

        root.addView(Button(this).apply {
            text = getString(R.string.ui_abrir_ajustes)
            setOnClickListener { openAppSettings() }
        })

        root.addView(Button(this).apply {
            text = getString(R.string.clear_debug_logs)
            setOnClickListener { confirmClearLogs() }
        })

        root.addView(Button(this).apply {
            text = getString(R.string.ui_ajustes_de_optimizacion_de_bateria)
            setOnClickListener { openBatterySettings() }
        })

        root.addView(Button(this).apply {
            text = "Actualizar"
            setOnClickListener { refresh() }
        })

        debugText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 18, 0, 0)
        }
        root.addView(debugText)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        with(SystemBarsInsets) { applyStandardSystemBars(scroll, handleIme = true) }
    }

    private fun refresh() {
        debugText.text = DebugInfoBuilder.summaryText(this)
    }

    private fun currentJsonText(): String = DebugInfoBuilder.build(
        this,
        includeFullBody = includeBodySwitch.isChecked,
        includeFullPhone = includePhoneSwitch.isChecked
    ).toString(2)

    private fun shareJsonFile() {
        val jsonText = currentJsonText()
        try {
            val file = writeDebugJson(jsonText)
            val uri = FileProvider.getUriForFile(this, "$packageName.debugfileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.ui_smsquickforwarder_debug_json))
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("sms_debug_json", uri)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_json_file)))
        } catch (e: ActivityNotFoundException) {
            showShareFallbackDialog(jsonText)
        } catch (e: SecurityException) {
            showShareFallbackDialog(jsonText)
        } catch (e: Exception) {
            showShareFallbackDialog(jsonText)
        }
    }

    private fun copyFullJson() {
        copyTextToClipboard(getString(R.string.ui_sms_quick_forwarder_debug_json), currentJsonText())
        Toast.makeText(this, getString(R.string.ui_depuracion_json_copiado_podes_pegarlo_directamente_en_el_chat), Toast.LENGTH_LONG).show()
    }

    private fun shareJsonAsText() {
        val jsonText = currentJsonText()
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, jsonText)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.ui_smsquickforwarder_debug_json))
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_as_text)))
        } catch (e: Exception) {
            copyTextToClipboard(getString(R.string.ui_sms_quick_forwarder_debug_json), jsonText)
            Toast.makeText(this, getString(R.string.ui_no_se_pudo_compartir_depuracion_json_copiado_podes_pegarlo_direct), Toast.LENGTH_LONG).show()
        }
    }

    private fun showShareFallbackDialog(jsonText: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_compartir_json_no_se_pudo_compartir_el_archivo))
            .setMessage(getString(R.string.ui_destino_app_puede_no_admitir_json_archivo_podes_copiar_el_json_o_))
            .setPositiveButton(getString(R.string.copy_full_json)) { _, _ ->
                copyTextToClipboard(getString(R.string.ui_sms_quick_forwarder_debug_json), jsonText)
                Toast.makeText(this, getString(R.string.ui_depuracion_json_copiado_podes_pegarlo_directamente_en_el_chat), Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(getString(R.string.share_as_text)) { _, _ -> shareJsonAsText() }
            .show()
    }

    private fun copyTextToClipboard(label: String, text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun writeDebugJson(jsonText: String): File {
        val dir = File(cacheDir, "debug_exports").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "sms-quick-forwarder-debug-$timestamp.json")
        OutputStreamWriter(file.outputStream(), Charsets.UTF_8).use { it.write(jsonText) }
        return file
    }

    private fun copyDiagnosticSummary() {
        copyTextToClipboard(getString(R.string.ui_sms_quick_forwarder_diagnostics), DebugInfoBuilder.diagnosticSummary(this))
        Toast.makeText(this, getString(R.string.ui_resumen_de_diagnostico_copiado), Toast.LENGTH_SHORT).show()
    }

    private fun confirmClearLogs() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_eliminar_registros_de_depuracion))
            .setMessage(getString(R.string.ui_solo_se_eliminan_los_registros_de_depuracion_los_ajustes_de_reenv))
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Vaciar") { _, _ ->
                ForwardLogStore.clear(this)
                refresh()
            }
            .show()
    }

    private fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { startActivity(intent) }.onFailure {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(
                this,
                getString(R.string.ui_no_se_pudieron_abrir_los_ajustes_automaticamente_abri_manualmente),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }
}
