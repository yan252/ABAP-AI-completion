/**
 * abap-cli command extension: `prog-xml-push`
 *
 * Reads one or more `*.prog.xml` files (abapGit-serialized ABAP program
 * metadata) and pushes their non-source content to SAP:
 *
 *   - `<PROGDIR>`    -> PUT /sap/bc/adt/programs/programs/<name>
 *                       (attributes SUBC/FIXPT/… and the ENTRY title)
 *   - `<I18N_TPOOL>` -> PUT /sap/bc/adt/textelements/programs/<name>/source/<category>
 *   - `<CUA>`        -> PUT /sap/bc/adt/programs/programs/<name>/cua      [if exposed]
 *   - `<DYNPROS>`    -> PUT /sap/bc/adt/programs/programs/<name>/dynpros  [if exposed]
 *
 * Two facts shape the design:
 *
 * 1. TEXT ELEMENTS ARE LANGUAGE-SCOPED.
 *    abapGit records the master language in
 *    `<I18N_TPOOL><item><LANGUAGE>1</LANGUAGE>…` — `1` is SAP's internal code
 *    for Chinese. A `*.prog.xml` written in ZH therefore has to be imported
 *    into the ZH textpool, otherwise the report title (LISTHEADER) silently
 *    lands in EN and SE38 shows nothing. We therefore
 *      • log the ADT session on with that language (`sap-language` at login),
 *      • send `sap-language` on every text-element PUT as well, and
 *      • use the language for `adtcore:language` / `adtcore:masterLanguage`.
 *
 *    The textpool also has to be ACTIVATED SEPARATELY. It is an activatable
 *    object of its own — `GET /sap/bc/adt/textelements/programs/<name>`
 *    answers `<rept:textElement … adtcore:type="PROG/PX" adtcore:version=…>`
 *    — and putting a reference for the program (`PROG/P`) into the activation
 *    request leaves the textpool `inactive`. Verified against S4DEV: after a
 *    text-element PUT the resource reports `version=inactive`, activating
 *    `PROG/P` does not change that, and only a second activation reference of
 *    `adtcore:type="PROG/PX"` on the text-elements URL flips it to `active`.
 *
 * 2. CLASSIC DYNPROS AND GUI STATUS (CUA) HAVE NO ADT ENDPOINT HERE.
 *    `PUT …/cua` and `PUT …/dynpros` answer
 *    `404 No suitable resource found`, and `/sap/bc/adt/abapconsole` does not
 *    exist either (verified against the live discovery document). The only
 *    server-side API for this data is the classic function-module pair
 *
 *        RPY_DYNPRO_INSERT      (function group SIFP / package SEUX)
 *        RS_CUA_INTERNAL_WRITE  (function group SMPI / package SEUC)
 *
 *    So when the REST endpoints are missing we synthesize a throw-away ABAP
 *    class implementing `IF_OO_ADT_CLASSRUN`, ship it through the ordinary
 *    class endpoints (create → PUT source → activate), execute it with
 *    `POST /sap/bc/adt/oo/classrun/<NAME>`, parse the machine-readable result
 *    it prints, and delete the class again.
 *
 * Usage:
 *   abap prog-xml-push <file1.prog.xml> [file2.prog.xml ...] [--transport <tr>]
 *
 * Configuration comes from `.abap.json` (system / package) and
 * `~/.abap-cli/systems.json` (URL / credentials), exactly like `abap push`.
 *
 * Environment knobs
 *   ABAP_PROG_XML_CLASSRUN=0   disable the ABAP-side classrun fallback
 *   ABAP_HELPER_PACKAGE=…      package for the throw-away class (default $TMP)
 *   ABAP_KEEP_HELPER=1         do not delete the throw-away class (debugging)
 *   ABAP_DEBUG=1               include stack traces in errors
 */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import * as os from 'node:os';
import * as fxp from 'fast-xml-parser';

// ---------------------------------------------------------------------------
// 1. ADTClient discovery & construction
// ---------------------------------------------------------------------------
// We can't import from `abap-cli/clients/adt-client.js` directly — extensions
// run as a sub-graph of the host. Use the lower-level `abap-adt-api`
// primitives the wrapper composes from.
import { ADTClient, createSSLConfig } from 'abap-adt-api';
import { formatTextElements } from 'abap-adt-api/build/api/textelements.js';

const CRED_KEYCHAIN_HOST = 'abap-cli';
const CRED_KEYCHAIN_SERVICE = 'credentials-for-system';

/**
 * Light shape of the relevant subset of `.abap.json`.
 * @typedef {{ system: string, package: string, sourceDir?: string }} AbapJson
 */
/** @typedef {{ url: string, client: string, username: string, password: string, language?: string, insecure?: boolean }} SapCreds */

/**
 * Loads `.abap.json` from the current working directory.
 * @returns {Promise<AbapJson>}
 */
async function loadAbapJson() {
    const p = path.join(process.cwd(), '.abap.json');
    const raw = await fs.readFile(p, 'utf8');
    const json = JSON.parse(raw);
    if (!json.system || !json.package) {
        throw new Error(`.abap.json must contain { system, package } (got: ${Object.keys(json).join(',')})`);
    }
    return /** @type {AbapJson} */ (json);
}

/**
 * Reads `~/.abap-cli/systems.json` (or platform-equivalent) and returns the
 * matching system's credentials.
 * @param {string} systemName
 * @returns {Promise<SapCreds>}
 */
async function loadProfile(systemName) {
    const home = process.env.HOME || process.env.USERPROFILE || os.homedir();
    const p = path.join(home, '.abap-cli', 'systems.json');
    let raw;
    try {
        raw = await fs.readFile(p, 'utf8');
    } catch {
        throw new Error(
            `Cannot read profile at ${p}. Run: abap profile add ${systemName} --url ... --client ... --username ... --password ...`,
        );
    }
    const parsed = JSON.parse(raw);
    // abap-cli writes two shapes over its lifetime:
    //   v1 (legacy):  { "S4D": { url, client, username, password } }
    //   v2 (current): { "systems": { "S4D": { url, client, username } } }
    // The v2 file stores NO password — it lives in the OS keychain.
    const all = parsed && typeof parsed === 'object'
        && parsed.systems && typeof parsed.systems === 'object'
        ? parsed.systems
        : parsed;
    const key = [systemName, systemName.toUpperCase(), systemName.toLowerCase()]
        .find(k => all && all[k]);
    if (!key) {
        const names = Object.keys(all ?? {}).join(', ');
        throw new Error(`System '${systemName}' not found in ${p}. Known: ${names}`);
    }
    const sys = /** @type {SapCreds} */ ({ ...all[key] });
    // SAP Preference-page overrides (set by the Eclipse plugin).
    if (process.env.ABAP_URL) sys.url = process.env.ABAP_URL;
    if (process.env.ABAP_CLIENT) sys.client = process.env.ABAP_CLIENT;
    if (process.env.ABAP_USERNAME) sys.username = process.env.ABAP_USERNAME;
    if (process.env.ABAP_LANGUAGE) sys.language = process.env.ABAP_LANGUAGE;
    if (!sys.url || !sys.client || !sys.username) {
        throw new Error(`Profile '${key}' in ${p} is missing url/client/username.`);
    }
    if (!sys.password) {
        sys.password = await resolvePassword([key, systemName]);
    }
    return sys;
}

/**
 * Fetches the SAP password from the OS keychain, mirroring abap-cli's own
 * `config/secrets.js` (SERVICE = 'abap-cli', account = profile name).
 * `ABAP_PASSWORD` wins when set, which keeps CI / headless runs possible.
 * @param {string[]} accounts
 * @returns {Promise<string>}
 */
async function resolvePassword(accounts) {
    if (process.env.ABAP_PASSWORD) return process.env.ABAP_PASSWORD;
    /** @type {{ getPassword: (s: string, a: string) => Promise<string | null> } | null} */
    let keytar = null;
    try {
        keytar = (await import('keytar')).default;
    } catch {
        keytar = null;
    }
    if (keytar) {
        for (const account of accounts) {
            try {
                const found = await keytar.getPassword('abap-cli', account);
                if (found) return found;
            } catch {
                /* try the next candidate account */
            }
        }
    }
    throw new Error(
        `No password for '${accounts[0]}': absent from systems.json, OS keychain ` +
        `(service 'abap-cli') and $ABAP_PASSWORD. Re-run: abap profile add ${accounts[0]} ... ` +
        `--password <pwd>`,
    );
}

/**
 * Best-effort read of `NODE_TLS_REJECT_UNAUTHORIZED=0` — same env as the rest
 * of abap-cli. Returns true when the user has opted into self-signed certs.
 */
function insecureFromEnv() {
    return process.env.NODE_TLS_REJECT_UNAUTHORIZED === '0';
}

/**
 * Minimal `--flag value` / `--flag=value` scanner over a raw argv array.
 * Needed because the host's extension wiring cannot declare commander options
 * (see the note on `export default` below).
 * @param {string[]} argv
 * @param {string} flag
 * @returns {string | undefined}
 */
function argvValue(argv, flag) {
    const eq = argv.find(a => a.startsWith(`${flag}=`));
    if (eq) return eq.slice(flag.length + 1);
    const i = argv.indexOf(flag);
    if (i >= 0 && i + 1 < argv.length && !argv[i + 1].startsWith('-')) return argv[i + 1];
    return undefined;
}

