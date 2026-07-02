package com.example.apipagmp

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/payments")
class PaymentController(
    private val mercadoPagoClient: MercadoPagoClient,
    private val paymentStatusService: PaymentStatusService,
) {
    @PostMapping
    fun create(@Valid @RequestBody request: PaymentRequest): PaymentResponse {
        val localStatus = paymentStatusService.create(request)
        return try {
            when (request.paymentType) {
                PaymentType.CHECKOUT_PRO -> {
                    val response = mercadoPagoClient.createPreference(request, localStatus.localPaymentId)
                    val status = paymentStatusService.markCheckoutCreated(localStatus.localPaymentId, response)
                    response.toCheckoutResponse(status)
                }

                PaymentType.PIX -> {
                    val response = mercadoPagoClient.createPixPayment(request, localStatus.localPaymentId)
                    val status = paymentStatusService.markPixCreated(localStatus.localPaymentId, response)
                    response.toPixResponse(status)
                }
            }
        } catch (exception: MercadoPagoException) {
            paymentStatusService.markFailed(localStatus.localPaymentId, exception.message ?: "Erro no Mercado Pago")
            throw exception
        }
    }

    @GetMapping("/{localPaymentId}/status")
    fun status(@PathVariable localPaymentId: String): PaymentStatusView =
        paymentStatusService.getAndRefresh(localPaymentId)

    @ExceptionHandler(MercadoPagoException::class)
    fun handleMercadoPagoException(exception: MercadoPagoException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(mapOf("message" to (exception.message ?: "Erro no Mercado Pago")))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(exception: MethodArgumentNotValidException): ResponseEntity<Map<String, Any>> {
        val errors = exception.bindingResult.fieldErrors.map {
            "${it.field.toSnakeCase()}: ${it.defaultMessage ?: "valor inválido"}"
        }
        return ResponseEntity.badRequest().body(mapOf("message" to "Dados inválidos", "errors" to errors))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableMessage(exception: HttpMessageNotReadableException): ResponseEntity<Map<String, String>> {
        val detail = exception.mostSpecificCause.message ?: exception.message ?: "JSON inválido"
        val missingField = Regex("JSON property ([A-Za-z0-9_]+)").find(detail)?.groupValues?.get(1)
        val message = missingField?.let { "Campo obrigatório ausente: $it" } ?: "JSON inválido ou incompatível"
        return ResponseEntity.badRequest().body(mapOf("message" to message, "detail" to detail))
    }
}

private fun String.toSnakeCase(): String =
    replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

private fun Map<String, Any?>.toCheckoutResponse(status: PaymentStatusView) = PaymentResponse(
    localPaymentId = status.localPaymentId,
    type = PaymentType.CHECKOUT_PRO,
    id = this["id"]?.toString(),
    status = status.status,
    statusMessage = status.statusMessage,
    paid = status.paid,
    redirectUrl = (this["init_point"] ?: this["sandbox_init_point"])?.toString(),
    raw = this,
)

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.toPixResponse(status: PaymentStatusView): PaymentResponse {
    val pointOfInteraction = this["point_of_interaction"] as? Map<String, Any?>
    val transactionData = pointOfInteraction?.get("transaction_data") as? Map<String, Any?>
    return PaymentResponse(
        localPaymentId = status.localPaymentId,
        type = PaymentType.PIX,
        id = this["id"]?.toString(),
        status = status.status,
        statusMessage = status.statusMessage,
        paid = status.paid,
        qrCode = transactionData?.get("qr_code")?.toString(),
        qrCodeBase64 = transactionData?.get("qr_code_base64")?.toString(),
        ticketUrl = transactionData?.get("ticket_url")?.toString(),
        raw = this,
    )
}
