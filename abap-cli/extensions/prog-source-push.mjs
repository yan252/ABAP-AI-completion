/**
 * abap-cli command extension: `prog-source-push`
 *
 * Pushes the source code from `*.prog.abap` files to the matching SAP object
 * via ADT, choosing the correct endpoint by SUBC:
 *
 *   SUBC=1 (PROG/P) → PUT /sap/bc/adt/programs/programs/<name>/source/main
 *   SUBC=I (PROG/I) → PUT /sap/bc/adt/programs/includes/<name>/source/main
 *   SUBC=M (PROG/M) → PUT /sap/bc/adt/programs/programs/<name>/source/main
 *   SUBC=S (PROG/S) → PUT /sap/bc/adt/programs/programs/<name>/source/main
 *   SUBC=T (PROG/T) → PUT /sap/bc/adt/programs/programs/<name>/source/main
 *
 * Why this extension exists: abap-cli's built-in `abap push` only knows
 * `/programs/programs/<name>/source` and treats INCLUDE programs (which live
 * under `/programs/includes/`) as missing, so they never get their source
 * uploaded even when the metadata was created correctly.
 *
 * NOTE on the URL: SAP exposes the source via `<object>/source/main` (a
 * `text/plain` handler reachable through an `atom:link` in the object's GET
 * response), not via the flatter `<object>/source`. The latter returns 404
 * on both PROG/P and PROG/I.
 *
 * Usage:
 *   abap prog-source-push repos/<pkg>/src/*.prog.abap
 *
 * This command ONLY pushes source. Every object it touches is left *inactive*
 * on purpose: programs, INCLUDEs, structures, tables and table types reference
 * each other, so activating one object before its dependencies (or before a
 * sibling INCLUDE carries the new source) makes SAP compile it against the old
 * state and report bogus errors such as `Field "S_MATNR" is unknown.`
 * Activation is therefore done once, for every object of the import together,
 * by the `prog-batch-activate` extension — the last step of the import flow.
 *
 * Locking: every PUT acquires a fresh ADT lock against the same URL it
 * writes to (the lock URL must match the PUT URL exactly), and unlocks in a
 * finally block so a mid-stage failure cannot strand an editor session.
 */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import * as os from 'node:os';
import { ADTClient, createSSLConfig } from 'abap-adt-api';
import keytar from 'keytar';

/**
 * Password resolution order (matches abap-cli's own auth/strategies/basic.js):
 *   1. ABAP_PASSWORD env var (so the user can override per-shell)
 *   2. OS keychain entry — service='abap-cli', account=<systemName>
 *      (written by `abap profile add ... --password <pwd>`)
 *
 * Previously this command only read systems.json which deliberately omits the
 * password field — abap-cli normalises it out (see
 * `config/user-config.js → normaliseStoredProfile`, whitelist-only) and only
 * `config/profiles.js → loadConfig()` injects the password back from the
 * keychain. Reproducing that injection here is mandatory, otherwise
 * `new ADTClient(...)` raises "Invalid ADTClient configuration: url, login
 * and password are required" with `password === undefined`.
 */
async function resolvePassword(systemName) {
    if (process.env.ABAP_PASSWORD) return process.env.ABAP_PASSWORD;
    try {
        return await keytar.getPassword('abap-cli', systemName) || undefined;
    } catch (e) {
        // keytar on headless CI / non-GUI Windows may throw; surface as warn
        console.warn(`[prog-source-push] keychain read failed: ${e?.message ?? e}`);
        return undefined;
    }
}

async function loadAbapJson() {
    const p = path.join(process.cwd(), '.abap.json');
    const json = JSON.parse(await fs.readFile(p, 'utf8'));
    if (!json.system) throw new Error('.abap.json missing "system"');
    return json;
}

