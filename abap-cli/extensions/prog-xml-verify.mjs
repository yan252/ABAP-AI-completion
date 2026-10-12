/**
 * abap-cli command extension: `prog-xml-verify`
 *
 * Reads one or more `*.prog.xml` files and checks, **against the live system**,
 * that everything `prog-xml-push` claims to have pushed is really there.
 *
 * Why this exists
 * ---------------
 * ADT exposes no endpoint for classic screens and GUI statuses (verified: the
 * discovery document of this release has no dynpro/screen/cua collection, and
 * `PUT …/cua` / `PUT …/dynpros` answer 404). That means the data can only be
 * *written* through the classic function modules, and — just as important — it
 * can only be *read back* through classic ABAP as well. This command is the
 * read-back half.
 *
 * What it checks
 * --------------
 * Over ADT (same service `prog-xml-push` wrote to, so it is a true round trip):
 *   - text elements (`symbols` / `selections` / `headings`) of the program,
 *     in the file's own language  -> `VERIFY::TEXT_<cat>::<n>`
 *   - the report title (`LISTHEADER`, i.e. the `R` entry)  -> `VERIFY::TITLE::…`
 *
 * Over a throw-away ABAP class + `classrun`:
 *   - `TADIR` entry of the program and of its includes      (PROG / PROG/I)
 *   - screen header `D020S` for every `<DYNPROS>` screen     (+ type/fnum/dgrp)
 *   - screen field texts `D021T` for the file's language
 *   - **the screen's flow logic** ("窗口代码", i.e.
 *     `<DYNPROS><item><FLOW_LOGIC><RPY_DYFLOW><LINE>`) read back with
 *     `RPY_DYNPRO_READ` — the exact counterpart of the `RPY_DYNPRO_INSERT`
 *     used to write it
 *   - GUI status texts `RSMPTEXTS` (+ the list of `obj_code`s, so the pushed
 *     function codes are visible without relying on Chinese text coming back
 *     intact through a `text/plain` classrun response)
 *
 * Two classes, on purpose
 * -----------------------
 * The flow-logic read (`RPY_DYNPRO_READ`) is generated into a **separate**
 * class from the table-only checks. A generated class is one compilation unit,
 * so if that function module's signature ever differs on another release, only
 * the flow check is lost — the `TADIR`/`D020S`/`D021T`/`RSMPTEXTS` results
 * still come back. `--no-flow` skips the second class entirely.
 *
 * Usage:
 *   abap prog-xml-verify <file1.prog.xml> [file2.prog.xml ...] [--no-flow]
 *
 * Environment knobs
 *   ABAP_HELPER_PACKAGE=…      package for the throw-away class (default $TMP)
 *   ABAP_KEEP_HELPER=1         do not delete the throw-away classes (debugging)
 *   ABAP_PROG_XML_CLASSRUN=0   skip the ABAP-side checks entirely
 *   ABAP_DEBUG=1               include stack traces in errors
 */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import * as os from 'node:os';
import * as fxp from 'fast-xml-parser';

import { ADTClient, createSSLConfig, textElementsUrl } from 'abap-adt-api';

const CRED_KEYCHAIN_HOST = 'abap-cli';
const CRED_KEYCHAIN_SERVICE = 'credentials-for-system';

const SAFE_CLASS = 'ZCL_ADT_PROG_VERIFY';
const FLOW_CLASS = 'ZCL_ADT_FLOW_VERIFY';
const MARK_START = '<<<ABAPGIT_PROGVERIFY>>>';
const MARK_END = '<<<ABAPGIT_PROGVERIFYEND>>>';
const SEP = '::';

// ---------------------------------------------------------------------------
// 0. Config & credentials (same shapes abap-cli writes)
// ---------------------------------------------------------------------------

