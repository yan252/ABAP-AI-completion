/**
 * abap-cli command extension: `oo-xml-create`
 *
 * Creates the OO objects of an abapGit export and pushes their source in one
 * go — the class/interface counterpart of `ddic-xml-create`:
 *
 *   *.clas.xml + *.clas.abap → CLAS/OC → POST /sap/bc/adt/oo/classes
 *   *.intf.xml + *.intf.abap → INTF/OI → POST /sap/bc/adt/oo/interfaces
 *      → client.lock(url)
 *      → PUT url/source/main?lockHandle=…  (the *.abap text, text/plain)
 *      → client.unLock(url, handle)
 *
 * Why this extension exists: the template import used to collect only
 * `*.prog.*`, `*.tabl.xml` and `*.ttyp.xml`, so a class shipped inside the
 * ZIP (e.g. ZCL_TEST_01.clas.abap) was silently dropped and never appeared in
 * SE24. The built-in `abap create CLAS` only POSTs an empty shell which, left
 * without source, cannot be activated.
 *
 * Source file: the `*.abap` next to the `*.xml`. A `*.clas.xml` whose source
 * file is missing is reported as failed — the class would otherwise stay an
 * empty, non-activatable shell.
 *
 * Activation is deliberately NOT done here: a class may reference DDIC types
 * and be referenced by programs, so every object of the import is activated
 * together by `prog-batch-activate`, the last step of the import flow.
 *
 * Usage:
 *   abap oo-xml-create repos/<pkg>/src/*.clas.xml repos/<pkg>/src/*.intf.xml
 *
 * Locking: each object is locked at the same URL it is written to and
 * unlocked in a finally block, so a mid-stage failure cannot strand an
 * editor session.
 */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import * as os from 'node:os';
import * as fxp from 'fast-xml-parser';
import { ADTClient, createSSLConfig } from 'abap-adt-api';

// ---------------------------------------------------------------------------
// 1. Config / credential loading (mirrors ddic-xml-create.mjs)
// ---------------------------------------------------------------------------

const CRED_KEYCHAIN_HOST = 'abap-cli';

async function loadAbapJson() {
    const p = path.join(process.cwd(), '.abap.json');
    const json = JSON.parse(await fs.readFile(p, 'utf8'));
    if (!json.system || !json.package) {
        throw new Error('.abap.json must contain { system, package }');
    }
    return json;
}

async function loadProfile(systemName) {
    const home = process.env.HOME || process.env.USERPROFILE || os.homedir();
    const p = path.join(home, '.abap-cli', 'systems.json');
    let raw;
    try {
        raw = await fs.readFile(p, 'utf8');
    } catch {
        throw new Error(`Cannot read profile at ${p}`);
    }
    const parsed = JSON.parse(raw);
    const all = parsed && typeof parsed === 'object'
        && parsed.systems && typeof parsed.systems === 'object'
        ? parsed.systems : parsed;
    const key = [systemName, systemName.toUpperCase(), systemName.toLowerCase()]
        .find(k => all && all[k]);
    if (!key) {
        throw new Error(`System '${systemName}' not found in ${p}. Known: ${Object.keys(all ?? {}).join(', ')}`);
    }
    const sys = { ...all[key] };
    // SAP Preference-page overrides (set by the Eclipse plugin).
    if (process.env.ABAP_URL) sys.url = process.env.ABAP_URL;
    if (process.env.ABAP_CLIENT) sys.client = process.env.ABAP_CLIENT;
    if (process.env.ABAP_USERNAME) sys.username = process.env.ABAP_USERNAME;
    if (process.env.ABAP_LANGUAGE) sys.language = process.env.ABAP_LANGUAGE;
    if (!sys.url || !sys.client || !sys.username) {
        throw new Error(`Profile '${key}' missing url/client/username`);
    }
    if (!sys.password) sys.password = await resolvePassword([key, systemName]);
    return sys;
}

async function resolvePassword(accounts) {
    if (process.env.ABAP_PASSWORD) return process.env.ABAP_PASSWORD;
    let keytar;
    try {
        keytar = (await import('keytar')).default;
    } catch {
        throw new Error('Neither $ABAP_PASSWORD nor keytar entry available. Set one.');
    }
    for (const acc of accounts) {
        try {
            const pw = await keytar.getPassword(CRED_KEYCHAIN_HOST, acc);
            if (pw) return pw;
        } catch { /* keep trying */ }
    }
    throw new Error(`No keychain entry for ${accounts.join(' / ')} under host '${CRED_KEYCHAIN_HOST}'.`);
}

