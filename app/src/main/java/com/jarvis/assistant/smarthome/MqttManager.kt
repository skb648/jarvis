package com.jarvis.assistant.smarthome

import android.util.Log
import com.jarvis.assistant.channels.JarviewModel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import kotlin.coroutines.resume

/**
 * MQTT Manager — Smart Home communication via MQTT.
 *
 * Provides:
 *   - TCP connection with auto-reconnect
 *   - Topic subscribe/unsubscribe
 *   - Message publishing
 *   - Auto re-subscribe on reconnect
 *   - Message forwarding to UI via JarviewModel
 */
object MqttManager {

    private const val TAG = "JarvisMQTT"

    private var client: MqttAndroidClient? = null
    private var mqttConnected = false
    // BUG FIX (BUG-5): Use ConcurrentHashMap.newKeySet() for thread-safe subscribed topics
    // mutableSetOf is NOT thread-safe — MQTT callbacks run on different threads
    private val subscribedTopics: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // Connection configuration
    private var brokerUrl: String = ""
    private var clientId: String = "jarvis_android"
    private var username: String = ""
    private var password: String = ""

    /**
     * Connect to MQTT broker.
     * SUSPEND: Waits for the connection result instead of returning true prematurely.
     */
    suspend fun connect(
        context: android.content.Context,
        broker: String,
        port: Int = 1883,
        mqttClientId: String = "jarvis_android",
        user: String = "",
        pass: String = ""
    ): Boolean {
        // BUG FIX (BUG-8): Strip existing scheme from broker URL to prevent
        // malformed URLs like "tcp://mqtt://broker.example.com:1883"
        val cleanBroker = broker
            .removePrefix("mqtt://")
            .removePrefix("tcp://")
            .removePrefix("ssl://")
            .removePrefix("mqtts://")
            .trimEnd('/')
        brokerUrl = "tcp://$cleanBroker:$port"
        clientId = mqttClientId
        username = user
        password = pass

        try {
            val serverUri = brokerUrl
            client = MqttAndroidClient(context, serverUri, clientId).apply {
                setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        mqttConnected = true
                        JarviewModel.mqttConnected = true
                        Log.i(TAG, "MQTT connected to $serverURI (reconnect=$reconnect)")

                        // Re-subscribe to all topics on reconnect
                        if (reconnect) {
                            resubscribeAll()
                        }

                        JarviewModel.sendEventToUi("mqtt_connected", mapOf(
                            "broker" to (serverURI ?: ""),
                            "reconnect" to reconnect
                        ))
                    }

                    override fun connectionLost(cause: Throwable?) {
                        mqttConnected = false
                        JarviewModel.mqttConnected = false
                        Log.w(TAG, "MQTT connection lost", cause)

                        JarviewModel.sendEventToUi("mqtt_disconnected", mapOf(
                            "error" to (cause?.message ?: "Unknown")
                        ))
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val payload = message?.toString() ?: ""
                        Log.d(TAG, "MQTT message on $topic: $payload")

                        JarviewModel.sendEventToUi("mqtt_message", mapOf(
                            "topic" to (topic ?: ""),
                            "payload" to payload
                        ))
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        // Message delivered successfully
                    }
                })
            }

            val mqttConnectOptions = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 60
                if (user.isNotEmpty()) {
                    this.userName = user
                    this.password = pass.toCharArray()
                }
            }

            return try {
                suspendCancellableCoroutine { cont ->
                    client?.connect(mqttConnectOptions, null, object : IMqttActionListener {
                        override fun onSuccess(asyncActionToken: IMqttToken?) {
                            mqttConnected = true
                            Log.i(TAG, "MQTT connected successfully")
                            if (cont.isActive) cont.resume(true) {}
                        }
                        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                            mqttConnected = false
                            Log.e(TAG, "MQTT connection failed: ${exception?.message}")
                            if (cont.isActive) cont.resume(false) {}
                        }
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "MQTT connect error: ${e.message}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate MQTT connection", e)
            return false
        }
    }

    /**
     * Disconnect from MQTT broker.
     */
    fun disconnect() {
        try {
            client?.disconnect(null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    mqttConnected = false
                    JarviewModel.mqttConnected = false
                    Log.i(TAG, "MQTT disconnected")
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.w(TAG, "MQTT disconnect failed", exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting MQTT", e)
        }
    }

    /**
     * Subscribe to a topic.
     */
    fun subscribe(topic: String, qos: Int = 1): Boolean {
        try {
            client?.subscribe(topic, qos, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    subscribedTopics.add(topic)
                    Log.d(TAG, "Subscribed to $topic (QoS $qos)")
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to subscribe to $topic", exception)
                }
            })
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Subscribe error", e)
            return false
        }
    }

    /**
     * Unsubscribe from a topic.
     */
    fun unsubscribe(topic: String): Boolean {
        try {
            client?.unsubscribe(topic, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    subscribedTopics.remove(topic)
                    Log.d(TAG, "Unsubscribed from $topic")
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to unsubscribe from $topic", exception)
                }
            })
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Publish a message to a topic.
     */
    fun publish(topic: String, payload: String, qos: Int = 1, retained: Boolean = false): Boolean {
        try {
            val message = MqttMessage(payload.toByteArray()).apply {
                this.qos = qos
                this.isRetained = retained
            }
            client?.publish(topic, message, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "Published to $topic: $payload")
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to publish to $topic", exception)
                }
            })
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Publish error", e)
            return false
        }
    }

    fun isMqttConnected(): Boolean = mqttConnected

    // Re-subscribe to all topics after reconnect
    private fun resubscribeAll() {
        val topics = subscribedTopics.toList()
        subscribedTopics.clear()
        topics.forEach { topic ->
            subscribe(topic)
        }
    }
}
