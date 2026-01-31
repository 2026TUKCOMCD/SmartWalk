-- Demo User for Development
-- This user is used when no authentication is provided

INSERT INTO users (id, phone_number, display_name, is_active)
VALUES ('00000000-0000-0000-0000-000000000001', '010-0000-0000', '데모 사용자', TRUE)
ON CONFLICT (id) DO NOTHING;
