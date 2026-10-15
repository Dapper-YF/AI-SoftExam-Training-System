# Docker development environment

The development stack is defined in `docker-compose.dev.yml`.

The MySQL container is initialized from a fresh dump in `docker/mysql/init`.
Do not use the old `docs/数据库设计/ai_exam_platform.sql` as the baseline.
Export the current database, including its `flyway_schema_history`, so the
runtime V9/V23/V24 validators see the same schema history as the source
development environment.
