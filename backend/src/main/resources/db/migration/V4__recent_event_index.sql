-- The operations overview reads the newest events across all workflows.
-- The existing (workflow_id, sequence_number) index cannot serve that ordering.
CREATE INDEX idx_workflow_events_recent ON workflow_events(created_at DESC, id DESC);
