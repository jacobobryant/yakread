# Development Notes for Claude/AI Assistants

## Running the App Locally

### Prerequisites
- Java 17+
- Clojure CLI tools (install via Docker or from https://clojure.org/guides/install_clojure)
- Node.js (for Tailwind CSS compilation)

### Initial Setup

1. **Generate configuration file:**
   ```bash
   clj -M:run generate-config
   ```
   This creates `config.env` with auto-generated secrets.

2. **Compile Java components:**
   ```bash
   javac --release 17 -cp $(clj -Spath) -d target/classes java/com/yakread/AverageRating.java -Xlint:-options
   ```

3. **Start the development server:**
   ```bash
   clj -M:run dev
   ```
   
   The server will:
   - Compile Tailwind CSS
   - Start XTDB database
   - Start Jetty on port 8080
   - Start nREPL server on port 7888
   - Enable file watching for hot reload

4. **Access the app:**
   - Web UI: http://localhost:8080
   - nREPL: localhost:7888

### File Watching and Hot Reload

The `clj -M:run dev` command includes a file watcher (Beholder) that automatically reloads changed files. However, in practice during development:

- **File watching works** for most Clojure files in `src/`
- Changes are automatically compiled and loaded
- You'll see reload messages in the server output like `:reloading (namespace1 namespace2 ...)`

### Using the REPL

The nREPL server starts automatically on port 7888. You can connect to it using:

1. **From command line:**
   ```bash
   clj -M:run nrepl
   ```
   This opens an nREPL session (though it requires proper nREPL client setup).

2. **From an editor:** Connect your editor's REPL client to `localhost:7888`

3. **Evaluate code in running server:**
   Since the file watcher handles most reloads automatically, you typically don't need manual REPL evaluation during development. Just save your files and the watcher will reload them.

### Common Development Issues

#### Issue: Analytics snippet causing 500 error
**Problem:** Empty string in `ANALYTICS_SNIPPET` config causes Rum rendering error.

**Solution:** The `unsafe` function in `com.yakread.util.biff-staging` now handles empty/nil values:
```clojure
(defn unsafe [& html]
  (let [html-str (apply str html)]
    (when-not (clojure.string/blank? html-str)
      {:dangerouslySetInnerHTML {:__html html-str}})))
```

And in `com.yakread.app.home`, wrap the analytics div conditionally:
```clojure
(when-not (str/blank? analytics-snippet)
  [:div (biff/unsafe analytics-snippet)])
```

#### Issue: Tailwind CSS version mismatch
**Problem:** System downloads Tailwind v4 which has breaking changes.

**Solution:** Manually download the correct version (v3.2.4):
```bash
curl -L -o bin/tailwindcss https://github.com/tailwindlabs/tailwindcss/releases/download/v3.2.4/tailwindcss-linux-x64
chmod +x bin/tailwindcss
```

#### Issue: RocksDB lock file error
**Problem:** Previous server instance didn't shut down cleanly.

**Solution:** 
```bash
rm -rf storage/biff-index
```

### Configuration

The app uses `config.env` for environment-specific settings. Key variables:

- `DOMAIN`: Your app domain (default: example.com for local dev)
- `ANALYTICS_SNIPPET`: Optional analytics code (leave empty for local dev)
- `S3_*`: S3/Spaces configuration (required for full functionality)
- `RECAPTCHA_*`: ReCaptcha keys (optional for local dev)
- Cookie and JWT secrets (auto-generated)

### Development Workflow

1. Start the server: `clj -M:run dev`
2. Make changes to `.clj` files in `src/`
3. Save files - they'll automatically reload
4. Check server output for reload confirmation
5. Test changes in browser
6. For CSS changes, Tailwind recompiles automatically

### Restarting the Server

If auto-reload isn't working or you need a clean start:

```bash
# Find the Java process
ps aux | grep "java.*clojure.*dev"

# Kill it
kill <PID>

# Restart
clj -M:run dev
```

### Useful Commands

```bash
# Run tests
clj -M:run test

# Generate CSS only
clj -M:run css

# Deploy (requires server setup)
clj -M:run deploy

# Connect to production REPL
clj -M:run prod-repl
```

## Architecture Notes

- **Framework:** Biff (Clojure web framework)
- **Database:** XTDB (document database)
- **Server:** Jetty
- **Routing:** Reitit
- **Templates:** Rum (server-side rendering)
- **CSS:** Tailwind CSS v3.2.4
- **Recommendation Engine:** Apache Spark MLlib (ALS collaborative filtering)

## Common Namespaces

- `com.yakread` - Main entry point
- `com.yakread.app.*` - HTTP handlers and page definitions
- `com.yakread.model.*` - Pathom resolvers, queries, derived data
- `com.yakread.lib.*` - Shared utilities
- `com.yakread.ui-components.*` - Reusable UI components (Pathom resolvers returning Hiccup)
- `com.yakread.work.*` - Background jobs and scheduled tasks

## Seed Data Generation (Future Enhancement)

For testing page load performance with production-like data volumes:

### Data Volumes in Production
- **User Items**: ~250k records
- **Items**: ~18m records
- **Users**: Varies

### Approach for Seed Data

1. **Create seed data generation script** in `dev/seed_data.clj`:
   ```clojure
   (ns seed-data
     (:require [xtdb.api :as xt]
               [com.yakread :as yakread]))
   
   (defn generate-items [n]
     ;; Generate n item documents
     )
   
   (defn generate-user-items [n]
     ;; Generate n user-item relationships
     )
   ```

2. **Start with smaller data sets** to test:
   - 1k items, 100 user-items
   - 10k items, 1k user-items  
   - 100k items, 10k user-items
   - Scale up as needed

3. **Monitor disk usage** as XTDB stores data on disk:
   ```bash
   watch -n 5 'du -sh storage/'
   ```

4. **Test page load performance**:
   - Home page
   - For You page (recommendation algorithm with real data)
   - Subscriptions page
   - Read Later page
   
5. **Use tools like `time curl` or browser dev tools** to measure:
   - Time to first byte (TTFB)
   - Full page load time
   - Database query performance

### Disk Space Considerations

- Current environment: 20GB free
- XTDB storage: ~88MB initially
- Estimated for 1M items: ~5-10GB (varies with content size)
- Should be feasible for moderate testing volumes

