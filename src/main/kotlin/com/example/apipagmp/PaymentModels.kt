package com.example.apipagmp

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal

const val BRL_CURRENCY = "BRL"

data class PaymentRequest(
    @field:NotBlank val accessToken: String,
    val publicKey: String? = null,
    @field:DecimalMin("0.01") val amount: BigDecimal,
    @field:NotBlank val description: String,
    val paymentType: PaymentType,
    @field:Email val payerEmail: String? = null,
    val successUrl: String? = null,
    val failureUrl: String? = null,
    val pendingUrl: String? = null,
)

enum class PaymentType { CHECKOUT_PRO, PIX }

data class PaymentResponse(
    val type: PaymentType,
    val id: String?,
    val status: String? = null,
    val redirectUrl: String? = null,
    val qrCode: String? = null,
    val qrCodeBase64: String? = null,
    val ticketUrl: String? = null,
    val raw: Map<String, Any?>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PreferencePayload(
    val items: List<PreferenceItem>,
    val payer: PayerPayload? = null,
    @JsonAlias("back_urls") val backUrls: BackUrls? = null,
    @JsonAlias("auto_return") val autoReturn: String? = "approved",
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PreferenceItem(
    val title: String,
    val quantity: Int = 1,
    @JsonAlias("currency_id") val currencyId: String = BRL_CURRENCY,
    @JsonAlias("unit_price") val unitPrice: BigDecimal,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class BackUrls(
    val success: String? = null,
    val failure: String? = null,
    val pending: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PayerPayload(val email: String? = null)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PixPaymentPayload(
    @JsonAlias("transaction_amount") val transactionAmount: BigDecimal,
    val description: String,
    @JsonAlias("payment_method_id") val paymentMethodId: String = "pix",
    val payer: PayerPayload,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MercadoPagoError(val message: String? = null, val error: String? = null, val status: Int? = null)
