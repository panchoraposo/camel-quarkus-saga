import Keycloak from 'keycloak-js';

function inferKeycloakUrl() {
  const explicit = String(process.env.REACT_APP_KEYCLOAK_URL || '').trim();
  if (explicit) return explicit;

  // Try to infer from OpenShift route convention: *.apps.<cluster-domain>
  const candidates = [
    process.env.REACT_APP_ORDER_API_URL,
    typeof window !== 'undefined' ? window.location.origin : null,
  ].filter(Boolean);

  for (const c of candidates) {
    try {
      const u = new URL(String(c));
      const idx = u.hostname.indexOf('.apps.');
      if (idx > 0) {
        const suffix = u.hostname.slice(idx); // includes ".apps...."
        const proto = u.protocol === 'http:' ? 'http:' : 'https:';
        return `${proto}//keycloak-keycloak${suffix}`;
      }
    } catch {
      // ignore
    }
  }

  return 'http://localhost:8080';
}

const keycloak = new Keycloak({
  url: inferKeycloakUrl(),
  realm: process.env.REACT_APP_KEYCLOAK_REALM || 'camel-saga',
  clientId: process.env.REACT_APP_KEYCLOAK_CLIENT_ID || 'frontend',
});

export default keycloak;

