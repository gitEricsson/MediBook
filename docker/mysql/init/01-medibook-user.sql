CREATE DATABASE IF NOT EXISTS medibook;

CREATE USER IF NOT EXISTS 'medibook'@'%' IDENTIFIED BY 'medibook';
ALTER USER 'medibook'@'%' IDENTIFIED BY 'medibook';

GRANT ALL PRIVILEGES ON medibook.* TO 'medibook'@'%';
FLUSH PRIVILEGES;
