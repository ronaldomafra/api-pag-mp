package com.example.apipagmp

import com.fasterxml.jackson.databind.JsonNode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

data class WebhookEvent(
    val id: Long,
    val receivedAt: String,
    val method: String,
    val query: Map<String, String>,
    val type: String?,
    val action: String?,
    val paymentId: String?,
    val body: JsonNode?,
)

@Service
class WebhookEventStore {
    private val counter = AtomicLong()
    private val events = mutableListOf<WebhookEvent>()
    private val lock = Any()

    fun add(request: HttpServletRequest, body: JsonNode?): WebhookEvent = synchronized(lock) {
        val event = WebhookEvent(
            id = counter.incrementAndGet(),
            receivedAt = Instant.now().toString(),
            method = request.method,
            query = request.parameterMap.mapValues { (_, value) -> value.joinToString(",") },
            type = body.textAt("type") ?: request.getParameter("topic"),
            action = body.textAt("action"),
            paymentId = body.textAt("data", "id") ?: request.getParameter("id"),
            body = body,
        )
        events.add(0, event)
        if (events.size > 100) {
            events.removeAt(events.lastIndex)
        }
        event
    }

    fun all(): List<WebhookEvent> = synchronized(lock) { events.toList() }

    fun clear() = synchronized(lock) { events.clear() }
}

@RestController
@RequestMapping("/api/webhooks/mercadopago")
class MercadoPagoWebhookController(private val store: WebhookEventStore) {
    @PostMapping
    fun receivePost(
        @RequestBody(required = false) body: JsonNode?,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val event = store.add(request, body)
        return mapOf("received" to true, "event_id" to event.id)
    }

    @GetMapping
    fun receiveGet(request: HttpServletRequest): Map<String, Any?> {
        val event = store.add(request, null)
        return mapOf("received" to true, "event_id" to event.id)
    }

    @GetMapping("/events")
    fun events(): List<WebhookEvent> = store.all()

    @DeleteMapping("/events")
    fun clear(): Map<String, Any> {
        store.clear()
        return mapOf("cleared" to true)
    }
}

private fun JsonNode?.textAt(vararg path: String): String? {
    var current = this ?: return null
    for (field in path) {
        current = current.path(field)
        if (current.isMissingNode || current.isNull) return null
    }
    return current.asText().takeIf { it.isNotBlank() }
}
