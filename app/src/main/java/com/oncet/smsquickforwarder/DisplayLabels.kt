package com.oncet.smsquickforwarder

/** Presentation labels only. Persisted enum names, JSON keys and decisions stay stable. */
object DisplayLabels {
    fun mode(value: String): String = when (value) {
        "ALL" -> "Todos los SMS"
        "MATCH_ONLY" -> "Solo coincidencias"
        else -> value
    }
    fun field(value: String): String = when (value) {
        "SENDER" -> "Remitente"
        "BODY" -> "Contenido"
        "ANY" -> "Cualquiera"
        else -> value
    }
    fun match(value: String): String = when (value) {
        "CONTAINS" -> "Contiene"
        "EQUALS" -> "Es igual a"
        "STARTS_WITH" -> "Empieza por"
        "REGEX" -> "Expresión regular"
        else -> value
    }
    fun decision(value: String): String = when (value) {
        "forwarded", "forwarded_rule_match", "forwarded_all_mode" -> "Reenviado"
        "skipped_from_target" -> "Omitido: remitente igual al destino"
        "skipped_forward_marker" -> "Omitido: mensaje ya reenviado"
        "skipped_rule_exclude" -> "Omitido por regla de exclusión"
        "skipped_no_include_match" -> "Omitido: ninguna coincidencia"
        "skipped_empty_target" -> "Omitido: destino vacío"
        "skipped_disabled" -> "Omitido: reenvío desactivado"
        "failed_no_permission" -> "Falló: falta permiso de envío"
        else -> when {
            value.contains("failed", true) -> "Falló el envío"
            value.startsWith("skipped") -> "Omitido"
            else -> value
        }
    }
    fun sendResult(value: String): String = when (value) {
        "success", "sent", "sent_success" -> "Enviado correctamente"
        "pending", "sending" -> "Envío pendiente"
        "failed" -> "Falló el envío"
        else -> value
    }
}
