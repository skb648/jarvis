#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════
# build.sh — Cross-compile Rust for Android JNI (Robust Edition)
# ══════════════════════════════════════════════════════════════════
# Compiles libjarvis_rust.so for Android architectures and copies
# them into the jniLibs directory so Gradle naturally packages
# them into the APK.
#
# COMPATIBILITY:
#   - Linux (Ubuntu/Debian/Fedora)
#   - Termux (Android)
#   - Google Colab
#   - macOS (with minor path adjustments)
#
# PREREQUISITES:
#   1. Rust toolchain installed: https://rustup.rs
#   2. cargo-ndk installed: cargo install cargo-ndk
#   3. Android NDK (version 25+) installed
#   4. Cross-compilation targets added:
#      rustup target add aarch64-linux-android armv7-linux-androideabi \
#                        x86_64-linux-android i686-linux-android
#
# USAGE (from project root):
#   ./rust/build.sh              # Build arm64-v8a only (default)
#   ./rust/build.sh all          # Build all ABIs
#   ./rust/build.sh arm64        # Build only arm64-v8a
#   ./rust/build.sh release      # Build arm64-v8a in release mode
#   ./rust/build.sh all release  # Build all ABIs in release mode
# ══════════════════════════════════════════════════════════════════

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JNILIBS_DIR="$PROJECT_ROOT/app/src/main/jniLibs"
RUST_DIR="$SCRIPT_DIR"

# ANSI colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

log_info()  { echo -e "${CYAN}[JARVIS]${NC} $1"; }
log_ok()    { echo -e "${GREEN}[✓]${NC} $1"; }
log_warn()  { echo -e "${YELLOW}[!]${NC} $1"; }
log_err()   { echo -e "${RED}[✗]${NC} $1"; }
log_step()  { echo -e "${BOLD}${CYAN}──▶${NC} $1"; }

# ─── Configuration ──────────────────────────────────────────────
API_LEVEL=26   # minSdk — must match app/build.gradle.kts

# ABI mapping: Android ABI → Rust target
declare -A ABI_MAP=(
    ["arm64-v8a"]="aarch64-linux-android"
    ["armeabi-v7a"]="armv7-linux-androideabi"
    ["x86_64"]="x86_64-linux-android"
    ["x86"]="i686-linux-android"
)

# ─── Detect Termux Environment ──────────────────────────────────
is_termux() {
    [ -n "${TERMUX_VERSION:-}" ] || [ -d "/data/data/com.termux" ] || \
        [ "$(uname -o 2>/dev/null)" = "Android" ]
}

