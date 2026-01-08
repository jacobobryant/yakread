# Yakread Development Guide

## Project Overview

Yakread is a Clojure web application that uses:
- **Clojure** as the primary language
- **XTDB** (v2) for the database (requires Java 21+)
- **Biff** as the web framework
- The **fx/defmachine** pattern for organizing side-effect-heavy application logic as pure functions

## Getting Started

### Prerequisites

- Java 21+ (required for XTDB v2)
- Clojure CLI (`clj`)

### Running Tests

Run the full test suite:
```bash
clj -X:run com.yakread.lib.test/run-examples!
```

Run tests for a specific file:
```bash
clj -X:run com.yakread.lib.test/run-examples! :ext '"materialized_views_test.edn"'
```

### Optional: Using nREPL for Faster Development

Start an nREPL server on port 7888:
```bash
clj -M:run nrepl
```

Then use `trench` (see `server_setup.sh`) to send commands.

## Testing Approach

This project uses **inline snapshot testing** stored in `*_test.edn` files:

1. Write the `:eval` expression (input)
2. Run tests - the runner automatically writes the `:result` or `:ex` (output)
3. Review the results and commit

### Test File Structure

```clojure
{:require
 [[com.yakread.lib.test :as t]
  [clojure.data.generators :as gen]
  [com.yakread.work.namespace-name :refer :all]],
 :tests
 [{:doc "Description of test case"
   :eval (function-under-test args)
   :result expected-result}
  _  ; separator between tests
  ...]}
```

### Test Utilities

- `t/with-node` - Creates a test database node with seed data
- `t/zdt year` - Creates a ZonedDateTime for year (e.g., `(t/zdt 2000)`)
- `t/instant year` - Creates an Instant for year
- `t/uuid n` - Creates a deterministic UUID from a number (e.g., `(t/uuid 1)` → `#uuid "00010000-0000-0000-0000-000000000001"`)

### Database Seeding Example

```clojure
(t/with-node
 [node
  {:table-name
   [{:xt/id (t/uuid 1) :field/value "data"}
    {:xt/id (t/uuid 2) :field/value "more data"}]}]
 (function-under-test {:biff/conn node} args))
```

**Important Notes:**
- All documents need a valid `:xt/id` field
- Use `t/uuid` for generating IDs that work with `biffx/prefix-uuid`
- Don't include empty table arrays - they cause XTDB errors

## fx/defmachine Pattern

The `fx/defmachine` macro defines state machines for organizing application logic as pure functions:

```clojure
(fx/defmachine machine-name
  :start
  (fn [{:keys [biff/job]}]
    {:biff.fx/pathom {...}
     :biff.fx/next :next-state})

  :next-state
  (fn [{:keys [biff.fx/pathom]}]
    {:biff.fx/tx [...]}))
```

### Common Effect Keys

- `:biff.fx/next` - Transition to another state
- `:biff.fx/pathom` - Execute a Pathom query (returned data available in next state)
- `:biff.fx/tx` - Execute database transactions
- `:biff.fx/queue` - Queue jobs for background processing
- `:biff.fx/http` - Make HTTP requests
- `:biff.fx/s3` - S3 operations
- `:biff.fx/email` - Send emails
- `:biff.fx/temp-dir` - Create temp directory
- `:biff.fx/write` - Write files
- `:biff.fx/shell` - Execute shell commands
- `:biff.fx/delete-files` - Delete files

### Testing Machines

Test each state function independently:

```clojure
;; Test :start state
{:eval (machine-name {:biff/job {:key "value"}} :start)
 :result {:biff.fx/next :next-state}}

;; Test :next-state with pathom result from previous state
{:eval (machine-name {:biff.fx/pathom {:result "data"}} :next-state)
 :result {:biff.fx/tx [...]}}
```

## Key Namespaces

- `com.yakread.lib.test` - Test utilities and snapshot test runner
- `com.yakread.lib.fx` - The fx/defmachine macro
- `com.yakread.work.*` - Background job machines
- `com.yakread.model.*` - Data model functions

## Common Patterns

### Using biffx/q for Database Queries

```clojure
(biffx/q conn {:select [:field1 :field2]
               :from :table
               :where [:= :field value]})
```

### Creating UUIDs with prefix-uuid

The `biffx/prefix-uuid` function expects a UUID-like hex string as the first argument:

```clojure
(biffx/prefix-uuid (t/uuid 1) (gen/uuid))  ; Works
(biffx/prefix-uuid "abc123" (gen/uuid))    ; Works (hex string)
(biffx/prefix-uuid "user1" (gen/uuid))     ; Fails (not hex)
```

## Troubleshooting

### Java Version Errors
If you see `class file version 65.0` errors, you need Java 21:
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
```

### XTDB "missing child vector: _id" Error
This occurs when trying to insert documents without `:xt/id` or with empty table arrays.

### Wrong Number of Args to biffx/q
Ensure you're passing both `conn` and the query map:
```clojure
(biffx/q conn {:select ...})  ; Correct
```
