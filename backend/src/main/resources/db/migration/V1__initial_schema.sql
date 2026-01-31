-- NavBlind Initial Schema
-- Version: V1
-- Date: 2026-01-30

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Users table
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    phone_number VARCHAR(20) NOT NULL UNIQUE,
    display_name VARCHAR(100),
    firebase_uid VARCHAR(128) UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_users_phone_number ON users(phone_number);
CREATE INDEX idx_users_firebase_uid ON users(firebase_uid);

-- Destinations table
CREATE TABLE destinations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    latitude DECIMAL(10, 8) NOT NULL,
    longitude DECIMAL(11, 8) NOT NULL,
    address VARCHAR(500),
    label VARCHAR(50),
    use_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_destination_user_id ON destinations(user_id);
CREATE INDEX idx_destination_use_count ON destinations(user_id, use_count DESC);

-- Navigation Sessions table
CREATE TABLE navigation_sessions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    origin_lat DECIMAL(10, 8) NOT NULL,
    origin_lng DECIMAL(11, 8) NOT NULL,
    dest_lat DECIMAL(10, 8) NOT NULL,
    dest_lng DECIMAL(11, 8) NOT NULL,
    dest_name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    distance_meters INTEGER,
    reroute_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT chk_session_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED', 'FAILED'))
);

CREATE INDEX idx_session_user_active ON navigation_sessions(user_id, status) WHERE status = 'ACTIVE';
CREATE INDEX idx_session_user_history ON navigation_sessions(user_id, started_at DESC);

-- Preferences table (key-value storage)
CREATE TABLE preferences (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key VARCHAR(100) NOT NULL,
    value VARCHAR(500) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_preference_key UNIQUE (user_id, key)
);

CREATE INDEX idx_preferences_user_id ON preferences(user_id);

-- Smart Glasses table
CREATE TABLE smart_glasses (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id VARCHAR(100) NOT NULL UNIQUE,
    mac_address VARCHAR(17),
    name VARCHAR(100) NOT NULL DEFAULT 'Smart Glasses',
    last_connected TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_smart_glasses_user_id ON smart_glasses(user_id);
CREATE INDEX idx_smart_glasses_device_id ON smart_glasses(device_id);
