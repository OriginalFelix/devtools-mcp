#!/bin/sh
# Baut die tree-sitter-Kernbibliothek für Windows x86_64 aus den Original-Quellen (MinGW-w64, Cross-Compile unter
# Linux) und legt sie als Ressource natives/x86_64-windows-tree-sitter.dll ab.
#
# Warum: Die Windows-DLL aus io.github.bonede:tree-sitter exportiert nur ihre JNI-Funktionen (Java_org_treesitter_*),
# nicht die C-API (ts_parser_new …). Die FFM-Bindings jtreesitter finden darin nichts – der Graph-Aufbau scheitert mit
# "Could not initialize class io.github.treesitter.jtreesitter.internal.TreeSitter". Unter Linux/macOS exportieren die
# bonede-Bibliotheken die C-API, dort werden sie weiter verwendet. Die Grammatik-DLL (tree-sitter-java) ist in Ordnung.
#
# Aufruf (Podman/Docker, im Projektverzeichnis):
#   podman run --rm -v "$PWD:/src" docker.io/library/debian:stable-slim sh /src/natives/build-windows-tree-sitter.sh
set -eu
VERSION=0.26.6   # wie io.github.bonede:tree-sitter (build.gradle.kts)
OUT=/src/src/main/resources/natives/x86_64-windows-tree-sitter.dll

apt-get update -qq >/dev/null
apt-get install -y -qq --no-install-recommends gcc-mingw-w64-x86-64 curl ca-certificates >/dev/null

mkdir -p /build && cd /build
curl -fsSL "https://github.com/tree-sitter/tree-sitter/archive/refs/tags/v${VERSION}.tar.gz" | tar -xz
cd "tree-sitter-${VERSION}"

# lib.c bindet alle Quellen ein (Amalgamation). -static-libgcc: keine MinGW-Laufzeit-DLL nötig, nur die C-Laufzeit
# von Windows. Alle nicht-statischen Funktionen werden exportiert (keine dllexport-Attribute im Quelltext).
x86_64-w64-mingw32-gcc -O2 -std=c11 -shared -Ilib/src -Ilib/include -Ilib/src/wasm \
    -D_POSIX_C_SOURCE=200112L -D_DEFAULT_SOURCE \
    lib/src/lib.c -o tree-sitter.dll -static-libgcc -Wl,--export-all-symbols
x86_64-w64-mingw32-strip --strip-unneeded tree-sitter.dll

mkdir -p "$(dirname "$OUT")"
cp tree-sitter.dll "$OUT"
x86_64-w64-mingw32-objdump -p "$OUT" | grep -c ' ts_' | sed 's/^/exportierte ts_-Funktionen: /'
x86_64-w64-mingw32-objdump -p "$OUT" | grep 'DLL Name' | sed 's/^/abhängig von: /'
echo "fertig: $OUT (tree-sitter v${VERSION})"