// ---------------------------------------------------------------------------
// 1.5 Language handling
// ---------------------------------------------------------------------------
// SAP's internal 1-character language code is what `SY-LANGU`, `TRDIR-MASTERLANG`
// and the ABAP function modules want; `sap-language` on the ADT wire and
// `adtcore:language` want ISO 639-1. Both directions are needed here, so the
// table is kept once and used from both sides.

/** SAP internal 1-char language code -> ISO 639-1 (upper case). */
const SAP_LANG_TO_ISO = {
    '1': 'ZH', E: 'EN', D: 'DE', F: 'FR', S: 'ES', J: 'JA', K: 'KO', R: 'RU',
    T: 'TH', B: 'PT', I: 'IT', N: 'NL', P: 'PL', A: 'AR', H: 'HE', V: 'SV',
    U: 'FI', O: 'NO', L: 'DA', C: 'CS', M: 'HU', G: 'EL', Q: 'TR', Z: 'SK',
    Y: 'SL', X: 'BG', '0': 'RO', '7': 'HR', '4': 'SR', '6': 'ET', '8': 'LV',
    '9': 'LT', W: 'ZF',
};

/** ISO 639-1 -> SAP internal 1-char code (reverse of the map above). */
const ISO_TO_SAP_LANG = (() => {
    /** @type {Record<string,string>} */
    const m = {};
    for (const [sap, iso] of Object.entries(SAP_LANG_TO_ISO)) {
        if (!(iso in m)) m[iso] = sap;
    }
    m.ZH = '1'; // Chinese — the canonical internal code (the table has no other ZH entry)
    return m;
})();

/**
 * Normalises whatever language marker a `*.prog.xml` carries into an ISO
 * 639-1 code suitable for `sap-language` / `adtcore:language`.
 *
 * Accepts the SAP internal code (`1`, `E`, `D`) *and* an already-ISO value
 * (`ZH`, `zh`) so hand-edited files keep working.
 *
 * @param {unknown} raw
 * @param {string} [fallback]
 */
function mapSapLanguage(raw, fallback = 'EN') {
    const v = String(raw ?? '').trim();
    if (!v) return fallback;
    if (v.length === 2 && /^[A-Za-z]{2}$/.test(v)) return v.toUpperCase();
    return SAP_LANG_TO_ISO[v.toUpperCase()] ?? fallback;
}

/**
 * Normalises a language marker into the SAP **internal** 1-char code that the
 * classic function modules (`RPY_DYHEAD-LANGUAGE`, `RS_CUA_INTERNAL_WRITE-LANGUAGE`)
 * require.
 *
 * @param {unknown} raw
 * @param {string} [fallback]
 */
function toSapLanguageCode(raw, fallback = 'E') {
    const v = String(raw ?? '').trim();
    if (!v) return fallback;
    if (v.length === 1) return v.toUpperCase();
    if (v.length === 2 && /^[A-Za-z]{2}$/.test(v)) return ISO_TO_SAP_LANG[v.toUpperCase()] ?? fallback;
    return fallback;
}

/** `<I18N_TPOOL><item><LANGUAGE>1</LANGUAGE>` — the master language abapGit stored. */
function languageFromTextpool(i18nBlock) {
    if (!i18nBlock) return '';
    const m = i18nBlock.match(/<LANGUAGE>\s*([^<\s]+)\s*<\/LANGUAGE>/);
    return m ? m[1] : '';
}

/** Fallback: `<DYNPROS><item><HEADER><LANGUAGE>1` . */
function languageFromDynpros(xml) {
    const block = blockOf(xml, 'DYNPROS');
    if (!block) return '';
    const m = block.match(/<LANGUAGE>\s*([^<\s]+)\s*<\/LANGUAGE>/);
    return m ? m[1] : '';
}

// ---------------------------------------------------------------------------
// 2. XML helpers — local copies of SAP wire envelopes
// ---------------------------------------------------------------------------

/**
 * AbapGit XML helper: trim a BOM + normalise line endings so fast-xml-parser
 * doesn't choke on a single giant line.
 */
function normaliseXml(raw) {
    return raw.replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n');
}

/**
 * Returns the text inside the first `<TAG>...</TAG>` (non-greedy) or null.
 * @param {string} xml
 * @param {string} tag
 */
function blockOf(xml, tag) {
    const re = new RegExp(`<${tag}[\\s\\S]*?<\\/${tag}>`, 'm');
    const m = xml.match(re);
    return m ? m[0] : null;
}

/**
 * Returns an array of XML strings for a repeated parent block.
 *  `repeatedBlocks("<a><b/><b/></a>", "b")` -> ["<b/>", "<b/>"]
 */
function repeatedBlocks(xml, parentTag, childTag) {
    const parent = blockOf(xml, parentTag);
    if (!parent) return [];
    const re = new RegExp(`<${childTag}[\\s\\S]*?<\\/${childTag}>`, 'g');
    return parent.match(re) ?? [];
}

/** Returns the first occurrence of <TAG>...</TAG>'s inner text. */
function innerText(xml, tag) {
    const m = xml.match(new RegExp(`<${tag}>([\\s\\S]*?)<\\/${tag}>`));
    return m ? m[1].trim() : '';
}

const FXP_OPTS = { ignoreAttributes: true, parseTagValue: false, trimValues: true };

/** Parses an XML string with the settings used throughout this extension. */
function fxpParse(xml) {
    return new fxp.XMLParser(FXP_OPTS).parse(xml);
}

/** Normalises a fast-xml-parser node into an array (absent -> []). */
function asArray(v) {
    if (v === undefined || v === null) return [];
    return Array.isArray(v) ? v : [v];
}

/**
 * Copies the allow-listed keys of a parsed XML node, stringified.
 * Any key not in `allow` is dropped — this is what stops an unexpected
 * abapGit field from being emitted as an invalid ABAP component name.
 *
 * @param {Record<string, unknown>} node
 * @param {string[]} allow
 * @returns {Record<string, string>}
 */
function pickFields(node, allow) {
    const out = {};
    for (const f of allow) {
        const v = node?.[f];
        if (v === undefined || v === null) continue;
        if (typeof v === 'object') continue; // nested element — never a scalar field
        out[f] = String(v);
    }
    return out;
}

// ---------------------------------------------------------------------------
// 3. CUA / DYNPROS payload wrapping (ADT-native path)
// ---------------------------------------------------------------------------

/**
 * Wraps the abapGit `<CUA>` body in the SAP wire envelope expected by
 *  PUT /sap/bc/adt/programs/programs/<name>/cua?lockHandle=...
 *
 * Only used on releases that actually expose that endpoint — on the systems we
 * tested it answers 404 and the classrun fallback takes over.
 *
 * @param {string} cuaInner  raw `<CUA>...</CUA>` block
 */
function wrapCua(cuaInner) {
    const inner = stripOuterTag(cuaInner, 'CUA');
    return `<?xml version="1.0" encoding="UTF-8"?>\n` +
        `<cua xmlns="http://www.sap.com/adt/cua/1.0">\n` +
        `${indent(inner, 1)}\n` +
        `</cua>`;
}

/** Wraps a single abapGit `<item>` from `<DYNPROS>...</DYNPROS>`. */
function wrapDynpro(item) {
    return `<?xml version="1.0" encoding="UTF-8"?>\n` +
        `<dynpros xmlns="http://www.sap.com/adt/dynpro/1.0">\n` +
        `${indent(item, 1)}\n` +
        `</dynpros>`;
}

function indent(xml, levels) {
    if (!xml.trim()) return '';
    const pad = '  '.repeat(levels);
    return xml
        .split('\n')
        .map(line => pad + line)
        .join('\n')
        .replace(new RegExp(`^${pad}\\s*$`, 'm'), `${pad}`);
}

function stripOuterTag(xml, tag) {
    // Remove exactly one matching <tag>...</tag>; if missing, return original.
    const re = new RegExp(`^\\s*<${tag}[^>]*>([\\s\\S]*)<\\/${tag}>\\s*$`);
    const m = xml.match(re);
    return m ? m[1].trim() : xml.trim();
}

// ---------------------------------------------------------------------------
// 3.5 PROGDIR — mapping abapGit program attributes to ADT update payload
// ---------------------------------------------------------------------------

/**
 * Mapping from abapGit PROGDIR <SUBC> to the ADT object type id used in
 * `adtcore:type` and as the object URL discriminator. SAP only allows
 * changing SUBC at create time, but the PUT endpoint still accepts the
 * attribute — the request will be rejected with PROGDIR_SUBC if it
 * conflicts with the existing object.
 *
 *  1   -> PROG/P  (executable program / report)
 *  I   -> PROG/I  (INCLUDE program)
 *  M   -> (module pool — same URL as PROG/P, but distinguished by SUBC)
 *  S   -> (subroutine pool)
 */
const SUBC_TO_TYPEID = {
    '1': 'PROG/P',
    'I': 'PROG/I',
    'M': 'PROG/P', // module pool — uses the same ADT URL, SUBC tells SAP the kind
    'S': 'PROG/P', // subroutine pool
    'F': 'PROG/P', // function group — not really PROG but accept defensively
};

/** All PROGDIR fields we forward to SAP. Anything else (RSTAT, VARCL, …)
 *  is intentionally not sent — the source-of-truth on the server side
 *  is what the activation pipeline writes, and including extra fields
 *  can trigger "field not editable" errors. */
const PROGDIR_FIELDS = ['SUBC', 'FIXPT', 'UNICODE', 'DBAPL', 'DBNA'];

/**
 * Returns the PROGDIR fields we want to push, keyed by uppercase name.
 * Falls back to safe defaults (FIXPT=X, UNICODE=X) when abapGit omitted
 * them — modern SAP releases require both.
 *
 * @param {Record<string, any>|undefined} progdir
 *        raw PROGDIR object straight out of fast-xml-parser
 */
