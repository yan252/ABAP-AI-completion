/**
 * abap-cli command extension: `prog-batch-activate`
 *
 * Activates every object of an import in ONE `POST /sap/bc/adt/activation`
 * request, as the final step after all metadata and source code have been
 * written.
 *
 * Why one batch at the end instead of per-object activation while pushing:
 * programs, INCLUDEs, structures, tables and table types reference each other.
 * Activating an object before its dependencies exist (or before a sibling
 * INCLUDE carries the new source) makes SAP compile it against the *old* state
 * and report bogus errors such as `Field "S_MATNR" is unknown.`, which leaves
 * the object inactive. Activating everything at once lets the activation
 * endpoint resolve the dependencies in the right order
 * (`generationExecuted="true"`).
 *
 * Accepted files (object type is derived from the file itself):
 *   *.prog.abap   → PROG/P (SUBC≠I) or PROG/I (SUBC=I, from the sibling .prog.xml)
 *   *.prog.xml    → PROG/P (PROGDIR/NAME; used only when no .prog.abap is given)
 *                   plus PROG/PX when the file declares text elements, see below
 *   *.tabl.xml    → TABL/DS (TABCLASS=INTTAB) or TABL/DT (TABCLASS=TRANSP)
 *   *.ttyp.xml    → TTYP/DA
 *   *.clas.abap   → CLAS/OC  (name from the sibling *.clas.xml)
 *   *.clas.xml    → CLAS/OC  (CLSNAME; used only when no *.clas.abap is given)
 *   *.intf.abap   → INTF/OI  (name from the sibling *.intf.xml)
 *   *.intf.xml    → INTF/OI  (CLSNAME; used only when no *.intf.abap is given)
 *   *.fugr.xml    → FUGR/F   (the function group object; name from the file token)
 *   *.fugr.*.reps.abap → FUGR/F for the function pool `SAPL<group>`, otherwise
 *                   FUGR/I include (name from the sibling *.fugr.*.xml <NAME>)
 *   *.fugr.*.func.abap → FUGR/FF function module (name from the file token)
 *
 * Usage:
 *   abap prog-batch-activate repos/<pkg>/src/*.prog.abap repos/<pkg>/src/*.tabl.xml \
 *                            repos/<pkg>/src/*.clas.abap repos/<pkg>/src/*.fugr.* ...
 *
 * Request body — validated against S4DEV by re-reading the *active* source
 * with `?version=active` after the call:
 *   • main program → `<adtcore:objectReference adtcore:uri=… adtcore:name=…>`
 *     (DDIC and OO objects carry `adtcore:type` as well)
 *   • INCLUDE → additionally `adtcore:type="PROG/I"` and
 *     `adtcore:parentUri="<url of the main program that includes it>"`; without
 *     a parent the include is compiled standalone and cannot see the main
 *     program's global data
 *   • text elements → a main program whose abapGit metadata carries an
 *     `<I18N_TPOOL><item><TEXTPOOL>` contributes a SECOND reference,
 *     `adtcore:type="PROG/PX"` on `/sap/bc/adt/textelements/programs/<name>`.
 *     The textpool is an activatable object of its own; a reference for
 *     `PROG/P` alone leaves it `inactive` (verified on S4DEV), so the report
 *     title (LISTHEADER) and the selection texts never reach the user even
 *     though the program itself is active.
 *
 * Failure handling: a batch is refused as a whole when one of its members is
 * not activatable, and a single bad object must never keep the rest of an
 * import inactive. Two refusals are therefore handled by dropping the culprit
 * and retrying the remaining objects:
 *   • HTTP 403 (`User X is currently editing Y`) — the object is locked by
 *     another user or transport request; the URI is in the error properties.
 *   • HTTP 200 with `activationExecuted="false"` — SAP stopped at the first
 *     object whose check failed and named it in a `<msg>`.
 * The dropped object is reported as failed.
 *
 * Verdict: HTTP 200 alone means nothing — SAP also answers it for a batch it
 * refused to execute. `activationExecuted` is the success criterion; a batch
 * that was not executed marks every object still in it as failed, carrying
 * SAP's messages.
 *
 * Verification: a batch response proves that the activation *ran*, not that
 * this particular object ended up active (objects SAP does not know are
 * silently ignored). Every activated object is therefore re-read on its own:
 * `adtcore:version` of the object metadata must no longer be `inactive`, and
 * a 404 means the object was never created by the earlier import steps.
 */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import * as os from 'node:os';