async function loadAbapJson() {
    const p = path.join(process.cwd(), '.abap.json');
    const json = JSON.parse(await fs.readFile(p, 'utf8'));
    if (!json.system || !json.package) {
        throw new Error(`.abap.json must contain { system, package } (got: ${Object.keys(json).join(',')})`);
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
        throw new Error(`Cannot read profile at ${p}. Run: abap profile add ${systemName} --url ... --client ... --username ... --password ...`);
    }
    const parsed = JSON.parse(raw);
    const all = parsed && typeof parsed === 'object' && parsed.systems && typeof parsed.systems === 'object'
        ? parsed.systems
        : parsed;
    const key = [systemName, systemName.toUpperCase(), systemName.toLowerCase()].find(k => all && all[k]);
    if (!key) throw new Error(`System '${systemName}' not found in ${p}. Known: ${Object.keys(all ?? {}).join(', ')}`);
    const sys = { ...all[key] };
    // SAP Preference-page overrides (set by the Eclipse plugin).
    if (process.env.ABAP_URL) sys.url = process.env.ABAP_URL;
    if (process.env.ABAP_CLIENT) sys.client = process.env.ABAP_CLIENT;
    if (process.env.ABAP_USERNAME) sys.username = process.env.ABAP_USERNAME;
    if (process.env.ABAP_LANGUAGE) sys.language = process.env.ABAP_LANGUAGE;
    if (!sys.password) sys.password = await resolvePassword([key, systemName]);
    return sys;
}

async function resolvePassword(accounts) {
    if (process.env.ABAP_PASSWORD) return process.env.ABAP_PASSWORD;
    try {
        const keytar = await import('keytar');
        for (const account of accounts) {
            const pw = await keytar.default.getPassword(CRED_KEYCHAIN_HOST, account);
            if (pw) return pw;
        }
    } catch { /* keytar unavailable — fall through */ }
    throw new Error(`No password for ${accounts[0]}. Set ABAP_PASSWORD or store it in the OS keychain.`);
}

function insecureFromEnv() {
    return process.env.NODE_TLS_REJECT_UNAUTHORIZED === '0';
}

function argvValue(argv, flag) {
    const eq = argv.find(a => a.startsWith(`${flag}=`));
    if (eq) return eq.slice(flag.length + 1);
    const i = argv.indexOf(flag);
    if (i >= 0 && i + 1 < argv.length && !argv[i + 1].startsWith('-')) return argv[i + 1];
    return undefined;
}

// ---------------------------------------------------------------------------
// 1. Language handling
// ---------------------------------------------------------------------------

const ISO_TO_SAP_LANG = {
    ZH: '1', EN: 'E', DE: 'D', FR: 'F', ES: 'S', JA: 'J', KO: 'K', RU: 'R',
    TH: 'T', PT: 'B', IT: 'I', NL: 'N', PL: 'P', AR: 'A', HE: 'H', SV: 'V',
    FI: 'U', NO: 'O', DA: 'L', CS: 'C', HU: 'M', EL: 'G', TR: 'Q', SK: 'Z',
    SL: 'Y', BG: 'X', RO: '0', HR: '7', SR: '4', ET: '6', LV: '8', LT: '9',
};

/** Normalises a `*.prog.xml` language marker into the SAP internal 1-char code. */
function toSapLanguageCode(raw, fallback = 'E') {
    const v = String(raw ?? '').trim();
    if (!v) return fallback;
    if (v.length === 1) return v.toUpperCase();
    if (v.length === 2 && /^[A-Za-z]{2}$/.test(v)) return ISO_TO_SAP_LANG[v.toUpperCase()] ?? fallback;
    return fallback;
}

// ---------------------------------------------------------------------------
// 2. XML helpers
// ---------------------------------------------------------------------------

function normaliseXml(raw) {
    return raw.replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n');
}

function blockOf(xml, tag) {
    const re = new RegExp(`<${tag}[\\s\\S]*?<\\/${tag}>`, 'm');
    const m = xml.match(re);
    return m ? m[0] : null;
}

function repeatedBlocks(xml, parentTag, childTag) {
    const parent = blockOf(xml, parentTag);
    if (!parent) return [];
    const re = new RegExp(`<${childTag}[\\s\\S]*?<\\/${childTag}>`, 'g');
    return parent.match(re) ?? [];
}

