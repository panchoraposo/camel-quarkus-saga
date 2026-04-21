import React, { useCallback, useEffect, useMemo, useState } from 'react';
import axios from 'axios';
import { BrowserRouter as Router, Routes, Route, Link } from 'react-router-dom';
import OrdersOverview from './OrdersOverview';
import { AuthProvider, useAuth } from './auth/AuthContext';
import './App.css';

function badgeTone(status) {
  const s = String(status || '').toUpperCase();
  switch (s) {
    case 'RESERVED':
    case 'COMPLETED':
    case 'FREE':
      return 'ok';
    case 'FAILED':
    case 'CANCELLED':
      return 'bad';
    case 'PENDING':
      return 'pending';
    default:
      return 'neutral';
  }
}

function money(value) {
  const n = Number(value);
  if (Number.isNaN(n)) return 'N/A';
  return `$${n.toFixed(2)}`;
}

function SeatSelection() {
  const [selectedSeat, setSelectedSeat] = useState('');
  const [seats, setSeats] = useState([]);
  const [status, setStatus] = useState('');
  const [statusTone, setStatusTone] = useState('neutral');
  const [loadingSeats, setLoadingSeats] = useState(false);
  const [forceFailPayment, setForceFailPayment] = useState(false);
  const [allowReservedSelection, setAllowReservedSelection] = useState(false);
  const [compactSeatmap, setCompactSeatmap] = useState(true);
  const [me, setMe] = useState(null);
  const [loadingMe, setLoadingMe] = useState(false);
  const orderBaseUrl = useMemo(() => {
    const explicit = String(process.env.REACT_APP_ORDER_API_URL || '').trim();
    if (explicit) return explicit;
    try {
      if (typeof window !== 'undefined') {
        const h = window.location.hostname;
        const idx = h.indexOf('.apps.');
        if (idx > 0) return `${window.location.protocol}//order-order${h.slice(idx)}`;
      }
    } catch {
      // ignore
    }
    return 'http://localhost:8083';
  }, []);

  const allocationBaseUrl = useMemo(() => {
    const explicit = String(process.env.REACT_APP_ALLOCATION_API_URL || '').trim();
    if (explicit) return explicit;
    try {
      if (typeof window !== 'undefined') {
        const h = window.location.hostname;
        const idx = h.indexOf('.apps.');
        if (idx > 0) return `${window.location.protocol}//allocation-allocation${h.slice(idx)}`;
      }
    } catch {
      // ignore
    }
    return 'http://localhost:8081';
  }, []);

  const UNAVAILABLE_STATUS = "RESERVED"; 

  const { ready, authenticated, token, profile, login, logout } = useAuth();

  const refreshSeats = useCallback(async () => {
    setLoadingSeats(true);
    try {
      const res = await axios.get(`${allocationBaseUrl}/seats`);
      const sortedSeats = (res.data || []).sort((a, b) => a.seatId.localeCompare(b.seatId));
      setSeats(sortedSeats);
    } catch (error) {
      console.error('Error al obtener asientos:', error);
      setStatusTone('bad');
      setStatus('Error al cargar la lista de asientos.');
    } finally {
      setLoadingSeats(false);
    }
  }, [allocationBaseUrl]);

  useEffect(() => {
    refreshSeats();
  }, [refreshSeats]);

  const refreshMe = useCallback(async () => {
    if (!authenticated || !token) return;
    setLoadingMe(true);
    try {
      const res = await axios.get(`${orderBaseUrl}/users/me`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      setMe(res.data || null);
    } catch (error) {
      console.error('Error al obtener usuario actual:', error);
      setMe(null);
    } finally {
      setLoadingMe(false);
    }
  }, [authenticated, token, orderBaseUrl]);

  useEffect(() => {
    refreshMe();
  }, [refreshMe]);

  const handleSeatClick = (seatId) => {
    const seat = seats.find(s => s.seatId === seatId);
    if (seat && seat.status && seat.status.toUpperCase() === UNAVAILABLE_STATUS) {
      if (!allowReservedSelection) {
        setStatusTone('bad');
        setStatus(`El asiento ${seatId} ya está reservado.`);
        return;
      }
      // Demo mode: allow selecting reserved seats to simulate races/late conflicts
      setStatusTone('pending');
      setStatus(`Modo demo: seleccionaste ${seatId} aunque ya esté reservado. Al generar la orden, la SAGA debería fallar y compensar.`);
    }
    setSelectedSeat(seatId);
    setStatusTone('neutral');
    if (!(seat && seat.status && seat.status.toUpperCase() === UNAVAILABLE_STATUS && allowReservedSelection)) {
      setStatus('');
    }
  };

  const handlePayment = async () => {
    if (!selectedSeat) {
      setStatusTone('bad');
      setStatus('Por favor, selecciona un asiento antes de pagar.');
      return;
    }
    if (!authenticated || !token) {
      setStatusTone('bad');
      setStatus('Debes iniciar sesión con Keycloak para generar una orden.');
      return;
    }

    const selectedSeatData = seats.find(seat => seat.seatId === selectedSeat);
    const price = selectedSeatData ? selectedSeatData.price : 0;

    try {
      const response = await axios.post(`${orderBaseUrl}/order`, {
        seatId: selectedSeat,
        price: price,
        forceFailPayment: forceFailPayment
      }, {
        headers: { Authorization: `Bearer ${token}` },
      });
      setStatusTone('ok');
      setStatus(`Orden creada. Sigue el flujo en “Órdenes / Timeline”.`);
      console.log(response.data);

      await refreshSeats();
      await refreshMe();
    } catch (error) {
      setStatusTone('bad');
      setStatus(`Error al crear orden para el asiento ${selectedSeat}`);
      console.error(error);
    }
  };

  const zoneForSeat = useCallback((seat) => {
    const p = Number(seat?.price);
    if (Number.isNaN(p)) return 'neutral';
    if (p >= 85) return 'premium'; // black
    if (p >= 75) return 'front';   // blue
    if (p >= 65) return 'mid';     // amber
    return 'stalls';               // green
  }, []);

  const seatRows = useMemo(() => {
    const byRow = new Map();
    for (const s of seats) {
      const row = String(s.seatId || '').charAt(0).toUpperCase();
      if (!row) continue;
      if (!byRow.has(row)) byRow.set(row, []);
      byRow.get(row).push(s);
    }
    // sort seats inside each row by number
    for (const [k, arr] of byRow.entries()) {
      arr.sort((a, b) => {
        const an = Number(String(a.seatId || '').slice(1));
        const bn = Number(String(b.seatId || '').slice(1));
        if (Number.isNaN(an) || Number.isNaN(bn)) return String(a.seatId).localeCompare(String(b.seatId));
        return an - bn;
      });
      byRow.set(k, arr);
    }
    // Stable seatmap layout: keep rows in alphabetical order (A..)
    return Array.from(byRow.entries())
      .map(([row, arr]) => ({ row, seats: arr }))
      .sort((a, b) => a.row.localeCompare(b.row));
  }, [seats]);

  const splitBlocks = useCallback((rowSeats) => {
    const left = [];
    const center = [];
    const right = [];
    for (const s of rowSeats || []) {
      const n = Number(String(s.seatId || '').slice(1));
      if (!Number.isFinite(n)) {
        center.push(s);
      } else if (n <= 10) {
        left.push(s);
      } else if (n <= 20) {
        center.push(s);
      } else {
        right.push(s);
      }
    }
    return { left, center, right };
  }, []);

  const tiers = useMemo(() => {
    const orchestra = [];
    const mezzanine = [];
    const balcony = [];

    for (const r of seatRows) {
      const idx = String(r.row || '').toUpperCase().charCodeAt(0) - 65;
      if (idx <= 7) orchestra.push(r);        // A..H
      else if (idx <= 13) mezzanine.push(r);  // I..N
      else balcony.push(r);                   // O..T
    }

    return { orchestra, mezzanine, balcony };
  }, [seatRows]);

  const tierConfigs = useMemo(
    () => ([
      { id: 'orchestra', title: 'ORCHESTRA', rows: tiers.orchestra, insetMax: 26 },
      { id: 'mezzanine', title: 'MEZZANINE', rows: tiers.mezzanine, insetMax: 18 },
      { id: 'balcony', title: 'BALCONY', rows: tiers.balcony, insetMax: 12 },
    ]),
    [tiers]
  );

  const seatStats = useMemo(() => (
    seats.reduce(
      (acc, s) => {
        const st = String(s.status || '').toUpperCase();
        acc.total += 1;
        if (st === 'RESERVED') acc.reserved += 1;
        else acc.free += 1;
        return acc;
      },
      { total: 0, free: 0, reserved: 0 }
    )
  ), [seats]);

  const selectedSeatObj = seats.find((s) => s.seatId === selectedSeat);
  const canSubmit = Boolean(authenticated && selectedSeat) && !(selectedSeatObj && String(selectedSeatObj.status || '').toUpperCase() === UNAVAILABLE_STATUS);

  return (
    <div className="page">
      <div className="hero">
        <div>
          <div className="hero-kicker">Ticketing · Distributed transactions</div>
          <h1 className="hero-title">TicketBlaster Live</h1>
          <div className="hero-subtitle">
            Selecciona un asiento, compra tu ticket y mira el timeline de eventos y compensaciones.
          </div>
        </div>
        <div className="hero-meta">
          <div className="pill">
            <span className="dot dot-ok" /> Kafka events
          </div>
          <div className="pill">
            <span className="dot dot-pending" /> Saga orchestration
          </div>
          <div className="pill">
            <span className="dot dot-bad" /> Compensations
          </div>
        </div>
      </div>

      <div className="grid">
        <div className="card">
          <div className="card-header">
            <div>
              <div className="card-title">Compra</div>
              <div className="card-subtitle">Elige un asiento y confirma tu compra</div>
            </div>
            <div className="card-actions">
              <button className="btn-secondary" type="button" onClick={refreshSeats} disabled={loadingSeats}>
                {loadingSeats ? 'Cargando…' : 'Asientos'}
              </button>
            </div>
          </div>

          <div className="summary">
            <div className="kv">
              <div className="k">Auth</div>
              <div className="v">
                {ready ? (
                  authenticated ? (
                    <span className="badge tone-ok">LOGGED IN</span>
                  ) : (
                    <span className="badge tone-neutral">LOGGED OUT</span>
                  )
                ) : (
                  <span className="badge tone-pending">LOADING</span>
                )}
              </div>
            </div>
            <div className="kv">
              <div className="k">Usuario</div>
              <div className="v mono">{authenticated ? (profile?.username || profile?.email || '—') : '—'}</div>
            </div>
            <div className="kv">
              <div className="k">Saldo</div>
              <div className="v mono">{authenticated ? (loadingMe ? '…' : money(me?.budget)) : '—'}</div>
            </div>
          </div>

          {!ready ? (
            <div className="hint muted" style={{ marginTop: 12 }}>
              Conectando con Keycloak…
            </div>
          ) : null}

          <div className="summary">
            <div className="kv">
              <div className="k">Asiento</div>
              <div className="v mono">{selectedSeatObj ? `${selectedSeatObj.seatId} · ${money(selectedSeatObj.price)}` : '—'}</div>
            </div>
            <div className="kv">
              <div className="k">Disponibilidad</div>
              <div className="v">
                <span className={`badge tone-${badgeTone(selectedSeatObj?.status || 'N/A')}`}>
                  {String(selectedSeatObj?.status || 'N/A').toUpperCase()}
                </span>
              </div>
            </div>
          </div>

          <div className="field" style={{ marginTop: 10 }}>
            <label className="label" style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <input
                type="checkbox"
                checked={forceFailPayment}
                onChange={(e) => setForceFailPayment(e.target.checked)}
              />
              Simular falla de pago (dispara compensación)
            </label>
          </div>

          <div className="field" style={{ marginTop: 10 }}>
            <label className="label" style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <input
                type="checkbox"
                checked={allowReservedSelection}
                onChange={(e) => setAllowReservedSelection(e.target.checked)}
              />
              Modo carrera: permitir seleccionar asientos ya reservados (para simular conflicto)
            </label>
          </div>

          <button className="btn-primary" onClick={handlePayment} disabled={!canSubmit}>
            Generar orden
          </button>

          {status ? (
            <div className={`alert tone-${statusTone}`}>
              <div className="alert-title">
                {statusTone === 'ok' ? 'Listo' : statusTone === 'bad' ? 'Atención' : 'Info'}
              </div>
              <div className="alert-body">{status}</div>
              <div className="alert-footer">
                <Link to="/orders" className="link">Abrir Órdenes / Timeline</Link>
              </div>
            </div>
          ) : (
            <div className="hint muted">
              Consejo: para ver compensaciones, activa “Simular falla de pago” o intenta comprar un asiento ya reservado.
            </div>
          )}
        </div>

        <div className="card card-wide">
          <div className="card-header">
            <div>
              <div className="card-title">Mapa de asientos</div>
              <div className="card-subtitle">
                Total <span className="mono">{seatStats.total}</span> · libres <span className="mono">{seatStats.free}</span> · reservados <span className="mono">{seatStats.reserved}</span>
              </div>
            </div>
            <div className="legend">
              <label className="legend-item" style={{ gap: 6 }}>
                <input
                  type="checkbox"
                  checked={compactSeatmap}
                  onChange={(e) => setCompactSeatmap(e.target.checked)}
                />
                Compacto
              </label>
              <span className="legend-item"><span className="seat-swatch swatch-free" /> FREE</span>
              <span className="legend-item"><span className="seat-swatch swatch-reserved" /> RESERVED</span>
              <span className="legend-item"><span className="seat-swatch swatch-selected" /> SELECTED</span>
            </div>
          </div>

          <div className="seats-container">
            <div className={`venue-map ${compactSeatmap ? 'is-compact' : ''}`}>
              <div className="venue-stage">STAGE</div>

              {tierConfigs.map((tier) => (
                <div key={tier.id} className={`venue-tier tier-${tier.id}`}>
                  <div className="venue-tier-title">{tier.title}</div>
                  <div className="venue-tier-labels mono">
                    <span>1–10</span>
                    <span>11–20</span>
                    <span>21–30</span>
                  </div>

                  <div className="venue-tier-rows">
                    {tier.rows.map(({ row, seats: rowSeats }, i) => {
                      const { left, center, right } = splitBlocks(rowSeats);
                      // Curvature facing the stage: rows closer to the stage should be wider,
                      // and rows farther away should be slightly narrower.
                      const t = i / Math.max(tier.rows.length - 1, 1);
                      const inset = Math.round(t * tier.insetMax);

                      const renderSeat = (seat, idx, len) => {
                        const isReserved = seat.status && seat.status.toUpperCase() === UNAVAILABLE_STATUS;
                        const isSelected = selectedSeat === seat.seatId;
                        const isFree = String(seat.status || '').toUpperCase() === 'FREE';
                        const zone = zoneForSeat(seat);
                        const disableSeat = isReserved && !allowReservedSelection;
                        const c = (len - 1) / 2;
                        const dist = Math.abs(idx - c);
                        const bend = dist * (compactSeatmap ? 0.8 : 1.2);
                        return (
                          <span key={seat.seatId} className="seat-arc" style={{ '--bend': `${bend}px` }}>
                            <button
                              type="button"
                              className={`seat seatmap-dot zone-${zone} ${isFree ? 'is-free' : ''} ${isSelected ? 'selected' : ''} ${isReserved ? 'not-available' : ''}`}
                              onClick={() => handleSeatClick(seat.seatId)}
                              disabled={disableSeat}
                              aria-label={`${seat.seatId} ${money(seat.price)} ${String(seat.status || '').toUpperCase()}`}
                              title={`${seat.seatId} · ${money(seat.price)} · ${String(seat.status || '').toUpperCase()}`}
                            />
                          </span>
                        );
                      };

                      return (
                        <div key={row} className="venue-row" style={{ '--inset': `${inset}px` }}>
                          <div className="venue-row-block venue-left">{left.map((s, idx) => renderSeat(s, idx, left.length))}</div>
                          <div className="venue-row-letter mono">{row}</div>
                          <div className="venue-row-block venue-center">{center.map((s, idx) => renderSeat(s, idx, center.length))}</div>
                          <div className="venue-row-letter mono">{row}</div>
                          <div className="venue-row-block venue-right">{right.map((s, idx) => renderSeat(s, idx, right.length))}</div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function Topbar() {
  const { ready, authenticated, profile, login, logout } = useAuth();
  return (
    <div className="topbar">
      <div className="topbar-inner">
        <div className="brand">TicketBlaster</div>
        <nav className="nav">
          <Link to="/" className="nav-link">Reservar asiento</Link>
          <Link to="/orders" className="nav-link">Órdenes / Timeline</Link>
        </nav>
        <div className="nav" style={{ marginLeft: 'auto', gap: 10 }}>
          {!ready ? (
            <span className="muted mono">auth…</span>
          ) : (
            <>
              <span className="muted mono" title="Usuario autenticado">
                {profile?.username || profile?.email || '—'}
              </span>
              <button className="btn-secondary" type="button" onClick={logout}>Salir</button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function App() {
  return (
    <AuthProvider>
      <Router>
        <Topbar />
        <Routes>
          <Route path="/" element={<SeatSelection />} />
          <Route path="/orders" element={<OrdersOverview />} />
        </Routes>
      </Router>
    </AuthProvider>
  );
}

export default App;