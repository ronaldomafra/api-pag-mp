package com.example.apipagmp

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

data class PaymentStatusView(
    val localPaymentId: String,
    val type: PaymentType,
    val status: String,
    val statusMessage: String,
    val paid: Boolean,
    val preferenceId: String? = null,
    val paymentId: String? = null,
    val merchantOrderId: String? = null,
    val externalReference: String,
    val createdAt: String,
    val updatedAt: String,
)

private data class PaymentStatusRecord(
    val localPaymentId: String,
    val type: PaymentType,
    val accessToken: String,
    val externalReference: String,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    var status: String = "created",
    var statusMessage: String = "Pagamento criado. Aguardando processamento.",
    var paid: Boolean = false,
    var preferenceId: String? = null,
    var paymentId: String? = null,
    var merchantOrderId: String? = null,
)

@Service
class PaymentStatusService(private val mercadoPagoClient: MercadoPagoClient) {
    private val lock = Any()
    private val payments = linkedMapOf<String, PaymentStatusRecord>()
    private var latestAccessToken: String? = null

    fun create(request: PaymentRequest): PaymentStatusView = synchronized(lock) {
        val localPaymentId = UUID.randomUUID().toString()
        val record = PaymentStatusRecord(
            localPaymentId = localPaymentId,
            type = request.paymentType,
            accessToken = request.accessToken,
            externalReference = localPaymentId,
            status = "created",
            statusMessage = "Pagamento criado localmente. Enviando para o Mercado Pago.",
        )
        latestAccessToken = request.accessToken
        payments[localPaymentId] = record
        record.toView()
    }

    fun markCheckoutCreated(localPaymentId: String, response: Map<String, Any?>): PaymentStatusView = synchronized(lock) {
        val record = recordOr404(localPaymentId)
        record.preferenceId = response.stringAt("id")
        record.status = "pending"
        record.statusMessage = "Checkout criado. Aguardando pagamento do comprador."
        record.paid = false
        record.touch()
        record.toView()
    }

    fun markPixCreated(localPaymentId: String, response: Map<String, Any?>): PaymentStatusView = synchronized(lock) {
        val record = recordOr404(localPaymentId)
        record.paymentId = response.stringAt("id")
        record.status = response.stringAt("status") ?: "pending"
        record.statusMessage = messageForStatus(record.status)
        record.paid = record.status == "approved"
        record.touch()
        record.toView()
    }

    fun markFailed(localPaymentId: String, message: String): PaymentStatusView = synchronized(lock) {
        val record = recordOr404(localPaymentId)
        record.status = "failed"
        record.statusMessage = message
        record.paid = false
        record.touch()
        record.toView()
    }

    fun get(localPaymentId: String): PaymentStatusView = synchronized(lock) {
        recordOr404(localPaymentId).toView()
    }

    fun getAndRefresh(localPaymentId: String): PaymentStatusView {
        val snapshot = synchronized(lock) { recordOr404(localPaymentId).toView() }
        if (snapshot.paid) return snapshot

        val refreshed = when (snapshot.type) {
            PaymentType.PIX -> snapshot.paymentId?.let { runCatching { refreshPayment(it) }.getOrNull() }
            PaymentType.CHECKOUT_PRO -> runCatching { refreshCheckout(snapshot) }.getOrNull()
        }
        return refreshed ?: synchronized(lock) { recordOr404(localPaymentId).toView() }
    }

    fun refreshFromWebhook(event: WebhookEvent): PaymentStatusView? {
        val resourceId = event.resourceId ?: event.paymentId ?: event.query["id"] ?: return null
        val type = event.type ?: event.query["topic"]
        return when {
            type?.contains("merchant_order", ignoreCase = true) == true -> refreshMerchantOrder(resourceId)
            type?.contains("payment", ignoreCase = true) == true -> refreshPayment(resourceId)
            else -> null
        }
    }

    fun refreshPayment(paymentId: String): PaymentStatusView? {
        val accessToken = accessTokenForPayment(paymentId) ?: latestAccessToken ?: return null
        val payment = mercadoPagoClient.getPayment(paymentId, accessToken)
        return synchronized(lock) {
            val externalReference = payment.stringAt("external_reference")
            val record = externalReference?.let { payments[it] }
                ?: payments.values.firstOrNull { it.paymentId == paymentId }
                ?: return@synchronized null

            record.paymentId = payment.stringAt("id") ?: paymentId
            record.status = payment.stringAt("status") ?: record.status
            record.statusMessage = payment.stringAt("status_detail") ?: messageForStatus(record.status)
            record.paid = record.status == "approved"
            record.touch()
            record.toView()
        }
    }