function insecureFromEnv() {
    if (process.env.ABAP_INSECURE === '1') return true;
    if (process.env.NODE_TLS_REJECT_UNAUTHORIZED === '0') return true;
    return false;
}

function argvValue(argv, flag) {
    for (let i = 0; i < argv.length; i++) {
        const a = argv[i];
        if (a === flag && i + 1 < argv.length) return argv[i + 1];
        if (a.startsWith(flag + '=')) return a.slice(flag.length + 1);
    }
    return undefined;
}

// ---------------------------------------------------------------------------
// 2. Language + XML helpers
// ---------------------------------------------------------------------------

/** SAP internal logon-language code → ISO 639-1 code (see ddic-xml-create.mjs). */
const SAP_LANG_TO_ISO = {
    '1': 'ZH', E: 'EN', D: 'DE', F: 'FR', S: 'ES', J: 'JA', K: 'KO',
    R: 'RU', T: 'TH', B: 'PT', I: 'IT', N: 'NL', P: 'PL', A: 'AR',
    H: 'HE', V: 'SV', U: 'FI', O: 'NO', L: 'DA', C: 'CS', M: 'HU',
    G: 'EL', Q: 'TR', Z: 'SK', Y: 'SL', X: 'BG', '0': 'RO', '7': 'HR',
    '4': 'SR', '6': 'ET', '8': 'LV', '9': 'LT', W: 'ZF',
};

function mapSapLanguage(sapCode, fallback = 'EN') {
    if (sapCode === undefined || sapCode === null) return fallback;
    const c = String(sapCode).trim().toUpperCase();
    if (!c) return fallback;
    if (c.length === 2 && /^[A-Z]{2}$/.test(c)) return c;
    return SAP_LANG_TO_ISO[c] || fallback;
}

/** `<VSEOCLASS>`-style leaf → trimmed string ('' when absent). */
function str(v) {
    if (v === undefined || v === null) return '';
    if (typeof v === 'object') return '';       // plain-text leaf expected
    return String(v).trim();
}

const XML_PARSER = new fxp.XMLParser({
    ignoreAttributes: true,
    parseTagValue: false,
    trimValues: true,
});

/** `<abapGit><asx:abap><asx:values>` — tolerates a missing abapGit wrapper. */
function valuesRootOf(xml) {
    const parsed = XML_PARSER.parse(xml);
    return parsed?.abapGit?.['asx:abap']?.['asx:values']
        ?? parsed?.['asx:abap']?.['asx:values'];
}

// ---------------------------------------------------------------------------
// 3. File extension → OO object type
// ---------------------------------------------------------------------------

/**
 * abapGit file prefix → ADT object type. Both a `*.xml` (metadata) and a
 * `*.abap` (source) file may be passed in; they resolve to the same object and
 * are deduplicated on the object URL.
 */
const OO_TYPES = {
    clas: {
        objtype: 'CLAS/OC',
        coll: 'oo/classes',
        node: 'VSEOCLASS',
        label: 'Class (类)',
    },
    intf: {
        objtype: 'INTF/OI',
        coll: 'oo/interfaces',
        node: 'VSEOINTERF',
        label: 'Interface (接口)',
    },
};

/** Matches `*.clas.abap` / `*.clas.xml` (also `*.intf.*`) and captures the type. */
const OO_FILE_RE = /\.(clas|intf)\.(xml|abap)$/i;

/** `zcl_test_01.clas.xml` → `{ typeKey: 'clas', base: '/…/zcl_test_01' }`. */
function splitOoFile(file) {
    const m = path.basename(file).match(OO_FILE_RE);
    if (!m) return null;
    const typeKey = m[1].toLowerCase();
    const base = file.replace(new RegExp(`\\.${m[1]}\\.(xml|abap)$`, 'i'), '');
    return { typeKey, base };
}