async function loadProfile(systemName) {
    const home = process.env.HOME || process.env.USERPROFILE || os.homedir();
    const p = path.join(home, '.abap-cli', 'systems.json');
    const raw = await fs.readFile(p, 'utf8');
    const parsed = JSON.parse(raw);
    const all = parsed && parsed.systems && typeof parsed.systems === 'object'
        ? parsed.systems : parsed;
    const key = [systemName, systemName.toUpperCase(), systemName.toLowerCase()]
        .find(k => all && all[k]);
    if (!key) throw new Error(`System '${systemName}' not found in ${p}. Known: ${Object.keys(all ?? {}).join(', ')}`);
    const sys = { ...all[key] };
    // SAP Preference-page overrides (set by the Eclipse plugin).
    if (process.env.ABAP_URL) sys.url = process.env.ABAP_URL;
    if (process.env.ABAP_CLIENT) sys.client = process.env.ABAP_CLIENT;
    if (process.env.ABAP_USERNAME) sys.username = process.env.ABAP_USERNAME;
    if (process.env.ABAP_LANGUAGE) sys.language = process.env.ABAP_LANGUAGE;
    sys.password = await resolvePassword(systemName);
    if (!sys.password) {
        throw new Error(
            `Password for system '${systemName}' not found. ` +
            `Either set ABAP_PASSWORD env var, or run: abap profile set ${systemName} --password <pwd>`
        );
    }
    return sys;
}

function insecureFromEnv() {
    return process.env.ABAP_INSECURE === '1' || process.env.NODE_TLS_REJECT_UNAUTHORIZED === '0';
}

function argvValue(argv, flag) {
    for (let i = 0; i < argv.length; i++) {
        const a = argv[i];
        if (a === flag && i + 1 < argv.length) return argv[i + 1];
        if (a.startsWith(flag + '=')) return a.slice(flag.length + 1);
    }
    return undefined;
}

/** Extracts SUBC from a paired .prog.xml file. Falls back to 1 if no xml. */
async function readSubc(progXmlPath) {
    try {
        const raw = await fs.readFile(progXmlPath, 'utf8');
        const m = raw.match(/<SUBC>\s*([^\s<]+)\s*<\/SUBC>/i);
        if (m) return m[1].toUpperCase().trim();
    } catch { /* ignore */ }
    return '1';
}

// SUBC → endpoint. INCLUDEs live under /programs/includes/, everything else
// (executable, module pool, subroutine pool) under /programs/programs/.
const ENDPOINTS = {
    '1': '/sap/bc/adt/programs/programs/',
    'I': '/sap/bc/adt/programs/includes/',
    'M': '/sap/bc/adt/programs/programs/',
    'S': '/sap/bc/adt/programs/programs/',
    'T': '/sap/bc/adt/programs/programs/',
};

/**
 * PUT the source of one file. `pushed === true` means the object now holds an
 * inactive version; activation happens later in `prog-batch-activate`.
 */
async function pushOne(client, transport, file) {
    const stages = [];
    const log = s => { stages.push(s); };
    const objectName = path.basename(file).replace(/\.prog\.abap$/i, '').toUpperCase();
    const xmlPath = file.replace(/\.prog\.abap$/i, '.prog.xml');
    const subc = await readSubc(xmlPath);
    log(`parsed: NAME=${objectName} SUBC=${subc}`);

    const base = ENDPOINTS[subc];
    if (!base) {
        log(`unsupported SUBC='${subc}'`);
        return { file, object: objectName, subc, action: 'failed', stages, unsupported: [subc] };
    }
    const objectUrl = base + objectName.toLowerCase();
    const result = { file, object: objectName, subc, objectUrl, action: 'failed', stages };

    // 1. Pre-flight existence
    try {
        await client.httpClient.request(objectUrl, { method: 'GET', headers: { Accept: '*/*' } });
    } catch (e) {
        const status = e?.err ?? e?.response?.status ?? e?.status;
        log(`preflight: NOT FOUND (${status}) — run \`abap prog-xml-create <file.prog.xml>\` first`);
        return result;
    }
    log(`preflight: object exists @ ${objectUrl}`);

    // 2. Read source
    const source = await fs.readFile(file, 'utf8');
    // Strip BOM if present (most abapGit exports are UTF-8 with BOM).
    const sourceBody = source.charCodeAt(0) === 0xFEFF ? source.slice(1) : source;

    // 3. Lock
    let lockHandle;
    try {
        const lock = await client.lock(objectUrl);
        lockHandle = lock.LOCK_HANDLE;
        log(`lock   : OK handle=${lockHandle.slice(0, 16)}…`);
    } catch (e) {
        log(`lock   : FAILED — ${e?.message ?? e}`);
        return result;
    }

    // 4. PUT source. SAP exposes source via /source/main (not /source) on
    // both PROG/P and PROG/I — the /source URL returns 404.
    const qs = transport ? { corrNr: transport } : {};
    try {
        await client.httpClient.request(objectUrl + '/source/main?lockHandle=' + encodeURIComponent(lockHandle), {
            method: 'PUT',
            body: sourceBody,
            headers: { 'Content-Type': 'text/plain; charset=UTF-8', Accept: 'application/xml' },
            qs,
        });
        log(`source : PUT OK (${sourceBody.length} bytes)`);
        log(`source : inactive on purpose — activated later by \`prog-batch-activate\``);
        result.pushed = true;
        result.action = 'pushed';
    } catch (e) {
        log(`source : PUT FAILED — ${e?.message?.slice(0, 200) ?? e}`);
        return result;
    } finally {
        try { await client.unLock(objectUrl, lockHandle); }
        catch { /* swallow */ }
    }
    return result;
}