# ─── Auto-Detect NDK Path ───────────────────────────────────────
# Searches multiple locations for the Android NDK.
# Sets ANDROID_NDK_HOME and NDK_HOME environment variables.
detect_ndk() {
    # Already set by user
    if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "${ANDROID_NDK_HOME}" ]; then
        export NDK_HOME="$ANDROID_NDK_HOME"
        log_ok "Using user-specified ANDROID_NDK_HOME: $ANDROID_NDK_HOME"
        return 0
    fi

    if [ -n "${NDK_HOME:-}" ] && [ -d "${NDK_HOME}" ]; then
        export ANDROID_NDK_HOME="$NDK_HOME"
        log_ok "Using user-specified NDK_HOME: $NDK_HOME"
        return 0
    fi

    log_step "Auto-detecting Android NDK..."

    # Candidate paths to search
    local candidates=()

    # 1. ANDROID_HOME/ANDROID_SDK_ROOT based
    local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -n "$sdk_root" ] && [ -d "$sdk_root" ]; then
        # Find the latest NDK version
        for ndk_dir in "${sdk_root}/ndk/"*; do
            [ -d "$ndk_dir" ] && candidates+=("$ndk_dir")
        done
    fi

    # 2. Common Linux paths
    candidates+=(
        "$HOME/Android/Sdk/ndk/28.0.13004108"
        "$HOME/Android/Sdk/ndk/27.2.12479018"
        "$HOME/Android/Sdk/ndk/26.3.11579264"
        "$HOME/Android/Sdk/ndk/25.2.9519653"
        "$HOME/android-sdk/ndk/28.0.13004108"
        "$HOME/android-sdk/ndk/27.2.12479018"
        "$HOME/android-sdk/ndk/26.3.11579264"
        "/opt/android-sdk/ndk/28.0.13004108"
        "/opt/android-sdk/ndk/27.2.12479018"
    )

    # 3. Termux paths
    if is_termux; then
        candidates+=(
            "${PREFIX:-/data/data/com.termux/files/usr}/share/android-ndk"
            "$HOME/android-ndk"
            "/data/data/com.termux/files/home/android-ndk"
        )
    fi

    # 4. Colab paths
    candidates+=(
        "/content/android-ndk"
        "/opt/android-ndk"
    )

    # 5. macOS paths
    candidates+=(
        "$HOME/Library/Android/sdk/ndk/28.0.13004108"
        "$HOME/Library/Android/sdk/ndk/27.2.12479018"
    )

    # Search ANDROID_HOME ndk directory with wildcard
    if [ -n "$sdk_root" ] && [ -d "$sdk_root/ndk" ]; then
        for ndk_dir in "${sdk_root}/ndk/"*; do
            [ -d "$ndk_dir" ] && candidates+=("$ndk_dir")
        done
    fi

    # Try each candidate
    for candidate in "${candidates[@]}"; do
        if [ -d "$candidate" ] && [ -f "$candidate/ndk-build" -o -f "$candidate/source.properties" ]; then
            export ANDROID_NDK_HOME="$candidate"
            export NDK_HOME="$candidate"
            log_ok "Auto-detected NDK: $candidate"
            return 0
        fi
    done

    # 6. Try to find via ndk-bundle symlink
    if [ -n "$sdk_root" ] && [ -d "${sdk_root}/ndk-bundle" ]; then
        export ANDROID_NDK_HOME="${sdk_root}/ndk-bundle"
        export NDK_HOME="${sdk_root}/ndk-bundle"
        log_ok "Auto-detected NDK (ndk-bundle): ${sdk_root}/ndk-bundle"
        return 0
    fi

    # 7. Last resort: try to find ndk-build in PATH
    local ndk_build_path
    ndk_build_path=$(command -v ndk-build 2>/dev/null || true)
    if [ -n "$ndk_build_path" ]; then
        local detected_dir
        detected_dir="$(cd "$(dirname "$ndk_build_path")" && pwd)"
        export ANDROID_NDK_HOME="$detected_dir"
        export NDK_HOME="$detected_dir"
        log_ok "Auto-detected NDK via ndk-build: $detected_dir"
        return 0
    fi

    log_err "Android NDK not found!"
    echo ""
    echo "Please set ANDROID_NDK_HOME to your NDK installation path."
    echo "Examples:"
    echo "  export ANDROID_NDK_HOME=\$HOME/Android/Sdk/ndk/28.0.13004108"
    echo "  export ANDROID_NDK_HOME=\$HOME/android-sdk/ndk/27.2.12479018"
    echo ""
    echo "Install NDK via sdkmanager:"
    echo "  sdkmanager \"ndk;28.0.13004108\""
    echo ""
    echo "Or download from: https://developer.android.com/ndk/downloads"
    return 1
}

# ─── Check Prerequisites ────────────────────────────────────────
check_prereqs() {
    log_info "Checking build prerequisites..."

    # Check Rust toolchain
    if ! command -v cargo &>/dev/null; then
        log_err "Cargo not found. Install Rust toolchain:"
        echo "  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh"
        exit 1
    fi

    local rust_version
    rust_version=$(cargo --version 2>/dev/null || echo "unknown")
    log_ok "Rust toolchain: $rust_version"

    # Check cargo-ndk
    if ! command -v cargo-ndk &>/dev/null; then
        log_err "cargo-ndk not found. Install it:"
        echo "  cargo install cargo-ndk"
        exit 1
    fi

    local ndk_version
    ndk_version=$(cargo ndk --version 2>/dev/null || echo "unknown")
    log_ok "cargo-ndk: $ndk_version"

    # Detect NDK
    if ! detect_ndk; then
        exit 1
    fi

    # Verify NDK toolchain exists
    if [ ! -f "${NDK_HOME}/source.properties" ]; then
        log_warn "NDK source.properties not found — NDK may be incomplete"
    fi

    log_ok "All prerequisites satisfied"
}

