# Development database snapshot

Put a fresh SQL dump exported from the current development database in this
directory before the first `docker compose -f docker-compose.dev.yml up`.

Use a filename such as `001-ai_exam_platform-latest.sql`. MySQL executes SQL
files in this directory only when the `mysql-dev-data` volume is empty.

Do not copy a MySQL `data` directory into this directory. The repository
snapshot in `docs/数据库设计/ai_exam_platform.sql` is not the current
development database and must not be used as the development baseline.
