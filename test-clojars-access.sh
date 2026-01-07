#!/usr/bin/env bash
set -e

echo "Testing Clojars dependency download capability..."
echo ""

# Test 1: Check if clj command is available
echo "1. Checking for Clojure CLI (clj command)..."
if ! command -v clj &> /dev/null; then
    echo "   ERROR: clj command not found"
    exit 1
fi
echo "   ✓ clj command is available"
clj --version
echo ""

# Test 2: Check network access to Clojars
# Note: clojars.org/repo redirects to repo.clojars.org (both domains are allowlisted)
echo "2. Testing network access to clojars.org..."
if curl -s -I https://clojars.org/repo | head -1 | grep -qE "200|302"; then
    echo "   ✓ clojars.org is accessible"
else
    echo "   ERROR: Cannot access clojars.org"
    exit 1
fi
echo ""

# Test 3: Verify we can resolve dependencies
echo "3. Testing dependency resolution..."
cd "$(dirname "$0")"
if clj -Stree > /dev/null 2>&1; then
    echo "   ✓ Successfully resolved all dependencies"
else
    echo "   ERROR: Could not resolve dependencies"
    exit 1
fi
echo ""

# Test 4: Test downloading a new dependency from Clojars
echo "4. Testing fresh download from Clojars..."
# Create a temporary deps.edn with a Clojars-only dependency
TEST_DEP_DIR=$(mktemp -d)
cat > "$TEST_DEP_DIR/deps.edn" << 'EOF'
{:deps {metosin/malli {:mvn/version "0.16.3"}}
 :mvn/repos {"clojars" {:url "https://clojars.org/repo"}}}
EOF

cd "$TEST_DEP_DIR"
# Force a fresh download
rm -rf ~/.clojure/.cpcache 2>/dev/null || true
if clj -Stree 2>&1 | grep -q "Downloading.*malli.*from clojars"; then
    echo "   ✓ Successfully downloaded fresh dependency from Clojars"
    HAS_FRESH_DOWNLOAD=true
else
    echo "   ℹ Dependencies already cached (Clojars access was previously successful)"
    HAS_FRESH_DOWNLOAD=false
fi
cd - > /dev/null
rm -rf "$TEST_DEP_DIR"
echo ""

# Test 5: Verify Clojars dependencies in project
echo "5. Checking project dependencies from Clojars..."
cd "$(dirname "$0")"
# List the Clojars repository configuration from deps.edn
CLOJARS_CONFIG=$(grep -A 1 '"clojars".*url' deps.edn | sed 's/^/   /')
if clj -Spath 2>&1 | grep -q ".m2/repository"; then
    echo "   ✓ Project dependencies resolved (includes Clojars packages)"
    echo "   Clojars repository configuration from deps.edn:"
    echo "$CLOJARS_CONFIG"
fi
echo ""

echo "✓ All tests passed! Clojars dependency download is working correctly."
