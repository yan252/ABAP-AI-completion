/**
 * abap-cli command extension: `ddic-xml-create`
 *
 * Creates DDIC objects in SAP from abapGit-serialized XML files:
 *
 *   *.tabl.xml  → TABL
 *       <DD02V><TABCLASS>INTTAB</TABCLASS> → TABL/DS → POST /sap/bc/adt/ddic/structures
 *       <DD02V><TABCLASS>TRANSP</TABCLASS> → TABL/DT → POST /sap/bc/adt/ddic/tables
 *   *.ttyp.xml  → TTYP (table type)           → POST /sap/bc/adt/ddic/tabletypes
 *
 * Why this extension exists: abap-cli's built-in `abap create TABL|TTYP` only
 * POSTs an empty shell and never fills the content — a shell without columns
 * cannot be activated, so the object never shows up in SE11.
 *
 * Both ADT flows below were validated by hand against S4DEV:
 *
 *   TABL: POST <collection>   (blue:blueSource, adtcore:type="TABL/DT|DS",
 *                              Content-Type: application/*)
 *         → client.lock(url)
 *         → PUT url/source/main?lockHandle=…  (DDL, text/plain; charset=UTF-8)
 *         → client.unLock(url, handle)
 *         → client.activate(name, url)
 *
 *   TTYP: POST /sap/bc/adt/ddic/tabletypes
 *                             (tabletype:tableType, adtcore:type="TTYP/TO")
 *         → client.lock(url)
 *         → GET url (Accept: application/*)      ← the *canonical* XML
 *         → replace <ttyp:rowType> (empty shell = predefinedAbapType CHAR(1))
 *           with <ttyp:typeKind>dictionaryType</ttyp:typeKind> +
 *           <ttyp:typeName>ROWTYPE</ttyp:typeName>
 *         → PUT url?lockHandle=… (Content-Type: application/*)
 *         → client.unLock(url, handle)
 *         → client.activate(name, url)
 *
 * ORDER MATTERS: a table type's row type is a table/structure that must
 * already exist and be active. The caller therefore has to pass every
 * `*.tabl.xml` file BEFORE any `*.ttyp.xml` file
 * (MultiTabTemplateImportService does exactly that in its DDIC step).
 *
 * Usage:
 *   abap ddic-xml-create repos/<pkg>/src/*.tabl.xml repos/<pkg>/src/*.ttyp.xml [--transport <tr>]
 *
 * Locking: each object is locked at its own URL and unlocked in a finally
 * block, so a mid-stage failure cannot strand an editor session.
 */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import * as os from 'node:os';
import * as fxp from 'fast-xml-parser';
import { ADTClient, createSSLConfig } from 'abap-adt-api';

// ---------------------------------------------------------------------------
// 1. Config / credential loading (mirrors prog-xml-create.mjs)
// ---------------------------------------------------------------------------

const CRED_KEYCHAIN_HOST = 'abap-cli';

