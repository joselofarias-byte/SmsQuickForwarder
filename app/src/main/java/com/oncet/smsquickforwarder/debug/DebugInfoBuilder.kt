package com.oncet.smsquickforwarder.debug

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.telephony.SubscriptionManager
import com.oncet.smsquickforwarder.BuildConfig
import com.oncet.smsquickforwarder.data.ForwardLogStore
import com.oncet.smsquickforwarder.data.SettingsStore
import com.oncet.smsquickforwarder.onboarding.OnboardingPreferences
import com.oncet.smsquickforwarder.rules.RuleStore
import com.oncet.smsquickforwarder.ui.SystemBarsInsets
import com.oncet.smsquickforwarder.update.UpdatePreferences
import com.oncet.smsquickforwarder.util.PhoneMaskUtils
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugInfoBuilder {
    fun build(context: Context, includeFullBody: Boolean, includeFullPhone: Boolean): JSONObject {
        val root = JSONObject()
        val errors = JSONArray()
        runCatching {
            root.put("appSettings", appSettings(context))
            root.put("permissions", permissions(context))
            root.put("systemInfo", systemInfo(context))
            root.put("appBuild", appBuild(context))
            root.put("simInfo", simInfo(context))
            root.put("batteryOptimization", batteryOptimization(context))
            root.put("windowInsets", SystemBarsInsets.lastInsetsJson(context))
            root.put("permissionGuidance", permissionGuidance(context))
            root.put("stabilitySelfCheck", stabilitySelfCheck(context))
            root.put("rules", RuleStore.summaryJson(context, includeSensitivePatterns = includeFullPhone))
            root.put("recentEvents", ForwardLogStore.recentEvents(context, 20, includeFullBody, includeFullPhone))
        }.onFailure { e ->
            errors.put("${e.javaClass.simpleName}: ${e.message}")
        }
        root.put("exceptions", errors)
        return root
    }

    fun summaryText(context: Context): String {
        val json = build(context, includeFullBody = false, includeFullPhone = false)
        return buildString {
            append("Ajustes de la aplicación\n")
            append(formatObject(json.optJSONObject("appSettings"))).append("\n\n")
            append("Información de compilación\n")
            append(formatObject(json.optJSONObject("appBuild"))).append("\n\n")
            append("Estado de permisos\n")
            append(permissionSummary(context)).append("\n\n")
            append("Información del sistema\n")
            append(formatObject(json.optJSONObject("systemInfo"))).append("\n\n")
            append("Comprobación de estabilidad\n")
            append(formatObject(json.optJSONObject("stabilitySelfCheck"))).append("\n\n")
            append("Ajustes de reglas\n")
            append(formatObject(json.optJSONObject("rules"))).append("\n\n")
            append("Información de SIM\n")
            append(formatObject(json.optJSONObject("simInfo"))).append("\n\n")
            append("Optimización de batería\n")
            append(formatObject(json.optJSONObject("batteryOptimization"))).append("\n\n")
            append("Eventos recientes\n")
            append(ForwardLogStore.listText(context, 20)).append("\n\n")
            append("Lista de pruebas de estabilidad\n")
            append(TEST_CHECKLIST)
        }
    }

    fun diagnosticSummary(context: Context): String {
        val target = PhoneMaskUtils.mask(SettingsStore.targetPhone(context))
        val lastSms = ForwardLogStore.findLatestTime(context) { it.optString("eventType") == "sms_received" }
        val lastForward = ForwardLogStore.findLatestTime(context) { isForwardSuccess(it.optString("decision")) }
        val lastError = ForwardLogStore.latestFailureReason(context)
        return buildString {
            append("SMS Quick Forwarder ${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}\n")
            append("Dispositivo: ${Build.BRAND} ${Build.MODEL}\n")
            append("Android: ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}\n")
            append("Reenvío: ${if (SettingsStore.isEnabled(context)) "activado" else "desactivado"}\n")
            append("Destino: $target\n")
            append("Recibir SMS: ${permissionState(context, Manifest.permission.RECEIVE_SMS)}\n")
            append("Enviar SMS: ${permissionState(context, Manifest.permission.SEND_SMS)}\n")
            append("Notificaciones: ${notificationState(context)}\n")
            append("Servicio en primer plano: ${if (SettingsStore.isServiceRunning(context)) "en ejecución" else "detenido"}\n")
            append("Batería sin restricciones: ${if (isBatteryUnrestricted(context)) "sí" else "no"}\n")
            append("Último SMS recibido: ${lastSms.ifBlank { "ninguno" }}\n")
            append("Último reenvío correcto: ${lastForward.ifBlank { "ninguno" }}\n")
            append("Último error: ${lastError.ifBlank { "ninguno" }}")
        }
    }

    private fun appSettings(context: Context) = JSONObject().apply {
        put("forwardingEnabled", SettingsStore.isEnabled(context))
        put("targetPhoneSet", SettingsStore.targetPhone(context).isNotBlank())
        put("loopGuardEnabled", true)
        put("foregroundServiceRunning", SettingsStore.isServiceRunning(context))
        put("onboardingCompleted", OnboardingPreferences.completed(context))
        put("failureNotificationsEnabled", SettingsStore.failureNotificationsEnabled(context))
        put("logRetention", SettingsStore.logRetention(context))
        put("autoUpdateCheckEnabled", UpdatePreferences.autoCheckEnabled(context))
    }

    private fun permissions(context: Context) = JSONObject().apply {
        put("RECEIVE_SMS", permissionState(context, Manifest.permission.RECEIVE_SMS))
        put("SEND_SMS", permissionState(context, Manifest.permission.SEND_SMS))
        put("POST_NOTIFICATIONS", notificationState(context))
        put("INTERNET", if (manifestDeclared(context, Manifest.permission.INTERNET)) "declared_update_check_only" else "not_declared")
        put("RECEIVE_BOOT_COMPLETED", manifestDeclared(context, Manifest.permission.RECEIVE_BOOT_COMPLETED))
        put("READ_PHONE_STATE", if (manifestDeclared(context, Manifest.permission.READ_PHONE_STATE)) permissionState(context, Manifest.permission.READ_PHONE_STATE) else "not_declared")
    }

    private fun permissionGuidance(context: Context) = JSONObject().apply {
        put("sendSmsActionRequired", context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
        put("recommendedPath", "Ajustes → Aplicaciones → SMS Quick Forwarder → Permisos → SMS → Permitir")
    }

    private fun permissionSummary(context: Context): String = buildString {
        append("Permiso para recibir SMS: ").append(readablePermissionState(context, Manifest.permission.RECEIVE_SMS)).append("\n")
        append("Permiso para enviar SMS: ").append(readablePermissionState(context, Manifest.permission.SEND_SMS)).append("\n")
        append("Permiso de notificaciones: ").append(if (notificationState(context) == "granted" || notificationState(context) == "not_required") "Permitido" else "No permitido").append("\n")
        append("Permiso de internet: solo se usa para buscar nuevas versiones en GitHub Releases")
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            append("\n\nEl permiso para enviar SMS no está habilitado. Ruta: Ajustes → Aplicaciones → SMS Quick Forwarder → Permisos → SMS → Permitir")
        }
    }

    private fun readablePermissionState(context: Context, permission: String): String =
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) "Permitido" else "No permitido"

    private fun systemInfo(context: Context) = JSONObject().apply {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        put("androidVersion", Build.VERSION.RELEASE)
        put("sdkVersion", Build.VERSION.SDK_INT)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("appVersionName", packageInfo.versionName ?: "")
        put("appVersionCode", if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else @Suppress("DEPRECATION") packageInfo.versionCode)
        put("currentTime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
    }

    private fun appBuild(context: Context) = JSONObject().apply {
        put("applicationId", BuildConfig.APPLICATION_ID)
        put("versionName", BuildConfig.VERSION_NAME)
        put("versionCode", BuildConfig.VERSION_CODE)
        put("buildType", BuildConfig.BUILD_TYPE)
        put("debuggable", BuildConfig.DEBUG)
        put("gitCommit", BuildConfig.GIT_COMMIT)
        put("buildTime", BuildConfig.BUILD_TIME)
        put("installedPackage", context.packageName)
    }

    private fun simInfo(context: Context) = JSONObject().apply {
        put("defaultSmsSubscriptionId", SubscriptionManager.getDefaultSmsSubscriptionId())
        val active = JSONArray()
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                val manager = context.getSystemService(SubscriptionManager::class.java)
                manager.activeSubscriptionInfoList?.forEach { info ->
                    active.put(JSONObject().apply {
                        put("subscriptionId", info.subscriptionId)
                        put("simSlotIndex", info.simSlotIndex)
                        put("carrierName", info.carrierName?.toString() ?: "")
                        put("displayName", info.displayName?.toString() ?: "")
                    })
                }
            }.onFailure { e ->
                put("activeSubscriptionError", "${e.javaClass.simpleName}: ${e.message}")
            }
        } else {
            put("activeSubscriptionNote", "Sin permiso READ_PHONE_STATE; no se puede leer la lista de SIM activas")
        }
        put("activeSubscriptions", active)
        put("lastReceivedRawValues", lastRawValues(context))
    }

    private fun lastRawValues(context: Context): JSONObject {
        val events = ForwardLogStore.recentEvents(context, 1, includeFullBody = false, includeFullPhone = false)
        if (events.length() == 0) return JSONObject()
        val last = events.getJSONObject(0)
        return JSONObject().apply {
            put("slot", last.optString("slot"))
            put("simId", last.optString("simId"))
            put("subscription", last.optString("subscription"))
        }
    }

    private fun batteryOptimization(context: Context) = JSONObject().apply {
        val unrestricted = isBatteryUnrestricted(context)
        put("isIgnoringBatteryOptimizations", unrestricted)
        put("restricted", !unrestricted)
    }

    private fun stabilitySelfCheck(context: Context): JSONObject {
        val receiveSms = context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val sendSms = context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val targetSet = SettingsStore.targetPhone(context).isNotBlank()
        val serviceRunning = SettingsStore.isServiceRunning(context)
        val forwardingEnabled = SettingsStore.isEnabled(context)
        val batteryOk = isBatteryUnrestricted(context)
        val lastFailureAt = ForwardLogStore.findLatestTime(context) { it.optString("decision").contains("failed", ignoreCase = true) }
        val lastForwardSuccessAt = ForwardLogStore.findLatestTime(context) { isForwardSuccess(it.optString("decision")) }
        val lastRealSmsReceivedAt = ForwardLogStore.findLatestTime(context) { it.optString("eventType") == "sms_received" }
        val lastRealSmsForwardedAt = ForwardLogStore.findLatestTime(context) {
            it.optString("eventType") == "sms_received" && isForwardSuccess(it.optString("decision"))
        }
        val failuresAfterLastSuccess = lastFailureAt.isNotBlank() && (lastForwardSuccessAt.isBlank() || lastFailureAt > lastForwardSuccessAt)
        val hardFail = !receiveSms || !sendSms || !targetSet
        val warning = forwardingEnabled && !serviceRunning || !batteryOk
        val overall = when {
            hardFail -> "No disponible"
            warning -> "Con advertencias"
            else -> "Correcto"
        }
        return JSONObject().apply {
            put("overallStatus", overall)
            put("forwardingEnabled", forwardingEnabled)
            put("receiveSms", receiveSms)
            put("sendSms", sendSms)
            put("postNotifications", notificationState(context))
            put("foregroundServiceRunning", serviceRunning)
            put("batteryOptimizationDisabled", batteryOk)
            put("notificationsAllowed", notificationState(context) == "granted" || notificationState(context) == "not_required")
            put("targetPhoneSet", targetSet)
            put("smsReceiverDeclared", receiverDeclared(context, "SmsReceiver"))
            put("bootReceiverDeclared", receiverDeclared(context, "BootReceiver"))
            put("defaultSmsSubscriptionId", SubscriptionManager.getDefaultSmsSubscriptionId())
            put("lastSmsReceivedAt", lastRealSmsReceivedAt)
            put("lastForwardSuccessAt", lastForwardSuccessAt)
            put("lastFailureAt", lastFailureAt)
            put("lastFailureReason", ForwardLogStore.latestFailureReason(context))
            put("failuresAfterLastSuccess", failuresAfterLastSuccess)
            put("currentFailureActive", failuresAfterLastSuccess)
            put("hasReceivedRealSms", lastRealSmsReceivedAt.isNotBlank())
            put("lastRealSmsReceivedAt", lastRealSmsReceivedAt)
            put("lastRealSmsForwardedAt", lastRealSmsForwardedAt)
            put(
                "realSmsStatus",
                if (lastRealSmsForwardedAt.isNotBlank()) "Reenvío de SMS real verificado" else "Todavía no se probó con un SMS real"
            )
            if (lastFailureAt.isNotBlank() && !failuresAfterLastSuccess) {
                put("historicalFailureNote", "Último fallo registrado: ${ForwardLogStore.latestFailureReason(context)}; el servicio volvió a funcionar después")
            }
        }
    }

    private fun permissionState(context: Context, permission: String): String {
        val declared = manifestDeclared(context, permission)
        val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return when {
            !declared -> "not_declared"
            granted -> "granted"
            else -> "denied"
        }
    }

    private fun notificationState(context: Context): String {
        if (Build.VERSION.SDK_INT < 33) return "not_required"
        return permissionState(context, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun manifestDeclared(context: Context, permission: String): Boolean {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        return info.requestedPermissions?.contains(permission) == true
    }

    private fun receiverDeclared(context: Context, simpleName: String): Boolean {
        val flags = PackageManager.GET_RECEIVERS
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        return info.receivers?.any { it.name.endsWith(".$simpleName") } == true
    }

    private fun isBatteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun isForwardSuccess(decision: String): Boolean =
        decision == "forwarded" || decision == "forwarded_rule_match" || decision == "forwarded_all_mode"

    private fun formatObject(obj: JSONObject?): String {
        if (obj == null) return ""
        val keys = obj.keys()
        val sb = StringBuilder()
        while (keys.hasNext()) {
            val key = keys.next()
            sb.append(key).append(": ").append(obj.opt(key)).append("\n")
        }
        return sb.toString().trim()
    }

    const val TEST_CHECKLIST = """
1. Recibir SMS con la aplicación abierta.
2. Recibir SMS con la aplicación en segundo plano.
3. Recibir SMS con la pantalla bloqueada.
4. Recibir SMS tras quitar la aplicación de las tareas recientes.
5. Recibir SMS después de reiniciar, sin abrir la aplicación.
6. Recibir SMS tras una hora en reposo.
7. Recibir SMS tras seis horas en reposo.
8. Comprobar recepción y envío en modo avión.
9. Comprobar fallo y reintento de envío sin señal móvil.
10. Recibir y enviar con doble SIM.
11. Comprobar SMS largos con varias partes.
12. Evitar bucles con SMS del número de destino.
13. Evitar bucles con SMS que incluyan [SMS Forward].
14. Recuperar el servicio en primer plano después de reiniciar.
15. Comprobar que los registros y el JSON de depuración estén completos.
"""
}
