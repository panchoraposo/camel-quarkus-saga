import React, { useCallback, useEffect, useMemo, useState } from "react";
import "./OrdersOverview.css";
import moment from "moment";
import "moment/locale/es";
import { useAuth } from "./auth/AuthContext";

const REFRESH_MS = 2000;

function normStatus(value) {
  if (!value) return "N/A";
  return String(value).toUpperCase();
}

function statusTone(status) {
  switch (normStatus(status)) {
    case "COMPLETED":
    case "RESERVED":
      return "ok";
    case "FAILED":
    case "CANCELLED":
      return "bad";
    case "PENDING":
      return "pending";
    default:
      return "neutral";
  }
}

function formatMoney(value) {
  if (value === null || value === undefined) return "N/A";
  const n = Number(value);
  if (Number.isNaN(n)) return "N/A";
  return `$${n.toFixed(2)}`;
}

const OrdersOverview = () => {
  const [orders, setOrders] = useState([]);
  const [selectedOrderId, setSelectedOrderId] = useState(null);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [lastUpdatedAt, setLastUpdatedAt] = useState(null);
  const [error, setError] = useState(null);
  const [statusFilter, setStatusFilter] = useState("ALL");
  const { token, profile } = useAuth();
  const orderBaseUrl = process.env.REACT_APP_ORDER_API_URL || 'http://localhost:8083';

  const fetchOrders = useCallback(async () => {
    const ordersRes = await fetch(`${orderBaseUrl}/orders`, {
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    });
    if (!ordersRes.ok) {
      const txt = await ordersRes.text().catch(() => "");
      throw new Error(`orders fetch failed: ${ordersRes.status}${txt ? ` — ${txt}` : ""}`);
    }
    const ordersData = await ordersRes.json();

    const detailedOrders = (ordersData || []).map((order) => ({
      ...order,
      seat: order.seatId
        ? { seatId: order.seatId, price: order.price, message: order.seatMessage, status: order.seatStatus }
        : { seatId: "N/A", price: null, message: "No seat assigned", status: "N/A" },
      payment: order.paymentId
        ? { paymentId: order.paymentId, status: order.paymentStatus, message: order.paymentMessage }
        : { paymentId: "N/A", status: "N/A", message: "No payment available" },
    }));

    setOrders(detailedOrders);
    setError(null);
    setLastUpdatedAt(Date.now());
  }, [orderBaseUrl, token]);

  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      try {
        await fetchOrders();
      } catch (err) {
        if (!cancelled) {
          console.error("Error fetching orders:", err);
          setError(err?.message || "No se pudo cargar órdenes.");
        }
      }
    };

    run();

    if (!autoRefresh) return () => { cancelled = true; };
    const id = setInterval(run, REFRESH_MS);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [fetchOrders, autoRefresh]);

  const selectedOrder = useMemo(() => {
    if (!selectedOrderId) return null;
    return orders.find((o) => o.orderId === selectedOrderId) || null;
  }, [orders, selectedOrderId]);

  const filteredOrders = useMemo(() => {
    const list = [...orders].sort((a, b) => (b?.date || 0) - (a?.date || 0));
    if (statusFilter === "ALL") return list;
    return list.filter((o) => normStatus(o.orderStatus) === statusFilter);
  }, [orders, statusFilter]);

  const summary = useMemo(() => {
    const all = orders.length;
    const completed = orders.filter((o) => normStatus(o.orderStatus) === "COMPLETED").length;
    const failed = orders.filter((o) => ["FAILED", "CANCELLED"].includes(normStatus(o.orderStatus))).length;
    const pending = orders.filter((o) => normStatus(o.orderStatus) === "PENDING").length;
    return { all, completed, failed, pending };
  }, [orders]);

  const sagaSteps = useMemo(() => {
    if (!selectedOrder) return [];
    return [
      {
        key: "order",
        title: "Order service",
        status: selectedOrder.orderStatus,
        subtitle: selectedOrder.orderId,
        message: selectedOrder.orderMessage,
      },
      {
        key: "allocation",
        title: "Allocation service",
        status: selectedOrder.seatStatus,
        subtitle: selectedOrder.seatId ? `seatId=${selectedOrder.seatId}` : "no seat",
        message: selectedOrder.seatMessage,
      },
      {
        key: "payment",
        title: "Payment service",
        status: selectedOrder.paymentStatus,
        subtitle: selectedOrder.paymentId ? `paymentId=${selectedOrder.paymentId}` : "no payment",
        message: selectedOrder.paymentMessage,
      },
      {
        key: "result",
        title: "Saga result",
        status: selectedOrder.orderStatus,
        subtitle: formatMoney(selectedOrder.price),
        message: selectedOrder.orderMessage,
      },
    ];
  }, [selectedOrder]);

  return (
    <div className="orders-page">
      <div className="orders-hero">
        <div className="orders-hero-left">
          <div className="orders-kicker">Kafka choreography · Live trace</div>
          <h1 className="orders-title">Órdenes / Timeline</h1>
          <div className="orders-subtitle">
            Selecciona una orden para ver el estado por microservicio y las compensaciones.
          </div>

          <div className="orders-pipeline">
            <span className="pipe-node">order-events</span>
            <span className="pipe-arrow">→</span>
            <span className="pipe-node">seat-events</span>
            <span className="pipe-arrow">→</span>
            <span className="pipe-node">payment-events</span>
            <span className="pipe-arrow">→</span>
            <span className="pipe-node dim">compensation-events</span>
          </div>
        </div>

        <div className="orders-hero-right">
          <div className="orders-metrics">
            <div className="metric">
              <div className="metric-label">Total</div>
              <div className="metric-value">{summary.all}</div>
            </div>
            <div className="metric">
              <div className="metric-label">Completed</div>
              <div className="metric-value ok">{summary.completed}</div>
            </div>
            <div className="metric">
              <div className="metric-label">Failed</div>
              <div className="metric-value bad">{summary.failed}</div>
            </div>
            <div className="metric">
              <div className="metric-label">Pending</div>
              <div className="metric-value pending">{summary.pending}</div>
            </div>
          </div>

          <div className="orders-controls">
            <div className="muted small right">
              {profile?.username ? <>User: <span className="mono">{profile.username}</span></> : null}
            </div>
            <label className="toggle">
              <input
                type="checkbox"
                checked={autoRefresh}
                onChange={(e) => setAutoRefresh(e.target.checked)}
              />
              Auto refresh ({REFRESH_MS / 1000}s)
            </label>
            <button className="btn" onClick={() => fetchOrders()} type="button">Actualizar</button>
            <div className="muted small right">
              {lastUpdatedAt ? `Última actualización: ${moment(lastUpdatedAt).format("HH:mm:ss")}` : "Sin datos"}
            </div>
          </div>
        </div>
      </div>

      {error ? (
        <div className="orders-alert">
          <div className="orders-alert-title">No pudimos cargar órdenes</div>
          <div className="orders-alert-body mono">{error}</div>
        </div>
      ) : null}

      <div className="orders-layout">
        <div className="orders-list-panel">
          <div className="orders-list-header">
            <div className="filters">
              <button className={`chip ${statusFilter === "ALL" ? "active" : ""}`} onClick={() => setStatusFilter("ALL")} type="button">Todas</button>
              <button className={`chip ${statusFilter === "COMPLETED" ? "active" : ""}`} onClick={() => setStatusFilter("COMPLETED")} type="button">Completed</button>
              <button className={`chip ${statusFilter === "PENDING" ? "active" : ""}`} onClick={() => setStatusFilter("PENDING")} type="button">Pending</button>
              <button className={`chip ${statusFilter === "FAILED" ? "active" : ""}`} onClick={() => setStatusFilter("FAILED")} type="button">Failed</button>
            </div>
            <div className="muted small right mono">{filteredOrders.length} items</div>
          </div>

          {filteredOrders.length === 0 ? (
            <div className="empty big">
              <div className="title">Aún no hay órdenes</div>
              <div className="muted">Crea una compra desde “Reservar asiento” para ver el flujo aquí.</div>
              <button className="btn-secondary" type="button" onClick={() => (window.location.href = "/")}>Ir a reservar</button>
            </div>
          ) : (
            <div className="orders-cards">
              {filteredOrders.map((order) => {
                const selected = order.orderId === selectedOrderId;
                return (
                  <button
                    key={order.orderId}
                    className={`order-card ${selected ? "selected" : ""}`}
                    onClick={() => setSelectedOrderId(order.orderId)}
                    type="button"
                  >
                    <div className="order-card-top">
                      <div className="order-id mono">{order.orderId}</div>
                      <span className={`badge tone-${statusTone(order.orderStatus)}`}>{normStatus(order.orderStatus)}</span>
                    </div>
                    <div className="order-card-mid">
                      <div className="kv">
                        <div className="k">Seat</div>
                        <div className="v mono">{order.seatId ?? "N/A"}</div>
                      </div>
                      <div className="kv">
                        <div className="k">Price</div>
                        <div className="v mono">{formatMoney(order.price)}</div>
                      </div>
                      <div className="kv">
                        <div className="k">User</div>
                        <div className="v mono">{order.userId ?? "N/A"}</div>
                      </div>
                      <div className="kv">
                        <div className="k">Time</div>
                        <div className="v">{order.date ? moment(order.date).format("DD/MM HH:mm:ss") : "N/A"}</div>
                      </div>
                    </div>
                    <div className="order-card-bottom">
                      <span className={`mini tone-${statusTone(order.seatStatus)}`}>ALLOC {normStatus(order.seatStatus)}</span>
                      <span className={`mini tone-${statusTone(order.paymentStatus)}`}>PAY {normStatus(order.paymentStatus)}</span>
                      {order.orderMessage ? (
                        <span className="mini-msg" title={order.orderMessage}>{order.orderMessage}</span>
                      ) : (
                        <span className="mini-msg muted">Sin mensaje</span>
                      )}
                    </div>
                  </button>
                );
              })}
            </div>
          )}
        </div>

        <div className="order-details">
          {!selectedOrder ? (
            <div className="empty">
              <div className="title">Selecciona una orden</div>
              <div className="muted">
                Aquí verás el detalle del flujo por microservicio (incluyendo compensaciones).
              </div>
            </div>
          ) : (
            <>
              <div className="details-header">
                <div>
                  <div className="title">Detalle / Trace</div>
                  <div className="muted mono">{selectedOrder.orderId}</div>
                </div>
                <button className="btn-secondary" type="button" onClick={() => setSelectedOrderId(null)}>Cerrar</button>
              </div>

              <div className="details-grid">
                <div><span className="muted">User</span><div className="mono">{selectedOrder.userId ?? "N/A"}</div></div>
                <div><span className="muted">Seat</span><div className="mono">{selectedOrder.seatId ?? "N/A"}</div></div>
                <div><span className="muted">Payment</span><div className="mono">{selectedOrder.paymentId ?? "N/A"}</div></div>
                <div><span className="muted">Price</span><div className="mono">{formatMoney(selectedOrder.price)}</div></div>
              </div>

              <div className="timeline">
                {sagaSteps.map((step) => (
                  <div key={step.key} className="step">
                    <div className={`dot tone-${statusTone(step.status)}`} />
                    <div className="step-body">
                      <div className="step-title">
                        <span>{step.title}</span>
                        <span className={`badge tone-${statusTone(step.status)}`}>{normStatus(step.status)}</span>
                      </div>
                      <div className="muted small">{step.subtitle}</div>
                      {step.message ? <div className="step-message">{step.message}</div> : <div className="muted small">Sin mensaje</div>}
                    </div>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
};

export default OrdersOverview;