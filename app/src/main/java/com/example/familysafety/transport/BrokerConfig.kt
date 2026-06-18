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
    
    private val brokers = mapOf(
        Environment.DEVELOPMENT to BrokerSettings(
            url = "ssl://broker.hivemq.com:8883",
            username = null,
            password = null,
            useTls = true,
            description = "Public HiveMQ broker over TLS (development only)"
        ),
        Environment.STAGING to BrokerSettings(
            url = "tcp://staging-mqtt.familysafety.app:1883",
            username = "familysafety-staging",
            password = null,
            useTls = true,
            description = "Private staging broker"
        ),
        Environment.PRODUCTION to BrokerSettings(
            url = "ssl://mqtt.familysafety.app:8883",
            username = "familysafety-prod",
            password = null,
            useTls = true,
            description = "Private production broker"
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
