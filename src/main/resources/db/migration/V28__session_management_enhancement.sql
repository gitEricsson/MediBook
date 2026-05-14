-- ═══════════════════════════════════════════════════════════════════════════════
-- V26: Session Management Enhancement — Tier 2, Fix 3
-- ═══════════════════════════════════════════════════════════════════════════════
--
-- Adds session timeout and activity tracking for enhanced security:
-- - lastActivityAt: Timestamp of last authenticated request (used for inactivity timeout)
-- - Session timeout: 30 minutes of inactivity triggers token revocation
-- - Token rotation: New token issued on each refresh, old one revoked
--
-- Impact: Users will be logged out after 30 minutes of inactivity.
-- ═══════════════════════════════════════════════════════════════════════════════

-- Add lastActivityAt column to users table for session timeout tracking
ALTER TABLE users ADD COLUMN last_activity_at TIMESTAMP NULL COMMENT 'Timestamp of last authenticated API request (for inactivity timeout)';

-- Create index for efficient timeout queries
CREATE INDEX idx_users_last_activity_at ON users(last_activity_at);

-- ═══════════════════════════════════════════════════════════════════════════════
-- Notes:
-- - lastActivityAt is updated on each authenticated API request by SessionTimeoutFilter
-- - Session timeout default: 30 minutes (configurable via app.security.session.timeout-minutes)
-- - On timeout: SessionTimeoutService revokes all active refresh tokens
-- - RefreshTokenService.rotate() now tracks token rotation events via TokenMetrics
-- ═══════════════════════════════════════════════════════════════════════════════
