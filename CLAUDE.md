# Claude Code Documentation for Yakread

This document contains information useful for AI assistants working on the Yakread codebase.

## Project Overview

Yakread is a Clojure web application built with the Biff framework. It's an algorithmic reading app that aggregates content from various sources (RSS feeds, emails, etc.).

## Running Tests

### Test Framework

This project uses an inline snapshot testing approach where unit tests are stored in `*_test.edn` files:
- You write the `:eval` part (the expression to evaluate)
- The test runner writes the `:result` part (the expected output)

### Running Tests

```bash
# Run all tests
clojure -X:run com.yakread.lib.test/run-examples!

# Run tests for a specific file
clojure -X:run com.yakread.lib.test/run-examples! :ext '"materialized_views_test.edn"'
```

### REPL-based Testing (Faster)

For faster iteration, start an nREPL server and connect to it:

```bash
# Start nREPL server on port 7888
clojure -M:run nrepl

# Then use trench or another nREPL client to call run-examples!
```

Install `trench` for command-line nREPL access:
```bash
TRENCH_VERSION=0.4.0
curl -sSLf https://github.com/athos/trenchman/releases/download/v$TRENCH_VERSION/trenchman_${TRENCH_VERSION}_linux_amd64.tar.gz | tar zxvfC - /usr/local/bin trench
```

## Code Architecture

### fx/defmachine Pattern

The codebase uses a "machine" pattern for structuring application logic as pure functions that return data describing side effects. This is defined in `com.yakread.lib.fx`.

Key concepts:
- **Machines** are defined with `fx/defmachine` and have named states
- Each state is a pure function that takes a context map and returns effect descriptions
- Effects are maps with keys like `:biff.fx/tx`, `:biff.fx/pathom`, `:biff.fx/http`, etc.
- `:biff.fx/next` specifies the next state to transition to

Example:
```clojure
(fx/defmachine my-machine
  :start
  (fn [{:keys [biff/job]}]
    {:biff.fx/pathom {:entity {:user/id (:user/id job)}
                      :query [:user/email]}
     :biff.fx/next :process})

  :process
  (fn [{:keys [biff.fx/pathom]}]
    {:biff.fx/email {:to (:user/email pathom)
                     :template :welcome}}))
```

### Testing Machines

Since machines are pure functions, you can test individual states by calling them directly:

```clojure
;; Test the :start state
(my-machine {:biff/job {:user/id "123"}} :start)

;; Test the :process state with mock pathom results
(my-machine {:biff.fx/pathom {:user/email "test@example.com"}} :process)
```

### Common Effect Keys

- `:biff.fx/next` - Next state to transition to
- `:biff.fx/pathom` - Execute a Pathom query
- `:biff.fx/tx` - Database transaction
- `:biff.fx/http` - HTTP request
- `:biff.fx/email` - Send email
- `:biff.fx/s3` - S3 operations
- `:biff.fx/queue` - Queue jobs
- `:biff.fx/temp-dir` - Create temporary directory
- `:biff.fx/shell` - Execute shell command
- `:biff.fx/write` - Write files
- `:biff.fx/delete-files` - Delete files

### Test Helpers (com.yakread.lib.test)

- `t/with-node` - Create a test database with initial data
- `t/instant` - Create a java.time.Instant
- `t/zdt` - Create a ZonedDateTime in UTC
- `t/uuid` - Create a deterministic UUID from a number
- `t/queue` - Create a test queue with optional initial jobs
- `t/truncate` - Truncate long strings in test output

### Test File Structure

Test files are EDN maps with:
- `:require` - Vector of namespace requires
- `:fixtures` - Optional map of fixture generators (stored in `*_fixtures.edn`)
- `:tests` - Vector of test cases, with `_` separators for readability

Each test case has:
- `:eval` - Expression to evaluate
- `:result` - Expected result (filled in by test runner)
- `:doc` - Optional description

## Directory Structure

```
src/com/yakread/
├── lib/          # Shared libraries and utilities
├── model/        # Data model and Pathom resolvers
├── app/          # Application routes and UI
├── work/         # Background job processors (machines)
└── util/         # Utility functions

test/com/yakread/
├── lib/          # Tests for lib namespaces
├── model/        # Tests for model namespaces
├── app/          # Tests for app namespaces
└── work/         # Tests for work namespaces
```

## Key Namespaces

### com.yakread.work.materialized-views

Handles updating materialized views when data changes:
- `update-views` machine: Updates subscription affinity and current item views
- `on-tx` machine: Triggered on transactions to queue view update jobs

### com.yakread.work.account

Handles account-related operations:
- `export-user-data` machine: Exports user data to a zip file and sends download link
- `delete-account` machine: Deletes user account and associated data

## Dependencies

Key dependencies:
- Biff framework (com.biffweb/biff)
- XTDB v2 for database
- Pathom3 for data fetching
- tick for date/time handling

## Notes

- The project uses XTDB v2 which has different APIs from v1
- Tests use `gen/*rnd*` bound to a seeded Random for deterministic UUIDs
- The `?` macro from Pathom marks optional query attributes