export default /** @type {import('abap-cli/dist/src/abap_cli/extensions/types.js').CommandExtension} */ ({
    type: 'command',
    name: 'prog-source-push',
    description:
        'Push *.prog.abap source code to the matching SAP PROG/INCLUDE ' +
        'object via ADT, choosing /programs/programs vs /programs/includes ' +
        'based on the paired *.prog.xml SUBC. The built-in `abap push` only ' +
        'knows the /programs/programs/ endpoint and silently drops INCLUDE ' +
        'sources. Objects are left inactive; run `prog-batch-activate` last.',
    command: 'prog-source-push [files...]',
    async action(ctx, opts, ...args) {
        const files = (args.length === 1 && Array.isArray(args[0]) ? args[0] : args)
            .filter(f => typeof f === 'string' && f.length > 0);
        if (files.length === 0) {
            console.error('Usage: abap prog-source-push <file1.prog.abap> [file2.prog.abap ...]');
            process.exitCode = 2;
            return;
        }
        const argv = Array.isArray(ctx?.argv) ? ctx.argv : [];
        const prettyJson = opts.prettyJson === true
            || argv.includes('--pretty-json')
            || process.env.ABAP_PRETTY_JSON === '1';
        const transport = opts.transport
            || argvValue(argv, '--transport')
            || process.env.ABAP_TRANSPORT
            || undefined;

        const abapJson = await loadAbapJson();
        const creds = await loadProfile(abapJson.system);
        const insecure = insecureFromEnv() || creds.insecure === true;

        const client = new ADTClient(
            creds.url, creds.username, creds.password, creds.client,
            creds.language || 'EN',
            insecure ? createSSLConfig(true) : undefined,
        );
        client.stateful = 'stateful';
        await client.login();

        const results = [];
        for (const f of files) {
            try {
                results.push(await pushOne(client, transport, f));
            } catch (err) {
                results.push({
                    file: f,
                    action: 'failed',
                    error: err?.message ?? String(err),
                    stages: [],
                    ...(process.env.ABAP_DEBUG ? { stack: err?.stack } : {}),
                });
            }
        }
        for (const r of results) {
            if (!r.pushed && !r.error && r.action === 'failed') {
                r.error = 'source was not pushed (see stages)';
            }
        }

        if (prettyJson) {
            process.stdout.write(JSON.stringify(results, null, 2) + '\n');
            // Surface failures as a non-zero exit code for Java callers that
            // only inspect exitCode (see prog-xml-verify for the reference).
            if (results.some(r => r.action === 'failed')) process.exitCode = 1;
        } else {
            for (const r of results) {
                if (r.action === 'failed') {
                    console.error(`✗ ${r.file ?? r.object}${r.error ? `: ${r.error}` : ''}`);
                    if (r.stages) for (const s of r.stages) console.error(`    ${s}`);
                    process.exitCode = 1;
                    continue;
                }
                console.log(`✓ ${r.action}: ${r.object}`);
                for (const s of r.stages) console.log(`    ${s}`);
            }
        }
    },
});