function extractProgdir(progdir) {
    if (!progdir) return null;
    const out = {};
    for (const f of PROGDIR_FIELDS) {
        const v = progdir[f];
        if (v !== undefined && v !== null && String(v).trim() !== '') {
            out[f] = String(v).trim();
        }
    }
    if (!out.SUBC) return null;
    if (!out.FIXPT) out.FIXPT = 'X';
    if (!out.UNICODE) out.UNICODE = 'X';
    return out;
}

/**
 * Builds the SAP ADT program object XML used by
 *  PUT /sap/bc/adt/programs/programs/<name>
 *
 * The root element differs by program type:
 *   PROG/P -> <program:abapProgram xmlns:program="…">
 *   PROG/I -> <include:abapInclude xmlns:include="…">
 *
 * @param {object} args
 * @param {string} args.name        program name (uppercase)
 * @param {string} args.typeId      PROG/P or PROG/I
 * @param {string} args.description ENTRY (title)
 * @param {string} args.responsible
 * @param {Record<string,string>} args.progdir
 * @param {string} [args.packageName]
 * @param {string} [args.language]  ISO language for adtcore:language
 */
function wrapProgdir({ name, typeId, description, responsible, progdir, packageName, language }) {
    const isInclude = typeId === 'PROG/I';
    const rootName = isInclude ? 'include:abapInclude' : 'program:abapProgram';
    const rootNs = isInclude
        ? 'xmlns:include="http://www.sap.com/adt/programs/includes"'
        : 'xmlns:program="http://www.sap.com/adt/programs/programs"';
    const descXml = description
        ? ` adtcore:description="${encodeXmlAttr(description)}"`
        : ' adtcore:description=""';
    const pkgRef = packageName
        ? `    <adtcore:packageRef adtcore:name="${encodeXmlAttr(packageName)}"/>\n`
        : '';
    const lang = encodeXmlAttr(language || 'EN');
    const progdirInner = PROGDIR_FIELDS
        .filter(f => progdir[f] !== undefined)
        .map(f => `        <${f}>${encodeXmlText(progdir[f])}</${f}>`)
        .join('\n');
    return `<?xml version="1.0" encoding="UTF-8"?>\n` +
        `<${rootName} ${rootNs}\n` +
        `   xmlns:adtcore="http://www.sap.com/adt/core"\n` +
        `   xmlns:progdirt="http://www.sap.com/adt/programs/progdir"\n` +
        `${descXml}\n` +
        `   adtcore:name="${encodeXmlAttr(name)}"\n` +
        `   adtcore:type="${typeId}"\n` +
        `   adtcore:language="${lang}"\n` +
        `   adtcore:masterLanguage="${lang}"\n` +
        `   adtcore:responsible="${encodeXmlAttr(responsible)}">\n` +
        `${pkgRef}` +
        `   <programConfiguration>\n` +
        `      <progdir:progDirectory xmlns:progdir="http://www.sap.com/adt/programs/progdir">\n` +
        `${progdirInner}\n` +
        `      </progdir:progDirectory>\n` +
        `   </programConfiguration>\n` +
        `</${rootName}>`;
}

