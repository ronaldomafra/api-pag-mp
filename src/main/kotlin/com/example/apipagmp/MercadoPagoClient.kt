package com.example.apipagmp

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import java.util.UUID

@Service
class MercadoPagoClient(builder: WebClient.Builder) {
    private val webClient = builder
        .baseUrl("https://api.mercadopago.com")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build()

    fun createPreference(request: PaymentRequest, externalReference: String): Map<String, Any?> {
        val notificationUrl = request.notificationUrl?.takeIf { it.isNotBlank() }
        val payload = PreferencePayload(
            items = listOf(PreferenceItem(title = request.description, unitPrice = request.amount)),
            payer = request.payerEmail?.let { PayerPayload(email = it) },
            backUrls = BackUrls(request.successUrl, request.failureUrl, request.pendingUrl)
                .takeIf { it.success != null || it.failure != null || it.pending != null },
            autoReturn = "approved".takeIf { request.successUrl != null },
            notificationUrl = notificationUrl,
            externalReference = externalReference,
        )
        return post("/checkout/preferences", request.accessToken, payload)
    }

    fun createPixPayment(request: PaymentRequest, externalReference: String): Map<String, Any?> {
        val notificationUrl = request.notificationUrl?.takeIf { it.isNotBlank() }
        val payload = PixPaymentPayload(
            transactionAmount = request.amount,
            description = request.description,
            payer = PayerPayload(email = request.payerEmail ?: "comprador@example.com"),
            notificationUrl = notificationUrl,
            externalReference = externalReference,
        )
        return post("/v1/payments", request.accessToken, payload, UUID.randomUUID().toString())
    }

    fun getPayment(paymentId: String, accessToken: String): Map<String, Any?> =
        get("/v1/payments/$paymentId", accessToken)

    fun getMerchantOrder(orderId: String, accessToken: String): Map<String, Any?> =
        get("/merchant_orders/$orderId", accessToken)

    fun searchMerchantOrders(
        preferenceId: String?,
        externalReference: String,
        accessToken: String,
    ): Map<String, Any?> =
        webClient.get()
            .uri { builder ->
                builder.path("/merchant_orders/search")
                    .apply {
                        preferenceId?.let { queryParam("preference_id", it) }
                        queryParam("external_reference", externalReference)
                    }
                    .build()
            }
            .headers { headers -> headers.setBearerAuth(accessToken.trim()) }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { response ->
                response.bodyToMono<String>().defaultIfEmpty("").flatMap { body ->
                    Mono.error(MercadoPagoException("Mercado Pago retornou ${response.statusCode().value()}: $body"))
                }
            }
            .bodyToMono<Map<String, Any?>>()
            .block() ?: emptyMap()

    private fun post(path: String, accessToken: String, payload: Any, idempotencyKey: String? = null): Map<String, Any?> =
        webClient.post()
            .uri(path)
            .headers { headers ->
                headers.setBearerAuth(accessToken.trim())
                idempotencyKey?.let { headers["X-Idempotency-Key"] = it }
            }
            .bodyValue(payload)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { response ->
                response.bodyToMono<String>().defaultIfEmpty("").flatMap { body ->
                    Mono.error(MercadoPagoException("Mercado Pago retornou ${response.statusCode().value()}: $body"))
                }
            }
            .bodyToMono<Map<String, Any?>>()
            .block() ?: emptyMap()

    private fun get(path: String, accessToken: String): Map<String, Any?> =
        webClient.get()
            .uri(path)
            .headers { headers -> headers.setBearerAuth(accessToken.trim()) }
            .retrieve()
            .onStatus(HttpStatusCode::isError) { response ->
                response.bodyToMono<String>().defaultIfEmpty("").flatMap { body ->
                    Mono.error(MercadoPagoException("Mercado Pago retornou ${response.statusCode().value()}: $body"))
                }
            }
            .bodyToMono<Map<String, Any?>>()
            .block() ?: emptyMap()
}

class MercadoPagoException(message: String) : RuntimeException(message)