function innerText(xml, tag) {
    const m = xml.match(new RegExp(`<${tag}>([\\s\\S]*?)<\\/${tag}>`));
    return m ? m[1].trim() : '';
}

/** Screen numbers declared in `<DYNPROS>` (always unpadded, e.g. "9001"). */
function dynproScreens(xml) {
    return repeatedBlocks(xml, 'DYNPROS', 'item')
        .map(item => {
            const header = blockOf(item, 'HEADER');
            const screen = header ? innerText(header, 'SCREEN') : '';
            return screen ? String(parseInt(screen, 10)) : '';
        })
        .filter(Boolean);
}

/** GUI status codes declared under `<CUA><STA><RSMPE_STAT><CODE>`. */
function cuaStatusCodes(xml) {
    const cua = blockOf(xml, 'CUA');
    if (!cua) return [];
    const sta = blockOf(cua, 'STA');
    if (!sta) return [];
    // abapGit emits one <RSMPE_STAT> per status (not <item> children).
    const codes = [];
    for (const m of sta.matchAll(/<RSMPE_STAT>[\s\S]*?<\/RSMPE_STAT>/g)) {
        const c = m[0].match(/<CODE>\s*([^<\s]+)\s*<\/CODE>/);
        if (c) codes.push(c[1]);
    }
    if (codes.length === 0) {
        const c = sta.match(/<CODE>\s*([^<\s]+)\s*<\/CODE>/);
        if (c) codes.push(c[1]);
    }
    return [...new Set(codes)];
}

function fxpParse(xml) {
    return new fxp.XMLParser({ ignoreAttributes: true, parseTagValue: false, trimValues: true }).parse(xml);
}

// ---------------------------------------------------------------------------
// 3. ABAP source generation
// ---------------------------------------------------------------------------

const AB = s => `'${String(s).replace(/'/g, "''")}'`;

