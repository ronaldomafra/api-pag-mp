# API Pagamento Mercado Pago

Backend Kotlin com Spring Boot para criar pagamentos no Mercado Pago, acompanhado de uma página HTML simples para configurar credenciais, valor e tipo de pagamento.

## Funcionalidades

- Criação de preferência **Checkout Pro** para redirecionamento do comprador.
- Criação de pagamento **Pix** com retorno de QR Code, copia-e-cola e URL de instruções quando disponibilizados pela API.
- Página web estática em `/` para informar credenciais, valor, descrição, e-mail do pagador e tipo de pagamento.
- Endpoint REST `POST /api/payments` para integração por JSON.

## Pré-requisitos

- Java 21.
- Gradle 8+ instalado localmente.
- Conta Mercado Pago com aplicação criada no Painel do Desenvolvedor.
- Credenciais da aplicação:
  - `Access Token` é obrigatório para criar pagamentos.
  - `Public Key` pode ser informada na tela para organização/testes, mas o backend atual não precisa dela para Checkout Pro ou Pix.

> Use credenciais de teste durante o desenvolvimento. Não versione tokens reais em arquivos do projeto.

## Como rodar

Na raiz do projeto, execute:

```bash
gradle bootRun
```

Depois acesse:

```text
http://localhost:8080
```

A aplicação sobe na porta `8080` por padrão. Para alterar a porta, edite `src/main/resources/application.properties` ou sobrescreva `server.port` por variável/propriedade do Spring.

## Como testar pela página HTML

1. Abra `http://localhost:8080`.
2. Informe o `Access Token` da aplicação Mercado Pago.
3. Opcionalmente informe a `Public Key`.
4. Informe valor, descrição e e-mail do pagador.
5. Escolha o tipo de pagamento:
   - `Checkout Pro`: cria uma preferência e retorna um link para abrir o checkout.
   - `Pix`: cria um pagamento Pix e exibe QR Code/copia-e-cola quando retornados pela API.
6. Clique em **Criar pagamento**.

## API REST

### Criar pagamento

```http
POST /api/payments
Content-Type: application/json
```

#### Checkout Pro

```json
{
  "access_token": "TEST-0000000000000000-000000-00000000000000000000000000000000-000000000",
  "public_key": "TEST-00000000-0000-0000-0000-000000000000",
  "amount": 10.50,
  "description": "Produto de teste",
  "payment_type": "CHECKOUT_PRO",
  "payer_email": "comprador@example.com"
}
```

Resposta esperada, em caso de sucesso:

```json
{
  "type": "CHECKOUT_PRO",
  "id": "1234567890",
  "redirect_url": "https://www.mercadopago.com.br/checkout/v1/redirect?...",
  "raw": {}
}
```

#### Pix

```json
{
  "access_token": "TEST-0000000000000000-000000-00000000000000000000000000000000-000000000",
  "amount": 25.00,
  "description": "Pedido Pix de teste",
  "payment_type": "PIX",
  "payer_email": "comprador@example.com"
}
```

Resposta esperada, em caso de sucesso:

```json
{
  "type": "PIX",
  "id": "1234567890",
  "status": "pending",
  "qr_code": "000201...",
  "qr_code_base64": "iVBORw0KGgo...",
  "ticket_url": "https://www.mercadopago.com.br/payments/...",
  "raw": {}
}
```

## Exemplo com curl

```bash
curl -X POST http://localhost:8080/api/payments \
  -H 'Content-Type: application/json' \
  -d '{
    "access_token": "TEST-SEU_ACCESS_TOKEN",
    "amount": 10.00,
    "description": "Produto de teste",
    "payment_type": "PIX",
    "payer_email": "comprador@example.com"
  }'
```

## Estrutura principal

```text
src/main/kotlin/com/example/apipagmp/
├── ApiPagMpApplication.kt   # bootstrap Spring Boot
├── MercadoPagoClient.kt     # cliente HTTP para API Mercado Pago
├── PaymentController.kt     # endpoint REST /api/payments
└── PaymentModels.kt         # DTOs e payloads

src/main/resources/
├── application.properties   # configuração do Spring/Jackson/porta
└── static/index.html        # página simples de teste
```

## Observações de segurança

- Não salve `Access Token` ou `Public Key` em repositórios públicos.
- Prefira variáveis de ambiente ou cofre de segredos em ambientes reais.
- A página HTML é apenas um painel simples para desenvolvimento/testes; em produção, restrinja acesso, autentique usuários e valide permissões.
- O backend encaminha o `Access Token` informado para a API Mercado Pago somente na chamada solicitada.

## Testes e verificações

Execute a compilação e os testes automatizados com:

```bash
gradle test
```

Atualmente o projeto não possui testes unitários customizados; esse comando valida compilação, configuração Gradle e ciclo de testes do Spring/Kotlin.
