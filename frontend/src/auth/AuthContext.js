import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import keycloak from './keycloak';

const AuthContext = createContext({
  ready: false,
  authenticated: false,
  token: null,
  profile: null,
  roles: [],
  login: () => {},
  logout: () => {},
});

// React.StrictMode runs effects twice in dev. Keycloak init is not safe to run twice,
// so we guard it with a module-level singleton promise.
let initPromise = null;
function initKeycloakOnce() {
  if (!initPromise) {
    initPromise = keycloak.init({
      // Hard-require auth for the demo: if not logged in, redirect to Keycloak.
      onLoad: 'login-required',
      pkceMethod: 'S256',
      checkLoginIframe: false,
    });
  }
  return initPromise;
}

export function AuthProvider({ children }) {
  const [ready, setReady] = useState(false);
  const [authenticated, setAuthenticated] = useState(false);
  const [token, setToken] = useState(null);
  const [profile, setProfile] = useState(null);
  const [authDegraded, setAuthDegraded] = useState(false);
  const [roles, setRoles] = useState([]);

  useEffect(() => {
    let cancelled = false;
    initKeycloakOnce()
      .then(async (auth) => {
        if (cancelled) return;
        setAuthenticated(Boolean(auth));
        setToken(keycloak.token || null);
        setReady(true);
        setAuthDegraded(false);

        if (auth) {
          // Prefer token claims for immediate UI correctness (topbar, /users/me).
          const parsed = keycloak.tokenParsed || {};
          const realmRoles = Array.isArray(parsed?.realm_access?.roles) ? parsed.realm_access.roles : [];
          const clientRoles =
            parsed?.resource_access && typeof parsed.resource_access === 'object'
              ? Object.values(parsed.resource_access)
                  .flatMap((v) => (Array.isArray(v?.roles) ? v.roles : []))
                  .filter(Boolean)
              : [];
          setRoles(Array.from(new Set([...realmRoles, ...clientRoles])));
          const claimFallback = {
            username: parsed.preferred_username || parsed.upn || parsed.email || null,
            email: parsed.email || null,
            firstName: parsed.given_name || null,
            lastName: parsed.family_name || null,
          };
          setProfile((prev) => prev || claimFallback);

          try {
            const p = await keycloak.loadUserProfile();
            if (!cancelled) setProfile(p);
          } catch {
            // Fallback to token claims so the UI doesn't show "user".
            if (!cancelled) setProfile((prev) => prev || claimFallback);
          }
        }
      })
      .catch(() => {
        if (cancelled) return;
        setAuthenticated(false);
        setToken(null);
        setReady(true);
        setAuthDegraded(false);
        setRoles([]);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!ready) return;
    let consecutiveFailures = 0;
    let timeoutId = null;

    const schedule = (ms) => {
      if (timeoutId) window.clearTimeout(timeoutId);
      timeoutId = window.setTimeout(tick, ms);
    };

    const tick = () => {
      if (!keycloak.token) return;
      keycloak
        .updateToken(30)
        .then((refreshed) => {
          if (refreshed) setToken(keycloak.token || null);
          consecutiveFailures = 0;
          setAuthDegraded(false);
          if (refreshed) {
            const parsed = keycloak.tokenParsed || {};
            const realmRoles = Array.isArray(parsed?.realm_access?.roles) ? parsed.realm_access.roles : [];
            const clientRoles =
              parsed?.resource_access && typeof parsed.resource_access === 'object'
                ? Object.values(parsed.resource_access)
                    .flatMap((v) => (Array.isArray(v?.roles) ? v.roles : []))
                    .filter(Boolean)
                : [];
            setRoles(Array.from(new Set([...realmRoles, ...clientRoles])));
          }
          schedule(10_000);
        })
        .catch(() => {
          // If Keycloak is temporarily unreachable (failover, rollout, LB switch),
          // keep the user session in the UI as long as the current token is still valid.
          consecutiveFailures += 1;
          setAuthDegraded(true);

          const expiresAtMs = (keycloak.tokenParsed?.exp || 0) * 1000;
          const now = Date.now();
          const stillValid = expiresAtMs > now + 5_000; // small safety margin

          if (!stillValid) {
            setAuthenticated(false);
            setToken(null);
            setProfile(null);
            setAuthDegraded(false);
            setRoles([]);
            return;
          }

          // Exponential backoff (max 60s) to avoid hammering the auth endpoint during outages.
          const backoffMs = Math.min(60_000, 2 ** Math.min(consecutiveFailures, 6) * 1_000);
          schedule(backoffMs);
        });
    };

    // Start immediately after we're ready so we learn quickly if refresh works.
    schedule(0);

    return () => {
      if (timeoutId) window.clearTimeout(timeoutId);
    };
  }, [ready]);

  const login = useCallback(() => keycloak.login(), []);
  const logout = useCallback(() => keycloak.logout({ redirectUri: window.location.origin }), []);

  const value = useMemo(
    () => ({ ready, authenticated, token, profile, roles, authDegraded, login, logout }),
    [ready, authenticated, token, profile, roles, authDegraded, login, logout]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}

