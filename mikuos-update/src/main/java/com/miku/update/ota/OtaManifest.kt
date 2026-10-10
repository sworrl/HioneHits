package com.miku.update.ota

import org.json.JSONObject
import java.time.Instant

/** The signed manifest. Schema in mikuos/ota/README.md. Parsed only after the signature checks out. */
data class OtaManifest(
    val schemaVersion: Int,
    val channel: String,
    val build: String,
    val minBuild: String?,
    val publishedAt: Instant,
    val changelog: String,
    val systemUpdate: SystemUpdate?,
    val apks: List<ApkEntry>,
    /** Releases pulled after the fact. A device running one of these goes back to its image copy. */
    val revoked: List<Revoked> = emptyList(),
) {
    data class Revoked(val packageName: String, val versionCode: Long, val reason: String)

    fun isRevoked(pkg: String, vc: Long) = revoked.any { it.packageName == pkg && it.versionCode == vc }

    data class SystemUpdate(
        val required: Boolean,
        val build: String,
        val notes: String,
        val installerUrl: String,
        /** A/B payloads for update_engine: a full one, and deltas from named builds. May be empty. */
        val payloads: List<Payload> = emptyList(),
    )

    /**
     * One payload.bin. [url] is relative to the manifest like the APK URLs. [headers] are the
     * payload_properties.txt lines (FILE_HASH, FILE_SIZE, METADATA_HASH, METADATA_SIZE) handed to
     * update_engine as is. [sourceBuild] is null for a full payload; a delta only applies on top
     * of exactly that build.
     */
    data class Payload(
        val type: String,
        val sourceBuild: String?,
        val url: String,
        val offset: Long,
        val size: Long,
        val sha256: String,
        val headers: List<String>,
    ) {
        val isFull get() = sourceBuild == null
    }

    data class ApkEntry(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val url: String,
        val size: Long,
        val sha256: String,
        val minSdk: Int,
        val requiresBuild: String?,
    )

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        /** org.json's optString turns a JSON null into the text "null"; this does not. */
        private fun JSONObject.str(name: String): String? =
            if (isNull(name)) null else optString(name, "").ifBlank { null }

        private val HEADER = Regex("^[A-Z_]+=[^\\n]*$")

        private fun parsePayloads(arr: org.json.JSONArray?): List<Payload> = buildList {
            if (arr == null) return@buildList
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val sha = p.getString("sha256").lowercase()
                require(HEX64.matches(sha)) { "Bad sha256 for a system payload" }
                val size = p.getLong("size")
                require(size > 0) { "Bad size for a system payload" }
                val offset = p.optLong("offset", 0L)
                require(offset >= 0) { "Bad offset for a system payload" }
                val h = p.getJSONArray("headers")
                val headers = (0 until h.length()).map { h.getString(it) }
                require(headers.all { HEADER.matches(it) }) { "Bad header in a system payload" }
                for (k in listOf("FILE_HASH=", "FILE_SIZE=", "METADATA_HASH=", "METADATA_SIZE=")) {
                    require(headers.any { it.startsWith(k) }) { "A system payload is missing $k" }
                }
                val type = p.optString("type", "full")
                val source = p.str("source_build")
                require((type == "full") == (source == null)) { "A system payload has a mismatched type and source" }
                add(Payload(type, source, p.getString("url"), offset, size, sha, headers))
            }
        }

        fun parse(bytes: ByteArray): OtaManifest {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val schema = o.getInt("schema_version")
            require(schema in 1..OtaConfig.SCHEMA_VERSION) {
                "This manifest uses format $schema and this app only knows ${OtaConfig.SCHEMA_VERSION}. Update Miku Update with a full system image."
            }
            val su = (if (o.isNull("system_update")) null else o.optJSONObject("system_update"))?.let {
                SystemUpdate(
                    required = it.optBoolean("required", false),
                    build = it.getString("build"),
                    notes = it.str("notes") ?: "",
                    installerUrl = it.str("installer_url") ?: OtaConfig.DEFAULT_INSTALLER_URL,
                    payloads = parsePayloads(it.optJSONArray("payloads")),
                )
            }
            val arr = o.optJSONArray("apks")
            val apks = buildList {
                if (arr != null) for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    val sha = a.getString("sha256").lowercase()
                    require(HEX64.matches(sha)) { "Bad sha256 for ${a.optString("package")}" }
                    val size = a.getLong("size")
                    require(size > 0) { "Bad size for ${a.optString("package")}" }
                    add(
                        ApkEntry(
                            packageName = a.getString("package"),
                            versionCode = a.getLong("versionCode"),
                            versionName = a.str("versionName") ?: "",
                            url = a.getString("url"),
                            size = size,
                            sha256 = sha,
                            minSdk = a.optInt("min_sdk", 0),
                            requiresBuild = a.str("requires_build"),
                        )
                    )
                }
            }
            val rarr = o.optJSONArray("revoked")
            val revoked = buildList {
                if (rarr != null) for (i in 0 until rarr.length()) {
                    val r = rarr.getJSONObject(i)
                    add(Revoked(r.getString("package"), r.getLong("versionCode"), r.str("reason") ?: ""))
                }
            }
            return OtaManifest(
                schemaVersion = schema,
                channel = o.getString("channel"),
                build = o.getString("build"),
                minBuild = o.str("min_build"),
                publishedAt = Instant.parse(o.getString("published_at")),
                changelog = o.str("changelog") ?: "",
                systemUpdate = su,
                apks = apks,
                revoked = revoked,
            )
        }
    }
}
