package com.example.familysafety.transport

object BrokerConfig {

    // Credentials come from keystore/mqtt.properties at build time (gitignored);
    // blank means anonymous — the current public-dev-broker behavior.
    private val buildUsername: String? =
        com.example.familysafety.BuildConfig.MQTT_USERNAME.ifBlank { null }
    private val buildPassword: String? =
        com.example.familysafety.BuildConfig.MQTT_PASSWORD.ifBlank { null }

    // EMQX Cloud serverless: TLS on 8883, publicly trusted CA, auth required —
    // credentials must be present in keystore/mqtt.properties or connect fails.
    private const val EMQX_URL = "ssl://r161feb1.ala.us-east-1.emqxsl.com:8883"

    // One broker for every build type. Debug and release used to name separate
    // environments, but all three pointed at this same broker.
    private val broker = BrokerSettings(
        url = EMQX_URL,
        username = buildUsername,
        password = buildPassword,
        useTls = true
    )

    fun getCurrentBroker(): BrokerSettings = broker

    fun isSecureBroker(): Boolean {
        return getCurrentBroker().useTls
    }
    
    fun getBrokerUrl(): String {
        return getCurrentBroker().url
    }
}

data class BrokerSettings(
    val url: String,
    val username: String?,
    val password: String?,
    val useTls: Boolean
)
