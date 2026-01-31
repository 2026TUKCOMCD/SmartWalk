-- NavBlind Database Initialization Script
-- This script runs on first PostgreSQL container startup

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Create schema (if needed)
-- Note: Tables are created by Flyway migrations

-- Grant permissions
GRANT ALL PRIVILEGES ON DATABASE navblind TO navblind;
