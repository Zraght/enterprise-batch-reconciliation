-- =====================================================================================================
-- Creates ReconciliationDB and the application login. Idempotent: safe to run more than once.
--
-- Run with sqlcmd, passing the application password as a variable (it is never stored in this file):
--
--   sqlcmd -S localhost,1433 -U sa -P "<sa-password>" -C -v APP_PASSWORD="<app-password>" \
--          -i database/01_create_database.sql
--
-- The application creates every table itself through Flyway at startup; do NOT create tables by hand.
-- Collation: the design assumes a case-INSENSITIVE collation (the default of SQL Server installations);
-- references are additionally normalised to upper case by the application.
-- =====================================================================================================
USE master;
GO

IF DB_ID(N'ReconciliationDB') IS NULL
BEGIN
    CREATE DATABASE ReconciliationDB COLLATE SQL_Latin1_General_CP1_CI_AS;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.sql_logins WHERE name = N'reconciliation_app')
BEGIN
    CREATE LOGIN reconciliation_app WITH PASSWORD = N'$(APP_PASSWORD)', CHECK_POLICY = ON, DEFAULT_DATABASE = ReconciliationDB;
END
GO

USE ReconciliationDB;
GO

IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = N'reconciliation_app')
BEGIN
    CREATE USER reconciliation_app FOR LOGIN reconciliation_app;
END
GO

-- Flyway needs DDL rights to evolve the schema. For a stricter setup use two accounts:
-- a migration account (db_owner / db_ddladmin) and a runtime account (db_datareader + db_datawriter).
ALTER ROLE db_owner ADD MEMBER reconciliation_app;
GO
