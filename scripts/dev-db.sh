#!/usr/bin/env bash
# Creates the local dev database role and database for the `dev` profile. Idempotent.
# Needs a running PostgreSQL on localhost (brew install postgresql@17 && brew services start postgresql@17).
# Matches the dev fallbacks in application-dev.properties: cinefilm / cinefilm on localhost:5432.
set -euo pipefail

DB_USER="${CINEFILM_DB_USER:-cinefilm}"
DB_PASSWORD="${CINEFILM_DB_PASSWORD:-cinefilm}"
DB_NAME="cinefilm"

if [ "$(psql -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname = '${DB_USER}'")" != "1" ]; then
  psql -d postgres -c "CREATE ROLE ${DB_USER} LOGIN PASSWORD '${DB_PASSWORD}'"
fi
if [ "$(psql -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '${DB_NAME}'")" != "1" ]; then
  createdb -O "${DB_USER}" "${DB_NAME}"
fi
echo "Dev database '${DB_NAME}' ready for role '${DB_USER}'."
