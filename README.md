## TicketBlaster Live (Camel + Quarkus SAGA, Kafka choreography)

This repository contains the **application source code** for a distributed ticketing demo that implements a **real event-driven SAGA (choreography)** using **Apache Camel on Quarkus** and **Apache Kafka**.

The demo is designed to run on OpenShift (recommended via the GitOps repo), but the services can also be run in dev mode for development and debugging.

## What you get

- **Frontend** (`frontend/`): React UI that authenticates via Keycloak and calls backend APIs.
- **Order service** (`order/`): entry point for creating orders, reserving/refunding budget, and orchestrating order state based on downstream events.
- **Allocation service** (`allocate/`): seat reservation component; publishes `SeatReserved` or `SeatReserveFailed`.
- **Payment service** (`payment/`): payment step; can be forced to fail to exercise compensation.

Kafka topics used for choreography:

- `order-events`
- `seat-events`
- `payment-events`
- `compensation-events`

## SAGA choreography (high-level)

- **1) Order created**: the Order service validates request + budget, persists an initial row, then publishes `OrderCreated` to `order-events`.
- **2) Seat allocation**: the Allocation service consumes `OrderCreated` and attempts an atomic DB update:
  - success → publishes `SeatReserved` to `seat-events`
  - failure (seat already reserved) → publishes `SeatReserveFailed` to `seat-events`
- **3) Payment**: the Payment service consumes `SeatReserved` and processes payment:
  - success → publishes `PaymentCompleted` to `payment-events`
  - failure → publishes `PaymentFailed` to `payment-events`
- **4) Order finalization + compensation**:
  - on `SeatReserveFailed`: Order marks the order as `FAILED` and refunds budget
  - on `PaymentFailed`: Order marks the order as `FAILED`, refunds budget, and publishes `CompensateSeat` to `compensation-events`
  - Allocation consumes `CompensateSeat` and publishes `SeatReleased` to `seat-events`

## Demo failure scenarios

- **Payment failure**: send `forceFailPayment=true` when creating the order to intentionally fail the payment step and trigger compensation (budget refund + seat release).
- **Seat conflict**: two users attempt to buy the same seat; the second order fails with `SeatReserveFailed` and the budget is refunded.

## Authentication (Keycloak)

The demo expects a Keycloak realm named `ticketblaster` and a public client `frontend`.

Default demo users commonly used in the environment:

- `johndoe` / `demo`
- `janedoe` / `demo`
- `alice` / `demo`
- `bob` / `demo`
- `admin` / `admin` (admin-only endpoints)

## Development workflow (service-by-service)

Each backend service is a Quarkus application.

- **Run in dev mode**:

```bash
./mvnw compile quarkus:dev
```

See each module’s README for Quarkus packaging options:

- `order/README.md`
- `allocate/README.md`
- `payment/README.md`
- `frontend/README.md`

## Deploying to OpenShift

Use the GitOps repository (`demo-camel-saga-gitops`) to install the complete demo (operators, Kafka cluster + topics, Keycloak, Kafka consoles, and the applications) using ArgoCD.