/** Parses a `*.clas.xml` / `*.intf.xml` for name, description and language. */
async function parseOoXml(file, typeKey) {
    const t = OO_TYPES[typeKey];
    const xml = await fs.readFile(file, 'utf8');
    const node = valuesRootOf(xml)?.[t.node];
    const objectName = str(node?.CLSNAME).toUpperCase();
    if (!objectName) {
        throw new Error(`No <${t.node}><CLSNAME> in ${file}`);
    }
    return {
        file,
        objectName,
        description: str(node?.DESCRIPT) || objectName,
        language: mapSapLanguage(str(node?.LANGU)),
    };
}

// ---------------------------------------------------------------------------
// 4. Shell + source push
// ---------------------------------------------------------------------------

/** `?lockHandle=…[&corrNr=…]` */
function lockQuery(lockHandle, transport) {
    let q = '?lockHandle=' + encodeURIComponent(lockHandle);
    if (transport) q += '&corrNr=' + encodeURIComponent(transport);
    return q;
}

/**
 * GET the object URL. The question after a creation POST is not "what did SAP
 * answer" but "is the object there now" — so the result is reported as
 * `{ exists, error }` instead of throwing, and the error is kept for the
 * caller's failure message.
 */
async function objectExists(client, url) {
    try {
        await client.httpClient.request(url, { method: 'GET', headers: { Accept: 'application/*' } });
        return { exists: true, error: null };
    } catch (error) {
        return { exists: false, error };
    }
}

/**
 * The transport request this object has to be recorded in.
 *
 * Normally the caller hands it in: the Eclipse import creates one request for
 * the whole run and passes it as `ABAP_TRANSPORT`. Without it SAP generates a
 * fresh request *per object* and then trips over its own lock — the create POST
 * answers HTTP 500 and the source PUT answers 409 (`CTS_WBO_API 019`), both
 * naming a request the client never saw. The CTS transport check reports the
 * request a URI belongs to, which is exactly what is missing here.
 *
 * Of the three candidates the CTS check returns, `LOCKS.HEADER` is the
 * transport *request* and `LOCKS.TASKS[0]` merely one of its tasks. SAP wants
 * the request: handing it the task number answers
 * `CTS_WBO_API 020 — cannot be edited under the request <task>, since it is
 * already locked in the request <request>`. So HEADER is the first choice, and
 * only when the object is still unknown does the candidate list apply.
 */
async function resolveTransport(client, url, packageName) {
    const info = await client.transportInfo(url, packageName, 'I');
    return info?.LOCKS?.HEADER?.TRKORR ?? info?.TRANSPORTS?.[0]?.TRKORR
        ?? info?.LOCKS?.TASKS?.[0]?.TRKORR ?? null;
}

/**
 * HTTP error → one line with status, message and SAP's response body.
 * A bare "status code 500" says nothing; the body carries the reason.
 *
 * abap-adt-api raises three shapes and only one of them is self-explaining:
 *  - `AdtErrorException` — `err`/`type`/`properties`/`localizedMessage` are
 *    already parsed from SAP's `<exc:exception>`;
 *  - `AdtHttpException`  — SAP's body still sits unparsed in
 *    `parent.response.body` (raised when the body is not an ADT exception,
 *    e.g. an HTML error page), so it must be reached through the wrapper;
 *  - anything else       — plain `message`.
 */
function failureDetail(e) {
    const status = e?.err ?? e?.status ?? e?.parent?.status
        ?? e?.response?.status ?? e?.statusCode;
    let body = e?.parent?.response?.body ?? e?.response?.body
        ?? e?.response?.data ?? e?.body;
    if (body !== undefined && typeof body !== 'string') {
        try { body = JSON.stringify(body); } catch { body = String(body); }
    }
    const raw = typeof body === 'string'
        ? body.replace(/\s+/g, ' ').trim().slice(0, 400) : '';
    const props = e?.properties && Object.keys(e.properties).length
        ? JSON.stringify(e.properties) : '';
    return [
        status ? `HTTP ${status}` : null,
        e?.type || null,
        e?.message || null,
        e?.localizedMessage || null,
        props || null,
        raw || null,
    ].filter(Boolean).join(' | ');
}

