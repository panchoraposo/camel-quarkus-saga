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

export function AuthProvider({ children }) {
  const [ready, setReady] = useState(false);
  const [authenticated, setAuthenticated] = useState(false);
  const [token, setToken] = useState(null);
  const [profile, setProfile] = useState(null);

  useEffect(() => {
    let cancelled = false;
    keycloak
      .init({
        onLoad: 'check-sso',
        pkceMethod: 'S256',
        checkLoginIframe: false,
      })
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
            // ignore
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

