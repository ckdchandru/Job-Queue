# Job Queue

A reliable background job queue built with Java, Spring Boot and PostgreSQL.

## About

Some tasks like sending emails or generating reports take time, and doing them inside an API
request makes the user wait. This project moves that work to the background. The API saves each
task as a job in PostgreSQL, and worker threads pick up the jobs and run them.

My main focus was reliability. A job should never be lost, never be run by two workers at the
same time, and never stay stuck if a worker crashes.

## Features

- Submit jobs through a REST API and check their status
- Multiple workers can process jobs at the same time without picking the same job
- Failed jobs are retried automatically with exponential backoff and jitter
- Jobs that fail too many times go to a dead letter queue
- Admin endpoints to view dead jobs and requeue them
- Background reclaimer that recovers jobs stuck on a crashed worker
- Idempotency keys so the same request does not create duplicate jobs
- Graceful shutdown, so running jobs finish before the app stops

## How It Works

Every job moves through these states:

```
PENDING -> PROCESSING -> COMPLETED
               |
               +--> PENDING (retry later)
               |
               +--> DEAD (too many failures)
```

1. A job is saved as `PENDING`.
2. A worker claims it with `SELECT ... FOR UPDATE SKIP LOCKED`. This locks the row, and other workers skip it instead of waiting, so each job goes to only one worker.
3. When claiming, the worker also gets a unique claim token. Every later update checks this token, so a worker that lost its job cannot overwrite it.
4. If the job succeeds it becomes `COMPLETED`. If it fails, it goes back to `PENDING` with a delay that doubles every attempt (plus some random jitter so retries don't all happen at once).
5. After the maximum attempts, the job becomes `DEAD`. An admin can requeue it later.
6. If a worker crashes while running a job, the job stays `PROCESSING`. The reclaimer checks every 30 seconds and puts such jobs back in the queue.

## Tech Stack

- Java 17
- Spring Boot 3 (Web, Data JPA, JDBC)
- PostgreSQL
- Flyway for database migrations
- JUnit 5 and Spring Boot Test
- Docker and Docker Compose

## Project Structure

All the code is inside `src/main/java/com/jq`:

- `controller` - REST endpoints
- `service` - business logic, retry backoff, reclaimer
- `repository` - database access (JPA and SQL queries)
- `worker` - worker threads and worker manager
- `handler` - job handlers (one per job type)
- `scheduler` - scheduled reclaimer task
- `model` - entity and data classes
- `dto` - request and response objects
- `exception` - custom exceptions and error handling
- `config` - admin token check

## Getting Started

You need Docker installed. Then run:

```
docker compose up --build
```

This starts PostgreSQL and the app. Flyway creates the tables automatically.
The app runs on `http://localhost:8080`.

## API

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/jobs` | Submit a job |
| GET | `/api/v1/jobs/{jobId}` | Get job status |
| GET | `/api/v1/admin/jobs/dead` | List dead jobs (admin) |
| POST | `/api/v1/admin/jobs/{jobId}/requeue` | Requeue a dead job (admin) |

Admin endpoints need the `X-Admin-Token` header.

Example request:

```
curl -X POST http://localhost:8080/api/v1/jobs \
  -H "Content-Type: application/json" \
  -d '{"type": "SEND_EMAIL", "payload": {"to": "user@example.com"}, "idempotencyKey": "order-101"}'
```

`maxAttempts` and `delaySeconds` are optional fields.

## Running Tests

The tests need a PostgreSQL database, so start it first:

```
docker compose up -d db
mvn test
```

The tests cover claim races between workers, retries, the dead letter queue, the reclaimer,
graceful shutdown and the API.

## What I Learned

- How row-level locking and `SKIP LOCKED` work in PostgreSQL
- Why retries need backoff and jitter
- Handling race conditions between workers and the reclaimer
- Writing integration tests for concurrent code
- Managing threads and shutdown in a Spring Boot app
