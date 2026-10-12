/**
 * abap-cli command extension: `prog-xml-create`
 *
 * Reads one or more `*.prog.xml` files (abapGit-serialized ABAP program
 * metadata) and CREATES the PROG / INCLUDE objects in SAP at the correct
 * program type (SUBC).
 *
 *   <PROGDIR>
 *     <NAME>ZTEMPLATE10_CLS</NAME>
 *     <SUBC>I</SUBC>     ← 1 = executableProgram (REPT)
 *                       ← I = INCLUDE program
 *                       ← M = module pool
 *                       ← F = function group (not used here)
 *                       ← S = subroutine pool
 *                       ← K = class pool (not used here)
 *                       ← T = type pool
 *   </PROGDIR>
 *
 *   <I18N_TPOOL>
 *     <item>
 *       <ID>R</ID>          ← Report title (PROGRAM title in SAP GUI)
 *       <ENTRY>Include ZTEMPLATE10_CLS</ENTRY>
 *     </item>
 *   </I18N_TPOOL>
 *
 * Why this extension exists: abap-cli's built-in `abap create PROG <name>` is
 * hard-coded to typeId `PROG/P` (executableProgram). abapGit deserialises
 * INCLUDE programs (`SUBC=I`) via SAP-side abapGit (which uses the legacy
 * RPY-PROGRAM-INSERT function module), but that path is not exposed via ADT.
 *
 *   ADT exposes TWO distinct endpoints:
 *     POST /sap/bc/adt/programs/programs  → executable / module / subroutine pools
 *     POST /sap/bc/adt/programs/includes  → INCLUDE programs (PROG/I)
 *
 *   Each expects a different root element:
 *     <program:abapProgram xmlns:program="http://www.sap.com/adt/programs/programs" ...>
 *     <include:abapInclude xmlns:include="http://www.sap.com/adt/programs/includes" ...>
 *
 *   `adtcore:type` on the body tells SAP which to instantiate:
 *     PROG/P → executable program  → programs/programs
 *     PROG/I → include             → programs/includes
 *
 * Locking: each object must be locked at its own URL before PUT. SAP rejects
 * "Resource X is not locked" if the lock URL and PUT URL disagree. After
 * POST + activate, the lock is released automatically.
 *
 * Usage:
 *   abap prog-xml-create repos/<pkg>/src/*.prog.xml [--transport <tr>]
 *
 * Re-uses the same config resolution as `prog-xml-push` (`.abap.json` +
 * `~/.abap-cli/systems.json` + keychain password).
 */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import * as os from 'node:os';
import * as fxp from 'fast-xml-parser';
import { ADTClient, createSSLConfig } from 'abap-adt-api';
import { formatTextElements } from 'abap-adt-api/build/api/textelements.js';

// ---------------------------------------------------------------------------
// 1. Config / credential loading (mirrors prog-xml-push.mjs)
// ---------------------------------------------------------------------------

const CRED_KEYCHAIN_HOST = 'abap-cli';
const CRED_KEYCHAIN_SERVICE = 'credentials-for-system';

/**
 * @typedef {{ system: string, package: string, sourceDir?: string }} AbapJson
 */
/** @typedef {{ url: string, client: string, username: string, password: string, language?: string, insecure?: boolean }} SapCreds */

