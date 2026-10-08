// Brücke zwischen tree-sitter (nach WebAssembly übersetzt) und Java (Chicory, siehe SyntaxEngine).
//
// Je Sprache entsteht ein eigenständiges Wasm-Modul aus tree-sitter-Kern, Grammatik und dieser Datei. Statt den Baum
// Knoten für Knoten über die Modulgrenze abzufragen, legt ast_parse ihn in einem Durchlauf flach in einen Puffer:
// AST_FIELDS uint32 je Knoten in Vorordnung (Eltern vor Kindern), Java liest den Puffer am Stück aus dem Speicher.
//
// Übernommen werden benannte Knoten und unbenannte Knoten, deren Typ ein Wort ist (Schlüsselwörter wie public, static,
// async, def) sowie fehlende Tokens der Fehlerkorrektur – sonstige Satzzeichen und Operatoren nur mit keep_all.
// Kinder eines ausgelassenen Knotens hängen am nächsten übernommenen Vorfahren.
//
// Übersetzen: natives/build-tree-sitter-wasm.sh (LANGUAGE_FN = Name der Sprachfunktion, z.B. tree_sitter_java).

#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <tree_sitter/api.h>

#ifndef LANGUAGE_FN
#error "LANGUAGE_FN fehlt (z.B. -DLANGUAGE_FN=tree_sitter_java)"
#endif

#define EXPORT(name) __attribute__((export_name(#name)))

const TSLanguage *LANGUAGE_FN(void);

// Aufbau eines Knotens im Puffer (muss zu SyntaxEngine.java passen)
enum {
  AST_SYMBOL,      // Symbol (Typname über ast_symbol_name)
  AST_FIELD,       // Feld-ID relativ zum Elternknoten, 0 = keins
  AST_FLAGS,       // AST_NAMED | AST_EXTRA | …
  AST_PARENT,      // Index des Elternknotens im Puffer, UINT32_MAX für die Wurzel
  AST_START_BYTE,  // UTF-8-Byte-Offsets
  AST_END_BYTE,
  AST_START_ROW,   // 0-basiert
  AST_START_COL,   // in Bytes
  AST_END_ROW,
  AST_END_COL,
  AST_FIELDS
};

enum {
  AST_NAMED = 1,
  AST_EXTRA = 2,      // Kommentare u.Ä., dürfen überall stehen
  AST_MISSING = 4,    // vom Parser zur Fehlerkorrektur eingefügt
  AST_HAS_ERROR = 8,  // Fehler in diesem Teilbaum
  AST_ERROR = 16,     // Knoten ist selbst ein ERROR-Knoten
};

static const TSLanguage *language;
static TSParser *parser;
static uint8_t *keep;       // je Symbol: unbenannt übernehmen?
static uint32_t *nodes;     // Ergebnis des letzten ast_parse
static uint32_t capacity;   // in Knoten
static uint32_t *stack;     // je Tiefe: Index des übernommenen Vorfahren
static uint32_t stack_capacity;

static const TSLanguage *lang(void) {
  if (!language) {
    language = LANGUAGE_FN();
  }
  return language;
}

// Wort: Buchstabe oder _ am Anfang, dann auch Ziffern und - (non-sealed)
static bool word(const char *s) {
  if (!s || !*s || !((*s >= 'a' && *s <= 'z') || (*s >= 'A' && *s <= 'Z') || *s == '_')) {
    return false;
  }
  for (; *s; s++) {
    char c = *s;
    if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
      return false;
    }
  }
  return true;
}

EXPORT(ast_abi_version) uint32_t ast_abi_version(void) {
  return ts_language_abi_version(lang());
}

EXPORT(ast_symbol_count) uint32_t ast_symbol_count(void) {
  return ts_language_symbol_count(lang());
}

EXPORT(ast_symbol_name) const char *ast_symbol_name(uint32_t symbol) {
  return ts_language_symbol_name(lang(), (TSSymbol) symbol);
}

EXPORT(ast_symbol_type) uint32_t ast_symbol_type(uint32_t symbol) {
  return ts_language_symbol_type(lang(), (TSSymbol) symbol);
}

EXPORT(ast_field_count) uint32_t ast_field_count(void) {
  return ts_language_field_count(lang());
}

EXPORT(ast_field_name) const char *ast_field_name(uint32_t field) {
  return ts_language_field_name_for_id(lang(), (TSFieldId) field);
}

EXPORT(ast_malloc) void *ast_malloc(uint32_t size) {
  return malloc(size);
}

EXPORT(ast_free) void ast_free(void *ptr) {
  free(ptr);
}