/** Both files of one object (`*.xml` + `*.abap`) resolve to the same URL. */
async function createOne(client, ctx, typeKey, parsed) {
    const t = OO_TYPES[typeKey];
    const url = `/sap/bc/adt/${t.coll}/${parsed.objectName.toLowerCase()}`;
    const stages = [];
    const log = s => stages.push(s);
    const failed = error => ({
        object: parsed.objectName, kind: t.objtype, action: 'failed', stages, error,
    });
    log(`parsed: NAME=${parsed.objectName} ${t.label} lang=${parsed.language}`);

    // 1. Source. Without it the shell could never be activated, so a missing
    //    `*.abap` is a hard failure rather than a silent skip.
    const srcFile = `${parsed.file.replace(OO_FILE_RE, `.${typeKey}.abap`)}`;
    let source;
    try {
        source = await fs.readFile(srcFile, 'utf8');
    } catch {
        return failed(`no source file next to ${path.basename(parsed.file)} `
            + `(expected ${path.basename(srcFile)})`);
    }
    // Strip BOM if present (most abapGit exports are UTF-8 with BOM).
    const sourceBody = source.charCodeAt(0) === 0xFEFF ? source.slice(1) : source;

    // 2. Transport. Asked for once before creating and, when the caller did not
    //    supply one, again after a failed create: SAP silently locks a new
    //    object in a request it has just generated, and only then is that
    //    request visible in the transport check.
    let transport = ctx.transport;
    const pickTransport = async when => {
        if (transport) return;
        try {
            transport = await resolveTransport(client, url, ctx.packageName);
            if (transport) log(`transp : no transport given — using ${transport} ${when}`);
        } catch (e) {
            log(`transp : no transport given and the CTS check failed ${when}`
                + ` — ${failureDetail(e)}`);
        }
    };
    await pickTransport('(from the CTS check)');

    // 3. Shell — skipped when the object already exists (re-import), so that a
    //    second run only refreshes the source instead of failing on a duplicate.
    const before = await objectExists(client, url);
    if (before.exists) {
        log('create : object already exists — refreshing source only');
    } else {
        try {
            // Delegated to abap-adt-api: it builds the `class:abapClass` /
            // `intf:abapInterface` shell exactly as SAP expects it (creation
            // path, namespace, root element, `adtcore:packageRef`).
            await client.createObject({
                objtype: t.objtype,
                name: parsed.objectName,
                parentName: ctx.packageName,
                parentPath: `/sap/bc/adt/packages/${ctx.packageName.toLowerCase()}`,
                description: parsed.description,
                responsible: ctx.responsible,
                language: parsed.language,
                transport,
            });
            log(`create : POST /sap/bc/adt/${t.coll} OK`);
        } catch (e) {
            // Without a transport SAP generates a request for the object and
            // then collides with the lock it just set itself: the AS error page
            // reads `对象 LIMU CPUB <name> 已在用户 … 的请求 … 中锁定`. The object
            // *is* created, so the POST is only a failure when the object is
            // really absent afterwards. (With an explicit transport the POST
            // succeeds outright — see resolveTransport.)
            //
            // The error page also tears the stateful session down: any further
            // request on it answers "400 Session Timed Out". Re-logon first,
            // otherwise the existence check — and the lock below — would fail
            // for a reason that has nothing to do with the object.
            try { await client.login(); } catch { /* session may already be usable */ }
            const after = await objectExists(client, url);
            if (after.exists) {
                log(`create : POST /sap/bc/adt/${t.coll} reported an error but the `
                    + `object was created — continuing [${failureDetail(e)}]`);
                await pickTransport('(reported by SAP after the create)');
            } else {
                return failed(`POST /sap/bc/adt/${t.coll} failed and the object is `
                    + `not there — ${failureDetail(e)}`
                    + (after.error ? ` | existence check: ${failureDetail(after.error)}` : ''));
            }
        }
    }

    // 4. Lock + PUT source. The lock answer carries the request the object is
    //    actually recorded in (`CORRNR`) — SAP's own statement, so it beats
    //    every guess made from the CTS check.
    let lockHandle;
    try {
        const lockResult = await client.lock(url);
        lockHandle = lockResult.LOCK_HANDLE;
        const lockTransport = str(lockResult.CORRNR);
        log(`lock   : OK handle=${String(lockHandle).slice(0, 16)}…`
            + (lockTransport ? ` corrNr=${lockTransport}` : ''));
        if (lockTransport && lockTransport !== transport) {
            log(`transp : object is recorded in ${lockTransport}`
                + (transport ? ` — overriding the guessed ${transport}` : ''));
            transport = lockTransport;
        }
    } catch (e) {
        return failed(`lock failed — ${failureDetail(e)}`);
    }
    try {
        // OO source endpoints accept exactly `text/plain` (no charset parameter):
        // `text/plain; charset=UTF-8` is refused with
        // "The message content is not acceptable. Accepted content types: text/plain".
        await client.httpClient.request(url + '/source/main' + lockQuery(lockHandle, transport), {
            method: 'PUT',
            body: sourceBody,
            headers: { 'Content-Type': 'text/plain', Accept: 'text/plain' },
        });
        log(`source : PUT OK (${sourceBody.length} bytes)`);
        log('source : inactive on purpose — activated later by `prog-batch-activate`');
    } catch (e) {
        return failed(`source PUT failed — ${failureDetail(e)}`);
    } finally {
        try { await client.unLock(url, lockHandle); } catch { /* swallow */ }
    }

    log(`done   : ${parsed.objectName} written to /sap/bc/adt/${t.coll}`);
    return {
        object: parsed.objectName, kind: t.objtype,
        action: before.exists ? 'updated' : 'created',
        url, stages,
    };
}