async function loadAbapJson() {
    const p = path.join(process.cwd(), '.abap.json');
    const json = JSON.parse(await fs.readFile(p, 'utf8'));
    if (!json.system || !json.package) {
        throw new Error(`.abap.json must contain { system, package }`);
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
    // SAP Preference-page overrides (set by the Eclipse plugin). Each wins
    // over whatever the profile stores.
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
// 1.5 Language + XML helpers
// ---------------------------------------------------------------------------

/** SAP internal logon-language code → ISO 639-1 code (see prog-xml-create.mjs). */
const SAP_LANG_TO_ISO = {
    '1': 'ZH', E: 'EN', D: 'DE', F: 'FR', S: 'ES', J: 'JA', K: 'KO',
    R: 'RU', T: 'TH', B: 'PT', I: 'IT', N: 'NL', P: 'PL', A: 'AR',
    H: 'HE', V: 'SV', U: 'FI', O: 'NO', L: 'DA', C: 'CS', M: 'HU',
    G: 'EL', Q: 'TR', Z: 'SK', Y: 'SL', X: 'BG', '0': 'RO', '7': 'HR',
    '4': 'SR', '6': 'ET', '8': 'LV', '9': 'LT', W: 'ZF',
};

/** abapGit `<LANGUAGE>` / `<LANGU>` (SAP internal code) → ISO-639 code. */
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

/** `<DD03P>`-style leaf → trimmed string ('' when absent). */
function str(v) {
    if (v === undefined || v === null) return '';
    if (typeof v === 'object') return '';       // plain-text leaf expected
    return String(v).trim();
}

function xmlAttr(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
        .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/** DDL string literal → single quotes doubled. */
function ddlText(s) {
    return String(s).replace(/'/g, "''");
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

/** First `<DD02_TEXTS><item><DDTEXT>` (the object description). */
function tableDescription(valuesRoot) {
    for (const item of asArray(valuesRoot?.DD02_TEXTS?.item ?? valuesRoot?.DD02_TEXTS)) {
        const txt = str(item?.DDTEXT);
        if (txt) return txt;
    }
    return '';
}

/** `<I18N_LANGS><LANGU>` → the object's master language (SAP internal code). */
function declaredLanguage(valuesRoot) {
    for (const l of asArray(valuesRoot?.I18N_LANGS?.LANGU ?? valuesRoot?.I18N_LANGS)) {
        const code = str(l);
        if (code) return code;
    }
    return '';
}

// ---------------------------------------------------------------------------
// 2. *.tabl.xml  →  TABL/DS (structure) or TABL/DT (transparent table)
// ---------------------------------------------------------------------------

const TABL_ENDPOINTS = {
    DS: { coll: 'structures', label: 'Structure (结构)' },
    DT: { coll: 'tables', label: 'Transparent table (透明表)' },
};

/** TABCLASS (DD02V) → ADT TABL sub-type. */
const TABCLASS_TO_KIND = {
    INTTAB: 'DS',
    TRANSP: 'DT',
};

/**
 * Parses a `*.tabl.xml` and returns everything needed to create the object.
 *
 * @returns {Promise<{file:string, objectName:string, kind:'DS'|'DT',
 *   tabClass:string, description:string, language:string,
 *   fields:Array<{name:string, type:string, key:boolean, notNull:boolean}>}>}
 */
async function parseTablXml(file) {
    const xml = await fs.readFile(file, 'utf8');
    const valuesRoot = valuesRootOf(xml);
    const dd02v = valuesRoot?.DD02V;
    const objectName = str(dd02v?.TABNAME).toUpperCase();
    if (!objectName) throw new Error(`No <DD02V><TABNAME> in ${file}`);

    const tabClass = str(dd02v?.TABCLASS).toUpperCase();
    const kind = TABCLASS_TO_KIND[tabClass];
    if (!kind) {
        throw new Error(`Unsupported <TABCLASS>'${tabClass}' for ${objectName} in ${file} `
            + `(only INTTAB → structure and TRANSP → transparent table are supported)`);
    }

    const fields = [];
    for (const f of asArray(valuesRoot?.DD03P_TABLE?.DD03P)) {
        const name = str(f?.FIELDNAME).toUpperCase();
        if (!name) continue;
        const rollName = str(f?.ROLLNAME).toUpperCase();
        if (!rollName) {
            throw new Error(`Field ${name} of ${objectName} has no <ROLLNAME> — `
                + `inline (built-in) field types are not supported by ddic-xml-create`);
        }
        fields.push({
            name,
            type: rollName,
            key: str(f?.KEYFLAG).toUpperCase() === 'X',
            notNull: str(f?.NOTNULL).toUpperCase() === 'X',
        });
    }
    if (fields.length === 0) {
        throw new Error(`No <DD03P_TABLE><DD03P> field entries for ${objectName} in ${file}`);
    }

    return {
        file,
        objectName,
        kind,
        tabClass,
        description: tableDescription(valuesRoot) || objectName,
        language: mapSapLanguage(declaredLanguage(valuesRoot)),
        fields,
    };
}

/** Builds the ABAP DDL source for a structure (TABL/DS). */
function buildStructureDdl(parsed) {
    const lines = [
        `@EndUserText.label : '${ddlText(parsed.description)}'`,
        `@AbapCatalog.enhancement.category : #NOT_EXTENSIBLE`,
        `define structure ${parsed.objectName.toLowerCase()} {`,
    ];
    for (const f of parsed.fields) {
        lines.push(`  ${f.name.toLowerCase()} : ${f.type.toLowerCase()};`);
    }
    lines.push('}');
    return lines.join('\n');
}

/** Builds the ABAP DDL source for a transparent table (TABL/DT). */
function buildTableDdl(parsed) {
    const lines = [
        `@EndUserText.label : '${ddlText(parsed.description)}'`,
        `@AbapCatalog.enhancement.category : #EXTENSIBLE_CHARACTER_NUMERIC`,
        `@AbapCatalog.tableCategory : #TRANSPARENT`,
        `@AbapCatalog.deliveryClass : #A`,
        `@AbapCatalog.dataMaintenance : #ALLOWED`,
        `define table ${parsed.objectName.toLowerCase()} {`,
    ];
    for (const f of parsed.fields) {
        const key = f.key ? 'key ' : '';
        const notNull = f.notNull ? ' not null' : '';
        lines.push(`  ${key}${f.name.toLowerCase()} : ${f.type.toLowerCase()}${notNull};`);
    }
    lines.push('}');
    return lines.join('\n');
}

/** blue:blueSource shell used to instantiate a TABL (DT or DS). */
function tablShell(kind, objectName, description, language, packageName, responsible) {
    return `<?xml version="1.0" encoding="UTF-8"?>
<blue:blueSource xmlns:blue="http://www.sap.com/wbobj/blue"
  xmlns:adtcore="http://www.sap.com/adt/core"
  adtcore:description="${xmlAttr(description)}" adtcore:name="${xmlAttr(objectName)}"
  adtcore:type="TABL/${kind}" adtcore:language="${xmlAttr(language)}"
  adtcore:masterLanguage="${xmlAttr(language)}"
  adtcore:responsible="${xmlAttr(responsible)}">
  <adtcore:packageRef adtcore:name="${xmlAttr(packageName)}"/>
</blue:blueSource>`;
}

async function exists(client, url) {
    try {
        await client.httpClient.request(url, { method: 'GET', headers: { Accept: 'application/*' } });
        return true;
    } catch (e) {
        const status = e?.err ?? e?.response?.status ?? e?.status ?? e?.statusCode;
        if (status === 404 || e?.type === 'ExceptionResourceNotFound') return false;
        // Any other error (5xx / network) is surfaced instead of silently
        // re-creating: a duplicate shell would be far worse than a clear error.
        throw new Error(`Existence check failed for ${url}: ${e?.message ?? e}`);
    }
}

async function createTabl(client, ctx, parsed) {
    const { coll, label } = TABL_ENDPOINTS[parsed.kind];
    const url = `/sap/bc/adt/ddic/${coll}/${parsed.objectName.toLowerCase()}`;
    const stages = [];
    const log = s => stages.push(s);
    log(`parsed: NAME=${parsed.objectName} TABCLASS=${parsed.tabClass} → TABL/${parsed.kind} (${label})`
        + ` fields=${parsed.fields.length} lang=${parsed.language}`);
    log(`ddl:\n${indent(buildTableDdlSafe(parsed))}`);

    const alreadyThere = await exists(client, url);

    // 1. Shell
    if (!alreadyThere) {
        try {
            await client.httpClient.request(`/sap/bc/adt/ddic/${coll}`, {
                method: 'POST',
                body: tablShell(parsed.kind, parsed.objectName, parsed.description,
                    parsed.language, ctx.packageName, ctx.responsible),
                headers: { 'Content-Type': 'application/*' },
                qs: ctx.transport ? { corrNr: ctx.transport } : {},
            });
            log(`create : POST /sap/bc/adt/ddic/${coll} OK`);
        } catch (e) {
            log(`create : POST FAILED — ${e?.message ?? e}`);
            return { object: parsed.objectName, kind: `TABL/${parsed.kind}`, action: 'failed', stages };
        }
    } else {
        log(`create : shell already exists — refreshing content`);
    }

    // 2. Lock + PUT DDL
    const ddl = parsed.kind === 'DS' ? buildStructureDdl(parsed) : buildTableDdl(parsed);
    let lockHandle;
    try {
        lockHandle = (await client.lock(url)).LOCK_HANDLE;
        log(`lock   : OK handle=${String(lockHandle).slice(0, 16)}…`);
    } catch (e) {
        log(`lock   : FAILED — ${e?.message ?? e}`);
        return { object: parsed.objectName, kind: `TABL/${parsed.kind}`, action: 'failed', stages };
    }
    try {
        await client.httpClient.request(sourceUrl(url, lockHandle, ctx.transport), {
            method: 'PUT',
            body: ddl,
            headers: { 'Content-Type': 'text/plain; charset=UTF-8', Accept: 'text/plain' },
        });
        log(`source : PUT OK (${ddl.length} bytes)`);
    } catch (e) {
        log(`source : PUT FAILED — ${e?.message ?? e}`);
        return { object: parsed.objectName, kind: `TABL/${parsed.kind}`, action: 'failed', stages };
    } finally {
        try { await client.unLock(url, lockHandle); } catch { /* swallow */ }
    }

    // 3. Activate + verify
    return finish(client, parsed.objectName, url, stages,
        `/sap/bc/adt/ddic/${coll}`, `TABL/${parsed.kind}`, alreadyThere);
}

/** DDL text used for the pre-flight log (never throws). */
function buildTableDdlSafe(parsed) {
    try {
        return parsed.kind === 'DS' ? buildStructureDdl(parsed) : buildTableDdl(parsed);
    } catch {
        return '(n/a)';
    }
}

// ---------------------------------------------------------------------------
// 3. *.ttyp.xml  →  TTYP (table type)
// ---------------------------------------------------------------------------

/** Matches the single `<ttyp:rowType>…</ttyp:rowType>` block in the canonical XML. */
const TTYP_ROW_RE = /<ttyp:rowType>[\s\S]*?<\/ttyp:rowType>/;

/**
 * Parses a `*.ttyp.xml`.
 *
 * @returns {Promise<{file:string, objectName:string, rowType:string,
 *   description:string, language:string}>}
 */
async function parseTtypXml(file) {
    const xml = await fs.readFile(file, 'utf8');
    const valuesRoot = valuesRootOf(xml);
    const dd40v = valuesRoot?.DD40V;
    const objectName = str(dd40v?.TYPENAME).toUpperCase();
    if (!objectName) throw new Error(`No <DD40V><TYPENAME> in ${file}`);
    const rowType = str(dd40v?.ROWTYPE).toUpperCase();
    if (!rowType) {
        throw new Error(`Table type ${objectName} has no <DD40V><ROWTYPE> — `
            + `only table types with a dictionary row type are supported (${file})`);
    }
    return {
        file,
        objectName,
        rowType,
        description: tableDescription(valuesRoot) || objectName,
        language: mapSapLanguage(declaredLanguage(valuesRoot)),
    };
}

/** tabletype:tableType shell used to instantiate a TTYP. */
function ttypShell(objectName, description, language, packageName, responsible) {
    return `<?xml version="1.0" encoding="UTF-8"?>
<tabletype:tableType xmlns:tabletype="http://www.sap.com/dictionary/tabletype"
  xmlns:adtcore="http://www.sap.com/adt/core"
  adtcore:description="${xmlAttr(description)}" adtcore:name="${xmlAttr(objectName)}"
  adtcore:type="TTYP/TO" adtcore:language="${xmlAttr(language)}"
  adtcore:masterLanguage="${xmlAttr(language)}"
  adtcore:responsible="${xmlAttr(responsible)}">
  <adtcore:packageRef adtcore:name="${xmlAttr(packageName)}"/>
</tabletype:tableType>`;
}

/** The `<ttyp:rowType>` block pointing at the dictionary row type. */
function ttypRowType(rowType) {
    return '<ttyp:rowType><ttyp:typeKind>dictionaryType</ttyp:typeKind>'
        + `<ttyp:typeName>${xmlAttr(rowType)}</ttyp:typeName>`
        + '<ttyp:builtInType><ttyp:dataType>CHAR</ttyp:dataType>'
        + '<ttyp:length>000001</ttyp:length><ttyp:decimals>000000</ttyp:decimals>'
        + '</ttyp:builtInType><ttyp:rangeType/></ttyp:rowType>';
}

async function createTtyp(client, ctx, parsed) {
    const url = `/sap/bc/adt/ddic/tabletypes/${parsed.objectName.toLowerCase()}`;
    const stages = [];
    const log = s => stages.push(s);
    log(`parsed: NAME=${parsed.objectName} ROWTYPE=${parsed.rowType} lang=${parsed.language}`);

    const alreadyThere = await exists(client, url);

    if (!alreadyThere) {
        try {
            await client.httpClient.request('/sap/bc/adt/ddic/tabletypes', {
                method: 'POST',
                body: ttypShell(parsed.objectName, parsed.description, parsed.language,
                    ctx.packageName, ctx.responsible),
                headers: { 'Content-Type': 'application/*' },
                qs: ctx.transport ? { corrNr: ctx.transport } : {},
            });
            log(`create : POST /sap/bc/adt/ddic/tabletypes OK`);
        } catch (e) {
            log(`create : POST FAILED — ${e?.message ?? e}`);
            return { object: parsed.objectName, kind: 'TTYP/DA', action: 'failed', stages };
        }
    } else {
        log(`create : shell already exists — refreshing content`);
    }

    let lockHandle;
    try {
        lockHandle = (await client.lock(url)).LOCK_HANDLE;
        log(`lock   : OK handle=${String(lockHandle).slice(0, 16)}…`);
    } catch (e) {
        log(`lock   : FAILED — ${e?.message ?? e}`);
        return { object: parsed.objectName, kind: 'TTYP/DA', action: 'failed', stages };
    }

    try {
        // The canonical XML is the only representation SAP accepts back for a
        // table type; the row type cannot be set through a plain source PUT.
        const resp = await client.httpClient.request(url, {
            method: 'GET', headers: { Accept: 'application/*' },
        });
        let canon = String(resp?.body ?? '');
        if (!canon) {
            log('rowType: FAILED — canonical XML came back empty');
            return { object: parsed.objectName, kind: 'TTYP/DA', action: 'failed', stages };
        }
        const match = canon.match(TTYP_ROW_RE);
        if (!match) {
            log('rowType: FAILED — no <ttyp:rowType> block in the canonical XML');
            return { object: parsed.objectName, kind: 'TTYP/DA', action: 'failed', stages };
        }
        const current = match[0];
        const alreadyOk = /<ttyp:typeKind>\s*dictionaryType\s*<\/ttyp:typeKind>/.test(current)
            && current.includes(`<ttyp:typeName>${parsed.rowType}</ttyp:typeName>`);
        if (alreadyOk) {
            log(`rowType: already dictionaryType/${parsed.rowType} — no change`);
        } else {
            canon = canon.replace(TTYP_ROW_RE, ttypRowType(parsed.rowType));
            await client.httpClient.request(url + lockQuery(lockHandle, ctx.transport), {
                method: 'PUT',
                body: canon,
                headers: { 'Content-Type': 'application/*', Accept: 'application/*' },
            });
            log(`rowType: PUT OK → dictionaryType/${parsed.rowType}`);
        }
    } catch (e) {
        log(`rowType: FAILED — ${e?.message ?? e}`);
        return { object: parsed.objectName, kind: 'TTYP/DA', action: 'failed', stages };
    } finally {
        try { await client.unLock(url, lockHandle); } catch { /* swallow */ }
    }

    return finish(client, parsed.objectName, url, stages,
        '/sap/bc/adt/ddic/tabletypes', 'TTYP/DA', alreadyThere);
}

/** `?lockHandle=…[&corrNr=…]` */
function lockQuery(lockHandle, transport) {
    let q = '?lockHandle=' + encodeURIComponent(lockHandle);
    if (transport) q += '&corrNr=' + encodeURIComponent(transport);
    return q;
}

/** `<url>/source/main?lockHandle=…[&corrNr=…]` */
function sourceUrl(url, lockHandle, transport) {
    return url + '/source/main' + lockQuery(lockHandle, transport);
}

/** Activate the object and record the outcome in `stages`. */
async function finish(client, objectName, url, stages, endpointPath, typeId, existedBefore) {
    const log = s => stages.push(s);
    let success = false;
    try {
        // String form (name + url) is the variant validated against S4DEV for
        // both /ddic/tables|structures and /ddic/tabletypes.
        const res = await client.activate(objectName, url);
        success = res?.success === true;
        const msgs = (res?.messages ?? []).map(m => m?.shortText).filter(Boolean);
        log(`activate: success=${success} inactive=${(res?.inactive ?? []).length}`
            + (msgs.length ? ` msgs=${msgs.join(' | ')}` : ''));
    } catch (e) {
        log(`activate: FAILED — ${e?.message ?? e}`);
    }
    if (!success) {
        return {
            object: objectName, kind: typeId, action: 'failed', stages,
            error: `activation reported failure (see stages)`,
        };
    }

    // Post-flight: the object must now be readable at its own URL.
    try {
        await client.httpClient.request(url, { method: 'GET', headers: { Accept: 'application/*' } });
        log(`verify : GET ${url} OK`);
    } catch (e) {
        log(`verify : FAILED — ${e?.message ?? e}`);
        return { object: objectName, kind: typeId, action: 'failed', stages };
    }
    log(`done   : ${objectName} active in ${endpointPath}`);
    return {
        object: objectName, kind: typeId,
        action: existedBefore ? 'updated' : 'created',
        stages, url,
    };
}

function indent(text) {
    return text.split('\n').map(l => '         ' + l).join('\n');
}

// ---------------------------------------------------------------------------
// 4. Command action
// ---------------------------------------------------------------------------

async function runAction(ctx, opts, args) {
    const files = (args.length === 1 && Array.isArray(args[0]) ? args[0] : args)
        .filter(f => typeof f === 'string' && f.length > 0);
    if (files.length === 0) {
        console.error('Usage: abap ddic-xml-create <file.tabl.xml|file.ttyp.xml> [...]');
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

    const results = [];
    for (const f of files) {
        try {
            const lower = f.toLowerCase();
            let r;
            if (lower.endsWith('.ttyp.xml')) {
                r = await createTtyp(client, shared, await parseTtypXml(f));
            } else if (lower.endsWith('.tabl.xml')) {
                r = await createTabl(client, shared, await parseTablXml(f));
            } else {
                r = { file: f, action: 'failed', error: 'unsupported file (expected *.tabl.xml or *.ttyp.xml)' };
            }
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

// Named exports for scripting / manual testing.
export {
    parseTablXml, parseTtypXml, buildStructureDdl, buildTableDdl,
    mapSapLanguage, tablShell, ttypShell,
};

export default /** @type {import('abap-cli/dist/src/abap_cli/extensions/types.js').CommandExtension} */ ({
    type: 'command',
    name: 'ddic-xml-create',
    description:
        'Create DDIC objects in SAP from abapGit XML: *.tabl.xml → TABL ' +
        '(TABCLASS=INTTAB → structure, TRANSP → transparent table) and ' +
        '*.ttyp.xml → TTYP table type. Fills the real content (DDL for TABL, ' +
        'canonical rowType for TTYP) — the built-in `abap create` only leaves ' +
        'an empty, non-activatable shell. Import *.tabl.xml before *.ttyp.xml.',
    command: 'ddic-xml-create [files...]',
    async action(ctx, opts, ...args) {
        return runAction(ctx, opts, args);
    },
});