import { ADTClient, createSSLConfig } from 'abap-adt-api';
import keytar from 'keytar';

/** See prog-source-push.mjs — same keychain/system resolution. */
async function resolvePassword(systemName) {
    if (process.env.ABAP_PASSWORD) return process.env.ABAP_PASSWORD;
    try {
        return await keytar.getPassword('abap-cli', systemName) || undefined;
    } catch (e) {
        console.warn(`[prog-batch-activate] keychain read failed: ${e?.message ?? e}`);
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
    if (!key) throw new Error(`System '${systemName}' not found in ${p}`);
    const sys = { ...all[key] };
    if (process.env.ABAP_URL) sys.url = process.env.ABAP_URL;
    if (process.env.ABAP_CLIENT) sys.client = process.env.ABAP_CLIENT;
    if (process.env.ABAP_USERNAME) sys.username = process.env.ABAP_USERNAME;
    if (process.env.ABAP_LANGUAGE) sys.language = process.env.ABAP_LANGUAGE;
    sys.password = await resolvePassword(systemName);
    if (!sys.password) {
        throw new Error(`Password for system '${systemName}' not found. `
            + `Set ABAP_PASSWORD or run: abap profile set ${systemName} --password <pwd>`);
    }
    return sys;
}

function insecureFromEnv() {
    return process.env.ABAP_INSECURE === '1' || process.env.NODE_TLS_REJECT_UNAUTHORIZED === '0';
}

// ---------------------------------------------------------------------------
// File → object
// ---------------------------------------------------------------------------

/** SUBC → endpoint, identical to prog-source-push. */
const PROGRAM_ENDPOINTS = {
    '1': '/sap/bc/adt/programs/programs/',
    'I': '/sap/bc/adt/programs/includes/',
    'M': '/sap/bc/adt/programs/programs/',
    'S': '/sap/bc/adt/programs/programs/',
    'T': '/sap/bc/adt/programs/programs/',
};

function tag(xml, name) {
    const m = xml.match(new RegExp(`<${name}>\\s*([^\\s<]*)\\s*</${name}>`, 'i'));
    return m ? m[1].trim().toUpperCase() : '';
}

async function readText(file) {
    try {
        return await fs.readFile(file, 'utf8');
    } catch {
        return '';
    }
}

/**
 * Human-readable object name from a FUGR disk token. abapGit writes a
 * namespaced name `/BMW/FOO` as `#bmw#foo` (fugr-layout.js#fugrFileToken →
 * objectDirName), so `#` round-trips back to `/` here.
 */
function fugrTokenName(token) {
    return token.toUpperCase().replace(/#/g, '/');
}

/**
 * Encode one FUGR URI segment exactly like push-fugr / fugr-layout do:
 * lower-case, percent-encoded, with the hex digits lower-cased again
 * (`/BMW/FOO` → `%2fbmw%2ffoo`).
 */
function encodeFugrSegment(name) {
    return encodeURIComponent(name.toLowerCase())
        .replace(/%[0-9A-F]{2}/g, part => part.toLowerCase());
}

/** ADT object URL of a function group (`FUGR/F`). */
function fugrGroupUrl(group) {
    return `/sap/bc/adt/functions/groups/${encodeFugrSegment(group)}`;
}

/**
 * True when abapGit metadata declares text elements for a program. They live in
 * `<I18N_TPOOL><item><LANGUAGE>…</LANGUAGE><TEXTPOOL><ID>R</ID><ENTRY>…` — a
 * `<TEXTPOOL>` block that carries at least one `<ENTRY>`.
 */
function hasTextElements(xml) {
    const block = (xml.match(/<I18N_TPOOL>[\s\S]*?<\/I18N_TPOOL>/i) ?? [])[0] ?? '';
    return /<ENTRY>/i.test(block);
}

/**
 * Resolves one input file to `{object}`, `{kind}`, `{url}` and — for a main
 * program with text elements — `{textUrl}`.
 * Returns `{skipped: '<reason>'}` for files that hold no activatable object.
 */
async function resolveObject(file) {
    const base = path.basename(file).toLowerCase();

    if (base.endsWith('.prog.abap') || base.endsWith('.prog.xml')) {
        const xmlPath = file.replace(/\.prog\.(abap|xml)$/i, '.prog.xml');
        const xml = await readText(xmlPath);
        const object = tag(xml, 'NAME') || base.replace(/\.prog\.(abap|xml)$/, '').toUpperCase();
        const subc = tag(xml, 'SUBC') || '1';
        const endpoint = PROGRAM_ENDPOINTS[subc];
        if (!endpoint) return { skipped: `unsupported SUBC='${subc}'` };
        const o = {
            object,
            kind: subc === 'I' ? 'PROG/I' : 'PROG/P',
            url: endpoint + object.toLowerCase(),
        };
        // Main programs are "root" objects: in the activation request they
        // must use a bare reference (uri+name only) — see activation phases
        // below. INCLUDEs are children and keep full references.
        if (o.kind === 'PROG/P') o.bareMain = true;
        // The textpool of a main program is an activatable object of its own
        // (`adtcore:type="PROG/PX"`) and must be part of the batch. An INCLUDE
        // has none — SAP answers "You cannot edit text elements for includes".
        if (o.kind === 'PROG/P' && hasTextElements(xml)) {
            o.textUrl = `/sap/bc/adt/textelements/programs/${object.toLowerCase()}`;
        }
        return o;
    }

    if (base.endsWith('.tabl.xml')) {
        const xml = await readText(file);
        const object = tag(xml, 'TABNAME') || base.replace(/\.tabl\.xml$/, '').toUpperCase();
        const tabclass = tag(xml, 'TABCLASS');
        if (tabclass === 'INTTAB') {
            return { object, kind: 'TABL/DS', url: `/sap/bc/adt/ddic/structures/${object.toLowerCase()}` };
        }
        if (tabclass === 'TRANSP') {
            return { object, kind: 'TABL/DT', url: `/sap/bc/adt/ddic/tables/${object.toLowerCase()}` };
        }
        return { skipped: `unsupported TABCLASS='${tabclass || '(none)'}'` };
    }

    if (base.endsWith('.ttyp.xml')) {
        const xml = await readText(file);
        const object = tag(xml, 'TYPENAME') || base.replace(/\.ttyp\.xml$/, '').toUpperCase();
        return { object, kind: 'TTYP/DA', url: `/sap/bc/adt/ddic/tabletypes/${object.toLowerCase()}` };
    }

    // Function groups (FUGR). abapGit lays a group out as
    //   <group>.fugr.xml              → the group object itself (FUGR/F)
    //   <group>.fugr.sapl<group>.reps → the function-pool main program (FUGR/F)
    //   <group>.fugr.l<group>….reps   → a FUGR/I include
    //   <group>.fugr.<fm>.func        → a function module (FUGR/FF)
    // The group name lives in the file-name token (`/BMW/FOO` → `#bmw#foo`).
    // An include's real name is NOT its file token (which only repeats the
    // group prefix); it must be read from the sibling
    // `<group>.fugr.<inc>.xml` (`<PROGDIR><NAME>L<group>TOP</NAME>`).
    if (base.includes('.fugr.')) {
        const token = base.slice(0, base.indexOf('.fugr.'));
        const group = fugrTokenName(token);
        const groupUrl = fugrGroupUrl(group);
        const sub = base.slice(base.indexOf('.fugr.') + '.fugr.'.length);

        if (base.endsWith('.func.abap') || base.endsWith('.func.xml')) {
            const fm = sub.replace(/\.func\.(abap|xml)$/, '').toUpperCase();
            return {
                object: fm,
                kind: 'FUGR/FF',
                url: `${groupUrl}/fmodules/${encodeFugrSegment(fm)}`,
                parentUri: groupUrl,
            };
        }

        if (base.endsWith('.reps.abap') || base.endsWith('.reps.xml')) {
            const xmlPath = file.replace(/\.reps\.(abap|xml)$/i, '.xml');
            const xml = await readText(xmlPath);
            const program = tag(xml, 'NAME')
                || sub.replace(/\.reps\.(abap|xml)$/, '').toUpperCase();
            // The function-pool main program `SAPL<group>` is a separate
            // activatable item: SAP's inactive-objects list reports it as
            // FUGR/I at `<groupUrl>/source/main`, and it activates ONLY from a
            // bare reference (uri+name). The group object FUGR/F itself comes
            // from `<group>.fugr.xml`. Every other reps file is an ordinary
            // FUGR/I include child.
            if (program.startsWith('SAPL')) {
                return {
                    object: program,
                    kind: 'FUGR/I',
                    url: `${groupUrl}/source/main`,
                    parentUri: groupUrl,
                    bareMain: true,
                };
            }
            return {
                object: program,
                kind: 'FUGR/I',
                url: `${groupUrl}/includes/${encodeFugrSegment(program)}`,
                parentUri: groupUrl,
            };
        }

        if (base.endsWith('.fugr.xml')) {
            // Root object — activated in phase B with a bare reference.
            return { object: group, kind: 'FUGR/F', url: groupUrl, parentUri: groupUrl, bareMain: true };
        }

        return { skipped: 'unsupported FUGR file' };
    }

    // OO objects — classes (CLAS/OC) and interfaces (INTF/OI). The name lives in
    // the sibling `*.clas.xml` / `*.intf.xml` (`<CLSNAME>`), which is also the
    // file abapGit ships for an object without source. Both `*.abap` and
    // `*.xml` inputs resolve to the same URL, so the caller's dedupe collapses
    // a class that was passed as two files into one reference.
    if (base.endsWith('.clas.abap') || base.endsWith('.clas.xml')
        || base.endsWith('.intf.abap') || base.endsWith('.intf.xml')) {
        const typeKey = base.includes('.clas.') ? 'clas' : 'intf';
        const coll = typeKey === 'clas' ? 'oo/classes' : 'oo/interfaces';
        const kind = typeKey === 'clas' ? 'CLAS/OC' : 'INTF/OI';
        const xmlPath = file.replace(/\.(clas|intf)\.(abap|xml)$/i, `.${typeKey}.xml`);
        const xml = await readText(xmlPath);
        const object = tag(xml, 'CLSNAME')
            || base.replace(/\.(clas|intf)\.(abap|xml)$/, '').toUpperCase();
        return { object, kind, url: `/sap/bc/adt/${coll}/${object.toLowerCase()}` };
    }

    return { skipped: 'not an activatable source/metadata file' };
}

// ---------------------------------------------------------------------------
// Activation request
// ---------------------------------------------------------------------------

/**
 * One `<adtcore:objectReference>`. Only the attributes SAP actually needs are
 * emitted: an INCLUDE needs `type` + `parentUri`, text elements need their own
 * `type` (without it the textpool is not activated), DDIC and OO objects need
 * `type`, and a main program is activated by uri + name alone.
 */
function referenceOf(o) {
    // ADT activation endpoint (POST /sap/bc/adt/activation) requires every
    // object reference to carry both adtcore:type and adtcore:parentUri —
    // abap-adt-api's activate() always serialises both attributes, and SAP
    // rejects references that lack them (the object is silently left
    // inactive). So we always emit type; for root objects (PROG/P, FUGR/F)
    // the parentUri defaults to the object's own URL.
    const parentUri = o.parentUri || o.url;
    return `<adtcore:objectReference adtcore:uri="${o.url}"`
        + ` adtcore:type="${o.kind}"`
        + ` adtcore:parentUri="${parentUri}"`
        + ` adtcore:name="${o.object}"/>`;
}

/**
 * Bare reference for a ROOT object: uri + name only, no type, no parentUri.
 * Verified against a real S/4 system: a main program (PROG/P), a function
 * group (FUGR/F) and the function-pool source/main item (FUGR/I at
 * `.../source/main`) stay silently inactive when sent with a full
 * self-parentUri reference, but activate from this bare form.
 */
function bareReferenceOf(o) {
    return `<adtcore:objectReference adtcore:uri="${o.url}" adtcore:name="${o.object}"/>`;
}

function bodyOf(objects, refFn = referenceOf) {
    return '<?xml version="1.0" encoding="UTF-8"?>'
        + '<adtcore:objectReferences xmlns:adtcore="http://www.sap.com/adt/core">'
        + objects.map(refFn).join('') + '</adtcore:objectReferences>';
}

async function postActivation(client, objects, refFn = referenceOf) {
    // preauditRequested=false: skip SAP's pre-activation syntax audit. The
    // audit rejects function groups whose TOP include contains FORM
    // implementations ("TOP include should contain definitions only"), even
    // though SE38 / Eclipse ADT manual activation accepts the same source
    // (the same lines then come back as mere W warnings). Skipping the audit
    // lets the activation run exactly like the GUI does.
    return client.httpClient.request('/sap/bc/adt/activation', {
        method: 'POST',
        body: bodyOf(objects, refFn),
        qs: { method: 'activate', preauditRequested: false },
        headers: { Accept: 'application/xml' },
    });
}

/** Human-readable digest of an activation response. */
function digestOf(xml) {
    return (xml.match(/<chkl:properties[^>]*>/) ?? ['(no chkl:properties)'])[0].trim();
}

/**
 * `<chkl:properties …>` of an activation response as plain attributes. The
 * element is written once for the whole batch, so `activationExecuted` tells
 * whether SAP executed the request at all.
 */
function propertiesOf(xml) {
    const element = (xml.match(/<chkl:properties[^>]*>/) ?? [])[0] ?? '';
    const props = {};
    for (const m of element.matchAll(/([\w:-]+)="([^"]*)"/g)) props[m[1]] = m[2];
    return props;
}

/**
 * Every `<msg>` of an activation response as `{attrs, text}`. The attributes
 * name the object a message belongs to (`objDescr`, `href`), the text is what
 * SAP wants the user to read.
 */
function rawMessagesOf(xml) {
    return [...xml.matchAll(/<msg\b([^>]*)>([\s\S]*?)<\/msg>/g)].map(m => {
        const attrs = {};
        for (const a of m[1].matchAll(/([\w:-]+)="([^"]*)"/g)) attrs[a[1]] = a[2];
        return { attrs, text: m[2].replace(/\s+/g, ' ').trim() };
    });
}

function messagesOf(xml) {
    return rawMessagesOf(xml).map(m => m.text);
}

/**
 * Objects SAP blamed for a refused batch. The activation endpoint stops at the
 * first object whose check fails and answers with a message naming it — either
 * through attributes (`objDescr` like `TABL ZSZTEST50`, `href`) or inside the
 * text (`ZSZTEST50 does not exist; check the name`). Matching the object name
 * works in every logon language, unlike the (translated) message text. The
 * name must not be part of a longer name: `ZTEST50` is not a hit in
 * `ZSZTEST50`.
 */
function blameOf(xml, objects) {
    const msgs = rawMessagesOf(xml);
    const blamed = [];
    for (const o of objects) {
        const name = String(o.object ?? '').toUpperCase();
        if (!name) continue;
        const re = new RegExp(`(^|[^A-Z0-9_])${name}([^A-Z0-9_]|$)`);
        const hit = msgs.find(m => re.test(
            `${m.attrs.objDescr ?? ''} ${m.attrs.href ?? ''} ${m.text}`.toUpperCase(),
        ));
        if (hit) blamed.push({ object: o, message: hit.text });
    }
    return blamed;
}

// ---------------------------------------------------------------------------
// Command
// ---------------------------------------------------------------------------

export default /** @type {import('abap-cli/dist/src/abap_cli/extensions/types.js').CommandExtension} */ ({
    type: 'command',
    name: 'prog-batch-activate',
    description:
        'Activate every imported object (programs, INCLUDEs, text elements, ' +
        'structures, tables, table types, classes, interfaces, function groups, ' +
        'function-group includes and function modules) in a single ADT ' +
        'activation request. Run this last, after all metadata and source code ' +
        'have been written, so interdependent objects are activated together ' +
        'instead of one by one.',
    command: 'prog-batch-activate [files...]',
    async action(ctx, opts, ...args) {
        const files = (args.length === 1 && Array.isArray(args[0]) ? args[0] : args)
            .filter(f => typeof f === 'string' && f.length > 0);
        if (files.length === 0) {
            console.error('Usage: abap prog-batch-activate <file.prog.abap|file.prog.xml|'
                + 'file.tabl.xml|file.ttyp.xml|file.clas.abap|file.intf.abap|'
                + 'file.fugr.xml|file.fugr.*.reps.abap|file.fugr.*.func.abap> ...');
            process.exitCode = 2;
            return;
        }
        const argv = Array.isArray(ctx?.argv) ? ctx.argv : [];
        const prettyJson = opts.prettyJson === true
            || argv.includes('--pretty-json')
            || process.env.ABAP_PRETTY_JSON === '1';

        const abapJson = await loadAbapJson();
        const creds = await loadProfile(abapJson.system);
        const client = new ADTClient(
            creds.url, creds.username, creds.password, creds.client,
            creds.language || 'EN',
            insecureFromEnv() || creds.insecure === true ? createSSLConfig(true) : undefined,
        );
        client.stateful = 'stateful';
        await client.login();

        // 1. Collect the distinct objects (a program is named both by its
        //    .prog.abap and its .prog.xml, so dedupe on the object URL).
        const results = [];
        const byUrl = new Map();
        for (const f of files) {
            let o;
            try {
                o = await resolveObject(f);
            } catch (e) {
                results.push({ file: f, action: 'failed', error: e?.message ?? String(e), stages: [] });
                continue;
            }
            if (o.skipped) {
                results.push({ file: f, action: 'skipped', error: o.skipped, stages: [] });
                continue;
            }
            const existing = byUrl.get(o.url.toLowerCase());
            if (existing) {
                existing.extraFiles.push(f);
                continue;
            }
            o.file = f;
            o.extraFiles = [];
            o.action = 'pending';
            o.stages = [`resolved: ${o.object} (${o.kind}) @ ${o.url}`];
            byUrl.set(o.url.toLowerCase(), o);
            results.push(o);

            // The textpool of a main program is a separate activatable object,
            // so it becomes its own entry with its own reference in the batch,
            // its own verdict and its own `adtcore:version` re-read.
            if (o.textUrl && !byUrl.has(o.textUrl.toLowerCase())) {
                const te = {
                    object: o.object,
                    kind: 'PROG/PX',
                    url: o.textUrl,
                    file: f,
                    extraFiles: [],
                    action: 'pending',
                    stages: [`resolved: ${o.object} (PROG/PX) @ ${o.textUrl}`,
                        `text elements pushed by prog-xml-push into the ${o.object} textpool`],
                };
                byUrl.set(te.url.toLowerCase(), te);
                results.push(te);
            }
        }

        const objects = [...byUrl.values()];
        if (objects.length === 0) {
            if (prettyJson) process.stdout.write(JSON.stringify(results, null, 2) + '\n');
            else console.log('prog-batch-activate: nothing to activate');
            return;
        }

        // 2. INCLUDEs need the main program that includes them. mainPrograms()
        //    returns every referencing program (an INCLUDE such as ZTR_SHOW_JD
        //    is shared by dozens), so prefer one that is part of this batch.
        const batchMainNames = new Set(
            objects.filter(o => o.kind === 'PROG/P').map(o => o.object),
        );
        for (const o of objects) {
            if (o.kind !== 'PROG/I') continue;
            try {
                const mains = await client.mainPrograms(o.url) ?? [];
                const progs = mains.filter(m => m?.['adtcore:type'] === 'PROG/P' && m['adtcore:uri']);
                const chosen = progs.find(m => batchMainNames.has(String(m['adtcore:name'] ?? '').toUpperCase()))
                    || progs[0];
                if (chosen) {
                    o.parentUri = chosen['adtcore:uri'];
                    o.stages.push(`parent: ${chosen['adtcore:name']}`);
                } else {
                    o.stages.push('parent: none found — activating without parentUri');
                }
            } catch (e) {
                o.stages.push(`parent: mainPrograms() failed — ${e?.message ?? e}`);
            }
        }

        // 3. Two-phase activation. Probing a real S/4 system established that
        //    /sap/bc/adt/activation handles the SAME object differently by
        //    reference shape:
        //      • full reference (uri+type+parentUri; parentUri=self for a root)
        //        → SAP silently leaves the ROOT inactive (even answering
        //          activationExecuted="true"), while every CHILD activates;
        //      • bare reference (uri+name only) → the ROOT activates:
        //        main program PROG/P, function group FUGR/F and the
        //        function-pool item FUGR/I @ <group>/source/main;
        //      • a bare root mixed with full children in ONE request makes SAP
        //        refuse the whole request (activationExecuted="false", no msg).
        //    Therefore phase A sends all objects with full references (children
        //    activate; the roots' self-parentUri refs are harmlessly ignored),
        //    then phase B sends only the roots with bare references. Both
        //    phases tolerate a per-object refusal by dropping the culprit and
        //    retrying, exactly like the previous single-phase version did.
        const executedIn = new Set();
        async function activationPhase(phaseObjects, refFn, tag) {
            let pending = phaseObjects.slice();
            for (let attempt = 0; attempt <= phaseObjects.length && pending.length > 0; attempt++) {
                let xml;
                try {
                    const res = await postActivation(client, pending, refFn);
                    xml = typeof res.body === 'string' ? res.body : JSON.stringify(res.body ?? '');
                } catch (e) {
                    const uri = e?.properties?.URI ?? e?.properties?.uri;
                    const idx = uri
                        ? pending.findIndex(o => o.url.toLowerCase() === String(uri).toLowerCase())
                        : -1;
                    if (idx < 0) {
                        // Not a per-object problem (auth, outage, …) — give up and
                        // report every remaining object as failed.
                        const reason = e?.message ?? String(e);
                        for (const o of pending) {
                            o.action = 'failed';
                            o.error = `activation request failed: ${reason}`;
                            o.stages.push(`activate${tag}: FAILED — ${reason}`);
                        }
                        pending = [];
                        break;
                    }
                    const o = pending[idx];
                    pending.splice(idx, 1);
                    o.action = 'failed';
                    o.error = `locked by another user/request: ${e?.message ?? e}`;
                    o.stages.push(`activate${tag}: REJECTED — ${e?.message ?? e}`);
                    o.stages.push(`activate${tag}: excluded from the batch, retrying the remaining objects`);
                    continue;
                }

                const props = propertiesOf(xml);
                if (props.activationExecuted !== 'false') {
                    const digest = digestOf(xml);
                    const msgs = messagesOf(xml);
                    for (const o of pending) {
                        executedIn.add(o);
                        o.stages.push(`activate${tag}: batch sent (${pending.length} object(s)) ${digest}`);
                        for (const m of msgs) o.stages.push(`activate${tag}: message — ${m}`);
                    }
                    break;
                }

                const blamed = blameOf(xml, pending);
                if (blamed.length === 0) break;   // nothing to point at — verdict below
                for (const b of blamed) {
                    pending = pending.filter(o => o !== b.object);
                    b.object.action = 'failed';
                    b.object.error = b.message || 'SAP refused to activate this object';
                    b.object.stages.push(`activate${tag}: REFUSED — ${b.message}`);
                    b.object.stages.push(`activate${tag}: excluded from the batch, retrying the remaining objects`);
                }
            }
        }

        // Phase A — all objects, full references (children activate; roots
        // travel along and are harmlessly skipped).
        await activationPhase(objects, referenceOf, '[children]');
        // Phase B — roots only, bare references (program / group / source/main).
        const roots = objects.filter(o => o.bareMain === true);
        await activationPhase(roots, bareReferenceOf, '[main]');

        // 4. Verdict. HTTP 200 is not a success signal — SAP answers it for a
        //    refused batch as well; an object counts as activated only when an
        //    executed request actually included it. Explicitly rejected/locked
        //    objects already carry action='failed'.
        for (const o of objects) {
            if (o.action !== 'failed' && executedIn.has(o)) o.action = 'activated';
            if (o.action === 'pending') {
                o.action = 'failed';
                o.error = 'no activation request was executed for this object';
                o.stages.push('verify : never included in an executed activation batch');
            }
        }

        // 5. Verify every activated object on its own. The batch response only
        //    proves that the activation ran; an object SAP does not know is
        //    ignored silently, so being absent from the inactive list is no
        //    proof either. `adtcore:version` of the object metadata answers
        //    both questions at once, a 404 means the object does not exist.
        for (const o of objects) {
            if (o.action !== 'activated') continue;
            // The function-pool source/main item answers 406 to a plain GET;
            // its active state is visible on the parent function group.
            const verifyUrl = /\/source\/main$/i.test(o.url) && o.parentUri ? o.parentUri : o.url;
            try {
                const res = await client.httpClient.request(verifyUrl, {
                    method: 'GET', headers: { Accept: 'application/*' },
                });
                const body = String(res.body ?? '');
                const version = (body.match(/adtcore:version="([^"]*)"/) ?? [])[1] ?? '';
                if (version.toLowerCase() === 'inactive') {
                    o.action = 'failed';
                    o.error = 'still inactive after activation';
                    o.stages.push('verify : object is still inactive');
                } else {
                    o.stages.push(`verify : confirmed active${version ? ` (version=${version})` : ''}`);
                }
            } catch (e) {
                if (e?.err === 404) {
                    o.action = 'failed';
                    o.error = 'object does not exist — it was not created by the earlier import steps';
                    o.stages.push('verify : SAP answers 404, the object is not in the system');
                } else {
                    o.stages.push(`verify : skipped (${e?.err ?? ''} ${e?.message ?? e})`);
                }
            }
        }

        if (prettyJson) {
            process.stdout.write(JSON.stringify(results, null, 2) + '\n');
            if (results.some(r => r.action === 'failed')) process.exitCode = 1;
        } else {
            for (const r of results) {
                const label = `${r.kind ?? '?'} ${r.object ?? r.file}`;
                if (r.action === 'failed') {
                    console.error(`✗ ${label}${r.error ? `: ${r.error}` : ''}`);
                    for (const s of r.stages ?? []) console.error(`    ${s}`);
                    process.exitCode = 1;
                } else {
                    console.log(`✓ ${r.action}: ${label}`);
                }
            }
        }
    },
});