function classShell(className, sections, mainBody) {
    const cls = className.toLowerCase();
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
    for (const s of sections) L.push(`    METHODS ${s.name} IMPORTING out TYPE REF TO if_oo_adt_classrun_out.`);
    L.push(`ENDCLASS.`);
    L.push(``);
    L.push(`CLASS ${cls} IMPLEMENTATION.`);
    L.push(``);
    L.push(`  METHOD if_oo_adt_classrun~main.`);
    L.push(`    out->write( ${AB(MARK_START)} ).`);
    for (const line of mainBody) L.push(`    ${line}`);
    L.push(`    out->write( ${AB(MARK_END)} ).`);
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

/**
 * Class 1 — table-only checks. These tables/statements are known to work on
 * this release (`TADIR`, `D020S`, `D021T`, `RSMPTEXTS`), so this class is the
 * one that must keep compiling.
 */
function generateSafeSource({ program, screens, sprsl }) {
    const P = '    ';
    const body = [];
    body.push(`${P}DATA lv_cnt TYPE i.`);
    body.push(`${P}SELECT COUNT(*) FROM tadir INTO lv_cnt`);
    body.push(`${P}  WHERE pgmid = 'R3TR' AND object = 'PROG' AND obj_name = ${AB(program)}.`);
    body.push(`${P}out->write( |VERIFY${SEP}TADIR_PROG${SEP}{ lv_cnt }| ).`);
    body.push(``);
    body.push(`${P}SELECT COUNT(*) FROM tadir INTO lv_cnt`);
    body.push(`${P}  WHERE pgmid = 'R3TR' AND object = 'PROG/I' AND obj_name LIKE ${AB(program + '%')}.`);
    body.push(`${P}out->write( |VERIFY${SEP}TADIR_PROG_INCLUDES${SEP}{ lv_cnt }| ).`);

    for (const screen of screens) {
        body.push(``);
        body.push(`${P}" ---------------- screen ${screen} ----------------`);
        body.push(`${P}DATA: ls_020s TYPE d020s.`);
        body.push(`${P}SELECT COUNT(*) FROM d020s INTO lv_cnt`);
        body.push(`${P}  WHERE prog = ${AB(program)} AND dnum = ${AB(screen)}.`);
        body.push(`${P}out->write( |VERIFY${SEP}D020S_${screen}${SEP}{ lv_cnt }| ).`);
        body.push(`${P}SELECT SINGLE * FROM d020s INTO ls_020s`);
        body.push(`${P}  WHERE prog = ${AB(program)} AND dnum = ${AB(screen)}.`);
        body.push(`${P}IF sy-subrc = 0.`);
        body.push(`${P}  out->write( |VERIFY${SEP}D020S_${screen}_ATTR${SEP}type={ ls_020s-type } fnum={ ls_020s-fnum } dgrp={ ls_020s-dgrp }| ).`);
        body.push(`${P}ENDIF.`);
        body.push(``);
        body.push(`${P}SELECT COUNT(*) FROM d021t INTO lv_cnt`);
        body.push(`${P}  WHERE prog = ${AB(program)} AND dynr = ${AB(screen)} AND lang = ${AB(sprsl)}.`);
        body.push(`${P}out->write( |VERIFY${SEP}D021T_${screen}_${sprsl}${SEP}{ lv_cnt }| ).`);
    }

    body.push(``);
    body.push(`${P}" ---------------- GUI status texts (CUA) ----------------`);
    body.push(`${P}SELECT COUNT(*) FROM rsmptexts INTO lv_cnt`);
    body.push(`${P}  WHERE progname = ${AB(program)} AND sprsl = ${AB(sprsl)}.`);
    body.push(`${P}out->write( |VERIFY${SEP}RSMPTEXTS_${sprsl}${SEP}{ lv_cnt }| ).`);
    body.push(`${P}DATA: ls_mp   TYPE rsmptexts,`);
    body.push(`${P}      lv_codes TYPE string.`);
    body.push(`${P}SELECT * FROM rsmptexts INTO ls_mp`);
    body.push(`${P}  WHERE progname = ${AB(program)} AND sprsl = ${AB(sprsl)}.`);
    body.push(`${P}  IF lv_codes IS INITIAL OR NOT lv_codes CS ls_mp-obj_code.`);
    body.push(`${P}    lv_codes = |{ lv_codes },{ ls_mp-obj_type }:{ ls_mp-obj_code }|.`);
    body.push(`${P}  ENDIF.`);
    body.push(`${P}ENDSELECT.`);
    body.push(`${P}ls_mp-obj_code = ''." avoid unused warning when table empty`);
    body.push(`${P}out->write( |VERIFY${SEP}CUA_TEXT_KEYS${SEP}{ lv_codes }| ).`);

    const sections = [{ name: 'do_checks', body: body.map(l => `  ${l}`) }];
    const main = ['do_checks( out = out ).'];
    return classShell(SAFE_CLASS, sections, main);
}

/**
 * Class 2 — flow-logic read-back. `RPY_DYNPRO_READ` is the counterpart of the
 * `RPY_DYNPRO_INSERT` used on push, and is the only way to see a screen's
 * processing logic again (`<DYNPROS><item><FLOW_LOGIC>`).
 */
function generateFlowSource({ program, screens }) {
    const P = '    ';
    const body = [];
    for (const screen of screens) {
        body.push(`${P}" ---------------- flow logic of screen ${screen} ----------------`);
        body.push(`${P}DATA: lt_flow       TYPE swydyflow,`);
        body.push(`${P}      ls_flow       TYPE LINE OF swydyflow,`);
        body.push(`${P}      lt_containers TYPE dycatt_tab,`);
        body.push(`${P}      lt_fields     TYPE dyfatc_tab,`);
        body.push(`${P}      ls_header     TYPE rpy_dyhead,`);
        body.push(`${P}      lv_cnt        TYPE i,`);
        body.push(`${P}      lv_idx        TYPE i.`);
        body.push(`${P}CLEAR: lt_flow, lt_containers, lt_fields, ls_header, lv_cnt, lv_idx.`);
        body.push(`${P}CALL FUNCTION 'RPY_DYNPRO_READ'`);
        body.push(`${P}  EXPORTING`);
        body.push(`${P}    progname             = ${AB(program)}`);
        body.push(`${P}    dynnr                = ${AB(screen)}`);
        body.push(`${P}  IMPORTING`);
        body.push(`${P}    header               = ls_header`);
        body.push(`${P}  TABLES`);
        body.push(`${P}    containers           = lt_containers`);
        body.push(`${P}    fields_to_containers = lt_fields`);
        body.push(`${P}    flow_logic           = lt_flow`);
        body.push(`${P}  EXCEPTIONS`);
        body.push(`${P}    cancelled            = 1`);
        body.push(`${P}    not_found            = 2`);
        body.push(`${P}    no_authority         = 3`);
        body.push(`${P}    OTHERS               = 4.`);
        body.push(`${P}IF sy-subrc <> 0.`);
        body.push(`${P}  out->write( |VERIFY${SEP}FLOW_${screen}${SEP}ERR{ sy-subrc }| ).`);
        body.push(`${P}ELSE.`);
        body.push(`${P}  DESCRIBE TABLE lt_flow LINES lv_cnt.`);
        body.push(`${P}  out->write( |VERIFY${SEP}FLOW_${screen}${SEP}{ lv_cnt }| ).`);
        body.push(`${P}  DESCRIBE TABLE lt_fields LINES lv_cnt.`);
        body.push(`${P}  out->write( |VERIFY${SEP}FLOW_${screen}_FIELDS${SEP}{ lv_cnt }| ).`);
        body.push(`${P}  LOOP AT lt_flow INTO ls_flow.`);
        body.push(`${P}    lv_idx = lv_idx + 1.`);
        body.push(`${P}    out->write( |VERIFY${SEP}FLOWLINE_${screen}_{ lv_idx }${SEP}{ ls_flow-line }| ).`);
        body.push(`${P}  ENDLOOP.`);
        body.push(`${P}ENDIF.`);
        body.push(``);
    }
    const sections = [{ name: 'do_flow', body: body.map(l => `  ${l}`) }];
    const main = ['do_flow( out = out ).'];
    return classShell(FLOW_CLASS, sections, main);
}

// ---------------------------------------------------------------------------
// 4. Helper-class lifecycle (create -> PUT source -> activate -> run -> delete)
// ---------------------------------------------------------------------------

const classUrl = name => `/sap/bc/adt/oo/classes/${name.toLowerCase()}`;

async function deleteHelperClass(ctx, name, transport) {
    const url = classUrl(name);
    let lock;
    try {
        lock = await ctx.client.lock(url);
    } catch {
        return false;                                  // does not exist
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
 * Runs one generated helper class and returns the parsed `VERIFY::` lines.
 * Never throws on an ABAP-side failure — the caller decides what to do with it.
 */
async function runHelperClass(ctx, className, source, { packageName, transport }) {
    const url = classUrl(className);
    const out = { className, ok: false, checks: [], output: '' };
    await deleteHelperClass(ctx, className, transport);

    try {
        await ctx.client.createObject(
            'CLAS/OC',
            className,
            ctx.helperPackage,
            'abap-cli prog-xml verify helper (auto-deleted)',
            `/sap/bc/adt/packages/${ctx.helperPackage.toLowerCase()}`,
            ctx.owner,
            transport,
        );
    } catch (err) {
        out.error = `createObject failed: ${err?.message ?? err}`;
        return out;
    }

    try {
        const lock = await ctx.client.lock(url);
        try {
            await ctx.client.setObjectSource(`${url}/source/main`, source, lock.LOCK_HANDLE, transport);
        } finally {
            try { await ctx.client.unLock(url, lock.LOCK_HANDLE); } catch { /* ignore */ }
        }

        // No `adtcore:parentUri` — self-reference makes activation a silent
        // no-op (HTTP 200 + activationExecuted="false").
        const act = await ctx.client.activate([
            { 'adtcore:uri': url, 'adtcore:type': 'CLAS/OC', 'adtcore:name': className },
        ], false);
        if (!act.success) {
            const msgs = (act.messages ?? [])
                .map(m => `${m.type ?? 'E'}${m.id ?? ''}${m.number ?? ''} ${String(m.shortText ?? '').trim()}`.trim());
            out.error = `did not activate: ${msgs.join('; ') || 'unknown activation error'}`;
            return out;
        }

        // A warm session cannot run the class it just created ("Class does not
        // implement if_oo_adt_classrun~main method!") — re-login first.
        await ctx.client.login();
        out.output = String(await ctx.client.runClass(className));
        out.checks = parseVerifyOutput(out.output);
        out.ok = out.checks.length > 0;
        if (!out.ok) out.error = 'helper class produced no VERIFY:: lines';
        return out;
    } catch (err) {
        out.error = err?.message ?? String(err);
        return out;
    } finally {
        if (process.env.ABAP_KEEP_HELPER === '1') out.deleted = false;
        else out.deleted = await deleteHelperClass(ctx, className, transport);
    }
}

/** Extracts `VERIFY::<key>::<value>` lines. */
function parseVerifyOutput(raw) {
    const checks = [];
    for (const line of String(raw ?? '').split(/\r?\n/)) {
        const t = line.trim();
        if (!t.startsWith(`VERIFY${SEP}`)) continue;
        const parts = t.split(SEP);
        checks.push({ key: parts[1] ?? '', value: parts.slice(2).join(SEP) });
    }
    return checks;
}

// ---------------------------------------------------------------------------
// 5. Per-file verification
// ---------------------------------------------------------------------------

async function verifyOne(ctx, file) {
    const raw = normaliseXml(await fs.readFile(file, 'utf8'));
    const doc = fxpParse(raw);
    const valuesRoot = doc?.abapGit?.['asx:abap']?.['asx:values']
        ?? doc?.['asx:abap']?.['asx:values'];
    const progdir = valuesRoot?.PROGDIR;
    if (!progdir?.NAME) throw new Error(`No <PROGDIR><NAME> in ${file}`);

    const program = String(progdir.NAME).toUpperCase().trim();
    const i18nInner = blockOf(raw, 'I18N_TPOOL');
    const languageRaw = (i18nInner && (i18nInner.match(/<LANGUAGE>\s*([^<\s]+)\s*<\/LANGUAGE>/) ?? [])[1])
        || (blockOf(raw, 'DYNPROS') ?? '').match(/<LANGUAGE>\s*([^<\s]+)\s*<\/LANGUAGE>/)?.[1]
        || 'E';
    const sprsl = toSapLanguageCode(languageRaw, 'E');
    const screens = dynproScreens(raw);
    const statuses = cuaStatusCodes(raw);

    const result = {
        ok: false, file, object: program, spras: languageRaw, sprsl,
        screens, statuses, adt: [], checks: [], errors: [],
    };

    // ---- 5a. text elements, through the very endpoint prog-xml-push wrote ----
    const teUrl = textElementsUrl('PROG/P', program);
    for (const category of ['symbols', 'selections', 'headings']) {
        try {
            const res = await ctx.client.getTextElements(teUrl, category);
            const n = res?.textElements?.length ?? 0;
            result.adt.push(`TEXT_${category.toUpperCase()}${SEP}${n}`);
            if (category === 'headings') {
                const title = (res?.textElements ?? []).find(e => e.id === 'R' || e.id === '001');
                if (title) result.adt.push(`TITLE${SEP}${title.id}=${title.text}`);
            }
        } catch (err) {
            result.adt.push(`TEXT_${category.toUpperCase()}${SEP}ERR ${err?.message ?? err}`);
        }
    }

    // ---- 5b. ABAP-side read-back ----
    if (!ctx.classrun) {
        result.errors.push('ABAP-side checks disabled (ABAP_PROG_XML_CLASSRUN=0)');
        return result;
    }

    const safe = await runHelperClass(ctx, SAFE_CLASS,
        generateSafeSource({ program, screens, sprsl }),
        { packageName: ctx.packageName, transport: ctx.transport });
    result.checks.push(...safe.checks);
    if (safe.error) result.errors.push(`table checks: ${safe.error}`);

    if (ctx.flow && screens.length > 0) {
        const flow = await runHelperClass(ctx, FLOW_CLASS,
            generateFlowSource({ program, screens }),
            { packageName: ctx.packageName, transport: ctx.transport });
        result.checks.push(...flow.checks);
        if (flow.error) result.errors.push(`flow-logic check: ${flow.error}`);
    }

    const expectedScreensOk = screens.every(s =>
        result.checks.some(c => c.key === `D020S_${s}` && c.value !== '0'));
    const flowOk = !ctx.flow || screens.every(s =>
        result.checks.some(c => c.key === `FLOW_${s}` && c.value !== '0' && !c.value.startsWith('ERR')));
    result.ok = result.errors.length === 0 && expectedScreensOk && flowOk;
    return result;
}

// ---------------------------------------------------------------------------
// 6. Extension export
// ---------------------------------------------------------------------------

export default /** @type {import('abap-cli/dist/src/abap_cli/extensions/types.js').CommandExtension} */ ({
    type: 'command',
    name: 'prog-xml-verify',
    description:
        'Verify that a *.prog.xml was really imported: text elements (ADT) plus ' +
        'dynpro screens, screen flow logic (FLOW_LOGIC) and GUI status texts ' +
        'read back from SAP through ABAP classrun.',
    command: 'prog-xml-verify [files...]',
    /**
     * The host registers extensions with a bare `program.command(ext.command)`,
     * so commander rejects undeclared options. Options are read from raw argv /
     * environment instead (ABAP_TRANSPORT, ABAP_PRETTY_JSON).
     */
    async action(ctx, opts, ...args) {
        const files = (args.length === 1 && Array.isArray(args[0]) ? args[0] : args)
            .filter(f => typeof f === 'string' && f.length > 0);
        if (files.length === 0) {
            console.error('Usage: abap prog-xml-verify <file1.prog.xml> [file2.prog.xml ...]');
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

        const runCtx = {
            client,
            transport,
            packageName: abapJson.package,
            owner: creds.username,
            helperPackage: process.env.ABAP_HELPER_PACKAGE || '$TMP',
            classrun: process.env.ABAP_PROG_XML_CLASSRUN !== '0',
            flow: !argv.includes('--no-flow'),
        };

        const results = [];
        try {
            for (const f of files) {
                try {
                    results.push(await verifyOne(runCtx, f));
                } catch (err) {
                    results.push({ ok: false, file: f, error: err?.message ?? String(err) });
                }
            }
        } finally {
            try { await client.logout(); } catch { /* ignore */ }
        }

        if (prettyJson) {
            console.log(JSON.stringify(results, null, 2));
        } else {
            for (const r of results) {
                console.log(`--- ${r.file} ---`);
                if (r.error) { console.log(`  ERROR            : ${r.error}`); continue; }
                console.log(`  object           : ${r.object}  (language ${r.spras}/${r.sprsl})`);
                if (r.screens?.length) console.log(`  screens declared : ${r.screens.join(', ')}`);
                if (r.statuses?.length) console.log(`  GUI status       : ${r.statuses.join(', ')}`);
                for (const a of r.adt ?? []) console.log(`  ${a.replace(SEP, ' = ')}`);
                for (const c of r.checks ?? []) console.log(`  ${c.key} = ${c.value}`);
                for (const e of r.errors ?? []) console.log(`  !! ${e}`);
                console.log(`  => ${r.ok ? 'OK' : 'INCOMPLETE'}`);
            }
        }

        process.exitCode = results.every(r => r.ok) ? 0 : 1;
    },
});
