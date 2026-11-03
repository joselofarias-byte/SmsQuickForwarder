package com.oncet.smsquickforwarder.backup

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.oncet.smsquickforwarder.R
import com.oncet.smsquickforwarder.service.ForwardForegroundService
import com.oncet.smsquickforwarder.ui.SystemBarsInsets
import com.oncet.smsquickforwarder.ui.UiKit

class BackupActivity : Activity() {
    private lateinit var root: LinearLayout
    private val currentBackup: String get() = BackupManager.exportJson(this)

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
        root.addView(UiKit.title(this, getString(R.string.ui_copia_de_seguridad_y_restauracion)))
        root.addView(UiKit.subtitle(this, getString(R.string.ui_solo_se_guardan_los_ajustes_y_las_reglas_no_incluye_el_contenido_)))
        root.addView(UiKit.card(this).apply {
            addView(UiKit.body(this@BackupActivity, getString(R.string.ui_exportar_configuracion)))
            addView(UiKit.primaryButton(this@BackupActivity, getString(R.string.ui_copiar_json_texto)) {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.ui_smsquickforwarder_backup), currentBackup))
                Toast.makeText(this@BackupActivity, getString(R.string.ui_copia_de_seguridad_json_copiado), Toast.LENGTH_SHORT).show()
            })
            addView(UiKit.secondaryButton(this@BackupActivity, getString(R.string.ui_compartir_json_archivo)) { shareBackupFile() })
            addView(UiKit.secondaryButton(this@BackupActivity, getString(R.string.ui_guardar_como_json_archivo)) { saveBackupFile() })
        })
        root.addView(UiKit.card(this).apply {
            addView(UiKit.body(this@BackupActivity, getString(R.string.ui_restaurar_configuracion)))
            addView(UiKit.subtitle(this@BackupActivity, getString(R.string.ui_pegar_copia_de_seguridad_json_se_mostrara_una_vista_previa_antes_)))
            addView(UiKit.primaryButton(this@BackupActivity, getString(R.string.ui_pegar_e_importar_json)) { showPasteImportDialog() })
        })
    }

    private fun shareBackupFile() {
        val file = BackupManager.writeCacheFile(this)
        val uri = FileProvider.getUriForFile(this, "${packageName}.debugfileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("sms_backup_json", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.ui_compartir_copia_de_seguridad)))
    }

    private fun saveBackupFile() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/json"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, BackupManager.writeCacheFile(this@BackupActivity).name)
        }
        runCatching { startActivityForResult(intent, 701) }.onFailure { shareBackupFile() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 701 && resultCode == RESULT_OK) {
            data?.data?.let { uri -> writeUri(uri, currentBackup) }
        }
    }

    private fun writeUri(uri: Uri, raw: String) {
        runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(raw.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, getString(R.string.ui_copia_de_seguridad_guardada), Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(this, getString(R.string.ui_no_se_pudo_guardar), Toast.LENGTH_SHORT).show() }
    }

    private fun showPasteImportDialog() {
        val input = EditText(this).apply {
            minLines = 8
            hint = "{ ... }"
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_pegar_copia_de_seguridad_json))
            .setView(input)
            .setPositiveButton(getString(R.string.ui_vista_previa)) { _, _ -> showPreview(input.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showPreview(raw: String) {
        val preview = BackupManager.preview(raw)
        AlertDialog.Builder(this)
            .setTitle(if (preview.valid) getString(R.string.ui_vista_previa_de_restauracion) else getString(R.string.ui_no_se_puede_restaurar))
            .setMessage("Versión: ${preview.appVersionName}\nFecha de exportación: ${preview.exportedAt}\nNúmero de destino: ${if (preview.hasTargetPhone) "Incluido" else "Ninguno"}\nCantidad de reglas: ${preview.ruleCount}\nCampos desconocidos: ${preview.unknownFieldCount}\nEstado: ${preview.message}")
            .setPositiveButton(getString(R.string.ui_restaurar_y_combinar)) { _, _ -> restore(raw, RestoreMode.MERGE) }
            .setNeutralButton(getString(R.string.ui_reemplazar_todo)) { _, _ -> confirmReplace(raw) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmReplace(raw: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_confirmar_reemplazo_completo))
            .setMessage(getString(R.string.ui_se_reemplazaran_los_ajustes_y_las_reglas_actuales_antes_de_restau))
            .setPositiveButton(getString(R.string.ui_confirmar_reemplazo)) { _, _ -> restore(raw, RestoreMode.REPLACE) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun restore(raw: String, mode: RestoreMode) {
        val result = BackupManager.restore(this, raw, mode)
        if (com.oncet.smsquickforwarder.data.SettingsStore.isEnabled(this)) {
            startForegroundService(Intent(this, ForwardForegroundService::class.java))
        } else {
            stopService(Intent(this, ForwardForegroundService::class.java))
        }
        Toast.makeText(this, "Restauración completada: Agregadas ${result.validRules}, Duplicadas ${result.duplicateRules}, No válidas ${result.invalidRules}", Toast.LENGTH_LONG).show()
        render()
    }
}
