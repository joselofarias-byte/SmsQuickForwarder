package com.oncet.smsquickforwarder.health

import com.oncet.smsquickforwarder.DisplayLabels
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import com.oncet.smsquickforwarder.data.ForwardLogStore
import com.oncet.smsquickforwarder.data.SettingsStore
import com.oncet.smsquickforwarder.failure.FailureNotificationPolicy
import com.oncet.smsquickforwarder.rules.ForwardMode
import com.oncet.smsquickforwarder.rules.RuleStore
import com.oncet.smsquickforwarder.rules.RuleType
import com.oncet.smsquickforwarder.update.UpdatePreferences
import com.oncet.smsquickforwarder.update.UpdateScheduler

class HealthCheckEngine(private val context: Context) {
    fun run(): HealthReport {
        val items = mutableListOf<HealthCheckItem>()
        items.add(item("Activar reenvío", SettingsStore.isEnabled(context), "Activado", "En pausa", HealthSeverity.WARNING))
        items.add(item("Número de destino", SettingsStore.targetPhone(context).isNotBlank(), "Configurado", "No se configuró un número de destino", HealthSeverity.ACTION_REQUIRED))
        items.add(permissionItem("Permiso para recibir SMS", Manifest.permission.RECEIVE_SMS))
        items.add(permissionItem("Permiso para enviar SMS", Manifest.permission.SEND_SMS))
        items.add(HealthCheckItem("Permiso de notificaciones", if (hasNotificationPermission()) HealthSeverity.OK else HealthSeverity.WARNING, if (hasNotificationPermission()) "Permitido" else "Sin permiso; las notificaciones no están disponibles", "Conceder permiso"))
        items.add(HealthCheckItem("SmsReceiver", HealthSeverity.OK, "Declarado en el manifiesto"))
        items.add(HealthCheckItem("BootReceiver", HealthSeverity.OK, "Declarado en el manifiesto"))
        items.add(HealthCheckItem("Servicio en primer plano", if (!SettingsStore.isEnabled(context) || SettingsStore.isServiceRunning(context)) HealthSeverity.OK else HealthSeverity.WARNING, if (SettingsStore.isServiceRunning(context)) "En ejecución" else "Detenido"))
        items.add(HealthCheckItem("Optimización de batería", if (batteryOk()) HealthSeverity.OK else HealthSeverity.WARNING, if (batteryOk()) "Sin restricciones" else "Puede estar restringido", "Abrir ajustes"))
        val lastSms = ForwardLogStore.findLatestTime(context) { it.optString("eventType") == "sms_received" }
        val lastSuccess = ForwardLogStore.findLatestTime(context) { isForwardSuccess(it.optString("decision")) }
        val lastFailure = ForwardLogStore.findLatestTime(context) { it.optString("decision").contains("failed", ignoreCase = true) }
        items.add(HealthCheckItem("Último SMS real recibido", HealthSeverity.OK, lastSms.ifBlank { "Todavía no se probó con un SMS real" }))
        items.add(HealthCheckItem("Último reenvío correcto", HealthSeverity.OK, lastSuccess.ifBlank { "Todavía no hay reenvíos correctos registrados" }))
        val activeFailure = FailureNotificationPolicy.hasActiveFailure(lastFailure, lastSuccess)
        items.add(HealthCheckItem("Último fallo", if (activeFailure) HealthSeverity.ACTION_REQUIRED else HealthSeverity.OK, if (lastFailure.isBlank()) "No hay fallos registrados" else "${ForwardLogStore.latestFailureReason(context)} ${if (activeFailure) "" else "; el servicio se recuperó después"}"))
        items.add(HealthCheckItem("Búsqueda automática de actualizaciones", if (UpdatePreferences.autoCheckEnabled(context)) HealthSeverity.OK else HealthSeverity.WARNING, if (UpdatePreferences.autoCheckEnabled(context)) "Activado" else "Desactivado"))
        val mode = RuleStore.forwardMode(context)
        val includeOk = mode != ForwardMode.MATCH_ONLY || RuleStore.rules(context).any { it.enabled && it.type == RuleType.INCLUDE }
        items.add(HealthCheckItem("Modo de reglas actual", if (includeOk) HealthSeverity.OK else HealthSeverity.ACTION_REQUIRED, if (includeOk) DisplayLabels.mode(mode.name) else "No hay reglas de inclusión activadas en el modo de coincidencias"))
        items.add(HealthCheckItem("Almacenamiento de registros", if (ForwardLogStore.totalCount(context) >= 0) HealthSeverity.OK else HealthSeverity.WARNING, "${ForwardLogStore.totalCount(context)} registros guardados"))
        return HealthReport(HealthRules.overall(items), items)
    }

    private fun permissionItem(name: String, permission: String): HealthCheckItem {
        val ok = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return HealthCheckItem(name, if (ok) HealthSeverity.OK else HealthSeverity.ACTION_REQUIRED, if (ok) "Permitido" else "Sin permiso; no se puede reenviar automáticamente", "Conceder permiso")
    }

    private fun item(name: String, ok: Boolean, okText: String, badText: String, badSeverity: HealthSeverity): HealthCheckItem =
        HealthCheckItem(name, if (ok) HealthSeverity.OK else badSeverity, if (ok) okText else badText)

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun batteryOk(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    private fun isForwardSuccess(decision: String): Boolean =
        decision == "forwarded" || decision == "forwarded_rule_match" || decision == "forwarded_all_mode"
}
