import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import keycloak from './keycloak';

const AuthContext = createContext({
  ready: false,
  authenticated: false,
  token: null,
  profile: null,
  login: () => {},
  logout: () => {},
});

// React.StrictMode runs effects twice in dev. Keycloak init is not safe to run twice,
// so we guard it with a module-level singleton promise.
let initPromise = null;
function initKeycloakOnce() {
  if (!initPromise) {
    initPromise = keycloak.init({
      onLoad: 'check-sso',
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

  useEffect(() => {
    let cancelled = false;
    initKeycloakOnce()
      .then(async (auth) => {
        if (cancelled) return;
        setAuthenticated(Boolean(auth));
        setToken(keycloak.token || null);
        setReady(true);

        if (auth) {
          try {
            const p = await keycloak.loadUserProfile();
            if (!cancelled) setProfile(p);
          } catch {
            // Fallback to token claims so the UI doesn't show "user".
            const parsed = keycloak.tokenParsed || {};
            const fallback = {
              username: parsed.preferred_username || parsed.upn || parsed.email || null,
              email: parsed.email || null,
              firstName: parsed.given_name || null,
              lastName: parsed.family_name || null,
            };
            if (!cancelled) setProfile(fallback);
          }
        }
      })
      .catch(() => {
        if (cancelled) return;
        setAuthenticated(false);
        setToken(null);
        setReady(true);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!ready) return;
    const interval = setInterval(() => {
      if (!keycloak.token) return;
      keycloak
        .updateToken(30)
        .then((refreshed) => {
          if (refreshed) setToken(keycloak.token || null);
        })
        .catch(() => {
          setAuthenticated(false);
          setToken(null);
          setProfile(null);
        });
    }, 10_000);
    return () => clearInterval(interval);
  }, [ready]);

  const login = useCallback(() => keycloak.login(), []);
  const logout = useCallback(() => keycloak.logout({ redirectUri: window.location.origin }), []);

  const value = useMemo(
    () => ({ ready, authenticated, token, profile, login, logout }),
    [ready, authenticated, token, profile, login, logout]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}

