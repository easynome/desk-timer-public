CREATE TABLE import_receipt (
  request_id TEXT PRIMARY KEY,
  payload_sha256 TEXT NOT NULL,
  entry_id TEXT NOT NULL REFERENCES time_entry(id),
  created_at TEXT NOT NULL
);