    fun refreshMerchantOrder(merchantOrderId: String): PaymentStatusView? {
        val accessToken = accessTokenForMerchantOrder(merchantOrderId) ?: latestAccessToken ?: return null
        val order = mercadoPagoClient.getMerchantOrder(merchantOrderId, accessToken)
        return synchronized(lock) {
            val externalReference = order.stringAt("external_reference")
            val preferenceId = order.stringAt("preference_id")
            val paymentsInOrder = order.listAt("payments")
            val approvedPayment = paymentsInOrder.firstOrNull { it.stringAt("status") == "approved" }
            val firstPayment = approvedPayment ?: paymentsInOrder.firstOrNull()

            val record = externalReference?.let { payments[it] }
                ?: payments.values.firstOrNull { it.preferenceId == preferenceId }
                ?: payments.values.firstOrNull { it.merchantOrderId == merchantOrderId }
                ?: return@synchronized null

            val orderStatus = order.stringAt("status") ?: record.status
            val paid = orderStatus == "closed" || approvedPayment != null

            record.merchantOrderId = order.stringAt("id") ?: merchantOrderId
            record.paymentId = firstPayment?.stringAt("id") ?: record.paymentId
            record.status = if (paid) "approved" else orderStatus
            record.statusMessage = if (paid) {
                "Pagamento aprovado pelo Mercado Pago."
            } else {
                messageForStatus(orderStatus)
            }
            record.paid = paid
            record.touch()
            record.toView()
        }
    }

    private fun refreshCheckout(snapshot: PaymentStatusView): PaymentStatusView? {
        snapshot.merchantOrderId?.let { return refreshMerchantOrder(it) }

        val record = synchronized(lock) { payments[snapshot.localPaymentId] } ?: return null
        val search = mercadoPagoClient.searchMerchantOrders(
            preferenceId = record.preferenceId,
            externalReference = record.externalReference,
            accessToken = record.accessToken,
        )
        val order = search.listAt("elements").firstOrNull() ?: return null
        val orderId = order.stringAt("id") ?: return null
        return refreshMerchantOrder(orderId)
    }

    private fun accessTokenForPayment(paymentId: String): String? = synchronized(lock) {
        payments.values.firstOrNull { it.paymentId == paymentId }?.accessToken
    }

    private fun accessTokenForMerchantOrder(merchantOrderId: String): String? = synchronized(lock) {
        payments.values.firstOrNull { it.merchantOrderId == merchantOrderId }?.accessToken
    }

    private fun recordOr404(localPaymentId: String): PaymentStatusRecord =
        payments[localPaymentId] ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Pagamento local nao encontrado")
}

private fun PaymentStatusRecord.touch() {
    updatedAt = Instant.now()
}

private fun PaymentStatusRecord.toView() = PaymentStatusView(
    localPaymentId = localPaymentId,
    type = type,
    status = status,
    statusMessage = statusMessage,
    paid = paid,
    preferenceId = preferenceId,
    paymentId = paymentId,
    merchantOrderId = merchantOrderId,
    externalReference = externalReference,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

private fun messageForStatus(status: String): String = when (status) {
    "approved" -> "Pagamento aprovado pelo Mercado Pago."
    "pending" -> "Pagamento pendente. Aguardando acao do comprador."
    "in_process" -> "Pagamento em analise/processamento."
    "authorized" -> "Pagamento autorizado, mas ainda nao capturado."
    "rejected" -> "Pagamento recusado."
    "cancelled" -> "Pagamento cancelado."
    "refunded" -> "Pagamento estornado."
    "charged_back" -> "Pagamento contestado."
    "closed" -> "Pedido fechado pelo Mercado Pago."
    "opened" -> "Pedido aberto. Aguardando pagamento."
    "expired" -> "Pedido expirado."
    else -> "Status atual: $status."
}

private fun Map<String, Any?>.stringAt(key: String): String? =
    this[key]?.toString()?.takeIf { it.isNotBlank() }

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.listAt(key: String): List<Map<String, Any?>> =
    this[key] as? List<Map<String, Any?>> ?: emptyList()
