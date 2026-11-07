# Connect another app

Sign in to the Habit Lab web app and open **Settings → API tokens**. Name the integration and create a token. Copy it immediately: only its hash is stored, so the secret cannot be shown again. Each token expires after 90 days and can be revoked separately from Settings.

Tokens grant **read-only** access to your synced server data. The available collections are `habit`, `run`, `run_habit`, and `check_in`. Local changes on a phone are visible after that phone syncs with the server. Responses are JSON arrays; each element contains the record's `uid` and available synced fields. Deleted records are omitted. A valid token identifies its account, so another account's data cannot be requested by changing a header.

Example (replace the server address and use a secret stored outside source code):

```sh
curl https://your-habitlab-server.example/api/integration/habit \
  -H "Authorization: Bearer $HABITLAB_API_TOKEN"
```

Use the same pattern with `/api/integration/run`, `/api/integration/run_habit`, and `/api/integration/check_in`. An expired, revoked, or invalid token returns HTTP 401. An unsupported collection returns HTTP 404. These endpoints return the current synced fields; they do not expose calculated summaries or provide write operations.

Keep tokens private and use HTTPS when connecting over a network. Revoking a token immediately blocks it without disconnecting your phone. The login/sync credential is separate and should not be shared with integrations. Integration tokens cannot manage other tokens or call the write-capable sync endpoint.
