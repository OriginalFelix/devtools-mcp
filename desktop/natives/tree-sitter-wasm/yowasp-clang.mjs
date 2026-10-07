// clang-Ersatz für build-tree-sitter-wasm.sh, wenn kein natives wasi-sdk laufen darf (z.B. Windows mit Smart App
// Control: wasm-ld.exe wird blockiert). YoWASP liefert Clang/LLD selbst als WebAssembly, ausgeführt von Node.
//
// Einmalig: npm install --prefix <ordner> @yowasp/clang   und   YOWASP_CLANG=<ordner>/node_modules/@yowasp/clang
// Aufruf wie clang: node yowasp-clang.mjs <clang-argumente>
//
// YoWASP arbeitet auf einem virtuellen Dateisystem: Quelldateien und -I-Verzeichnisse (nur .c/.h) werden hinein
// gespiegelt, die Ausgabe (-o) wird zurückgeschrieben.
import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const pkg = process.env.YOWASP_CLANG;
if (!pkg) {
    console.error('YOWASP_CLANG auf das Paketverzeichnis von @yowasp/clang setzen');
    process.exit(2);
}
const { commands } = await import(pathToFileURL(resolve(pkg, 'gen/bundle.js')).href);

const tree = {};

function virtual(hostPath) {
    // C:\a\b → h/C/a/b
    return 'h/' + resolve(hostPath).replace(/\\/g, '/').replace(/^\/+/, '').replace(':', '');
}

function put(virtualPath, content) {
    const parts = virtualPath.split('/');
    let dir = tree;
    for (const p of parts.slice(0, -1)) {
        dir = dir[p] ??= {};
    }
    dir[parts.at(-1)] = content;
}

function mountFile(hostPath) {
    put(virtual(hostPath), readFileSync(hostPath));
}

function mountDir(hostDir) {
    for (const name of readdirSync(hostDir)) {
        const p = resolve(hostDir, name);
        if (statSync(p).isDirectory()) {
            mountDir(p);
        } else if (/\.(c|h)$/.test(name)) {
            mountFile(p);
        }
    }
}

function get(virtualPath, files) {
    let node = files;
    for (const p of virtualPath.split('/')) {
        node = node?.[p];
    }
    return node;
}

const args = [];
let output = null;
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '-o') {
        output = argv[++i];
        args.push('-o', 'out.wasm');
    } else if (a.startsWith('-I')) {
        const dir = a.slice(2);
        mountDir(dir);
        args.push('-I' + virtual(dir));
    } else if (!a.startsWith('-') && /\.(c|o)$/.test(a)) {
        mountFile(a);
        args.push(virtual(a));
    } else {
        args.push(a);
    }
}

try {
    const files = await commands.clang(args, tree, { decodeASCII: false });
    if (output) {
        writeFileSync(output, get('out.wasm', files));
    }
} catch (e) {
    if (e?.code !== undefined) {
        process.exit(e.code || 1);
    }
    throw e;
}
