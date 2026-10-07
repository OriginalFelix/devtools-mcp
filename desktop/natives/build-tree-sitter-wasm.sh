#!/bin/sh
# Baut tree-sitter samt Grammatiken nach WebAssembly – je Sprache ein eigenständiges Modul aus Kern, Grammatik und
# Brücke (tree-sitter-wasm/ast.c), abgelegt als Ressource tree-sitter/<sprache>.wasm.
#
# Warum: Der Code-Graph lief früher über native Bibliotheken (FFM/jtreesitter, aus dem Temp-Verzeichnis entpackte
# DLL/.so). Windows blockiert solche unsignierten Bibliotheken (Smart App Control, WDAC, AppLocker). Die Wasm-Module
# führt Chicory aus – reiner Java-Code, zur Laufzeit wird nichts Natives geladen (siehe SyntaxEngine).
#
# Voraussetzung: wasi-sdk (https://github.com/WebAssembly/wasi-sdk/releases, getestet mit 34), Java (JAVA=…), curl, tar.
#   WASI_SDK=/opt/wasi-sdk sh natives/build-tree-sitter-wasm.sh            # alle Sprachen
#   WASI_SDK=/opt/wasi-sdk sh natives/build-tree-sitter-wasm.sh java go    # nur einzelne
# Unter Windows in Git Bash mit dem Windows-Paket von wasi-sdk. Blockiert Windows dessen wasm-ld.exe (Smart App
# Control), übernimmt YoWASP (Clang/LLD als WebAssembly in Node, siehe tree-sitter-wasm/yowasp-clang.mjs):
#   YOWASP_CLANG=<...>/node_modules/@yowasp/clang CC="node natives/tree-sitter-wasm/yowasp-clang.mjs" sh natives/build-tree-sitter-wasm.sh
# SOURCES=<ordner> nimmt bereits geladene Quellen.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/../src/main/resources/tree-sitter"
SOURCES=${SOURCES:-"${TMPDIR:-/tmp}/tree-sitter-wasm-src"}
if [ -z "${CC:-}" ]; then
    : "${WASI_SDK:?WASI_SDK auf das wasi-sdk-Verzeichnis setzen (oder CC, siehe oben)}"
    CC="$WASI_SDK/bin/clang"
    [ -x "$CC" ] || CC="$CC.exe"
fi

CORE_VERSION=0.26.6
# Optimierung, siehe unten
OPT=${OPT:-"-O2 -fno-inline-functions"}

# sprache  repository  tag  unterverzeichnis-mit-src  sprachfunktion
GRAMMARS='
java        tree-sitter/tree-sitter-java              v0.23.5  .           tree_sitter_java
kotlin      tree-sitter-grammars/tree-sitter-kotlin   v1.1.0   .           tree_sitter_kotlin
scala       tree-sitter/tree-sitter-scala             v0.26.2  .           tree_sitter_scala
python      tree-sitter/tree-sitter-python            v0.25.0  .           tree_sitter_python
javascript  tree-sitter/tree-sitter-javascript        v0.25.0  .           tree_sitter_javascript
typescript  tree-sitter/tree-sitter-typescript        v0.23.2  typescript  tree_sitter_typescript
tsx         tree-sitter/tree-sitter-typescript        v0.23.2  tsx         tree_sitter_tsx
go          tree-sitter/tree-sitter-go                v0.25.0  .           tree_sitter_go
rust        tree-sitter/tree-sitter-rust              v0.24.2  .           tree_sitter_rust
c           tree-sitter/tree-sitter-c                 v0.24.2  .           tree_sitter_c
cpp         tree-sitter/tree-sitter-cpp               v0.23.4  .           tree_sitter_cpp
csharp      tree-sitter/tree-sitter-c-sharp           v0.23.5  .           tree_sitter_c_sharp
php         tree-sitter/tree-sitter-php               v0.25.1  php         tree_sitter_php
ruby        tree-sitter/tree-sitter-ruby              v0.23.1  .           tree_sitter_ruby
bash        tree-sitter/tree-sitter-bash              v0.25.1  .           tree_sitter_bash
'

fetch() { # ziel repository tag
    if [ ! -d "$SOURCES/$1" ]; then
        mkdir -p "$SOURCES/$1"
        curl -fsSL "https://github.com/$2/archive/refs/tags/$3.tar.gz" | tar -xz -C "$SOURCES/$1" --strip-components=1
    fi
}

fetch core tree-sitter/tree-sitter "v$CORE_VERSION"
mkdir -p "$OUT"

echo "$GRAMMARS" | while read -r lang repo tag sub fn; do
    [ -n "$lang" ] || continue
    if [ $# -gt 0 ] && ! echo " $* " | grep -q " $lang "; then
        continue
    fi
    dir=$(basename "$repo")
    fetch "$dir" "$repo" "$tag"
    src="$SOURCES/$dir/$sub/src"
    # Lexer in kleine Funktionen zerlegen (JIT, siehe SplitLexer.java)
    ${JAVA:-java} "$HERE/tree-sitter-wasm/SplitLexer.java" "$src/parser.c" "$src/parser.split.c"
    scanner=""
    [ -f "$src/scanner.c" ] && scanner="$src/scanner.c"
    # -O2: Lexer und Scanner laufen in Chicory als Bytecode, die Parse-Tabellen sind Daten. -fno-inline-functions:
    # nur als inline markierte Funktionen einbetten – sonst wächst ts_parser_parse über die 8000 Bytes, ab denen
    # HotSpot eine Methode nicht mehr per JIT übersetzt.
    # Stack 1 MiB: Scanner einiger Grammatiken (z.B. Verschachtelung in Templates) brauchen mehr als die üblichen 64 KiB.
    # Nur die ast_*-Funktionen und _initialize (Reactor) werden exportiert; ungenutzte WASI-Importe entfallen.
    $CC --target=wasm32-wasip1 -mexec-model=reactor $OPT -DNDEBUG -std=c11 \
        -D_POSIX_C_SOURCE=200112L -D_DEFAULT_SOURCE -DLANGUAGE_FN="$fn" \
        -I"$SOURCES/core/lib/include" -I"$SOURCES/core/lib/src" -I"$src" -I"$SOURCES/$dir" \
        -Wno-unused-parameter -Wno-unused-but-set-variable \
        "$SOURCES/core/lib/src/lib.c" "$src/parser.split.c" $scanner "$HERE/tree-sitter-wasm/ast.c" \
        -Wl,-z,stack-size=1048576 -Wl,--strip-all \
        -o "$OUT/$lang.wasm"
    echo "$lang: $(wc -c < "$OUT/$lang.wasm") Bytes ($repo $tag, tree-sitter $CORE_VERSION)"
done
