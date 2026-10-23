# ChangeScope

ChangeScope is a GitHub-integrated code change impact analysis platform for Java and Spring Boot repositories. It analyzes pull requests and source code modifications, constructs AST-based dependency graphs, identifies affected classes and test suites, calculates blast radius, and provides actionable verification plans and reports.

---

## Current Architecture & Implementation

ChangeScope is actively implemented with a Spring Boot backend and an interactive frontend:

- **AST-Based Static Analysis:** Uses [JavaParser](https://javaparser.org/) (3.26.2) to parse Java 21 sources, extracting structural declarations for classes, interfaces, records, and enums, along with method declarations, constructor signatures, fields, and imports.
- **Blast Radius & Dependency Analysis:** Traces method-level and class-level ripple effects across changed files, generating call graphs and affected type dependencies.
- **Test Impact Analysis & Verification Plans:** Maps modified methods and types to potentially impacted test suites, generating ordered verification plans for efficient regression testing.
- **Repository Registry & Persistent Analysis:** Manages monitored repositories and records full historical PR impact reports in PostgreSQL (`/api/v1/repositories`, `/api/v1/analyses`).
- **Automated Lifecycle Reconciliation:** Scheduled background task reconciles abandoned or timed-out `RUNNING` analyses to `FAILED` after a configurable threshold (`analysis.stale-timeout`, `analysis.reconciliation-interval`).
- **GitHub Integration:**
  - REST API client with dual authentication: GitHub App (RSA PEM key with JWT generation and installation token caching) and Personal Access Token (PAT) fallback.
  - Webhook processing (`POST /api/v1/webhooks/github`) with HMAC-SHA256 signature verification, delivery deduplication via `X-GitHub-Delivery`, transient retry handling, and explicit head SHA validation.
- **Frontend Dashboard:** Modern React, TypeScript, and Vite dashboard (located in `frontend/`) styled with Tailwind CSS.

---

## Technology Stack

- **Backend:** Java 21, Spring Boot 3.3.5, Spring Data JPA / Hibernate, PostgreSQL, JavaParser 3.26.2, Spring Boot Actuator, Apache Maven
- **Frontend:** React 18, TypeScript, Vite, Tailwind CSS
- **Authentication & Integrations:** GitHub REST API, GitHub Apps, GitHub Webhooks (HMAC-SHA256)

---

## Local Setup Instructions

### 1. Prerequisites

Ensure the following tools are installed on your machine:
- **Java Development Kit (JDK) 21** or higher (`java -version`)
- **PostgreSQL 14+** running locally or in Docker (`psql --version`)
- **Maven 3.9+** (or use the included `./mvnw` wrapper in `backend/`)
- **Node.js 18+** & npm (for the frontend)

### 2. Database Creation

Create the `changescope` PostgreSQL database. Using the PostgreSQL CLI:

```bash
# Using createdb command
createdb -U postgres changescope

# Or using psql:
psql -U postgres -c "CREATE DATABASE changescope;"
```

Ensure your PostgreSQL service is running on `localhost:5432` with user `postgres` (or adjust credentials in `.env`).

### 3. Environment Configuration

Spring Boot reads environment variables mapped to Spring properties. ChangeScope provides a template in `backend/.env.example`.

Copy the template to `.env` in the `backend/` directory:

```bash
cd backend
cp .env.example .env
```

Edit `backend/.env` with your local PostgreSQL credentials:

```bash
PORT=8081
DB_HOST=localhost
DB_PORT=5432
DB_NAME=changescope
DB_USERNAME=postgres
DB_PASSWORD=your_postgres_password
```

> [!IMPORTANT]
> **Spring Boot does not automatically load `.env` files.**
> `backend/.env` is git-ignored and intended for local development. ChangeScope does not add third-party dotenv dependencies. You must export these variables into your shell environment before starting the application, or configure them in your IDE.

#### Recommended Ways to Load Environment Variables

**Option A: Shell Export (Bash / Zsh)**
```bash
cd backend
set -a
source .env
set +a
./mvnw spring-boot:run
```

**Option B: Single-command Inline Execution (Bash / Zsh)**
```bash
cd backend
env $(grep -v '^#' .env | xargs) ./mvnw spring-boot:run
```

**Option C: IDE Run Configuration**
- **IntelliJ IDEA:** Open `Run/Debug Configurations` -> Select `ChangeScopeApplication` -> Add environment variables under *Environment variables*, or point to your `.env` file using the EnvFile plugin.
- **VS Code:** Add an `"envFile": "${workspaceFolder}/backend/.env"` entry to your `.vscode/launch.json` configuration.

---

## Configuration Reference

The following table summarizes all configuration properties recognized by `backend/src/main/resources/application.properties`:

| Environment Variable | Spring Property | Default Value | Required? | Description |
|---|---|---|---|---|
| `PORT` | `server.port` | `8081` | Optional | HTTP port for the Spring Boot application. |
| `DB_HOST` | `spring.datasource.url` | `localhost` | Required* | PostgreSQL server hostname. |
| `DB_PORT` | `spring.datasource.url` | `5432` | Required* | PostgreSQL server port. |
| `DB_NAME` | `spring.datasource.url` | `changescope` | Required* | PostgreSQL database name. |
| `DB_URL` | `spring.datasource.url` | *(Constructed from host/port/name)* | Optional | Full JDBC URL override. Overrides `DB_HOST`, `DB_PORT`, and `DB_NAME` if provided. |
| `DB_USERNAME` | `spring.datasource.username` | `postgres` | Required | PostgreSQL user. |
| `DB_PASSWORD` | `spring.datasource.password` | `postgres` | Required | PostgreSQL password. |
| `JPA_DDL_AUTO` | `spring.jpa.hibernate.ddl-auto` | `update` | Optional | Hibernate schema update mode (`update`, `validate`, `none`). |
| `ANALYSIS_STALE_TIMEOUT` | `analysis.stale-timeout` | `30m` | Optional | Cutoff duration after which an unfinished `RUNNING` analysis is transitioned to `FAILED`. |
| `ANALYSIS_RECONCILIATION_INTERVAL` | `analysis.reconciliation-interval` | `5m` | Optional | Interval for the background scheduler to scan for stale analyses. |
| `GITHUB_API_BASE_URL` | `github.api-base-url` | `https://api.github.com` | Optional | Base URL for GitHub REST API (set for GitHub Enterprise or mock servers). |
| `GITHUB_TOKEN` | `github.token` | *(empty)* | Optional | Personal Access Token (PAT) fallback for GitHub API requests during local development. |
| `GITHUB_APP_ID` | `github.app-id` | *(empty)* | Optional | GitHub App ID for App-based authentication. |
| `GITHUB_PRIVATE_KEY` | `github.private-key` | *(empty)* | Optional | GitHub App RSA private key in PEM format (inline string). |
| `GITHUB_PRIVATE_KEY_PATH` | `github.private-key-path` | *(empty)* | Optional | File path to GitHub App RSA private key PEM file. |
| `GITHUB_INSTALLATION_ID` | `github.installation-id` | *(empty)* | Optional | Default/fallback GitHub App installation ID. *(Repositories registered in ChangeScope specify their own installation ID; this is only a fallback).* |
| `GITHUB_WEBHOOK_SECRET` | `github.webhook-secret` | *(empty)* | Optional | Secret key used to verify incoming GitHub webhook HMAC-SHA256 signatures (`X-Hub-Signature-256`). |

*\* Note: GitHub credentials are NOT required to start the application. You only need database credentials for basic local operation and static analysis.*

---

## Starting and Validating the Application

### 1. Build and Run the Backend

```bash
cd backend
# Export variables and run:
set -a && source .env && set +a
./mvnw spring-boot:run
```

The application will start on port `8081` (or your configured `PORT`).

### 2. Verify Startup & Health

You can verify the backend is running properly without configuring any GitHub credentials:

**Health Check (Actuator):**
```bash
curl -s http://localhost:8081/actuator/health
```
*Expected response:*
```json
{"status":"UP","components":{"db":{"status":"UP",...},"diskSpace":{...},"ping":{...}}}
```

**Application Status Check:**
```bash
curl -s http://localhost:8081/api/v1/status
```
*Expected response:*
```json
{"status":"UP","application":"ChangeScope Backend","version":"0.0.1-SNAPSHOT","timestamp":...}
```

### 3. Test Static Impact Analysis Locally

You can test ChangeScope's static analysis directly via HTTP POST without any GitHub tokens:

```bash
curl -X POST http://localhost:8081/api/v1/analysis/impact \
  -H "Content-Type: application/json" \
  -d '{
    "changedTypes": ["com.example.OrderService"],
    "fileInputs": [
      {
        "filePath": "src/main/java/com/example/OrderService.java",
        "content": "package com.example; public class OrderService { public void placeOrder() {} }"
      },
      {
        "filePath": "src/test/java/com/example/OrderServiceTest.java",
        "content": "package com.example; public class OrderServiceTest { @org.junit.jupiter.api.Test void testOrder() { new OrderService().placeOrder(); } }"
      }
    ]
  }'
```

---

## GitHub Webhook Integration

### Webhook Endpoint & Setup
- **Endpoint:** `POST /api/v1/webhooks/github`
- **Payload Format:** JSON (`application/json`)
- **Secret:** Configured in `GITHUB_WEBHOOK_SECRET`.
- **Signature Verification:** All incoming requests are verified using HMAC-SHA256 against the raw payload bytes (`X-Hub-Signature-256` header). Missing or mismatched signatures return `401 Unauthorized` (`INVALID_SIGNATURE`).

### Supported Events & Actions
- **Event:** `X-GitHub-Event: pull_request`
- **Supported Actions:** `opened`, `synchronize`, `reopened`.
- All other actions (e.g. `closed`, `labeled`) or non-PR events (e.g. `push`) are acknowledged with status `ignored`.

### Deduplication and Retry Handling
- Every webhook delivery contains an `X-GitHub-Delivery` GUID.
- Deliveries are recorded in the `webhook_deliveries` table.
- Duplicate deliveries that are already `RECEIVED` or `PROCESSED` return `200 OK` (`duplicate`) to prevent redundant concurrent processing.
- If a delivery fails due to a transient error (e.g. temporary network failure or rate limiting), it is marked retryable and can be safely reprocessed by GitHub webhook retries.
- Permanent errors (e.g. invalid signature, unregistered repository, unsupported historical revision) are marked non-retryable.

### Known Limitation: Historical Webhook Revisions
GitHub's REST API endpoint for pull request changed files (`/repos/{owner}/{repo}/pulls/{pull_number}/files`) returns only the file diff corresponding to the **current, live head** of the pull request.

When a webhook arrives with an explicit `pull_request.head.sha` that does not match the live PR's current head SHA (such as delayed webhook delivery or replay of an earlier commit), ChangeScope detects the divergence and rejects the delivery with `409 Conflict` (`HISTORICAL_REVISION_UNSUPPORTED`). This prevents inaccurate analysis of historical commits against mismatched live file sets. The webhook must be re-delivered for the active head revision.

---

## Analysis Capabilities & Current Limitations

1. **Static Analysis Only:**
   - ChangeScope performs static AST parsing using JavaParser to inspect class, interface, record, and enum structures, method declarations, invocations, field access, and imports.
   - It does not perform dynamic code execution, bytecode enhancement, or runtime profiling.
2. **Candidate Test Recommendations (No Execution):**
   - The analysis recommends candidate test suites and test methods based on static dependency graphs and naming/import relationships.
   - **Candidate tests are not automatically executed** by the backend (Surefire/Failsafe/JUnit test runners are not invoked).
3. **PR Changeset Analysis Scope:**
   - Analysis is scoped to files modified in the pull request changeset and their immediate referenced types fetched via the GitHub API or provided in the request payload.
   - It does not maintain a persistent, whole-repository semantic graph or global index across all historical commits.

---

## Primary API Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/actuator/health` | Spring Boot Actuator health check (DB, disk, status). |
| `GET` | `/api/v1/status` | Application status and version check. |
| `POST` | `/api/v1/analysis/impact` | Direct static code impact analysis from raw source files and changed types. |
| `GET` | `/api/v1/repositories` | List all registered repositories. |
| `POST` | `/api/v1/repositories` | Register a repository (`owner`, `name`, `defaultBranch`, `installationId`). |
| `GET` | `/api/v1/repositories/{id}` | Get repository details by ID. |
| `DELETE` | `/api/v1/repositories/{id}` | Delete a registered repository. |
| `POST` | `/api/v1/repositories/{id}/pulls/{number}/analyze` | Trigger impact analysis for a specific pull request. |
| `GET` | `/api/v1/repositories/{id}/analyses` | Retrieve paginated analysis history for a repository. |
| `GET` | `/api/v1/repositories/{id}/analyses/{analysisId}` | Retrieve full report snapshot for a specific analysis run. |
| `POST` | `/api/v1/webhooks/github` | Receive and process signed GitHub pull request webhooks. |