async function loadAbapJson() {
    const p = path.join(process.cwd(), '.abap.json');
    const json = JSON.parse(await fs.readFile(p, 'utf8'));
    if (!json.system || !json.package) {
        throw new Error(`.abap.json must contain { system, package }`);
    }
    return /** @type {AbapJson} */ (json);
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
    const sys = /** @type {SapCreds} */ ({ ...all[key] });
    // SAP Preference-page overrides (set by the Eclipse plugin). Each wins
    // over whatever the profile stores, so the import uses the connection
    // info entered on the plugin's SAP config page — no profile dependency.
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
// 1.5 Language mapping + textpool helpers
// ---------------------------------------------------------------------------
// These are the heart of "the import must use the language the abapGit file
// declares". abapGit stores the SAP *internal* logon-language code in
// `<LANGUAGE>` (the same value that sits in T002-SPRAS / SY-LANGU):
//
//   <LANGUAGE>1</LANGUAGE>   ← 1 = ZH (Chinese)
//   <LANGUAGE>E</LANGUAGE>   ← E = EN (English)
//   <LANGUAGE>D</LANGUAGE>   ← D = DE (German)
//
// ADT, on the other hand, wants the ISO 639 code in the `sap-language` query
// parameter and in `adtcore:language` / `adtcore:masterLanguage`. Passing the
// internal code through unchanged (or hard-coding 'EN') silently writes the
// title / symbol texts into the wrong language table — which is exactly why a
// Chinese program ended up with no visible title in SE38.

/**
 * SAP internal logon-language code → ISO 639-1 code.
 * Covers the languages SAP ships as standard (T002).
 */
const SAP_LANG_TO_ISO = {
    '1': 'ZH', E: 'EN', D: 'DE', F: 'FR', S: 'ES', J: 'JA', K: 'KO',
    R: 'RU', T: 'TH', B: 'PT', I: 'IT', N: 'NL', P: 'PL', A: 'AR',
    H: 'HE', V: 'SV', U: 'FI', O: 'NO', L: 'DA', C: 'CS', M: 'HU',
    G: 'EL', Q: 'TR', Z: 'SK', Y: 'SL', X: 'BG', '0': 'RO', '7': 'HR',
    '4': 'SR', '6': 'ET', '8': 'LV', '9': 'LT', W: 'ZF',
};

/**
 * Normalises an abapGit `<LANGUAGE>` value to an ISO-639 code.
 * Accepts both the SAP internal code ('1', 'E') and an already-ISO code ('ZH').
 *
 * @param {string|number|undefined} sapCode
 * @param {string} [fallback]  returned when the code is empty / unknown
 * @returns {string} e.g. 'ZH'
 */
function mapSapLanguage(sapCode, fallback = 'EN') {
    if (sapCode === undefined || sapCode === null) return fallback;
    const c = String(sapCode).trim().toUpperCase();
    if (!c) return fallback;
    if (c.length === 2 && /^[A-Z]{2}$/.test(c)) return c;
    return SAP_LANG_TO_ISO[c] || fallback;
}

/** Normalises a fast-xml-parser node that may be absent / an object / an array. */
function asArray(v) {
    return Array.isArray(v) ? v : (v === undefined || v === null ? [] : [v]);
}

/** abapGit textpool `<ID>` → ADT text-element category. */
const TPOOL_ID_TO_CATEGORY = { I: 'symbols', S: 'selections', R: 'headings', H: 'headings', C: 'headings' };

/** abapGit heading `<ID>` → the fixed heading key the ADT API accepts. */
const TPOOL_HEADING_KEY = { R: 'LISTHEADER', H: 'COLUMNHEADER_1', C: 'COLUMNHEADER_2' };

/**
 * Walks `<I18N_TPOOL>` — handling both the nested multi-language shape
 * (`<item><LANGUAGE>1</LANGUAGE><TEXTPOOL><item>…`) and the legacy flat shape
 * (`<item><ID>R</ID><ENTRY>…`) — and returns the declared SAP language plus
 * every text element, bucketed into the three ADT categories.
 *
 * @param {any} valuesRoot  `<asx:values>` node from fast-xml-parser
 * @returns {{ language: string, symbols: any[], selections: any[], headings: any[], warnings: string[] }}
 */
function extractTextpool(valuesRoot) {
    const warnings = [];
    const out = { language: '', symbols: [], selections: [], headings: [], warnings };
    for (const node of asArray(valuesRoot?.I18N_TPOOL)) {
        for (const item of asArray(node?.item ?? node)) {
            if (item?.LANGUAGE !== undefined && !out.language) {
                out.language = String(item.LANGUAGE).trim();
            }
            const inner = item?.TEXTPOOL;
            const leaves = inner ? asArray(inner.item) : (item?.ID ? [item] : []);
            for (const leaf of leaves) {
                const id = String(leaf?.ID ?? '').trim().toUpperCase();
                const category = TPOOL_ID_TO_CATEGORY[id];
                if (!category) continue;
                const key = String(leaf?.KEY ?? '').trim();
                const text = String(leaf?.ENTRY ?? '');
                const parsedLen = parseInt(leaf?.LENGTH, 10);
                const maxLength = Number.isFinite(parsedLen) && parsedLen > 0 ? parsedLen : text.length;
                if (category === 'headings') {
                    out.headings.push({ id: TPOOL_HEADING_KEY[id] ?? 'LISTHEADER', text, maxLength });
                } else if (category === 'selections') {
                    if (!key) { warnings.push(`textpool: selection text without a KEY — skipped ("${text}")`); continue; }
                    // abapGit flags a selection text that references the DDIC
                    // (data element) with <SPLIT>D</SPLIT>. Without this flag SAP
                    // leaves "数据字典参考" (DDIC reference) unchecked.
                    const ddicReference = String(leaf?.SPLIT ?? '').trim().toUpperCase() === 'D';
                    out.selections.push({ id: key, text, maxLength, ddicReference });
                } else {
                    if (key.length !== 3) {
                        warnings.push(`textpool: symbol key "${key}" is not exactly 3 characters — skipped`);
                        continue;
                    }
                    out.symbols.push({ id: key, text, maxLength });
                }
            }
        }
    }
    return out;
}

/** Number of text elements in a bucket set. */
function textpoolSize(tp) {
    return (tp?.symbols?.length ?? 0) + (tp?.selections?.length ?? 0) + (tp?.headings?.length ?? 0);
}

// SUBC → human-readable label (PROGDIR-SUBC domain).
// Module scope (NOT inside parseProgXml) so that both parseProgXml and
// createOne can put `category` into their result objects without a
// ReferenceError ("SUBC_LABEL is not defined") on the create path.
const SUBC_LABEL = {
    '1': 'Executable program (可执行程序)',
    'I': 'INCLUDE program (INCLUDE)',
    'M': 'Module pool (模块池)',
    'S': 'Subroutine pool (子程序池)',
    'T': 'Type pool (类型组)',
    'X': 'Dynpro flow logic (流逻辑)',
};

// ---------------------------------------------------------------------------
// 2. XML parsing
// ---------------------------------------------------------------------------

/**
 * Parses a *.prog.xml file and returns the bits we need to create the
 * object:
 *   - objectName : NAME in PROGDIR
 *   - subc       : SUBC in PROGDIR (1/I/M/F/S/K/T/J/X)
 *   - language   : ISO code derived from <I18N_TPOOL><item><LANGUAGE>1</LANGUAGE>
 *                  ('1' → 'ZH'); falls back to the DYNPRO header language
 *   - description: the ID='R' textpool entry (the SE38 title), else objectName
 *   - textpool   : { symbols, selections, headings } — ALL text elements,
 *                  bucketed into the three ADT categories
 *   - rload      : RLOAD attribute (loaded-with-program flag)
 *   - fixpt      : FIXPT attribute (X = fixed-point arithmetic)
 *   - uccheck    : UCCHECK attribute (X = unicode check)
 *   - sourceType : 'abapProgram' | 'abapInclude' | 'modulePool' | 'subroutinePool' | 'typePool'
 *   - typeId     : 'PROG/P' | 'PROG/I' | 'PROG/M' | 'PROG/S' | 'PROG/T'
 *
 * @param {string} file
 * @returns {Promise<{ objectName: string, subc: string, description: string, language: string, textpool: { symbols: any[], selections: any[], headings: any[] }, warnings: string[], rload: string, fixpt: string, uccheck: string, sourceType: string, typeId: string }>}
 */
async function parseProgXml(file) {
    const raw = await fs.readFile(file, 'utf8');
    // abapGit XML uses UTF-8 BOM in many exports; strip it for the parser.
    const xml = raw.charCodeAt(0) === 0xFEFF ? raw.slice(1) : raw;
    const parsed = (new fxp.XMLParser({
        ignoreAttributes: true,
        parseTagValue: false,
        trimValues: true,
    })).parse(xml);

    const valuesRoot = parsed?.abapGit?.['asx:abap']?.['asx:values']
        ?? parsed?.['asx:abap']?.['asx:values'];
    const progdir = valuesRoot?.PROGDIR;
    if (!progdir?.NAME) {
        throw new Error(`No <PROGDIR><NAME> in ${file}`);
    }
    const objectName = String(progdir.NAME).toUpperCase().trim();
    const subc = String(progdir.SUBC ?? '1').toUpperCase().trim();

    // --- Language -----------------------------------------------------------
    // abapGit records the SAP internal logon-language code in
    // <I18N_TPOOL><item><LANGUAGE>1</LANGUAGE>. Everything we push afterwards
    // (title, text symbols, selection texts, the object's adtcore:language)
    // must use this language — otherwise SAP writes it into the logon
    // language's text table and the Chinese title never shows up in SE38.
    //
    // Fallback chain: I18N_TPOOL language → first DYNPRO header language → EN.
    const tpool = extractTextpool(valuesRoot);
    let rawLanguage = tpool.language;
    if (!rawLanguage) {
        const firstDynpro = asArray(valuesRoot?.DYNPROS).flatMap(d => asArray(d?.item))[0];
        rawLanguage = String(firstDynpro?.HEADER?.LANGUAGE ?? '').trim();
    }
    const language = mapSapLanguage(rawLanguage, 'EN');

    // --- Description (report title) ----------------------------------------
    // The SE38 / SE80 title is the textpool entry with ID='R', which maps to
    // the ADT `headings` key LISTHEADER. Fall back to the object name when the
    // file carries no R entry (e.g. INCLUDE programs).
    const listHeader = tpool.headings.find(h => h.id === 'LISTHEADER');
    const description = (listHeader?.text || '').trim() || objectName;

    const rload = String(progdir.RLOAD ?? '1').toUpperCase().trim();
    const fixpt = String(progdir.FIXPT ?? 'X').toUpperCase().trim();
    const uccheck = String(progdir.UCCHECK ?? 'X').toUpperCase().trim();

    // Map SUBC → ADT typeId + source body root name.
    //
    // SUBC values per SAP PROGDIR-SUBC domain:
    //   1 = executable program      → adtcore:type="PROG/P" → programs/programs
    //   I = INCLUDE                 → adtcore:type="PROG/I" → programs/includes
    //   M = module pool             → adtcore:type="PROG/M" → programs/programs
    //   F = function group          → adtcore:type="FUGR/F" (not handled here)
    //   S = subroutine pool         → adtcore:type="PROG/S" → programs/programs
    //   K = class pool              → adtcore:type="CLAS/OC" (not handled here)
    //   T = type pool               → adtcore:type="PROG/T" → programs/programs
    //   J = interface pool          → adtcore:type="INTF/OI" (not handled here)
    //   X = dynpro flow logic (dec) → adtcore:type="PROG/X" (rare; treated like program)
    const SUBC_TO_ENDPOINT = {
        '1': { typeId: 'PROG/P', path: '/sap/bc/adt/programs/programs',
               rootName: 'program:abapProgram',
               rootXmlns: 'xmlns:program="http://www.sap.com/adt/programs/programs"',
               contentType: 'application/vnd.sap.adt.programs.programs.v4+xml',
               sourceType: 'abapProgram' },
        'M': { typeId: 'PROG/M', path: '/sap/bc/adt/programs/programs',
               rootName: 'program:abapProgram',
               rootXmlns: 'xmlns:program="http://www.sap.com/adt/programs/programs"',
               contentType: 'application/vnd.sap.adt.programs.programs.v4+xml',
               sourceType: 'modulePool' },
        'S': { typeId: 'PROG/S', path: '/sap/bc/adt/programs/programs',
               rootName: 'program:abapProgram',
               rootXmlns: 'xmlns:program="http://www.sap.com/adt/programs/programs"',
               contentType: 'application/vnd.sap.adt.programs.programs.v4+xml',
               sourceType: 'subroutinePool' },
        'T': { typeId: 'PROG/T', path: '/sap/bc/adt/programs/programs',
               rootName: 'program:abapProgram',
               rootXmlns: 'xmlns:program="http://www.sap.com/adt/programs/programs"',
               contentType: 'application/vnd.sap.adt.programs.programs.v4+xml',
               sourceType: 'typePool' },
        'I': { typeId: 'PROG/I', path: '/sap/bc/adt/programs/includes',
               rootName: 'include:abapInclude',
               rootXmlns: 'xmlns:include="http://www.sap.com/adt/programs/includes"',
               contentType: 'application/vnd.sap.adt.programs.includes.v1+xml',
               sourceType: 'abapInclude' },
        'X': { typeId: 'PROG/X', path: '/sap/bc/adt/programs/programs',
               rootName: 'program:abapProgram',
               rootXmlns: 'xmlns:program="http://www.sap.com/adt/programs/programs"',
               contentType: 'application/vnd.sap.adt.programs.programs.v4+xml',
               sourceType: 'abapProgram' },
    };
    const cfg = SUBC_TO_ENDPOINT[subc];
    if (!cfg) {
        throw new Error(
            `Unsupported SUBC='${subc}' in ${file}. ` +
            `Supported: ${Object.keys(SUBC_TO_ENDPOINT).join(', ')}`,
        );
    }

    return {
        objectName, subc, category: SUBC_LABEL[subc] ?? subc,
        description, language,
        textpool: { symbols: tpool.symbols, selections: tpool.selections, headings: tpool.headings },
        warnings: tpool.warnings,
        rload, fixpt, uccheck, ...cfg,
    };
}

// ---------------------------------------------------------------------------
// 3. ADT operations: exists? / create / activate
// ---------------------------------------------------------------------------

/** Build the create-request XML body. */
function buildCreateBody({
    objectName, typeId, description, rootName, rootXmlns,
    language = 'EN', packageName, transport,
}) {
    const qs = transport ? ` corrNr="${transport.replace(/"/g, '&quot;')}"` : '';
    // Both body roots (program:abapProgram / include:abapInclude) share the
    // same adtcore / abapsource namespaces and the same child shape.
    // `program:programType` is intentionally OMITTED: the SAP-side ST
    // `SEDI_ADT_PROGRAM` rejects it with
    // "An error occurred when deserializing in the simple transformation
    //  program SEDI_ADT_PROGRAM" — `adtcore:type` alone is sufficient.
    return `<?xml version="1.0" encoding="UTF-8"?>
<${rootName} ${rootXmlns}
  xmlns:abapsource="http://www.sap.com/adt/abapsource"
  xmlns:adtcore="http://www.sap.com/adt/core"
  abapsource:fixPointArithmetic="true"
  abapsource:activeUnicodeCheck="true"
  adtcore:name="${xmlAttr(objectName)}"
  adtcore:type="${xmlAttr(typeId)}"
  adtcore:description="${xmlAttr(description)}"
  adtcore:language="${xmlAttr(language)}"
  adtcore:masterLanguage="${xmlAttr(language)}"${qs}>
  <adtcore:packageRef adtcore:name="${xmlAttr(packageName)}"/>
</${rootName}>`;
}

function xmlAttr(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
        .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/**
 * Returns true if the object already exists in SAP. ADT returns 404 for a
 * non-existent program — we treat that as "not exists"; any other non-2xx is
 * treated as "exists" (a misread should not block creation, but a clear error
 * should). The two URL variants (programs/programs + programs/includes) are
 * checked because SAP only stores an object at one of them depending on SUBC.
 *
 * abap-adt-api exposes the HTTP status on `err.err` (not `err.response.status`).
 * `err.type === 'ExceptionResourceNotFound'` is also a reliable 404 sentinel.
 */
async function objectExists(client, objectName) {
    const urls = [
        '/sap/bc/adt/programs/programs/' + objectName.toLowerCase(),
        '/sap/bc/adt/programs/includes/' + objectName.toLowerCase(),
    ];
    for (const url of urls) {
        try {
            await client.httpClient.request(url, {
                method: 'GET',
                headers: { Accept: '*/*' },
            });
            return { exists: true, url };
        } catch (e) {
            const status = e?.err ?? e?.response?.status ?? e?.status ?? e?.statusCode;
            if (status === 404 || e?.type === 'ExceptionResourceNotFound') continue;
            // Some 5xx / network errors: surface as "uncertain, treat as exists"
            // so we don't accidentally create a duplicate.
            return { exists: true, url, uncertain: true, error: String(e?.message ?? e) };
        }
    }
    return { exists: false };
}

/**
 * Find the correct URL for an existing object (returns both URL + typeId).
 * For programs both `/programs/programs/...` and `/programs/includes/...` are
 * checked, since which one SAP picked depends on SUBC.
 */
async function findObjectUrl(client, objectName) {
    const urls = [
        ['/sap/bc/adt/programs/programs/' + objectName.toLowerCase(), 'PROG/P'],
        ['/sap/bc/adt/programs/includes/' + objectName.toLowerCase(), 'PROG/I'],
    ];
    for (const [url, typeId] of urls) {
        try {
            await client.httpClient.request(url, {
                method: 'GET',
                headers: { Accept: '*/*' },
            });
            return { url, typeId };
        } catch (e) {
            const status = e?.err ?? e?.response?.status ?? e?.status ?? e?.statusCode;
            if (status === 404 || e?.type === 'ExceptionResourceNotFound') continue;
            throw e;
        }
    }
    return null;
}

/**
 * Serialises the `selections` category.
 *
 * We cannot reuse `abap-adt-api`'s `formatTextElements()` here: it writes the
 * DDIC reference as `@DDICReference:<value>`, which SAP rejects with HTTP 406
 * "Cannot parse the source code, due to invalid syntax". The format SAP
 * actually expects — and returns on GET — is a bare `@DDICReference` line
 * (no colon, no value) placed immediately before the selection entry:
 *
 *     @DDICReference
 *     P_FILEN =.
 *
 * The 30-character limit mirrors `validateTextElements()` in abap-adt-api.
 *
 * @param {Array<{id: string, text: string, ddicReference?: boolean}>} elements
 */
function formatSelectionElements(elements) {
    for (const el of elements) {
        if (el.text.length > 30) {
            throw new Error(`Selection "${el.id}" text exceeds maximum length of 30`);
        }
    }
    const lines = [];
    for (const el of elements) {
        if (el.ddicReference) lines.push('@DDICReference');
        lines.push(`${el.id.toUpperCase()}=${el.text}`);
        lines.push('');
    }
    return lines.join('\n');
}

/**
 * Pushes ALL text elements (text symbols / selection texts / list headings)
 * for a program via the ADT textelements API — in the language the abapGit
 * file declares.
 *
 * Why we don't just call the library's `setTextElements`:
 *   1. It cannot carry `sap-language`, so a Chinese program's text elements
 *      were written under the logon language (EN) and the title never showed
 *      up in SE38. We build the requests ourselves and add
 *      `sap-language=<ISO>` to the query string (AdtHTTP forwards `qs`
 *      verbatim → AxiosHttpClient maps it to `params`).
 *   2. It pushes one category at a time; here we want the whole textpool:
 *      ID=I → symbols, ID=S → selections, ID=R/H/C → headings.
 *
 * The body is formatted by the library's own `formatTextElements()` — except
 * for `selections`, which uses the local `formatSelectionElements()` so the
 * DDIC reference directive matches what SAP accepts (bare `@DDICReference`).
 * The lock must be taken on the textelements URL — a lock on the program
 * object is rejected with "Resource REPT <name> is not locked".
 *
 * @param {ADTClient} client
 * @param {string} objectName  Uppercase program name
 * @param {{symbols: any[], selections: any[], headings: any[]}} textpool
 * @param {string} [transport]  Optional transport / correction number
 * @param {boolean} [isInclude] INCLUDE programs have no textpool → skip
 * @param {string} [language]   ISO code, e.g. 'ZH'
 * @returns {Promise<{ ok: boolean, skipped?: boolean, error?: string, results: Array<{category: string, count: number, ok: boolean, error?: string}> }>}
 */
async function pushTextElements(client, objectName, textpool, transport, isInclude, language) {
    if (isInclude) {
        return {
            ok: false, skipped: true, results: [],
            error: 'skipped (include programs have no textpool)',
        };
    }
    if (textpoolSize(textpool) === 0) {
        return { ok: false, skipped: true, results: [], error: 'no text elements in *.prog.xml' };
    }
    const teUrl = `/sap/bc/adt/textelements/programs/${objectName.toLowerCase()}`;
    /** @type {Array<{category: string, count: number, ok: boolean, error?: string}>} */
    const results = [];

    for (const category of ['symbols', 'selections', 'headings']) {
        const items = textpool?.[category] ?? [];
        if (items.length === 0) continue;
        const mediaType = `application/vnd.sap.adt.textelements.${category}.v1`;

        let body;
        try {
            body = category === 'selections'
                ? formatSelectionElements(items)
                : formatTextElements(items, category);
        } catch (e) {
            results.push({ category, count: items.length, ok: false, error: e?.message ?? String(e) });
            continue;
        }

        let lock;
        try {
            lock = await client.lock(teUrl);
        } catch (e) {
            results.push({ category, count: items.length, ok: false, error: `lock failed: ${e?.message ?? e}` });
            continue;
        }
        try {
            /** @type {Record<string, string>} */
            const qs = { lockHandle: lock.LOCK_HANDLE };
            if (language) qs['sap-language'] = language;
            if (transport) qs.corrNr = transport;
            await client.httpClient.request(`${teUrl}/source/${category}`, {
                method: 'PUT',
                body,
                qs,
                headers: { 'Content-Type': `${mediaType}; charset=UTF-8`, Accept: mediaType },
            });
            results.push({ category, count: items.length, ok: true });
        } catch (e) {
            results.push({ category, count: items.length, ok: false, error: e?.message ?? String(e) });
        } finally {
            try { await client.unLock(teUrl, lock.LOCK_HANDLE); } catch { /* lock may already be gone */ }
        }
    }

    const ok = results.length > 0 && results.every(r => r.ok);
    return {
        ok,
        results,
        error: ok ? undefined
            : results.filter(r => !r.ok).map(r => `${r.category}: ${r.error}`).join('; '),
    };
}

/** Echoes a `pushTextElements()` result into the per-object stage log. */
function logTextpoolResult(log, tr) {
    if (tr.skipped) { log(`textpool: ${tr.error}`); return; }
    for (const r of tr.results ?? []) {
        if (r.ok) log(`textpool[${r.category}]: pushed ${r.count}`);
        else log(`textpool[${r.category}]: FAILED — ${r.error}`);
    }
    if (tr.ok) log('title  : pushed LISTHEADER OK');
    else log(`title  : FAILED — ${tr.error}`);
}

/**
 * PUT the program object XML (description + PROGDIR fields) to update an
 * existing object in place. Locks first, unlocks after. Returns the new
 * description as confirmation.
 *
 * After the PUT, also pushes the whole textpool (title + symbols + selection
 * texts) via the textelements API because SAP silently ignores
 * `adtcore:description` on the program PUT — the title only appears in SE38
 * when the textpool R entry is set, and it must be written in the language
 * declared by the *.prog.xml file.
 */
async function updateDescription(client, parsed, packageName, transport) {
    const { objectName, subc, description, language, textpool, typeId, rootName, rootXmlns,
            contentType, fixpt, uccheck } = parsed;

    const loc = await findObjectUrl(client, objectName);
    if (!loc) {
        return { ok: false, error: 'object does not exist (cannot update)' };
    }
    // If typeId disagrees with SUBC, we don't try to change SUBC here (SAP
    // refuses) — we only fix the description. Flag it for the caller.
    const typeMismatch = (loc.typeId !== typeId);

    const isInclude = loc.typeId === 'PROG/I';
    const root = isInclude ? 'include:abapInclude'
        : 'program:abapProgram';
    const xmlns = isInclude
        ? 'xmlns:include="http://www.sap.com/adt/programs/includes"'
        : 'xmlns:program="http://www.sap.com/adt/programs/programs"';
    const body = `<?xml version="1.0" encoding="UTF-8"?>
<${root} ${xmlns}
  xmlns:abapsource="http://www.sap.com/adt/abapsource"
  xmlns:adtcore="http://www.sap.com/adt/core"
  abapsource:fixPointArithmetic="${fixpt === 'X' ? 'true' : 'false'}"
  abapsource:activeUnicodeCheck="${uccheck === 'X' ? 'true' : 'false'}"
  adtcore:name="${xmlAttr(objectName)}"
  adtcore:type="${xmlAttr(loc.typeId)}"
  adtcore:description="${xmlAttr(description)}"
  adtcore:language="${xmlAttr(language || 'EN')}"
  adtcore:masterLanguage="${xmlAttr(language || 'EN')}">
  <adtcore:packageRef adtcore:name="${xmlAttr(packageName)}"/>
</${root}>`;

    const lock = await client.lock(loc.url);
    const lockHandle = lock.LOCK_HANDLE;
    try {
        /** @type {Record<string, string>} */
        const qs = { lockHandle };
        if (transport) qs.corrNr = transport;
        await client.httpClient.request(loc.url, {
            method: 'PUT',
            body,
            qs,
            headers: { 'Content-Type': contentType, Accept: 'application/xml' },
        });
    } finally {
        try { await client.unLock(loc.url, lockHandle); } catch { /* swallow */ }
    }

    // Push the whole textpool (LISTHEADER heading + symbols + selection texts)
    // in the declared language — this is what SE38 actually displays.
    let textpoolPush = { ok: false, skipped: true, results: [], error: 'not attempted' };
    try {
        textpoolPush = await pushTextElements(
            client, objectName, textpool, transport, isInclude, language,
        );
    } catch (e) {
        textpoolPush = { ok: false, results: [], error: e?.message ?? String(e) };
    }

    return { ok: true, url: loc.url, typeId: loc.typeId, typeMismatch, language,
             newDesc: description, textpoolPush };
}

/**
 * Create + activate one PROG/INCLUDE object.
 * @param {ADTClient} client
 * @param {string} packageName
 * @param {string} transport  Optional transport number (for non-$TMP pkgs).
 * @param {object} parsed     Output of parseProgXml.
 * @param {object} [opts]     { updateOnly?: boolean }
 * @returns {Promise<{ object: string, action: 'created'|'updated'|'exists'|'failed', stages: string[], unsupported?: string[] }>}
 */
async function createOne(client, packageName, transport, parsed, opts = {}) {
    const stages = [];
    const log = s => { stages.push(s); };
    const { objectName, subc, description, language, textpool, typeId,
            path: endpointUrlPath, rootName, rootXmlns, contentType, sourceType } = parsed;

    log(`parsed: NAME=${objectName} SUBC=${subc} typeId=${typeId} (${sourceType})`);
    log(`lang  : ${language} (from <I18N_TPOOL><LANGUAGE>`);
    log(`title : "${description}"`);
    if (textpoolSize(textpool) > 0) {
        log(`textpool: ${textpool.symbols.length} symbol(s), ${textpool.selections.length} selection(s), ` +
            `${textpool.headings.length} heading(s)`);
    }
    for (const w of (parsed.warnings ?? [])) log(w);

    // 1. Pre-flight existence
    const exist = await objectExists(client, objectName);
    if (exist.exists) {
        log(`exists : ${objectName} @ ${exist.url}${exist.uncertain ? ' (uncertain — manual check needed)' : ''}`);
        if (opts.updateOnly) {
            // Update-only mode: PUT description onto the existing object.
            try {
                const upd = await updateDescription(client, parsed, packageName, transport);
                if (!upd.ok) {
                    log(`update : FAILED — ${upd.error}`);
                    return { object: objectName, subc, action: 'failed', stages,
                             error: `description update rejected: ${upd.error}` };
                }
                const mismatchNote = upd.typeMismatch
                    ? ` (NOTE: SUBC mismatch — file=${subc} but SAP stores typeId=${upd.typeId}; only description updated)`
                    : '';
                log(`update : PUT OK @ ${upd.url}${mismatchNote}`);
                log(`update : desc → "${upd.newDesc}" lang=${upd.language}`);
                logTextpoolResult(log, upd.textpoolPush);
                return { object: objectName, subc, action: 'updated', stages, url: upd.url,
                         language, textpool: upd.textpoolPush };
            } catch (e) {
                log(`update : FAILED — ${e?.message ?? e}`);
                return { object: objectName, subc, action: 'failed', stages,
                         error: `update threw: ${e?.message ?? e}` };
            }
        }
        // Object exists, not update-only → skip. But still push the textpool
        // because adtcore:description on the original POST may have been
        // silently ignored by SAP, and the language may differ.
        try {
            const tr = await pushTextElements(
                client, objectName, textpool, transport, subc === 'I', language,
            );
            logTextpoolResult(log, tr);
        } catch (e) {
            log(`textpool: FAILED — ${e?.message ?? e}`);
        }
        return { object: objectName, subc, action: 'exists', stages, url: exist.url, language };
    }

    if (opts.updateOnly) {
        log(`update : SKIPPED (object does not exist; nothing to update)`);
        return { object: objectName, subc, action: 'failed', stages,
                 error: `object does not exist (update-only mode; nothing to update)` };
    }

    // 2. Build POST body
    const body = buildCreateBody({
        objectName, typeId, description, rootName, rootXmlns,
        language, packageName, transport,
    });

    // 3. POST create
    const qs = transport ? { corrNr: transport } : {};
    log(`create : POST ${endpointUrlPath} (SUBC=${subc})`);
    try {
        const resp = await client.httpClient.request(endpointUrlPath, {
            method: 'POST',
            body,
            headers: { 'Content-Type': contentType, Accept: 'application/xml' },
            qs,
        });
        log('create : POST OK');
    } catch (e) {
        log(`create : POST FAILED — ${e?.message ?? e}`);
        return { object: objectName, subc, action: 'failed', stages,
                 error: `POST create rejected: ${e?.message ?? e}` };
    }

    // 4. Activate the object so it is visible in SE38 right away. String form
    // (name + url) is the variant validated against S4DEV — see
    // ddic-xml-create.mjs. The array form requires an explicit
    // `adtcore:parentUri`; without one SAP still answers 200 but reports
    // activationExecuted="false" (a silent no-op), which is why this used to log
    // "activate: OK" while nothing was activated.
    // A failure here is not fatal: prog-source-push re-activates the object
    // after uploading the source (and does fail hard if that also fails).
    try {
        const objUrl = endpointUrlPath + '/' + objectName.toLowerCase();
        const res = await client.activate(objectName, objUrl);
        const ok = res?.success === true;
        const msgs = (res?.messages ?? []).map(m => m?.shortText).filter(Boolean);
        log(`activate: success=${ok} inactive=${(res?.inactive ?? []).length}`
            + (msgs.length ? ` msgs=${msgs.join(' | ')}` : ''));
    } catch (e) {
        log(`activate: FAILED — ${e?.message ?? e}`);
    }

    // 5. Push the whole textpool (LISTHEADER heading + symbols + selection
    //    texts) in the declared language. SAP silently ignores
    //    adtcore:description on POST — the title must be pushed via the
    //    textelements API for it to show in SE38.
    try {
        const tr = await pushTextElements(
            client, objectName, textpool, transport, subc === 'I', language,
        );
        logTextpoolResult(log, tr);
    } catch (e) {
        log(`textpool: FAILED — ${e?.message ?? e}`);
    }
    return { object: objectName, subc, category: SUBC_LABEL[subc] ?? subc,
             action: 'created', stages, language };
}

// ---------------------------------------------------------------------------
// 4. Per-file flow
// ---------------------------------------------------------------------------

async function createOneFile(ctx, file, opts = {}) {
    const { client, packageName, transport } = ctx;
    const parsed = await parseProgXml(file);
    return await createOne(client, packageName, transport, parsed, opts);
}

// ---------------------------------------------------------------------------
// 5. Extension export
// ---------------------------------------------------------------------------

// Named exports for unit testing / scripting (default export below is the
// abap-cli command-extension contract).
export {
    parseProgXml, buildCreateBody, objectExists, updateDescription, createOne, createOneFile,
    mapSapLanguage, extractTextpool, pushTextElements,
};

// ---------------------------------------------------------------------------
// 6. Shared action runner — drives both `prog-xml-create` and `prog-xml-update`.
// ---------------------------------------------------------------------------
// We can't add a `--update` flag to `prog-xml-create` directly because the
// abap-cli extension host registers commands with a bare
// `program.command(ext.command).description(...).action(...)` — no
// `.option(...)` call — so commander rejects any unknown flag before our
// action runs. Registering a second command name is the path of least
// resistance.

/**
 * The actual command body, factored out so `prog-xml-create` and
 * `prog-xml-update` can both call it. `forcedUpdateOnly` lets the caller
 * pin the mode without exposing a CLI flag.
 *
 * @param {boolean} forcedUpdateOnly
 */
async function runAction(ctx, opts, args, forcedUpdateOnly) {
    const files = (args.length === 1 && Array.isArray(args[0]) ? args[0] : args)
        .filter(f => typeof f === 'string' && f.length > 0);
    if (files.length === 0) {
        console.error(
            `Usage: abap ${forcedUpdateOnly ? 'prog-xml-update' : 'prog-xml-create'} ` +
            '<file1.prog.xml> [file2.prog.xml ...]'
        );
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
    const updateOnly = forcedUpdateOnly
        || argv.includes('--update')
        || process.env.ABAP_UPDATE_ONLY === '1';

    const abapJson = await loadAbapJson();
    const creds = await loadProfile(abapJson.system);
    const insecure = insecureFromEnv() || creds.insecure === true;

    // -------------------------------------------------------------------
    // One ADT session per language.
    //
    // The logon language is fixed for the lifetime of an ADT session
    // (`AdtHTTP.login()` only sends `sap-language` once). Because a program's
    // textpool language comes from the *.prog.xml file — not from the
    // systems.json profile — we open a dedicated client the first time we
    // meet a given language and reuse it for the rest of the batch. On top of
    // that, `pushTextElements()` also sends `sap-language` on each write, so
    // either mechanism alone is enough for SAP to pick the right language.
    // -------------------------------------------------------------------
    /** @type {Map<string, ADTClient>} */
    const clientCache = new Map();
    const clientFor = async (language) => {
        const lang = language || creds.language || 'EN';
        if (!clientCache.has(lang)) {
            const c = new ADTClient(
                creds.url, creds.username, creds.password, creds.client, lang,
                insecure ? createSSLConfig(true) : undefined,
            );
            c.stateful = 'stateful';
            await c.login();
            clientCache.set(lang, c);
        }
        return clientCache.get(lang);
    };

    const results = [];
    for (const f of files) {
        try {
            const parsed = await parseProgXml(f);
            const client = await clientFor(parsed.language);
            const r = await createOne(client, abapJson.package, transport, parsed, { updateOnly });
            results.push(r);
        } catch (err) {
            results.push({
                file: f,
                action: 'failed',
                error: err?.message ?? String(err),
                ...(process.env.ABAP_DEBUG ? { stack: err?.stack } : {}),
            });
        }
    }

    if (prettyJson) {
        process.stdout.write(JSON.stringify(results, null, 2) + '\n');
        // A failed result must still surface as a non-zero exit code so the
        // Java caller (which only inspects exitCode) never sees a false
        // "Overall: OK" when a create/update was actually rejected.
        if (results.some(r => r.action === 'failed')) process.exitCode = 1;
    } else {
        for (const r of results) {
            if (r.action === 'failed') {
                console.error(`✗ ${r.file ?? r.object}: ${r.error}`);
                process.exitCode = 1;
                continue;
            }
            console.log(`✓ ${r.action}: ${r.object}${r.subc ? ` (SUBC=${r.subc} → ${r.category ?? '?'})` : ''}`);
            for (const s of r.stages) console.log(`    ${s}`);
        }
    }
}

// ---------------------------------------------------------------------------
// 6. Extension exports — commander wired by the abap-cli loader
// ---------------------------------------------------------------------------

/**
 * `prog-xml-create` — POST-create objects that don't yet exist; silently
 * skip objects that already exist (the legacy abap-cli `create` command
 * hard-codes SUBC=1 so it cannot make INCLUDE programs at all).
 */
const createExtension = /** @type {import('abap-cli/dist/src/abap_cli/extensions/types.js').CommandExtension} */ ({
    type: 'command',
    name: 'prog-xml-create',
    description:
        'Create PROG objects in SAP from *.prog.xml metadata. Honours <PROGDIR>' +
        '<SUBC> so INCLUDE programs go to /programs/includes and executable ' +
        'programs to /programs/programs. Titles come from <I18N_TPOOL> ID=R.',
    command: 'prog-xml-create [files...]',
    async action(ctx, opts, ...args) {
        return runAction(ctx, opts, args, /* forcedUpdateOnly */ false);
    },
});

/**
 * `prog-xml-update` — PUT-update the title (and PROGDIR fields) on objects
 * that already exist; skip objects that don't exist. Use this to fix the
 * description after the fact without re-creating.
 *
 * We register a sibling command rather than a `--update` flag because the
 * abap-cli extension host registers commands with a bare
 * `program.command(ext.command).description(...).action(...)` — no
 * `.option(...)` call — so commander rejects any unknown flag before our
 * action runs.
 */
const updateExtension = /** @type {import('abap-cli/dist/src/abap_cli/extensions/types.js').CommandExtension} */ ({
    type: 'command',
    name: 'prog-xml-update',
    description:
        'Update PROG objects in SAP from *.prog.xml metadata. Locks the ' +
        'existing object and PUTs the new title (and PROGDIR fields) from ' +
        '<I18N_TPOOL> ID=R. Skips objects that do not exist.',
    command: 'prog-xml-update [files...]',
    async action(ctx, opts, ...args) {
        return runAction(ctx, opts, args, /* forcedUpdateOnly */ true);
    },
});

// abap-cli's loader picks the *first* command extension as the default and
// iterates remaining exports for sibling commands (see
// `node_modules/abap-cli/dist/src/abap_cli/extensions/registry.js`).
export default createExtension;
export { updateExtension };