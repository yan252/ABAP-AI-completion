#!/usr/bin/env node
/**
 * Temporary connection-prefs sync for the Eclipse plugin import pipeline.
 *
 * The plugin's SAP Preference-page fields (abap-cli URL / client / language /
 * user / password) drive the whole import. The 5 `prog-*` extension commands
 * accept $ABAP_* env overrides, but the abap-cli BUILT-IN commands used by
 * the pipeline (`inspect`, `transport create`) read connection data ONLY from
 * `~/.abap-cli/systems.json` (url/client/username/language) and the OS
 * keychain (password, SERVICE='abap-cli', account=<system name>). So to make
 * every step use the Preference-page connection info we temporarily:
 *
 *   1. snapshot the current systems.json content + keychain password,
 *   2. overwrite the resolved system profile with the Preference-page values,
 *   3. run the pipeline,
 *   4. restore the snapshot.
 *
 * Usage:
 *   node _prefs_snapshot.mjs snapshot <systemName>
 *       → prints one-line JSON: {"file": <systems.json text or null>, "password": <pwd or null>}
 *   node _prefs_snapshot.mjs apply <systemName> <url> <client> <username> <language> <password>
 *       → writes url(client/username/language only when non-empty) into the
 *         profile (+ creates it if missing) and, when <password> is non-empty,
 *         stores it in the keychain; prints `applied:ok`
 *   node _prefs_snapshot.mjs restore <snapshotFile>
 *       → writes the snapshotted systems.json text back (or deletes the file
 *         if it did not exist) and restores the previous keychain entry;
 *         prints `restored:ok`
 *
 * Exit code 0 on success, 1 on failure.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import keytar from 'keytar';

const SERVICE = 'abap-cli';
// ABAP_CLI_HOME is a testing seam (mirrors `config/paths.ts` injectable home);
// defaults to the real home directory.
const HOME = process.env.ABAP_CLI_HOME || os.homedir();
const FILE = path.join(HOME, '.abap-cli', 'systems.json');

const [mode, systemName, ...rest] = process.argv.slice(2);

function fail(msg) {
    console.error(`[_prefs_snapshot] ${msg}`);
    process.exit(1);
}

async function snapshot() {
    if (!systemName) fail('snapshot requires <systemName>');
    let fileText = null;
    try {
        if (fs.existsSync(FILE)) fileText = fs.readFileSync(FILE, 'utf-8');
    } catch { /* leave null */ }
    let password = null;
    try {
        password = await keytar.getPassword(SERVICE, systemName) ?? null;
    } catch (e) {
        password = null;
    }
    console.log(JSON.stringify({ account: systemName, file: fileText, password }));
}

async function applyPrefs() {
    if (!systemName) fail('apply requires <systemName>');
    if (rest.length < 5) fail('apply requires <url> <client> <username> <language> <password>');
    const [rawUrl, rawClient, rawUsername, rawLanguage] = rest.slice(0, 4);
    const password = rest[4];
    // Trim whitespace so a page filled with spaces never masquerades as a real
    // value, and never clobbers a previously working URL.
    const url = rawUrl?.trim?.() ?? '';
    const client = rawClient?.trim?.() ?? '';
    const username = rawUsername?.trim?.() ?? '';
    const language = rawLanguage?.trim?.() ?? '';
    if (!url) fail('apply requires a non-empty <url>');

    // read or create the profile map, preserving v1 (flat) vs v2 ({systems})
    // shape — but always write v2 for fresh/empty files because abap-cli's
    // BUILT-IN commands (config/user-config.js) only read `raw.systems`.
    let existed = false;
    let root = {};
    if (fs.existsSync(FILE)) {
        existed = true;
        try {
            root = JSON.parse(fs.readFileSync(FILE, 'utf-8'));
        } catch (e) {
            fail(`cannot parse ${FILE}: ${e?.message}`);
        }
    }
    const isEmpty = !root || typeof root !== 'object' || Object.keys(root).length === 0;
    const v2 = (root.systems && typeof root.systems === 'object') || !existed || isEmpty;
    const all = v2 ? (root.systems = root.systems ?? {}) : root;

    const keys = Object.keys(all ?? {});
    const key = keys.find(k => k === systemName) ||
                keys.find(k => k.toUpperCase() === systemName.toUpperCase()) || systemName;

    // Empty args keep whatever the profile already stored, so a partially
    // filled preference page never clobbers a working client/language/user.
    const existing = all[key] ?? {};
    all[key] = {
        ...existing,
        ...(url ? { url } : {}),
        ...(client ? { client } : {}),
        ...(username ? { username } : {}),
        ...(language ? { language } : {}),
    };

    fs.mkdirSync(path.dirname(FILE), { recursive: true, mode: 0o700 });
    fs.writeFileSync(FILE, JSON.stringify(v2 ? { systems: all } : all, null, 2) + '\n', {
        encoding: 'utf-8',
        mode: 0o600,
    });

    // Keychain password: only overwrite when a password is supplied; an empty
    // value leaves the existing keychain entry untouched (preserve semantics).
    if (password) {
        try {
            await keytar.setPassword(SERVICE, key, password);
        } catch (e) {
            fail(`keychain set failed for '${key}': ${e?.message ?? e}`);
        }
    }
    console.log(`applied:ok`);
}

async function restorePrefs() {
    const snapshotFile = systemName; // second positional under restore mode
    if (!snapshotFile) fail('restore requires <snapshotFile>');
    let snap;
    try {
        snap = JSON.parse(fs.readFileSync(snapshotFile, 'utf-8'));
    } catch (e) {
        fail(`cannot read snapshot ${snapshotFile}: ${e?.message}`);
    }
    const { file, password, account } = snap;
    const acc = account || '';

    if (file != null) {
        fs.mkdirSync(path.dirname(FILE), { recursive: true, mode: 0o700 });
        fs.writeFileSync(FILE, file, { encoding: 'utf-8', mode: 0o600 });
    } else if (fs.existsSync(FILE)) {
        // original file did not exist -> remove what we created
        try { fs.unlinkSync(FILE); } catch { /* ignore */ }
    }

    if (acc) {
        try {
            if (password != null) {
                await keytar.setPassword(SERVICE, acc, password);
            } else {
                await keytar.deletePassword(SERVICE, acc);
            }
        } catch (e) {
            console.error(`[_prefs_snapshot] keychain restore failed for '${acc}': ${e?.message ?? e}`);
        }
    }
    console.log('restored:ok');
}

switch (mode) {
    case 'snapshot': await snapshot(); break;
    case 'apply': await applyPrefs(); break;
    case 'restore': await restorePrefs(); break;
    default: fail(`unknown mode: ${mode}`);
}