# ─── Install Rust Targets ───────────────────────────────────────
ensure_targets() {
    local targets=("$@")
    log_step "Ensuring Rust cross-compilation targets are installed..."
    for target in "${targets[@]}"; do
        rustup target add "$target" 2>/dev/null || true
        # Verify
        if rustup target list --installed 2>/dev/null | grep -q "$target"; then
            log_ok "Target installed: $target"
        else
            log_warn "Target $target may not be installed — build may fail"
        fi
    done
}

# ─── Build for a single ABI ────────────────────────────────────
build_abi() {
    local abi="$1"
    local target="${ABI_MAP[$abi]}"
    local build_type="${2:-debug}"

    log_step "Building for $abi ($target) [$build_type]..."

    # Create output directory FIRST — cargo-ndk needs it to exist
    local abi_output_dir="$JNILIBS_DIR/$abi"
    mkdir -p "$abi_output_dir"

    # Build with cargo-ndk
    local cargo_args=(
        ndk
        -t "$target"
        -o "$JNILIBS_DIR"
        --manifest-path "$RUST_DIR/Cargo.toml"
        --platform "$API_LEVEL"
    )

    if [ "$build_type" = "release" ]; then
        cargo_args+=(build --release)
    else
        cargo_args+=(build)
    fi

    log_info "Running: cargo ${cargo_args[*]}"

    # Execute cargo-ndk
    if ! cargo "${cargo_args[@]}" 2>&1; then
        log_err "cargo-ndk build failed for $abi"
        return 1
    fi

    # Verify and explicitly copy the .so file
    local so_name="libjarvis_rust.so"

    # cargo-ndk outputs to jniLibs/<abi>/ already, but let's verify
    # and also check the standard Cargo target directory as fallback
    local so_path="$JNILIBS_DIR/$abi/$so_name"

    # If cargo-ndk didn't put it in jniLibs, check Cargo target dir
    if [ ! -f "$so_path" ]; then
        log_warn ".so not found at expected jniLibs path, checking Cargo target dir..."
        local cargo_target_dir
        if [ "$build_type" = "release" ]; then
            cargo_target_dir="$RUST_DIR/target/$target/release"
        else
            cargo_target_dir="$RUST_DIR/target/$target/debug"
        fi

        local cargo_so="$cargo_target_dir/$so_name"
        if [ -f "$cargo_so" ]; then
            cp -v "$cargo_so" "$so_path"
            log_ok "Copied $so_name from Cargo target dir to jniLibs"
        else
            log_err "Built .so not found in Cargo target dir either: $cargo_so"
            return 1
        fi
    fi

    # Final verification
    if [ -f "$so_path" ]; then
        local size
        size=$(du -h "$so_path" | cut -f1)
        local file_type
        file_type=$(file "$so_path" 2>/dev/null | head -1 || echo "unknown")
        log_ok "Built: $so_path ($size)"
        log_info "  Type: $file_type"
    else
        log_err "Failed to produce: $so_path"
        return 1
    fi
}

