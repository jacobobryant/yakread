# GitHub Agent Configuration

This directory contains configuration files for GitHub agents (including GitHub Copilot agents) that work on this repository.

## Overview

The configuration ensures that agents have access to a properly provisioned container environment with all necessary dependencies installed.

## Files

### `.github/workflows/agent-setup.yml`
Basic GitHub Actions workflow that sets up the agent environment with:
- Java 17 (Temurin distribution)
- Clojure CLI tools (latest version)
- All dependencies from `deps.edn`

### `.github/workflows/copilot-agent.yml`
Comprehensive workflow for GitHub Copilot agents with:
- Full environment verification
- Dependency caching for faster setup
- Repository structure validation
- Detailed logging and diagnostics

### `.devcontainer/devcontainer.json`
Development container configuration for VS Code and GitHub Codespaces:
- Pre-configured Clojure development environment
- Automatic dependency installation
- Calva extension for Clojure development
- Persistent Maven and Git library caches

## Dependencies Installed

The container is provisioned with:

1. **Java 17** - Required for running Clojure applications
2. **Clojure CLI** - Command-line tools for running Clojure code
3. **Project Dependencies** - All dependencies from `deps.edn` including:
   - Biff web framework
   - Apache Spark (for ML features)
   - Various Clojure libraries for web development, data processing, etc.

## Usage

### For GitHub Actions Workflows

The workflows are automatically triggered on:
- Pull requests (opened, synchronized, reopened)
- Manual dispatch via GitHub Actions UI
- Pushes to main/master branch (copilot-agent.yml only)

### For GitHub Copilot Agents

When a GitHub Copilot agent works on this repository, it will use the workflow configurations to provision its environment. The agent will have access to:
- Clojure CLI for running commands like `clj -M:run dev`
- All project dependencies pre-cached
- Proper Java version (17)

### For Development Containers

To use the development container configuration:
1. Open the repository in VS Code
2. Install the "Dev Containers" extension
3. Click "Reopen in Container" when prompted
4. The container will be built and dependencies will be installed automatically

## Caching

Dependencies are cached to speed up subsequent runs:
- Maven dependencies: `~/.m2/repository`
- Git dependencies: `~/.gitlibs`
- Clojure cache: `~/.clojure`

Cache keys are based on the hash of `deps.edn`, so the cache is automatically invalidated when dependencies change.

## Verification

After setup, the environment can be verified with:
```bash
java -version           # Should show Java 17
clojure -version        # Should show Clojure CLI version
clojure -P -M:run       # Should download all dependencies
```

## Customization

To modify the environment:
1. Update the Java version in the workflow files by changing `JAVA_VERSION`
2. Modify dependency installation commands in `postCreateCommand`
3. Add additional steps to the workflow files as needed

## Troubleshooting

If dependencies fail to download:
1. Check that `deps.edn` is valid EDN syntax
2. Verify network connectivity to Maven Central and Clojars
3. Check GitHub Actions logs for specific error messages
4. Clear the cache by updating the cache key in the workflow files
