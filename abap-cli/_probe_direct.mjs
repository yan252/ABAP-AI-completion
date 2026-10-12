#!/usr/bin/env node
/**
 * _probe_direct.mjs — 独立 abapGit / abap-cli 连接探测脚本。
 *
 * 用途：Eclipse 插件 "SAP 配置" 页的 "Test Connection" 现在要用**配置页自身**
 * 的 SAP 连接信息（URL、Client、用户名、密码）做 abapGit 方式(abap-cli 同款)
 * 的连接测试，而不是读 ~/.abap-cli/systems.json + 系统钥匙串的 profile。
 * abap-cli CLI 自身不允许通过命令行覆盖 url/username/password（`profile test`
 * 零参数、密码只在钥匙串），因此本脚本直接在进程内用 abap-adt-api 复刻
 * `abap-cli profile test` 的探测逻辑：tls → auth → adt → icf（下游失败则上游
 * 层传 skipped），输出与人读格式兼容的行，供 Java 端 parseLayers() 复用。
 *
 * 用法：
 *   node _probe_direct.mjs --url <url> --client <c> --username <u> --password <p>
 *        [--language EN] [--insecure 1] [--fallback-url <http-url>]
 *
 * 输出（stdout，兼容 `abap-cli profile test` 的人读格式；注意不要输出
 * 带冒号的标题行，否则 Java 端 parseLayers() 会把标题里的 scheme/端口
 * 误抓成多余"层"键）：
 *   tls: ok
 *   auth: ok
 *   adt: ok
 *   icf: error — <message>
 *   url: <实际使用的 URL>
 * 退出码：tls+auth+adt 全 ok → 0；否则 → 1（Java 端以层状态为准，退出码仅供参考）。
 */
import { ADTClient, createSSLConfig } from 'abap-adt-api';
import * as tls from 'tls';

const MAX_DETAIL = 180;

function parseArgs(argv) {
    const out = {};
    for (let i = 0; i < argv.length; i++) {
        const a = argv[i];
        if (!a.startsWith('--')) continue;
        const key = a.slice(2);
        const eq = key.indexOf('=');
        if (eq >= 0) {
            out[key.slice(0, eq)] = key.slice(eq + 1);
        } else if (i + 1 < argv.length && !argv[i + 1].startsWith('--')) {
            out[key] = argv[++i];
        } else {
            out[key] = '1';
        }
    }
    return out;
}

function skipped() {
    return { ok: false, status: 'skipped', detail: '' };
}

function clip(s) {
    s = (s || '').replace(/\s+/g, ' ').trim();
    return s.length > MAX_DETAIL ? s.slice(0, MAX_DETAIL) + '…' : s;
}

/** TLS 层：https 做原始握手；http 直接 ok（与 abap-cli probeTls 一致）。 */
function probeTls(url, config) {
    if (!url.startsWith('https://')) {
        return Promise.resolve({ ok: true, status: 'ok', detail: '' });
    }
    let u;
    try {
        u = new URL(url);
    } catch (e) {
        return Promise.resolve({ ok: false, status: 'error', detail: ' — Invalid URL: ' + url });
    }
    return new Promise((resolve) => {
        const socket = tls.connect({
            host: u.hostname,
            port: Number(u.port) || 443,
            servername: u.hostname,
            rejectUnauthorized: !config.insecure,
        }, () => {
            socket.destroy();
            resolve({ ok: true, status: 'ok', detail: '' });
        });
        socket.on('error', (err) => {
            resolve({ ok: false, status: 'error', detail: ' — ' + clip(err?.message || String(err)) });
        });
    });
}

/** auth / adt / icf：ADTClient login + GET 指定端点（与 abap-cli probeWithAdtClient 一致）。 */
async function probeWithAdtClient(url, config, endpoint) {
    try {
        const options = createSSLConfig(config.insecure, undefined);
        const client = new ADTClient(
            url, config.username, config.password, config.client, config.language, options);
        await client.login();
        await client.httpClient.request(endpoint.path, {
            method: 'GET',
            headers: { Accept: endpoint.accept },
        });
        return { ok: true, status: 'ok', detail: '' };
    } catch (error) {
        return { ok: false, status: 'error', detail: ' — ' + clip(error?.message || String(error)) };
    }
}

/**
 * 对单个 URL 运行四层探测，输出与人读格式兼容的行。
 * @returns {{ok:boolean, lines:string[]}}
 */
async function runProbe(url, config) {
    const lines = [];
    const tlsResult = await probeTls(url, config);
    const authResult = tlsResult.ok
        ? await probeWithAdtClient(url, config, { path: '/sap/bc/adt/compatibility/graph', accept: 'application/xml' })
        : skipped();
    const adtResult = authResult.ok
        ? await probeWithAdtClient(url, config, { path: '/sap/bc/adt/discovery', accept: 'application/atomsvc+xml' })
        : skipped();
    const icfResult = authResult.ok
        ? await probeWithAdtClient(url, config, { path: '/sap/zabap_vibe/', accept: 'application/json' })
        : skipped();
    lines.push(`  tls: ${tlsResult.status}${tlsResult.detail}`);
    lines.push(`  auth: ${authResult.status}${authResult.detail}`);
    lines.push(`  adt: ${adtResult.status}${adtResult.detail}`);
    lines.push(`  icf: ${icfResult.status}${icfResult.detail}`);
    return { ok: tlsResult.ok && authResult.ok && adtResult.ok, lines };
}

const args = parseArgs(process.argv.slice(2));
const url = (args.url || '').trim();
if (!url) {
    console.error('Missing --url');
    process.exit(2);
}

const config = {
    url,
    client: (args.client || '').trim() || '001',
    username: (args.username || '').trim() || '',
    language: (args.language || '').trim() || 'EN',
    password: args.password || '',
    insecure: args.insecure !== '0',
};

let result = await runProbe(url, config);
let used = url;

// 主 URL 失败且给定了 http 备用 URL 时，重跑一次（SAP 系统常同时开 http/https，
// 44300+编号 不通时回退 8000+编号），结果里标明实际使用的 URL。
if (!result.ok && args['fallback-url'] && (args['fallback-url'] || '').trim()) {
    const fb = (args['fallback-url'] || '').trim();
    const fbResult = await runProbe(fb, config);
    if (fbResult.ok) {
        result = fbResult;
        used = fb;
    } else {
        result.lines.push('  -- both primary and http fallback probes failed');
    }
}
result.lines.push(`  url: ${used}`);

console.log(result.lines.join('\n'));
process.exit(result.ok ? 0 : 1);