static bool ensure(uint32_t count) {
  if (count <= capacity) {
    return true;
  }
  uint32_t next = capacity ? capacity * 2 : 4096;
  while (next < count) {
    next *= 2;
  }
  uint32_t *grown = realloc(nodes, (size_t) next * AST_FIELDS * sizeof(uint32_t));
  if (!grown) {
    return false;
  }
  nodes = grown;
  capacity = next;
  return true;
}

static bool ensure_stack(uint32_t depth) {
  if (depth < stack_capacity) {
    return true;
  }
  uint32_t next = stack_capacity ? stack_capacity * 2 : 256;
  while (next <= depth) {
    next *= 2;
  }
  uint32_t *grown = realloc(stack, (size_t) next * sizeof(uint32_t));
  if (!grown) {
    return false;
  }
  stack = grown;
  stack_capacity = next;
  return true;
}

// Ergebnis von ast_parse: Anzahl Knoten, Fehlerflag, Zeiger auf die Knoten
static uint32_t result[3];

/**
 * Parst UTF-8-Quelltext und legt den Baum in den Puffer. Rückgabe: Zeiger auf {Anzahl Knoten, Syntaxfehler 0/1,
 * Zeiger auf die Knoten} oder 0, wenn der Speicher nicht reicht. Der Puffer gilt bis zum nächsten Aufruf.
 */
EXPORT(ast_parse) uint32_t *ast_parse(const char *source, uint32_t length, uint32_t keep_all) {
  const TSLanguage *l = lang();
  if (!parser) {
    parser = ts_parser_new();
    if (!ts_parser_set_language(parser, l)) {
      return 0;
    }
  }
  if (!keep) {
    uint32_t count = ts_language_symbol_count(l);
    keep = calloc(count, 1);
    if (!keep) {
      return 0;
    }
    for (uint32_t s = 0; s < count; s++) {
      keep[s] = ts_language_symbol_type(l, (TSSymbol) s) == TSSymbolTypeRegular
                || word(ts_language_symbol_name(l, (TSSymbol) s));
    }
  }
  TSTree *tree = ts_parser_parse_string(parser, NULL, source, length);
  if (!tree) {
    return 0;
  }
  TSNode root = ts_tree_root_node(tree);
  TSTreeCursor cursor = ts_tree_cursor_new(root);
  uint32_t count = 0;
  uint32_t depth = 0;
  bool ok = true;
  for (;;) {
    TSNode node = ts_tree_cursor_current_node(&cursor);
    TSSymbol symbol = ts_node_symbol(node);
    bool named = ts_node_is_named(node);
    uint32_t parent = depth == 0 ? UINT32_MAX : stack[depth - 1];
    if (!ensure_stack(depth)) {
      ok = false;
      break;
    }
    // Fehlende Tokens (Fehlerkorrektur) immer übernehmen, auch Satzzeichen – sie zeigen, was im Quelltext fehlt
    if (depth == 0 || named || keep_all || ts_node_is_missing(node)
        || (symbol < ts_language_symbol_count(l) && keep[symbol])) {
      if (!ensure(count + 1)) {
        ok = false;
        break;
      }
      uint32_t *n = nodes + (size_t) count * AST_FIELDS;
      TSPoint start = ts_node_start_point(node);
      TSPoint end = ts_node_end_point(node);
      n[AST_SYMBOL] = symbol;
      n[AST_FIELD] = ts_tree_cursor_current_field_id(&cursor);
      n[AST_FLAGS] = (named ? AST_NAMED : 0) | (ts_node_is_extra(node) ? AST_EXTRA : 0)
                     | (ts_node_is_missing(node) ? AST_MISSING : 0) | (ts_node_has_error(node) ? AST_HAS_ERROR : 0)
                     | (ts_node_is_error(node) ? AST_ERROR : 0);
      n[AST_PARENT] = parent;
      n[AST_START_BYTE] = ts_node_start_byte(node);
      n[AST_END_BYTE] = ts_node_end_byte(node);
      n[AST_START_ROW] = start.row;
      n[AST_START_COL] = start.column;
      n[AST_END_ROW] = end.row;
      n[AST_END_COL] = end.column;
      stack[depth] = count++;
    } else {
      stack[depth] = parent;
    }
    if (ts_tree_cursor_goto_first_child(&cursor)) {
      depth++;
      continue;
    }
    while (!ts_tree_cursor_goto_next_sibling(&cursor)) {
      if (!ts_tree_cursor_goto_parent(&cursor)) {
        goto done;
      }
      depth--;
    }
  }
done:
  result[0] = count;
  result[1] = ts_node_has_error(root);
  result[2] = (uint32_t) (uintptr_t) nodes;
  ts_tree_cursor_delete(&cursor);
  ts_tree_delete(tree);
  if (!ok) {
    return 0;
  }
  return result;
}