# ─── Main ───────────────────────────────────────────────────────
main() {
    local filter="${1:-arm64}"
    local build_type="debug"

    # Parse arguments
    local remaining_args=()
    for arg in "$@"; do
        case "$arg" in
            release)
                build_type="release"
                ;;
            debug)
                build_type="debug"
                ;;
            *)
                remaining_args+=("$arg")
                ;;
        esac
    done

    # Override filter if non-build-type arg was provided
    if [ ${#remaining_args[@]} -gt 0 ]; then
        filter="${remaining_args[0]}"
    fi

    echo ""
    log_info "╔══════════════════════════════════════════════════════════╗"
    log_info "║     JARVIS RUST CORE — ANDROID JNI BUILD                ║"
    log_info "║     Build type: $build_type"
    log_info "║     Target: $filter"
    log_info "║     Output: $JNILIBS_DIR"
    log_info "╚══════════════════════════════════════════════════════════╝"
    echo ""

    # Step 1: Check prerequisites
    check_prereqs

    # Step 2: Create jniLibs directory structure
    log_step "Creating jniLibs directory structure..."
    mkdir -p "$JNILIBS_DIR"
    for abi in "${!ABI_MAP[@]}"; do
        mkdir -p "$JNILIBS_DIR/$abi"
    done
    log_ok "jniLibs directories created at: $JNILIBS_DIR"

    # Step 3: Determine which ABIs to build
    local abis_to_build=()
    if [ "$filter" = "all" ]; then
        for abi in "${!ABI_MAP[@]}"; do
            abis_to_build+=("$abi")
        done
    elif [ "$filter" = "arm64" ]; then
        abis_to_build+=("arm64-v8a")
    else
        # Try to match the filter to an ABI name
        for abi in "${!ABI_MAP[@]}"; do
            if [[ "$abi" == *"$filter"* ]]; then
                abis_to_build+=("$abi")
            fi
        done
        if [ ${#abis_to_build[@]} -eq 0 ]; then
            log_err "Unknown ABI filter: $filter"
            echo "Valid options: all, arm64, arm, x86_64, x86"
            echo "  or exact: arm64-v8a, armeabi-v7a, x86_64, x86"
            exit 1
        fi
    fi

    # Step 4: Install required Rust targets
    local targets_needed=()
    for abi in "${abis_to_build[@]}"; do
        targets_needed+=("${ABI_MAP[$abi]}")
    done
    ensure_targets "${targets_needed[@]}"

    # Step 5: Build each ABI
    local failed=0
    local succeeded=0
    for abi in "${abis_to_build[@]}"; do
        echo ""
        log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        if build_abi "$abi" "$build_type"; then
            succeeded=$((succeeded + 1))
        else
            failed=$((failed + 1))
            log_err "Build failed for $abi — continuing with other ABIs..."
        fi
    done

    # Step 6: Summary
    echo ""
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log_info "BUILD SUMMARY"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    if [ $failed -eq 0 ]; then
        log_ok "All $succeeded ABI(s) built successfully! ($build_type mode)"
    else
        log_warn "$succeeded succeeded, $failed failed"
    fi

    echo ""
    log_info "JNI libraries at: $JNILIBS_DIR"
    echo ""

    # List all .so files found
    local so_count=0
    for abi_dir in "$JNILIBS_DIR"/*/; do
        if [ -d "$abi_dir" ]; then
            for so_file in "$abi_dir"*.so; do
                if [ -f "$so_file" ]; then
                    local so_size
                    so_size=$(du -h "$so_file" | cut -f1)
                    local so_abi
                    so_abi=$(basename "$(dirname "$so_file")")
                    log_ok "  $so_abi/$(basename "$so_file") ($so_size)"
                    so_count=$((so_count + 1))
                fi
            done
        fi
    done

    echo ""
    if [ $so_count -gt 0 ]; then
        log_ok "$so_count .so file(s) ready for Gradle packaging"
        log_info "Run './gradlew assembleDebug' to build the APK with native libraries"
    else
        log_err "No .so files were produced!"
        log_info "Troubleshooting:"
        log_info "  1. Check Rust compilation errors above"
        log_info "  2. Ensure NDK version >= 25"
        log_info "  3. Ensure cargo-ndk is up to date: cargo install cargo-ndk"
        log_info "  4. Try building a single ABI: ./rust/build.sh arm64"
        exit 1
    fi

    # Step 7: Verify Gradle can find the .so files
    local gradle_jnilibs_check="$PROJECT_ROOT/app/src/main/jniLibs"
    if [ "$JNILIBS_DIR" = "$gradle_jnilibs_check" ]; then
        log_ok "jniLibs path matches Gradle sourceSets — .so files will be packaged automatically"
    else
        log_warn "jniLibs path does NOT match expected Gradle path!"
        log_warn "  Expected: $gradle_jnilibs_check"
        log_warn "  Actual:   $JNILIBS_DIR"
    fi
}

main "$@"
