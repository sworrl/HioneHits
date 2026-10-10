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
) {
    data class SystemUpdate(
        val required: Boolean,
        val build: String,
        val notes: String,
        val installerUrl: String,
    )

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

        fun parse(bytes: ByteArray): OtaManifest {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val schema = o.getInt("schema_version")
            require(schema in 1..OtaConfig.SCHEMA_VERSION) {
                "This manifest uses format $schema and this app only knows ${OtaConfig.SCHEMA_VERSION}. Update Miku Update with a full system image."
            }
            val su = o.optJSONObject("system_update")?.let {
                SystemUpdate(
                    required = it.optBoolean("required", false),
                    build = it.getString("build"),
                    notes = it.optString("notes", ""),
                    installerUrl = it.optString("installer_url", "").ifBlank { OtaConfig.DEFAULT_INSTALLER_URL },
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
                            versionName = a.optString("versionName", ""),
                            url = a.getString("url"),
                            size = size,
                            sha256 = sha,
                            minSdk = a.optInt("min_sdk", 0),
                            requiresBuild = a.optString("requires_build", "").ifBlank { null },
                        )
                    )
                }
            }
            return OtaManifest(
                schemaVersion = schema,
                channel = o.getString("channel"),
                build = o.getString("build"),
                minBuild = o.optString("min_build", "").ifBlank { null },
                publishedAt = Instant.parse(o.getString("published_at")),
                changelog = o.optString("changelog", ""),
                systemUpdate = su,
                apks = apks,
            )
        }
    }
}
