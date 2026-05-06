# Kaoto + PostgreSQL demo (DevSpaces + Camel JBang)

This small demo shows how to use **Kaoto** to edit a **Camel YAML** route that writes to and reads from a **PostgreSQL** database running on OpenShift.

## Prereqs

- A PostgreSQL service is deployed in OpenShift:
  - **Service**: `kaoto-postgres`
  - **Namespace**: `kaoto`
  - **Database**: `kaoto`
  - **Username/Password**: `kaoto` / `kaoto`
- DevSpaces workspace has outbound network access to the cluster DNS.

## Run with Camel JBang

From a DevSpaces terminal (workspace repo root):

```bash
cd kaoto-postgres-demo/routes

# If you don't have the `camel` CLI yet:
jbang app install camel@apache/camel

# Optional overrides
export KAOTO_DB_USER=kaoto
export KAOTO_DB_PASSWORD=kaoto

# Run the route
camel run kaoto-postgres.camel.yaml \
  --properties=../application.properties \
  --dep org.postgresql:postgresql:42.7.3 \
  --dep org.apache.camel:camel-sql \
  --dep org.apache.camel:camel-xpath \
  --dep org.apache.camel:camel-xslt-saxon \
  --dep org.apache.camel:camel-jackson \
  --dep org.apache.camel:camel-netty-http
```

You should see logs printing a JSON payload with the latest rows from `kaoto_demo`.

## Send XML, get JSON (HTTP)

The route also exposes an HTTP endpoint that accepts an XML payload and returns JSON:

```bash
curl -sS -X POST http://localhost:8080/kaoto/demo \
  -H 'Content-Type: application/xml' \
  --data-binary @- <<'XML'
<reading>
  <sensorId>S-200</sensorId>
  <location>office</location>
  <tempC>22.1</tempC>
  <humidity>40.0</humidity>
</reading>
XML
```

## Open in Kaoto

1. Open `kaoto-postgres-demo/routes/kaoto-postgres.camel.yaml` in the editor.
2. Click the **Kaoto DataMapper** step (`kaoto-datamapper-reading`) and press **Configure**.
3. Attach schemas so the DataMapper canvas shows a tree on both sides:
   - **Source → Body**: `routes/schemas/reading.xsd` (root element: `reading`)
   - **Target → Body**: `routes/schemas/canonical-reading.schema.json`
4. Create mappings by drag-and-drop (for example, `reading/sensorId` → `sensor/id`, etc.).
5. Re-run the `camel run ...` command to test your changes.