/** Minimal XML-attribute escaping — covers the characters SAP will reject. */
function encodeXmlAttr(s) {
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/"/g, '&quot;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}
function encodeXmlText(s) {
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

// ---------------------------------------------------------------------------
// 4. Textpool — grouping items into the three SAP categories
// ---------------------------------------------------------------------------

/** Mapping from abapGit <ID> to the ADTClient text-element category. */
const ID_TO_CATEGORY = {
    I: 'symbols',
    S: 'selections',
    R: 'headings',
    H: 'headings',
    C: 'headings',
};

/**
 * abapGit textpool `<ID>` → the key the ADT textelements API expects for the
 * `headings` category. The API only accepts the fixed set
 * LISTHEADER / COLUMNHEADER_1..4 (see `validateTextElements` in abap-adt-api).
 */
const HEADING_ID_TO_KEY = {
    R: 'LISTHEADER',      // list heading
    H: 'COLUMNHEADER_1',  // column header
    C: 'COLUMNHEADER_2',
};

/**
 * @typedef {{ id: string, text: string, maxLength: number, ddicReference?: boolean }} TextElementPayload
 */

/**
 * Convert one `<item>` from abapGit's `<TEXTPOOL>` into the ADTClient shape.
 *
 * Note the key semantics differ per category:
 *   symbols    → 3-char symbol key, used verbatim (`001`, `B01`, …)
 *   selections → the FULL parameter/select-option name (`S_MATNR`) — SAP keys
 *                selection texts by the field name, so truncating to 3 chars
 *                (as an earlier revision did) silently produces the wrong entry
 *   headings   → a fixed LISTHEADER/COLUMNHEADER_n key, not the abapGit id
 */
function parseTextpoolItem(item) {
    const id = (innerText(item, 'ID') || '').trim().toUpperCase();
    const key = innerText(item, 'KEY').trim();
    const entry = innerText(item, 'ENTRY');
    const length = parseInt(innerText(item, 'LENGTH'), 10) || entry.length;
    const category = ID_TO_CATEGORY[id];
    if (!category) return null;

    let normalisedId;
    let ddicReference = false;
    if (category === 'headings') {
        normalisedId = HEADING_ID_TO_KEY[id] ?? 'LISTHEADER';
    } else if (category === 'selections') {
        normalisedId = key;
        if (!normalisedId) return null; // a selection text without a key is unusable
        // abapGit flags a selection text that references the DDIC (data element)
        // with <SPLIT>D</SPLIT>. Without this flag the selection text is a plain
        // manual text and SAP leaves "数据字典参考" (DDIC reference) unchecked.
        ddicReference = (innerText(item, 'SPLIT') || '').trim().toUpperCase() === 'D';
    } else {
        normalisedId = key;
        if (normalisedId.length !== 3) {
            // The ADT endpoint rejects symbol keys that are not exactly three
            // characters and rejects blanks outright, so we cannot pad.
            return { __invalid: `symbol key "${normalisedId}" is not 3 characters` };
        }
    }
    return /** @type {TextElementPayload} */ ({
        id: normalisedId,
        text: entry,
        maxLength: length,
        ddicReference,
    });
}

/**
 * Group abapGit textpool items by ADTClient category and return
 * {symbols, selections, headings} arrays ready for `formatTextElements`.
 *
 * @param {string} i18nBlock  raw `<I18N_TPOOL>...</I18N_TPOOL>`
 */
function groupTextpool(i18nBlock) {
    const textpoolBlocks = repeatedBlocks(i18nBlock, 'TEXTPOOL', 'TEXTPOOL');
    const allItems = textpoolBlocks
        .flatMap(b => repeatedBlocks(b, 'TEXTPOOL', 'item'))
        .filter(s => s.trim().length > 0);
    const buckets = {
        symbols: /** @type {TextElementPayload[]} */ ([]),
        selections: /** @type {TextElementPayload[]} */ ([]),
        headings: /** @type {TextElementPayload[]} */ ([]),
        warnings: /** @type {string[]} */ ([]),
    };
    for (const item of allItems) {
        const te = parseTextpoolItem(item);
        if (!te) continue;
        if (te.__invalid) {
            buckets.warnings.push(`textpool: skipped — ${te.__invalid}`);
            continue;
        }
        const category = ID_TO_CATEGORY[innerText(item, 'ID').trim().toUpperCase()] || 'symbols';
        buckets[category].push(te);
    }
    return buckets;
}

// ---------------------------------------------------------------------------
// 4.5 Structured extraction of DYNPROS / CUA for the classrun generator
// ---------------------------------------------------------------------------
// The allow-lists below are the DDIC components of the corresponding
// structures (read straight out of the system). Anything abapGit writes that
// is not listed is reported as a warning instead of being emitted as an
// invalid ABAP component name.

const DYHEAD_FIELDS = [
    'PROGRAM', 'SCREEN', 'LANGUAGE', 'DESCRIPT', 'TYPE', 'NEXTSCREEN', 'CURSOR_POS',
    'SCREEN_GRP', 'LINES', 'COLUMNS', 'HOLD_DATA', 'FIXED_FONT', 'NO_COMPR',
    'NO_EXECUTE', 'KEEP_SCPOS', 'NO_TOOLBAR', 'CTX_MENON', 'FIORIENLARGEOFF',
];

const DYCATT_FIELDS = [
    'TYPE', 'NAME', 'ELEMENT_OF', 'LINE', 'COLUMN', 'LENGTH', 'HEIGHT', 'LOOP_TYPE',
    'LOOP_BLOCK', 'LOOP_DISP', 'C_RESIZE_V', 'C_RESIZE_H', 'C_LINE_MIN', 'C_COLN_MIN',
    'C_SCROLL_V', 'C_SCROLL_H', 'TC_TABTYPE', 'TC_SEPAR_V', 'TC_SEPAR_H', 'TC_TITLE',
    'TC_HEADER', 'TC_CONFIG', 'TC_SEL_LNS', 'TC_SEL_CLS', 'TC_LSEL_CL', 'TC_FIXCOL',
    'CU_CC_NAME', 'SFW_SWITCHID', 'SFW_SHOWHIDE', 'TSVHEIGHT',
];

const DYFAT_FIELDS = [
    'CONT_TYPE', 'CONT_NAME', 'TYPE', 'NAME', 'TEXT', 'DROPDOWN', 'ICON_NAME',
    'ICON_QINFO', 'ICON_QSPEC', 'WITH_ICON', 'ROLLING', 'LINE', 'COLUMN', 'LENGTH',
    'VISLENGTH', 'HEIGHT', 'GROUP1', 'GROUP2', 'GROUP3', 'GROUP4', 'PUSH_FCODE',
    'PUSH_FTYPE', 'CXT_MENON', 'FORMAT', 'FROM_DICT', 'MODIFIC', 'CONV_EXIT',
    'PARAM_ID', 'SET_PARAM', 'GET_PARAM', 'UP_LOWER', 'NO_TEMPL', 'FOREIGNKEY',
    'INPUT_FLD', 'OUTPUT_FLD', 'OUTPUTONLY', 'REQU_ENTRY', 'POSS_ENTRY', 'DROPFROM',
    'RIGHT_JUST', 'LEAD_ZEROS', 'NO_RESET', 'FIXED_FONT', 'BRIGHT', 'INVISIBLE',
    'LABELLEFT', 'LABELRIGHT', 'LTR', 'BIDICTRL', 'DBLCLICK', 'MATCHCODE',
    'REF_FIELD', 'TC_TITLE', 'TC_HEADING', 'TC_SELCOL', 'BUTT_RIGHT',
    'DICT_I_FKY', 'DICT_I_FKD', 'DICT_I_SGN',
];

const ADM_FIELDS = ['ACTCODE', 'MENCODE', 'PFKCODE', 'DEFAULTACT', 'DEFAULTPFK', 'MOD_LANGU'];
const STAT_FIELDS = ['CODE', 'MODAL', 'ACTCODE', 'PFKCODE', 'BUTCODE', 'INT_NOTE', 'CTXCODE'];
const FUNT_FIELDS = [
    'CODE', 'TEXTNO', 'TYPE', 'MODIF', 'TEXT_TYPE', 'TEXT_NAME', 'ICON_ID', 'FUN_TEXT',
    'ICON_TEXT', 'INFO_TEXT', 'PATH', 'USAGE', 'SFW_SWITCHID', 'SFW_SHOWHIDE',
];
const PFK_FIELDS = ['CODE', 'PFNO', 'FUNCODE', 'FUNNO'];
const STAF_FIELDS = ['STATUS', 'FUNCTION', 'ACTIVE_VIEW'];
const ATRT_FIELDS = ['OBJ_TYPE', 'OBJ_CODE', 'SUB_CODE', 'MODAL', 'NORM', 'INT_NOTE'];
const TITT_FIELDS = ['CODE', 'TEXT'];

/**
 * Parses one `<DYNPROS><item>` into the shapes needed for code generation.
 *
 * @param {string} itemXml
 * @returns {{ header: Record<string,string>, containers: Record<string,string>[],
 *             fields: Record<string,string>[], flow: string[], warnings: string[] }}
 */
function parseDynproItem(itemXml) {
    const warnings = [];
    // `itemXml` is the *whole* `<item>…</item>` element (that is what
    // `repeatedBlocks` returns, and what `wrapDynpro` needs for the ADT path).
    // Wrapping it again in `<__item>` therefore introduces one extra level —
    // the payload ends up at `__item.item`, not `__item`. Unwrap it, otherwise
    // every field lookup below silently misses and the generated ABAP gets an
    // empty screen header.
    const wrapped = fxpParse(`<__item>${itemXml}</__item>`).__item ?? {};
    const node = wrapped.item ?? wrapped;
    const header = pickFields(node.HEADER ?? {}, DYHEAD_FIELDS);
    const containers = asArray(node.CONTAINERS?.RPY_DYCATT).map(c => pickFields(c, DYCATT_FIELDS));
    const fields = asArray(node.FIELDS?.RPY_DYFATC).map(f => pickFields(f, DYFAT_FIELDS));
    const flow = asArray(node.FLOW_LOGIC?.RPY_DYFLOW).map(l => String(l.LINE ?? '').toString());

    // Report (but do not fail on) anything abapGit sent that we cannot map.
    for (const key of Object.keys(node.HEADER ?? {})) {
        if (!DYHEAD_FIELDS.includes(key)) warnings.push(`dynpro header field ${key} ignored`);
    }
    for (const c of asArray(node.CONTAINERS?.RPY_DYCATT)) {
        for (const key of Object.keys(c ?? {})) {
            if (!DYCATT_FIELDS.includes(key)) warnings.push(`container field ${key} ignored`);
        }
    }
    for (const f of asArray(node.FIELDS?.RPY_DYFATC)) {
        for (const key of Object.keys(f ?? {})) {
            if (!DYFAT_FIELDS.includes(key)) warnings.push(`field attribute ${key} ignored`);
        }
    }
    return { header, containers, fields, flow, warnings };
}

/**
 * Parses the `<CUA>` block into per-table arrays of DDIC-component maps.
 *
 * @param {string} xml  the whole `.prog.xml`
 */
function parseCuaBlock(xml) {
    const block = blockOf(xml, 'CUA');
    if (!block) return null;
    const inner = stripOuterTag(block, 'CUA');
    if (!/\S/.test(inner)) return null;
    const node = fxpParse(`<__cua>${inner}</__cua>`).__cua ?? {};
    const map = (v, allow) => asArray(v).map(n => pickFields(n, allow));
    return {
        adm: pickFields(node.ADM ?? {}, ADM_FIELDS),
        sta: map(node.STA?.RSMPE_STAT, STAT_FIELDS),
        fun: map(node.FUN?.RSMPE_FUNT, FUNT_FIELDS),
        pfk: map(node.PFK?.RSMPE_PFK, PFK_FIELDS),
        set: map(node.SET?.RSMPE_STAF, STAF_FIELDS),
        doc: map(node.DOC?.RSMPE_ATRT, ATRT_FIELDS),
        tit: map(node.TIT?.RSMPE_TITT, TITT_FIELDS),
        // Tables we do not (yet) translate — surfacing them beats silently
        // writing an incomplete GUI status.
        unsupported: ['MEN', 'MTX', 'ACT', 'BUT', 'BIV'].filter(t => node[t] !== undefined),
    };
}

// ---------------------------------------------------------------------------
// 5. PUT helpers — thin wrappers around AdtHTTP.request
// ---------------------------------------------------------------------------

/**
 * Resolves the lower-case URL SAP expects for a program.
 * @param {string} name
 */
function programObjectUrl(name) {
    return `/sap/bc/adt/programs/programs/${name.toLowerCase()}`;
}

/**
 * PUTs an XML body at `<objectUrl>/<suffix>?lockHandle=...` with the
 * lockHandle / corrNr / content-type headers and Accept that SAP expects.
 * Any keys in `extraQs` (e.g. `{ screenNumber: '9001' }`) are merged into the
 * final query string alongside the standard lockHandle / corrNr.
 */
async function putXml(client, objectUrl, suffix, body, lockHandle, transport, extraQs) {
    const qs = { lockHandle, ...(extraQs ?? {}) };
    if (transport) qs.corrNr = transport;
    const url = suffix ? `${objectUrl}/${suffix}` : objectUrl;
    try {
        await client.httpClient.request(url, {
            method: 'PUT',
            body,
            qs,
            headers: {
                'content-type': 'application/*',
                Accept: 'application/*',
            },
        });
    } catch (err) {
        if (isNotFound(err)) {
            throw new EndpointUnsupportedError(
                `PUT ${url} → 404 "No suitable resource found". This SAP release does not ` +
                `expose that ADT endpoint.`,
            );
        }
        throw err;
    }
}

/** True when an abap-adt-api error carries HTTP 404. */
function isNotFound(err) {
    const status = err?.response?.status ?? err?.status ?? err?.statusCode;
    if (status === 404) return true;
    return /status code 404/i.test(err?.message ?? '');
}

/** Thrown when a required ADT endpoint simply does not exist on this system. */
class EndpointUnsupportedError extends Error {}

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
 * @param {TextElementPayload[]} elements
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
 * Pushes one textelements category.
 *
 * Deliberately a local copy of `abap-adt-api`'s `setTextElements` so we can add
 * the `sap-language` query parameter — without it SAP writes the English
 * textpool even though the session was opened in Chinese.
 *
 * @param {ADTClient} client
 * @param {string} teUrl       `/sap/bc/adt/textelements/programs/<name>`
 * @param {'symbols'|'selections'|'headings'} category
 * @param {TextElementPayload[]} elements
 * @param {string} lockHandle
 * @param {string} [transport]
 * @param {string} [isoLanguage]
 */
async function putTextElements(client, teUrl, category, elements, lockHandle, transport, isoLanguage) {
    const mediaType = `application/vnd.sap.adt.textelements.${category}.v1`;
    const body = category === 'selections'
        ? formatSelectionElements(elements)
        : formatTextElements(elements, category);
    const qs = { lockHandle };
    if (transport) qs.corrNr = transport;
    if (isoLanguage) qs['sap-language'] = isoLanguage;
    await client.httpClient.request(`${teUrl}/source/${category}`, {
        method: 'PUT',
        body,
        qs,
        headers: {
            'Content-Type': `${mediaType}; charset=UTF-8`,
            Accept: mediaType,
        },
    });
}

async function pushCua(client, objectUrl, cuaXml, lockHandle, transport) {
    await putXml(client, objectUrl, 'cua', cuaXml, lockHandle, transport);
}

async function pushDynpros(client, objectUrl, dynproXmlArray, lockHandle, transport) {
    // Each screen is its own PUT against `<url>/dynpros?screenNumber=...`.
    // The screen number is mandatory — without it SAP writes to screen 0 and
    // silently drops the request.
    for (const xml of dynproXmlArray) {
        const screenMatch = xml.match(/<SCREEN>(\d+)<\/SCREEN>/);
        if (!screenMatch) {
            throw new Error('dynpro <item> is missing <SCREEN>...</SCREEN> — cannot determine screenNumber for PUT');
        }
        await putXml(client, objectUrl, 'dynpros', xml, lockHandle, transport, { screenNumber: screenMatch[1] });
    }
}

// ---------------------------------------------------------------------------
// 6. Classrun fallback — dynpros + GUI status through ABAP
// ---------------------------------------------------------------------------
// `POST /sap/bc/adt/oo/classrun/<NAME>` runs a class that implements
// `IF_OO_ADT_CLASSRUN`. We generate that class, ship it, run it and delete it.

/** Fixed helper-class name — created in the helper package, deleted afterwards. */
const HELPER_CLASS = 'ZCL_ADT_CUA_IMP';

const HELPER_START = '<<<ABAPGIT_PROGXMLPUSH>>>';
const HELPER_END = '<<<ABAPGIT_PROGXMLEND>>>';
const FIELD_SEP = '::';

/** ABAP string literal with `'` doubled. */
function abapStr(v) {
    return `'${String(v ?? '').replace(/'/g, "''")}'`;
}

/** `      ls_x-field = 'value'.` */
function abapAssign(varName, field, value, pad = '      ') {
    return `${pad}${varName}-${field.toLowerCase()} = ${abapStr(value)}.`;
}

/** Emits `CLEAR v.` + assignments + `APPEND v TO t.` for one table row. */
function abapAppendRow(lines, rowVar, tableVar, row, pad = '      ') {
    lines.push(`${pad}CLEAR ${rowVar}.`);
    for (const [f, v] of Object.entries(row)) lines.push(abapAssign(rowVar, f, v, pad));
    lines.push(`${pad}APPEND ${rowVar} TO ${tableVar}.`);
}

/**
 * Generates the body of the `do_dynpros` method: one RPY_DYNPRO_INSERT call
 * per screen, each reporting a `DYNPRO::<screen>::OK|ERR::<detail>` line.
 *
 * The parameter set mirrors abapGit's `deserialize_dynpros`, which drives the
 * same FM:
 *   - `SUPPRESS_EXIST_CHECKS = 'X'` skips the TRDIR/D020S existence checks
 *     (they only run `IF SUPPRESS_EXIST_CHECKS EQ SPACE`), so a re-import no
 *     longer aborts with ALREADY_EXISTS.
 *   - `SUPPRESS_GENERATE` is driven by the screen's own NO_EXECUTE flag, which
 *     is how abapGit decides whether to run `GENERATE DYNPRO`.
 *   - the overlap / minimum-dimension checks are deliberately left ENABLED
 *     (abapGit leaves them on too) so a genuinely broken screen fails loudly.
 */
function generateDynproBody(dynpros, sapLanguage, transport) {
    const P = '      ';
    const L = [];
    // Table-type names follow abapGit's proven declarations (it drives the very
    // same FM): DYCATT_TAB / DYFATC_TAB are table TYPES, so they are used
    // directly instead of `STANDARD TABLE OF rpy_dycatt`. The flow logic uses
    // SWYDYFLOW, whose row component is LINE — identical in shape to the
    // RPY_DYFLOW the FM declares.
    L.push(`${P}DATA: ls_header     TYPE rpy_dyhead,`);
    L.push(`${P}      lt_containers TYPE dycatt_tab,`);
    L.push(`${P}      lt_fields     TYPE dyfatc_tab,`);
    L.push(`${P}      lt_flow       TYPE swydyflow,`);
    L.push(`${P}      ls_container  TYPE rpy_dycatt,`);
    L.push(`${P}      ls_field      TYPE rpy_dyfatc,`);
    L.push(`${P}      ls_flow       TYPE LINE OF swydyflow.`);
    L.push('');

    for (const d of dynpros) {
        const screen = d.header.SCREEN ?? '?';
        const header = { ...d.header };
        if (!header.LANGUAGE) header.LANGUAGE = sapLanguage;
        L.push(`${P}" ---------------- screen ${screen} ----------------`);
        L.push(`${P}CLEAR: ls_header, lt_containers, lt_fields, lt_flow.`);
        for (const [f, v] of Object.entries(header)) L.push(abapAssign('ls_header', f, v, P));
        for (const c of d.containers) abapAppendRow(L, 'ls_container', 'lt_containers', c, P);
        for (const f of d.fields) abapAppendRow(L, 'ls_field', 'lt_fields', f, P);
        for (const line of d.flow) {
            L.push(`${P}CLEAR ls_flow.`);
            L.push(abapAssign('ls_flow', 'LINE', line, P));
            L.push(`${P}APPEND ls_flow TO lt_flow.`);
        }
        L.push('');
        L.push(`${P}CALL FUNCTION 'RPY_DYNPRO_INSERT'`);
        L.push(`${P}  EXPORTING`);
        L.push(`${P}    suppress_exist_checks = 'X'`);
        L.push(`${P}    suppress_generate     = ls_header-no_execute`);
        L.push(`${P}    header                = ls_header`);
        if (transport) {
            L.push(`${P}    corrnum               = ${abapStr(transport)}`);
        } else {
            L.push(`${P}    suppress_corr_checks  = 'X'`);
        }
        L.push(`${P}  TABLES`);
        L.push(`${P}    containers           = lt_containers`);
        L.push(`${P}    fields_to_containers = lt_fields`);
        L.push(`${P}    flow_logic           = lt_flow`);
        L.push(`${P}  EXCEPTIONS`);
        L.push(`${P}    cancelled              = 1`);
        L.push(`${P}    already_exists         = 2`);
        L.push(`${P}    program_not_exists     = 3`);
        L.push(`${P}    not_executed           = 4`);
        L.push(`${P}    missing_required_field = 5`);
        L.push(`${P}    illegal_field_value    = 6`);
        L.push(`${P}    field_not_allowed      = 7`);
        L.push(`${P}    not_generated          = 8`);
        L.push(`${P}    illegal_field_position = 9`);
        L.push(`${P}    OTHERS                 = 10.`);
        // subrc 2 = ALREADY_EXISTS, which abapGit tolerates so that a re-import
        // is a no-op instead of a failure.
        L.push(`${P}IF sy-subrc = 0 OR sy-subrc = 2.`);
        L.push(`${P}  out->write( |DYNPRO${FIELD_SEP}${screen}${FIELD_SEP}OK${FIELD_SEP}subrc={ sy-subrc }| ).`);
        L.push(`${P}ELSE.`);
        L.push(`${P}  out->write( |DYNPRO${FIELD_SEP}${screen}${FIELD_SEP}ERR${FIELD_SEP}{ sy-subrc }${FIELD_SEP}{ sy-msgid }{ sy-msgno } { sy-msgv1 } { sy-msgv2 } { sy-msgv3 } { sy-msgv4 }| ).`);
        L.push(`${P}ENDIF.`);
        L.push('');
    }
    // Drop the trailing blank line so the generated source stays tidy.
    while (L.length && L[L.length - 1] === '') L.pop();
    return L;
}

/**
 * Generates the body of the `do_cua` method: one RS_CUA_INTERNAL_WRITE call.
 *
 * `STATE = 'A'` writes the *active* version straight into EUDB (relid 'CU') and
 * refreshes the active RSMPTEXTS rows, so the GUI status is usable without a
 * separate activation step.
 *
 * Two details are copied from abapGit's `deserialize_cua`, both required:
 *   - `SY-TCODE = 'SE41'` before the call. The FM's elementary checks
 *     (`TRDIR` lookup -> NOT_FOUND, `SUBC CN '1MF'` -> INVALID_DATA, TADIR
 *     master-language mismatch -> e471) only run
 *     `IF sy-tcode <> 'SE41' AND sy-tcode <> 'SEU_INT' AND l_last_prog <> program`.
 *     Note the ADM check (`PERFORM check_adm`) runs BEFORE that guard, so `ADM`
 *     still has to be valid: ACTCODE / PFKCODE must be digits or blank.
 *   - `TR_KEY` carries the real `DEVCLASS` / `OBJ_TYPE` / `OBJ_NAME` plus
 *     `SUB_TYPE = 'CUAD'` / `SUB_NAME = <program>`; the FM uses those to index
 *     the transport- and text-log tables.
 */
function generateCuaBody(cua, program, packageName, sapLanguage) {
    const P = '      ';
    const L = [];
    L.push(`${P}DATA: ls_adm   TYPE rsmpe_adm,`);
    L.push(`${P}      ls_trkey TYPE trkey,`);
    L.push(`${P}      lt_sta   TYPE STANDARD TABLE OF rsmpe_stat WITH DEFAULT KEY,`);
    L.push(`${P}      lt_fun   TYPE STANDARD TABLE OF rsmpe_funt WITH DEFAULT KEY,`);
    L.push(`${P}      lt_men   TYPE STANDARD TABLE OF rsmpe_men  WITH DEFAULT KEY,`);
    L.push(`${P}      lt_mtx   TYPE STANDARD TABLE OF rsmpe_mnlt WITH DEFAULT KEY,`);
    L.push(`${P}      lt_act   TYPE STANDARD TABLE OF rsmpe_act  WITH DEFAULT KEY,`);
    L.push(`${P}      lt_but   TYPE STANDARD TABLE OF rsmpe_but  WITH DEFAULT KEY,`);
    L.push(`${P}      lt_pfk   TYPE STANDARD TABLE OF rsmpe_pfk  WITH DEFAULT KEY,`);
    L.push(`${P}      lt_set   TYPE STANDARD TABLE OF rsmpe_staf WITH DEFAULT KEY,`);
    L.push(`${P}      lt_doc   TYPE STANDARD TABLE OF rsmpe_atrt WITH DEFAULT KEY,`);
    L.push(`${P}      lt_tit   TYPE STANDARD TABLE OF rsmpe_titt WITH DEFAULT KEY,`);
    L.push(`${P}      lt_biv   TYPE STANDARD TABLE OF rsmpe_buts WITH DEFAULT KEY,`);
    L.push(`${P}      ls_sta   TYPE rsmpe_stat,`);
    L.push(`${P}      ls_fun   TYPE rsmpe_funt,`);
    L.push(`${P}      ls_pfk   TYPE rsmpe_pfk,`);
    L.push(`${P}      ls_set   TYPE rsmpe_staf,`);
    L.push(`${P}      ls_doc   TYPE rsmpe_atrt,`);
    L.push(`${P}      ls_tit   TYPE rsmpe_titt.`);
    L.push('');

    const adm = { ...cua.adm };
    if (!adm.MOD_LANGU) adm.MOD_LANGU = sapLanguage;
    L.push(`${P}CLEAR ls_adm.`);
    for (const [f, v] of Object.entries(adm)) L.push(abapAssign('ls_adm', f, v, P));
    L.push('');
    L.push(`${P}CLEAR ls_trkey.`);
    L.push(abapAssign('ls_trkey', 'DEVCLASS', packageName, P));
    L.push(abapAssign('ls_trkey', 'OBJ_TYPE', 'PROG', P));
    L.push(abapAssign('ls_trkey', 'OBJ_NAME', program, P));
    L.push(abapAssign('ls_trkey', 'SUB_TYPE', 'CUAD', P));
    L.push(abapAssign('ls_trkey', 'SUB_NAME', program, P));
    L.push('');
    for (const r of cua.sta) abapAppendRow(L, 'ls_sta', 'lt_sta', r, P);
    for (const r of cua.fun) abapAppendRow(L, 'ls_fun', 'lt_fun', r, P);
    for (const r of cua.pfk) abapAppendRow(L, 'ls_pfk', 'lt_pfk', r, P);
    for (const r of cua.set) abapAppendRow(L, 'ls_set', 'lt_set', r, P);
    for (const r of cua.doc) abapAppendRow(L, 'ls_doc', 'lt_doc', r, P);
    for (const r of cua.tit) abapAppendRow(L, 'ls_tit', 'lt_tit', r, P);
    L.push('');
    // See the method doc: this is what abapGit does to skip the FM's
    // TRDIR / SUBC / master-language consistency checks.
    L.push(`${P}sy-tcode = 'SE41' ##WRITE_OK.`);
    L.push(`${P}CALL FUNCTION 'RS_CUA_INTERNAL_WRITE'`);
    L.push(`${P}  EXPORTING`);
    L.push(`${P}    program  = ${abapStr(program)}`);
    L.push(`${P}    language = ${abapStr(sapLanguage)}`);
    L.push(`${P}    tr_key   = ls_trkey`);
    L.push(`${P}    adm      = ls_adm`);
    L.push(`${P}    state    = 'A'`);
    L.push(`${P}  TABLES`);
    L.push(`${P}    sta = lt_sta`);
    L.push(`${P}    fun = lt_fun`);
    L.push(`${P}    men = lt_men`);
    L.push(`${P}    mtx = lt_mtx`);
    L.push(`${P}    act = lt_act`);
    L.push(`${P}    but = lt_but`);
    L.push(`${P}    pfk = lt_pfk`);
    L.push(`${P}    set = lt_set`);
    L.push(`${P}    doc = lt_doc`);
    L.push(`${P}    tit = lt_tit`);
    L.push(`${P}    biv = lt_biv`);
    L.push(`${P}  EXCEPTIONS`);
    L.push(`${P}    not_found    = 1`);
    L.push(`${P}    invalid_data = 2`);
    L.push(`${P}    OTHERS       = 3.`);
    const statusList = (cua.sta.map(s => s.CODE).filter(Boolean).join(',') || 'CUA');
    L.push(`${P}IF sy-subrc = 0.`);
    L.push(`${P}  out->write( 'CUA${FIELD_SEP}${statusList}${FIELD_SEP}OK${FIELD_SEP}' ).`);
    L.push(`${P}ELSE.`);
    L.push(`${P}  out->write( |CUA${FIELD_SEP}${statusList}${FIELD_SEP}ERR${FIELD_SEP}{ sy-subrc }${FIELD_SEP}{ sy-msgid }{ sy-msgno } { sy-msgv1 } { sy-msgv2 } { sy-msgv3 } { sy-msgv4 }| ).`);
    L.push(`${P}ENDIF.`);
    return L;
}

/**
 * Assembles the complete ABAP class source.
 *
 * @param {{ program: string, packageName: string, sapLanguage: string,
 *           dynpros: any[], cua: any|null, transport?: string }} opts
 * @returns {string}
 */
function generateClassrunSource({ program, packageName, sapLanguage, dynpros, cua, transport }) {
    /** @type {{name:string, body:string[]}[]} */
    const sections = [];
    if (dynpros.length > 0) {
        sections.push({ name: 'do_dynpros', body: generateDynproBody(dynpros, sapLanguage, transport) });
    }
    if (cua) {
        sections.push({ name: 'do_cua', body: generateCuaBody(cua, program, packageName, sapLanguage) });
    }

    const cls = HELPER_CLASS.toLowerCase();
    const L = [];
    L.push(`CLASS ${cls} DEFINITION`);
    L.push(`  PUBLIC`);
    L.push(`  FINAL`);
    L.push(`  CREATE PUBLIC.`);
    L.push(``);
    L.push(`  PUBLIC SECTION.`);
    L.push(`    INTERFACES if_oo_adt_classrun.`);
    L.push(``);
    L.push(`  PRIVATE SECTION.`);
    for (const s of sections) {
        L.push(`    METHODS ${s.name} IMPORTING out TYPE REF TO if_oo_adt_classrun_out.`);
    }
    L.push(`ENDCLASS.`);
    L.push(``);
    L.push(`CLASS ${cls} IMPLEMENTATION.`);
    L.push(``);
    L.push(`  METHOD if_oo_adt_classrun~main.`);
    L.push(`    out->write( '${HELPER_START}' ).`);
    for (const s of sections) L.push(`    ${s.name}( out = out ).`);
    L.push(`    COMMIT WORK AND WAIT.`);
    L.push(`    out->write( '${HELPER_END}' ).`);
    L.push(`  ENDMETHOD.`);
    for (const s of sections) {
        L.push(``);
        L.push(`  METHOD ${s.name}.`);
        L.push(...s.body);
        L.push(`  ENDMETHOD.`);
    }
    L.push(`ENDCLASS.`);
    return L.join('\n');
}

/** `/sap/bc/adt/oo/classes/<name>` */
function classObjectUrl(name) {
    return `/sap/bc/adt/oo/classes/${name.toLowerCase()}`;
}

/**
 * Best-effort removal of a helper class left over by an earlier run.
 * A missing object is not an error.
 */
async function deleteHelperClass(ctx, name, transport) {
    const url = classObjectUrl(name);
    let lock;
    try {
        lock = await ctx.client.lock(url);
    } catch {
        return false; // does not exist (or is locked by someone else) — nothing to do
    }
    try {
        await ctx.client.deleteObject(url, lock.LOCK_HANDLE, transport);
        return true;
    } catch {
        try { await ctx.client.unLock(url, lock.LOCK_HANDLE); } catch { /* ignore */ }
        return false;
    }
}

/**
 * Creates the throw-away class, uploads the generated source, activates it and
 * runs it. Returns the parsed console output.
 *
 * @returns {Promise<{ ok: boolean, output: string, results: any[], error?: string,
 *                     activation?: any, deleted?: boolean }>}
 */
async function runMetadataClass(ctx, source, { packageName, transport }) {
    const url = classObjectUrl(HELPER_CLASS);
    const deletedPreviously = await deleteHelperClass(ctx, HELPER_CLASS, transport);

    await ctx.client.createObject(
        'CLAS/OC',
        HELPER_CLASS,
        ctx.helperPackage,
        'abap-cli prog-xml helper (auto-deleted)',
        `/sap/bc/adt/packages/${ctx.helperPackage.toLowerCase()}`,
        ctx.owner,
        transport,
    );

    const result = { ok: false, output: '', results: [], deletedPreviously };
    try {
        // --- upload source ---
        const lock = await ctx.client.lock(url);
        try {
            await ctx.client.setObjectSource(`${url}/source/main`, source, lock.LOCK_HANDLE, transport);
        } finally {
            try { await ctx.client.unLock(url, lock.LOCK_HANDLE); } catch { /* ignore */ }
        }

        // --- activate ---
        // NOTE: do NOT send `adtcore:parentUri` here. Pointing a class at
        // itself makes SAP answer HTTP 200 with
        //   <chkl:properties activationExecuted="false"/>
        // i.e. a silent no-op, leaving the uploaded source in the inactive
        // version (which then breaks `classrun`). Verified against S4D:
        // same payload minus parentUri reports activationExecuted="true".
        const act = await ctx.client.activate([{
            'adtcore:uri': url,
            'adtcore:type': 'CLAS/OC',
            'adtcore:name': HELPER_CLASS,
        }], false);
        result.activation = act;
        if (!act.success) {
            const msgs = (act.messages ?? []).map(m => `${m.type ?? 'E'}${m.id ?? ''}${m.number ?? ''} ${m.shortText ?? ''}`.trim());
            result.error = `helper class did not activate: ${msgs.join('; ') || 'unknown activation error'}`;
            return result;
        }

        // --- execute ---
        // A *warm* ADT session cannot run the class it has just created: the
        // classrun service reads a stale definition and answers
        //   "Class does not implement if_oo_adt_classrun~main method!"
        // `AdtHTTP.login()` clears the session cookie and re-authenticates, so
        // calling it here gives the fresh session classrun needs.
        // (Session *type* is irrelevant — a brand-new stateful client works too.)
        await ctx.client.login();
        result.output = await ctx.client.runClass(HELPER_CLASS);
        const parsed = parseClassrunOutput(result.output);
        result.results = parsed.results;
        result.startMarkerSeen = parsed.startMarkerSeen;
        result.ok = parsed.results.length > 0 && parsed.results.every(r => r.status === 'OK');
        if (!result.ok) {
            result.error = parsed.results
                .filter(r => r.status !== 'OK')
                .map(r => `${r.kind} ${r.id}: subrc ${r.detail}`)
                .join('; ')
                || (parsed.startMarkerSeen ? 'helper class produced no result lines' : 'helper class did not run');
        }
        return result;
    } finally {
        if (ctx.keepHelper) {
            result.deleted = false;
        } else {
            result.deleted = await deleteHelperClass(ctx, HELPER_CLASS, transport);
        }
    }
}

/**
 * Extracts the `KIND::id::status::detail` lines the generated class prints.
 *
 * @param {string} raw console output of the classrun
 */
function parseClassrunOutput(raw) {
    const text = String(raw ?? '');
    const results = [];
    let startMarkerSeen = false;
    let endMarkerSeen = false;
    for (const line of text.split(/\r?\n/)) {
        const t = line.trim();
        if (t.includes(HELPER_START)) startMarkerSeen = true;
        if (t.includes(HELPER_END)) endMarkerSeen = true;
        if (!/^(DYNPRO|CUA)::/.test(t)) continue;
        const parts = t.split(FIELD_SEP);
        // DYNPRO::<screen>::OK::  /  CUA::<status>::ERR::<subrc>::<msg>
        results.push({
            kind: parts[0],
            id: parts[1] ?? '',
            status: parts[2] ?? '',
            detail: parts.slice(3).join(FIELD_SEP),
        });
    }
    return { results, startMarkerSeen, endMarkerSeen, raw: text };
}

// ---------------------------------------------------------------------------
// 7. Per-file flow
// ---------------------------------------------------------------------------

/**
 * Main per-file operation.
 *
 *  read & parse → resolve language → push textpool → push CUA/dynpro (ADT, or
 *  the ABAP classrun fallback when those endpoints are absent) → activate.
 *
 * @param {object} ctx
 * @param {ADTClient} ctx.client
 * @param {string}   ctx.transport
 * @param {string}   [ctx.language]      login language (ISO)
 * @param {string}   [ctx.packageName]   package from .abap.json
 * @param {string}   [ctx.owner]         SAP user
 * @param {string}   [ctx.helperPackage] package for the throw-away class
 * @param {boolean}  [ctx.keepHelper]
 * @param {boolean}  [ctx.classrun]      allow the ABAP-side fallback
 * @returns {Promise<object>}
 */
async function pushOne(ctx, file) {
    const stages = [];
    const log = s => { stages.push(s); };
    const raw = await fs.readFile(file, 'utf8');
    const xml = normaliseXml(raw);
    const parsedDoc = fxpParse(xml);
    const valuesRoot = parsedDoc?.abapGit?.['asx:abap']?.['asx:values']
        ?? parsedDoc?.['asx:abap']?.['asx:values'];
    const progdir = valuesRoot?.PROGDIR;
    if (!progdir?.NAME) {
        throw new Error(`No <PROGDIR><NAME> in ${file}; nothing to push.`);
    }
    const objectName = String(progdir.NAME).toUpperCase().trim();
    const objectUrl = programObjectUrl(objectName);
    const runPackage = ctx.packageName || '$TMP';

    // ---- language ----------------------------------------------------------
    const i18nInner = blockOf(xml, 'I18N_TPOOL');
    const rawLanguage = languageFromTextpool(i18nInner)
        || languageFromDynpros(xml)
        || ctx.language
        || 'E';
    const isoLanguage = mapSapLanguage(rawLanguage, 'EN');
    const sapLanguage = toSapLanguageCode(rawLanguage, 'E');
    log(`parsed ${path.basename(file)} -> ${objectName} | language ${rawLanguage} (${isoLanguage})`);

    // ---- CUA / DYNPROS / TEXTPOOL -----------------------------------------
    const cua = parseCuaBlock(xml);
    const dynproItems = repeatedBlocks(xml, 'DYNPROS', 'item');
    const dynpros = dynproItems.map(parseDynproItem);
    for (const d of dynpros) for (const w of d.warnings) log(w);
    if (cua) {
        log(`cua: ${cua.sta.length} status(es), ${cua.fun.length} function(s), ${cua.pfk.length} PF key(s), ` +
            `${cua.set.length} set(s), ${cua.doc.length} doc(s), ${cua.tit.length} title(s)`);
        for (const t of cua.unsupported) log(`cua: WARNING — <${t}> table is not imported (not supported yet)`);
    } else {
        log('cua: empty, skipped');
    }
    if (dynpros.length > 0) {
        // The screen's own processing logic ("窗口代码" — PBO/PAI plus the
        // MODULE statements) lives in
        //   <DYNPROS><item><FLOW_LOGIC><RPY_DYFLOW><LINE>…
        // and is handed to RPY_DYNPRO_INSERT as the `flow_logic` table. Printing
        // containers/fields/flow_logic counts here makes it visible that the
        // flow logic really travelled with the screen.
        const desc = dynpros.map(d => `${d.header.SCREEN} [containers=${d.containers.length}, ` +
            `fields=${d.fields.length}, flow_logic=${d.flow.length} line(s)]`).join(', ');
        log(`dynpros: ${dynpros.length} screen(s) (${desc})`);
    } else log('dynpros: empty, skipped');

    let tpool = { symbols: [], selections: [], headings: [], warnings: [] };
    if (i18nInner) {
        tpool = groupTextpool(i18nInner);
        const tCount = tpool.symbols.length + tpool.selections.length + tpool.headings.length;
        if (tCount > 0) log(`textpool: ${tCount} element(s) prepared`);
        else log('textpool: empty, skipped');
    } else {
        log('textpool: empty, skipped');
    }

    const hasTextpool = tpool.symbols.length + tpool.selections.length + tpool.headings.length > 0;
    if (!cua && dynpros.length === 0 && !hasTextpool) {
        log(`nothing to push for ${objectName} (no CUA/DYNPROS/textpool sections)`);
        return { object: objectName, language: isoLanguage, stages, unsupported: [] };
    }

    // -------------------------------------------------------------------
    // SAP drops the edit lock as soon as a modifying request FAILS (e.g. the
    // 404 from an endpoint this release does not have). Every stage therefore
    // takes its own fresh lock, and the stages that may be missing run last.
    // -------------------------------------------------------------------
    const unsupported = [];
    const skippedStages = [];

    /** Locks `lockUrl`, runs `fn(lockHandle)`, then always tries to unlock. */
    const withLock = async (lockUrl, fn) => {
        const l = await ctx.client.lock(lockUrl);
        try {
            return await fn(l.LOCK_HANDLE);
        } finally {
            try {
                await ctx.client.unLock(lockUrl, l.LOCK_HANDLE);
            } catch {
                /* the lock may already be gone (see note above) — that is fine */
            }
        }
    };

    /** Runs one stage, degrading gracefully when its ADT endpoint is absent. */
    const stage = async (name, lockUrl, fn, okMsg) => {
        try {
            await withLock(lockUrl, fn);
            log(okMsg);
            return true;
        } catch (err) {
            if (err instanceof EndpointUnsupportedError) {
                unsupported.push(name);
                log(`${name}: SKIPPED — ADT endpoint not available on this system`);
                return false;
            }
            throw err;
        }
    };

    // --- 1. text elements (language-scoped) --------------------------------
    // The text-elements service insists on a lock taken on ITS OWN url — a lock
    // on the program object is rejected with
    // "Resource REPT <name> is not locked (invalid lock handle: …)".
    const teUrl = `/sap/bc/adt/textelements/programs/${objectName.toLowerCase()}`;
    let textpoolPushed = false;
    for (const [category, count] of [
        ['symbols', tpool.symbols.length],
        ['selections', tpool.selections.length],
        ['headings', tpool.headings.length],
    ]) {
        if (count === 0) continue;
        const pushed = await stage(category, teUrl, lockHandle => putTextElements(
            ctx.client, teUrl, category, tpool[category], lockHandle, ctx.transport, isoLanguage,
        ), `textpool[${category}]: pushed ${count} (sap-language=${isoLanguage})`);
        textpoolPushed = textpoolPushed || pushed;
    }
    for (const w of (tpool.warnings ?? [])) log(w);

    // --- 2. CUA / dynpro through the native ADT endpoints (if present) -----
    let cuaPushed = false;
    let dynprosPushed = false;
    if (cua) {
        cuaPushed = await stage('cua', objectUrl,
            lockHandle => pushCua(ctx.client, objectUrl, wrapCua(blockOf(xml, 'CUA')), lockHandle, ctx.transport),
            'cua: pushed via ADT');
    }
    if (dynpros.length > 0) {
        const bodies = dynproItems.map(item => wrapDynpro(item));
        dynprosPushed = await stage('dynpros', objectUrl,
            lockHandle => pushDynpros(ctx.client, objectUrl, bodies, lockHandle, ctx.transport),
            `dynpros: pushed ${dynpros.length} screen(s) via ADT`);
    }

    // --- 3. classrun fallback for whatever the ADT endpoints could not do --
    const needFallback = (cua && !cuaPushed) || (dynpros.length > 0 && !dynprosPushed);
    let classrunResult = null;
    if (needFallback) {
        if (!ctx.classrun) {
            skippedStages.push('classrun (disabled)');
            log('classrun: SKIPPED — ABAP-side importer disabled (ABAP_PROG_XML_CLASSRUN=0)');
        } else {
            const source = generateClassrunSource({
                program: objectName,
                packageName: runPackage,
                sapLanguage,
                dynpros: dynprosPushed ? [] : dynpros,
                cua: (cua && !cuaPushed) ? cua : null,
                transport: ctx.transport,
            });
            classrunResult = await runMetadataClass(ctx, source, {
                packageName: runPackage,
                transport: ctx.transport,
            });
            for (const r of classrunResult.results) {
                log(`${r.kind.toLowerCase()}[${r.id}]: ${r.status === 'OK' ? 'pushed' : 'FAILED'} (ABAP) ${r.detail}`);
            }
            log(classrunResult.ok
                ? `classrun: pushed via ${HELPER_CLASS}${classrunResult.deleted ? ' (helper class removed)' : ''}`
                : `classrun: FAILED — ${classrunResult.error}`);
            if (!classrunResult.ok) {
                stages.push(`classrun raw output: ${String(classrunResult.output ?? '').replace(/\s+/g, ' ').slice(0, 500)}`);
            }
        }
    }

    // --- 4. activate the program (and its textpool) -----------------------
    // As with the helper class: a self-referencing `adtcore:parentUri` makes
    // the activation endpoint return 200 with activationExecuted="false".
    //
    // The textpool needs its OWN reference. Activating `PROG/P` alone leaves
    // the text elements in the inactive version (verified on S4DEV), so the
    // program is activated together with `PROG/PX` on the text-elements URL.
    const refs = [
        {
            'adtcore:uri': objectUrl,
            'adtcore:type': 'PROG/P',
            'adtcore:name': objectName,
        },
    ];
    if (textpoolPushed) {
        refs.push({
            'adtcore:uri': teUrl,
            'adtcore:type': 'PROG/PX',
            'adtcore:name': objectName,
        });
    }
    /** Activates one batch and reports whether SAP really executed it. */
    const activate = async (batch, what) => {
        try {
            const res = await ctx.client.activate(batch, false);
            if (res?.success !== false) {
                log(`activate: ${what} complete`);
                return true;
            }
            const detail = (res?.messages ?? []).map(m => m.shortText).filter(Boolean).join(' / ');
            log(`activate: ${what} refused${detail ? ` — ${detail}` : ''}`);
            return false;
        } catch (err) {
            log(`activate: ${what} skipped (${err?.message ?? err})`);
            return false;
        }
    };

    const activated = await activate(refs, textpoolPushed ? 'program + text elements' : 'program');
    // A refused batch (program locked, source not compilable yet, …) would keep
    // the text elements inactive as well, so they get an isolated second try —
    // the `PROG/PX` reference on its own is enough to flip them to `active`.
    if (textpoolPushed && !activated) {
        await activate([{ 'adtcore:uri': teUrl, 'adtcore:type': 'PROG/PX', 'adtcore:name': objectName }],
            'text elements');
    }

    const ok = !needFallback || (classrunResult?.ok === true);
    return {
        object: objectName,
        language: isoLanguage,
        sapLanguage,
        stages,
        unsupported,
        skippedStages,
        classrun: classrunResult
            ? { ok: classrunResult.ok, error: classrunResult.error, results: classrunResult.results }
            : undefined,
        ok,
    };
}

// ---------------------------------------------------------------------------
// 8. Extension export — commander wired by the abap-cli loader
// ---------------------------------------------------------------------------

export default /** @type {import('abap-cli/dist/src/abap_cli/extensions/types.js').CommandExtension} */ ({
    type: 'command',
    name: 'prog-xml-push',
    description:
        'Push abapGit *.prog.xml metadata (textpool in the file\'s own language, ' +
        'DYNPROS, CUA) into SAP. Use after `abap push` because native abap-git ' +
        'does not push this data.',
    command: 'prog-xml-push [files...]',
    /**
     * NOTE on options: the host registers extensions with a bare
     * `program.command(ext.command)` call, and commander rejects any option
     * that is not declared. So `--transport` / `--pretty-json` are parsed from
     * the raw argv below and from the environment instead
     * (`ABAP_TRANSPORT`, `ABAP_PRETTY_JSON`).
     * @param {{ command: string, argv: string[] }} ctx
     * @param {{ transport?: string, prettyJson?: boolean }} opts
     * @param  {...string} files
     */
    async action(ctx, opts, ...args) {
        // The host's `_registerCommandExtension` forwards `args.slice(0, -2)`
        // verbatim, but commander hands a variadic positional (`[files...]`)
        // over as a single ARRAY. Flatten both shapes here.
        const files = (args.length === 1 && Array.isArray(args[0]) ? args[0] : args)
            .filter(f => typeof f === 'string' && f.length > 0);
        if (files.length === 0) {
            console.error('Usage: abap prog-xml-push <file1.prog.xml> [file2.prog.xml ...]');
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

        const classrun = process.env.ABAP_PROG_XML_CLASSRUN !== '0';
        const helperPackage = process.env.ABAP_HELPER_PACKAGE || '$TMP';
        const keepHelper = process.env.ABAP_KEEP_HELPER === '1';

        // The ADT session language is fixed at login, and the textpool we push
        // is language-scoped, so one client per language is required. Files are
        // grouped lazily — in practice a repo uses a single master language.
        const clientCache = new Map();
        /** @param {string} iso */
        const clientFor = async (iso) => {
            const lang = iso || creds.language || 'EN';
            if (!clientCache.has(lang)) {
                const c = new ADTClient(
                    creds.url,
                    creds.username,
                    creds.password,
                    creds.client,
                    lang,
                    insecure ? createSSLConfig(true) : undefined,
                );
                c.stateful = 'stateful';
                await c.login();
                clientCache.set(lang, c);
            }
            return clientCache.get(lang);
        };

        const results = [];
        try {
            for (const f of files) {
                try {
                    // Pre-read the language so we can pick the matching session.
                    let isoHint = creds.language || 'EN';
                    try {
                        const head = await fs.readFile(f, 'utf8');
                        const norm = normaliseXml(head);
                        const rawLang = languageFromTextpool(blockOf(norm, 'I18N_TPOOL'))
                            || languageFromDynpros(norm);
                        if (rawLang) isoHint = mapSapLanguage(rawLang, isoHint);
                    } catch { /* pushOne will surface the real error */ }

                    const client = await clientFor(isoHint);
                    const r = await pushOne({
                        client,
                        transport,
                        language: isoHint,
                        packageName: abapJson.package,
                        owner: creds.username,
                        helperPackage,
                        keepHelper,
                        classrun,
                    }, f);
                    results.push({ ok: r.ok !== false, file: f, ...r });
                } catch (err) {
                    results.push({
                        ok: false,
                        file: f,
                        error: err?.message ?? String(err),
                        ...(process.env.ABAP_DEBUG ? { stack: err?.stack } : {}),
                    });
                }
            }
        } finally {
            for (const c of clientCache.values()) {
                try { await c.logout(); } catch { /* ignore */ }
            }
        }

        if (prettyJson) {
            console.log(JSON.stringify(results, null, 2));
            // Surface failures as a non-zero exit code for Java callers that
            // only inspect exitCode (see prog-xml-verify for the reference).
            if (results.some(r => !r.ok)) process.exitCode = 1;
        } else {
            for (const r of results) {
                if (r.ok) {
                    console.log(`  ok    ${r.file} -> ${r.object} [${r.language ?? '?'}]`);
                    for (const s of r.stages) console.log(`         | ${s}`);
                } else {
                    console.error(`  FAIL  ${r.file}: ${r.error ?? 'see stages below'}`);
                    for (const s of (r.stages ?? [])) console.error(`         | ${s}`);
                    if (r.classrun) {
                        console.error(`         | classrun: ${JSON.stringify(r.classrun)}`);
                    }
                }
            }
            const failed = results.filter(r => !r.ok).length;
            const unsupported = [...new Set(results.flatMap(r => r.unsupported ?? []))];
            if (unsupported.length > 0 && classrun) {
                console.error(
                    `\nprog-xml-push: note — the native ADT endpoints ${unsupported.join(' + ')} ` +
                    'do not exist on this SAP system;\n' +
                    '  the ABAP-side importer (classrun) was used instead.',
                );
            }
            if (failed > 0) {
                process.exitCode = 1;
            } else {
                console.log(`\nprog-xml-push: ${results.length} file(s) processed, all ok.`);
            }
        }
    },
});

export {
    mapSapLanguage,
    toSapLanguageCode,
    groupTextpool,
    parseDynproItem,
    parseCuaBlock,
    generateClassrunSource,
    parseClassrunOutput,
    pushOne,
};
