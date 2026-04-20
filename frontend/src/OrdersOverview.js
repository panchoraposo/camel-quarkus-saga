import React, { useCallback, useEffect, useMemo, useState } from "react";
import "./OrdersOverview.css";
import moment from "moment";
import "moment/locale/es";

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
  const orderBaseUrl = process.env.REACT_APP_ORDER_API_URL || 'http://localhost:8083';

  const fetchOrders = useCallback(async () => {
    const ordersRes = await fetch(`${orderBaseUrl}/orders`);
    if (!ordersRes.ok) {
      throw new Error(`orders fetch failed: ${ordersRes.status}`);
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
    setLastUpdatedAt(Date.now());
  }, [orderBaseUrl]);

  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      try {
        await fetchOrders();
      } catch (err) {
        if (!cancelled) console.error("Error fetching orders:", err);
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
    <div className="container">
      <div className="orders-header">
        <div>
          <h1>Resumen de Órdenes (SAGA)</h1>
          <div className="muted">
            Topics: <code>order-events</code> → <code>seat-events</code> → <code>payment-events</code> (y <code>compensation-events</code>)
          </div>
        </div>

        <div className="orders-controls">
          <label className="toggle">
            <input
              type="checkbox"
              checked={autoRefresh}
              onChange={(e) => setAutoRefresh(e.target.checked)}
            />
            Auto refresh ({REFRESH_MS / 1000}s)
          </label>
          <button className="btn" onClick={() => fetchOrders()} type="button">Actualizar</button>
          <div className="muted small">
            {lastUpdatedAt ? `Última actualización: ${moment(lastUpdatedAt).format("HH:mm:ss")}` : "Sin datos"}
          </div>
        </div>
      </div>

      <div className="orders-layout">
        <div className="orders-table-container">
          <table className="orders-table">
            <thead>
              <tr>
                <th>Order ID</th>
                <th>User</th>
                <th>Order</th>
                <th>Allocation</th>
                <th>Payment</th>
                <th>Fecha</th>
                <th>Precio</th>
              </tr>
            </thead>
            <tbody>
              {orders.map((order) => {
                const selected = order.orderId === selectedOrderId;
                return (
                  <tr
                    key={order.orderId}
                    className={selected ? "row-selected" : ""}
                    onClick={() => setSelectedOrderId(order.orderId)}
                    role="button"
                    tabIndex={0}
                  >
                    <td className="mono">{order.orderId}</td>
                    <td className="mono">{order.userId ?? "N/A"}</td>

                    <td>
                      <span className={`badge tone-${statusTone(order.orderStatus)}`}>{normStatus(order.orderStatus)}</span>
                      <span className="tooltip" title={order.orderMessage || "No additional info"}>ℹ️</span>
                    </td>

                    <td>
                      <span className={`badge tone-${statusTone(order.seatStatus)}`}>{normStatus(order.seatStatus)}</span>
                      <span className="mono muted"> {order.seatId || "N/A"}</span>
                      <span className="tooltip" title={order.seatMessage || "No additional info"}>ℹ️</span>
                    </td>

                    <td>
                      <span className={`badge tone-${statusTone(order.paymentStatus)}`}>{normStatus(order.paymentStatus)}</span>
                      <span className="tooltip" title={order.paymentMessage || "No additional info"}>ℹ️</span>
                    </td>

                    <td>{order.date ? moment(order.date).format("DD/MM HH:mm:ss") : "N/A"}</td>
                    <td>{formatMoney(order.price)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
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
                  <div className="title">Detalle</div>
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