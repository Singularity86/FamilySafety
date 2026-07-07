package com.example.familysafety.transport

object BrokerConfig {

    enum class Environment {
        DEVELOPMENT,
        STAGING,
        PRODUCTION
    }

    private var currentEnvironment: Environment = runCatching {
        Environment.valueOf(com.example.familysafety.BuildConfig.MQTT_ENVIRONMENT)
    }.getOrDefault(Environment.DEVELOPMENT)

    // Credentials come from keystore/mqtt.properties at build time (gitignored);
    // blank means anonymous — the current public-dev-broker behavior.
    private val buildUsername: String? =
        com.example.familysafety.BuildConfig.MQTT_USERNAME.ifBlank { null }
    private val buildPassword: String? =
        com.example.familysafety.BuildConfig.MQTT_PASSWORD.ifBlank { null }

    // EMQX Cloud serverless: TLS on 8883, publicly trusted CA, auth required —
    // credentials must be present in keystore/mqtt.properties or connect fails.
    private const val EMQX_URL = "ssl://r161feb1.ala.us-east-1.emqxsl.com:8883"

    private val brokers = mapOf(
        Environment.DEVELOPMENT to BrokerSettings(
            url = EMQX_URL,
            username = buildUsername,
            password = buildPassword,
            useTls = true,
            description = "Private EMQX Cloud broker (dev builds)"
        ),
        Environment.STAGING to BrokerSettings(
            url = EMQX_URL,
            username = buildUsername,
            password = buildPassword,
            useTls = true,
            description = "Private EMQX Cloud broker (staging)"
        ),
        Environment.PRODUCTION to BrokerSettings(
            url = EMQX_URL,
            username = buildUsername,
            password = buildPassword,
            useTls = true,
            description = "Private EMQX Cloud broker (production)"
        )
    )
    
    fun getCurrentBroker(): BrokerSettings {
        return brokers[currentEnvironment] ?: brokers.values.first()
    }
    
    fun setEnvironment(env: Environment) {
        currentEnvironment = env
    }
    
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
    val useTls: Boolean,
    val description: String
)