// ---------------------------------------------------------------------------
// 5. Command action
// ---------------------------------------------------------------------------

async function runAction(ctx, opts, args) {
    const files = (args.length === 1 && Array.isArray(args[0]) ? args[0] : args)
        .filter(f => typeof f === 'string' && f.length > 0);
    if (files.length === 0) {
        console.error('Usage: abap oo-xml-create <file.clas.xml|file.clas.abap|file.intf.xml|…> [...]');
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

    const shared = {
        packageName: abapJson.package,
        transport,
        responsible: String(creds.username ?? '').toUpperCase(),
    };

    // Dedupe: `*.clas.xml` and `*.clas.abap` name the same object.
    const results = [];
    const seen = new Set();
    for (const f of files) {
        const split = splitOoFile(f);
        if (!split) {
            results.push({
                file: f, action: 'failed',
                error: 'unsupported file (expected *.clas.xml|*.clas.abap|*.intf.xml|*.intf.abap)',
            });
            continue;
        }
        const key = `${split.typeKey}:${split.base.toLowerCase()}`;
        if (seen.has(key)) continue;
        seen.add(key);

        try {
            const xmlFile = split.base + `.${split.typeKey}.xml`;
            results.push(await createOne(client, shared, split.typeKey,
                await parseOoXml(xmlFile, split.typeKey)));
        } catch (err) {
            results.push({
                file: f,
                action: 'failed',
                error: err?.message ?? String(err),
                ...(process.env.ABAP_DEBUG ? { stack: err?.stack } : {}),
            });
        }
    }

    try { await client.logout(); } catch { /* best effort */ }

    if (prettyJson) {
        process.stdout.write(JSON.stringify(results, null, 2) + '\n');
        // Surface failures as a non-zero exit code: the Java caller only
        // inspects exitCode and would otherwise report a false "Overall: OK".
        if (results.some(r => r.action === 'failed')) process.exitCode = 1;
    } else {
        for (const r of results) {
            if (r.action === 'failed') {
                console.error(`✗ ${r.file ?? r.object}${r.error ? `: ${r.error}` : ''}`);
                if (r.stages) for (const s of r.stages) console.error(`    ${s}`);
                process.exitCode = 1;
                continue;
            }
            console.log(`✓ ${r.action}: ${r.object} (${r.kind})`);
            for (const s of r.stages) console.log(`    ${s}`);
        }
    }
}

export default /** @type {import('abap-cli/dist/src/abap_cli/extensions/types.js').CommandExtension} */ ({
    type: 'command',
    name: 'oo-xml-create',
    description:
        'Create classes (*.clas.xml + *.clas.abap → CLAS/OC) and interfaces ' +
        '(*.intf.xml + *.intf.abap → INTF/OI) from an abapGit export and push ' +
        'their source via ADT. Objects are left inactive; run ' +
        '`prog-batch-activate` last to activate every object of the import ' +
        'together.',
    command: 'oo-xml-create [files...]',
    async action(ctx, opts, ...args) {
        return runAction(ctx, opts, args);
    },
});
