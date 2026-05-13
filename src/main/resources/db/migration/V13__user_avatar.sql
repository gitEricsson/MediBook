-- V13: Add avatar_url to users table for profile picture support
ALTER TABLE users ADD COLUMN avatar_url MEDIUMTEXT NULL AFTER locale;
