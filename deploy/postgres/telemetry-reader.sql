-- A read-only role for Grafana that sees the consented telemetry tables and
-- nothing else: no users, emails, libraries or reading progress. Run after
-- migrations (the tables must exist); docker-compose's telemetry-reader
-- service does that. In production use a real password and the same grants.
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'grafana_reader') THEN
    CREATE ROLE grafana_reader LOGIN PASSWORD 'grafana_reader';
  END IF;
END
$$;
GRANT CONNECT ON DATABASE libraryz TO grafana_reader;
GRANT USAGE ON SCHEMA public TO grafana_reader;
GRANT SELECT ON crash_groups, crash_reports, telemetry_events TO grafana_reader;
