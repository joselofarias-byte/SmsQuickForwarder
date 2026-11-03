package com.oncet.smsquickforwarder.backup

import org.json.JSONObject

object BackupValidator {
    private val knownTopLevel = setOf("schemaVersion", "appVersionName", "appVersionCode", "exportedAt", "settings", "rules")

    fun preview(raw: String): BackupPreview {
        val root = runCatching { JSONObject(raw) }.getOrElse {
            return BackupPreview(false, 0, "", "", false, 0, 0, "No válido JSON")
        }
        val schema = root.optInt("schemaVersion", -1)
        if (schema != 1) {
            return BackupPreview(false, schema, root.optString("appVersionName"), root.optString("exportedAt"), false, 0, 0, "Versión de copia de seguridad no compatible")
        }
        val settings = root.optJSONObject("settings") ?: JSONObject()
        val rules = root.optJSONArray("rules")
        var unknown = 0
        root.keys().forEach { if (it !in knownTopLevel) unknown += 1 }
        return BackupPreview(
            valid = true,
            schemaVersion = schema,
            appVersionName = root.optString("appVersionName"),
            exportedAt = root.optString("exportedAt"),
            hasTargetPhone = settings.optString("targetPhone").isNotBlank(),
            ruleCount = rules?.length() ?: 0,
            unknownFieldCount = unknown,
            message = "Listo para restaurar"
        )
    }

    fun excludesPrivateData(raw: String): Boolean {
        val lower = raw.lowercase()
        return !lower.contains("fullbody") &&
            !lower.contains("bodypreview") &&
            !lower.contains("sendparts") &&
            !lower.contains("keystore") &&
            !lower.contains("password") &&
            !lower.contains("token")
    }
}
