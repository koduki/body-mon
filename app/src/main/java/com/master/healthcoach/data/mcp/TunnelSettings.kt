package com.master.healthcoach.data.mcp

import android.content.Context
import com.master.healthcoach.data.security.EncryptedTextStore
import kotlinx.serialization.Serializable

@Serializable
data class SharePermissions(
    val health: Boolean = true,
    val conversations: Boolean = false,
    val diagnostics: Boolean = false,
    val evidence: Boolean = false,
) {
    fun allows(scope: String): Boolean = when (scope) {
        "basic" -> true
        "health" -> health
        "conversations" -> conversations
        "diagnostics" -> diagnostics
        "evidence" -> evidence && health && conversations
        else -> false
    }
}

data class TunnelConfig(val id: String, val apiKey: String, val organization: String, val minutes: Int, val permissions: SharePermissions)

class TunnelSettings(context: Context) {
    private val prefs = context.getSharedPreferences("secure_tunnel_settings", Context.MODE_PRIVATE)
    private val crypto = EncryptedTextStore("health_coach_tunnel_key")
    fun id(): String = prefs.getString("id", "").orEmpty()
    fun organization(): String = prefs.getString("organization", "").orEmpty()
    fun minutes(): Int = prefs.getInt("minutes", 30).takeIf { it in setOf(15, 30, 60) } ?: 30
    fun hasKey(): Boolean = prefs.contains("credential")
    fun permissions() = SharePermissions(prefs.getBoolean("health", true), prefs.getBoolean("conversations", false), prefs.getBoolean("diagnostics", false), prefs.getBoolean("evidence", false))
    fun save(id: String, key: String, organization: String, minutes: Int, permissions: SharePermissions) {
        require(ID.matches(id.trim())) { "トンネルIDを確認してください" }
        require(minutes in setOf(15, 30, 60))
        require(organization.length <= 128 && !organization.any { it == '\r' || it == '\n' })
        require(key.isNotBlank() || (hasKey() && id.trim() == this.id())) { "実行用キーを入力してください" }
        val editor = prefs.edit().putString("id", id.trim()).putString("organization", organization.trim()).putInt("minutes", minutes)
            .putBoolean("health", permissions.health).putBoolean("conversations", permissions.conversations)
            .putBoolean("diagnostics", permissions.diagnostics).putBoolean("evidence", permissions.evidence && permissions.health && permissions.conversations)
        if (key.isNotBlank()) {
            require(key.length <= 4096 && !key.any { it == '\r' || it == '\n' })
            editor.putString("credential", crypto.encrypt(key.trim()))
        }
        editor.commit()
    }
    fun load(): TunnelConfig = TunnelConfig(id(), crypto.decrypt(prefs.getString("credential", null) ?: error("キー未設定")), organization(), minutes(), permissions())
    fun clear() { prefs.edit().clear().commit() }
    companion object { val ID = Regex("tunnel_(?:[a-z0-9]{4}_)?[a-z0-9]{32}") }
}
