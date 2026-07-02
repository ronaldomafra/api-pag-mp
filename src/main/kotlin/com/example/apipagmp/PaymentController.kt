package com.example.apipagmp

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/payments")
class PaymentController(private val mercadoPagoClient: MercadoPagoClient) {
    @PostMapping
    fun create(@Valid @RequestBody request: PaymentRequest): PaymentResponse = when (request.paymentType) {
        PaymentType.CHECKOUT_PRO -> mercadoPagoClient.createPreference(request).toCheckoutResponse()
        PaymentType.PIX -> mercadoPagoClient.createPixPayment(request).toPixResponse()
    }

    @ExceptionHandler(MercadoPagoException::class)
    fun handleMercadoPagoException(exception: MercadoPagoException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(mapOf("message" to (exception.message ?: "Erro no Mercado Pago")))
}

private fun Map<String, Any?>.toCheckoutResponse() = PaymentResponse(
    type = PaymentType.CHECKOUT_PRO,
    id = this["id"]?.toString(),
    redirectUrl = (this["init_point"] ?: this["sandbox_init_point"])?.toString(),
    raw = this,
)

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.toPixResponse(): PaymentResponse {
    val pointOfInteraction = this["point_of_interaction"] as? Map<String, Any?>
    val transactionData = pointOfInteraction?.get("transaction_data") as? Map<String, Any?>
    return PaymentResponse(
        type = PaymentType.PIX,
        id = this["id"]?.toString(),
        status = this["status"]?.toString(),
        qrCode = transactionData?.get("qr_code")?.toString(),
        qrCodeBase64 = transactionData?.get("qr_code_base64")?.toString(),
        ticketUrl = transactionData?.get("ticket_url")?.toString(),
        raw = this,
    )
}
