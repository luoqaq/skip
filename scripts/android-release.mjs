#!/usr/bin/env node
// Local Android release preparation & Cloudflare R2 publishing for Skip (com.luo.skip).
import { spawnSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { constants, createReadStream } from 'node:fs';
import { access, chmod, copyFile, mkdir, readFile, readdir, rename, stat, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
export const APPLICATION_ID = 'com.luo.skip';
export const MAX_APK_BYTES = 512 * 1024 * 1024;
export const MAX_LATEST_BYTES = 64 * 1024;
const FIELDS = ['schemaVersion', 'applicationId', 'versionCode', 'versionName', 'minSdk', 'size', 'sha256', 'url', 'notes', 'publishedAt'];
const fail = message => { throw new Error(message); };
const positiveInt = value => Number.isSafeInteger(value) && value > 0;
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const jsonBytes = value => Buffer.from(`${JSON.stringify(value, null, 2)}\n`);
const utf8 = bytes => new TextDecoder('utf-8', { fatal: true }).decode(bytes);

function validateApkMetadata({ versionCode, versionName, minSdk }) {
  if (!positiveInt(versionCode) || versionCode > 2_100_000_000 || typeof versionName !== 'string' || !versionName.trim() || versionName.length > 100) fail('Invalid APK version');
  if (!Number.isInteger(minSdk) || minSdk < 26 || minSdk > 1000) fail('Invalid minSdk');
}

export function normalizeSigner(value) {
  if (typeof value !== 'string' || !/^(?:[a-fA-F0-9]{64}|(?:[a-fA-F0-9]{2}:){31}[a-fA-F0-9]{2})$/.test(value)) fail('expected-signer must be a SHA-256 certificate digest');
  return value.replaceAll(':', '').toLowerCase();
}

function httpsUrl(value) {
  if (typeof value !== 'string' || value !== value.trim() || /[?#\s\\]/.test(value)) fail('URL must be HTTPS without credentials, query or fragment');
  let url;
  try { url = new URL(value); } catch { fail('Invalid HTTPS URL'); }
  if (url.protocol !== 'https:' || !url.hostname || url.username || url.password) fail('URL must be HTTPS without credentials, query or fragment');
  return url;
}

export function normalizeBaseUrl(value) {
  const url = httpsUrl(value);
  if (!url.pathname.endsWith('/')) url.pathname += '/';
  let path;
  try { path = decodeURIComponent(url.pathname); } catch { fail('Invalid URL path encoding'); }
  if (/[\\\u0000-\u001f\u007f]/.test(path) || /%2f|%5c/i.test(url.pathname) || path.split('/').some(part => part === '.' || part === '..')) fail('Ambiguous base URL path');
  return url.href;
}

function isoTimestamp(value) {
  if (typeof value !== 'string') return false;
  const parts = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(Z|[+-]\d{2}:\d{2})$/.exec(value);
  if (!parts || !Number.isFinite(Date.parse(value))) return false;
  const [year, month, day, hour, minute, second] = parts.slice(1, 7).map(Number);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return month >= 1 && month <= 12 && day >= 1 && day <= days[month - 1] && hour < 24 && minute < 60 && second < 60;
}

export function validateLatest(value, latestUrl, bytes = jsonBytes(value)) {
  if (bytes.length > MAX_LATEST_BYTES) fail('latest.json exceeds 64 KiB');
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== FIELDS.length || FIELDS.some(key => !Object.hasOwn(value, key))) fail('Invalid latest.json fields');
  if (value.schemaVersion !== 1 || value.applicationId !== APPLICATION_ID) fail('Unsupported schemaVersion or applicationId');
  validateApkMetadata(value);
  if (!positiveInt(value.size) || value.size > MAX_APK_BYTES) fail('Invalid APK size (maximum 512 MiB)');
  if (typeof value.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(value.sha256)) fail('Invalid APK sha256');
  const apkUrl = httpsUrl(value.url);
  if (apkUrl.origin !== httpsUrl(latestUrl).origin || !apkUrl.pathname.endsWith('.apk')) fail('APK must be a same-origin HTTPS APK URL');
  if (typeof value.notes !== 'string' || value.notes.length > 8000) fail('notes must be a string of at most 8000 characters');
  if (!isoTimestamp(value.publishedAt)) fail('publishedAt must be ISO8601');
  return value;
}

export function parseAaptBadging(output) {
  const packages = [...output.matchAll(/^package: (.+)$/gm)];
  if (packages.length !== 1) fail('aapt must report exactly one package');
  const attr = name => {
    const matches = [...packages[0][1].matchAll(new RegExp(`(?:^| )${name}='([^']*)'`, 'g'))];
    if (matches.length !== 1) fail(`Missing or ambiguous aapt ${name}`);
    return matches[0][1];
  };
  const sdk = [...output.matchAll(/^sdkVersion:'([0-9]+)'\r?$/gm)];
  const major = /(?:^| )versionCodeMajor='([^']*)'/.exec(packages[0][1]);
  if (major && major[1] !== '0') fail('APK versionCodeMajor is not supported by this release protocol');
  const code = attr('versionCode');
  if (!/^[1-9][0-9]*$/.test(code) || sdk.length !== 1) fail('Invalid aapt versionCode or minSdk');
  const metadata = { applicationId: attr('name'), versionCode: Number(code), versionName: attr('versionName'), minSdk: Number(sdk[0][1]) };
  if (metadata.applicationId !== APPLICATION_ID) fail(`APK applicationId must be ${APPLICATION_ID}`);
  validateApkMetadata(metadata);
  return metadata;
}

export function parseApkSigner(output) {
  const matches = [...output.matchAll(/^Signer #(\d+) certificate SHA-256 digest: ([a-fA-F0-9]{64})\r?$/gm)];
  if (matches.length !== 1 || matches[0][1] !== '1') fail('APK must have exactly one verified signer');
  return normalizeSigner(matches[0][2]);
}

export function runCommand(command, args) {
  const result = spawnSync(command, args, { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 8 * 1024 * 1024, timeout: 120_000, shell: false });
  if (result.error || result.status !== 0) fail('Release tool failed; check tool availability and APK validity locally');
  return result.stdout;
}

export async function discoverTools(options = {}, env = process.env, fs = { readdir, access }) {
  const sdkRoots = [env.ANDROID_SDK_ROOT, env.ANDROID_HOME, join(homedir(), 'Library/Android/sdk'), join(homedir(), 'Android/Sdk'), env.LOCALAPPDATA && join(env.LOCALAPPDATA, 'Android/Sdk')].filter(Boolean);
  async function discover(name) {
    const explicit = options[name] || env[`${name.toUpperCase()}_PATH`] || env[name.toUpperCase()];
    if (explicit) return explicit;
    for (const sdk of sdkRoots) {
      let versions;
      try { versions = await fs.readdir(join(sdk, 'build-tools')); } catch { continue; }
      versions = versions.filter(v => /^\d+(?:\.\d+)*(?:[-\w.]*)$/.test(v)).sort((a, b) => b.localeCompare(a, 'en', { numeric: true }));
      for (const version of versions) {
        const candidate = join(sdk, 'build-tools', version, name + (process.platform === 'win32' && name === 'aapt' ? '.exe' : ''));
        try { await fs.access(candidate, constants.X_OK); return candidate; } catch {}
      }
    }
    return name;
  }
  return { aapt: await discover('aapt'), apksigner: await discover('apksigner') };
}

export async function inspectApk(apk, options = {}, deps = {}) {
  const expected = normalizeSigner(options.expectedSigner);
  const tools = await discoverTools(options, deps.env);
  const run = deps.runCommand || runCommand;
  const metadata = parseAaptBadging(await run(tools.aapt, ['dump', 'badging', apk]));
  const signerSha256 = parseApkSigner(await run(tools.apksigner, ['verify', '--verbose', '--print-certs', apk]));
  if (signerSha256 !== expected) fail('APK signer does not match expected-signer');
  return { ...metadata, signerSha256 };
}

async function hashFile(path) {
  const hash = createHash('sha256');
  let size = 0;
  for await (const bytes of createReadStream(path)) {
    size += bytes.length;
    if (size > MAX_APK_BYTES) fail('APK exceeds 512 MiB');
    hash.update(bytes);
  }
  if (!size) fail('APK is empty');
  return { size, sha256: hash.digest('hex') };
}

async function boundedFile(path, max) {
  if ((await stat(path)).size > max) fail('Input file exceeds size limit');
  const bytes = await readFile(path);
  if (bytes.length > max) fail('Input file exceeds size limit');
  return bytes;
}

function releasePaths(baseUrl, latest) {
  const apkRelative = `releases/skip-${latest.versionCode}-${latest.sha256}.apk`;
  return { apkRelative, latestUrl: new URL('latest.json', baseUrl).href, apkUrl: new URL(apkRelative, baseUrl).href };
}

export async function prepareRelease(options, deps = {}) {
  normalizeSigner(options.expectedSigner);
  if (!options.apk || !options.notesFile) fail('--apk and --notes-file are required');
  const baseUrl = normalizeBaseUrl(options.baseUrl);
  let notes;
  try { notes = utf8(await boundedFile(options.notesFile, 32_000)); } catch { fail('notes-file must be UTF8 and at most 8000 characters'); }
  if (notes.length > 8000) fail('notes must be at most 8000 characters');
  const source = await stat(options.apk);
  if (!source.isFile() || !source.size || source.size > MAX_APK_BYTES) fail('APK must be a nonempty file of at most 512 MiB');
  const runName = `run-${new Date().toISOString().replaceAll(':', '-')}-${randomBytes(6).toString('hex')}`;
  const directory = resolve(options.output || join(ROOT, 'output/android-release', runName));
  await mkdir(dirname(directory), { recursive: true });
  await mkdir(directory);
  const frozen = join(directory, 'candidate.apk');
  await copyFile(options.apk, frozen, constants.COPYFILE_EXCL);
  await chmod(frozen, 0o444);
  const actual = await inspectApk(frozen, options, deps);
  const hash = await hashFile(frozen);
  const latest = { schemaVersion: 1, applicationId: actual.applicationId, versionCode: actual.versionCode, versionName: actual.versionName, minSdk: actual.minSdk, ...hash, url: '', notes, publishedAt: new Date().toISOString() };
  const paths = releasePaths(baseUrl, latest);
  latest.url = paths.apkUrl;
  validateLatest(latest, paths.latestUrl);
  const latestBytes = jsonBytes(latest);
  const manifest = { schemaVersion: 1, baseUrl, apk: paths.apkRelative, signerSha256: actual.signerSha256, latestSha256: digest(latestBytes) };
  await mkdir(join(directory, 'releases'));
  await rename(frozen, join(directory, paths.apkRelative));
  await writeFile(join(directory, 'latest.json'), latestBytes, { flag: 'wx', mode: 0o444 });
  await writeFile(join(directory, 'release-manifest.json'), jsonBytes(manifest), { flag: 'wx', mode: 0o444 });
  return { directory, latest, manifest };
}

export async function loadPrepared(directory, options = {}, deps = {}) {
  const expected = normalizeSigner(options.expectedSigner);
  directory = resolve(directory);
  let manifest, latest;
  const latestBytes = await boundedFile(join(directory, 'latest.json'), MAX_LATEST_BYTES);
  try {
    manifest = JSON.parse(utf8(await boundedFile(join(directory, 'release-manifest.json'), MAX_LATEST_BYTES)));
    latest = JSON.parse(utf8(latestBytes));
  } catch { fail('Invalid prepared release JSON'); }
  const keys = ['schemaVersion', 'baseUrl', 'apk', 'signerSha256', 'latestSha256'];
  if (!manifest || Object.keys(manifest).length !== keys.length || keys.some(key => !Object.hasOwn(manifest, key)) || manifest.schemaVersion !== 1) fail('Invalid release manifest');
  const baseUrl = normalizeBaseUrl(manifest.baseUrl);
  const paths = releasePaths(baseUrl, latest);
  validateLatest(latest, paths.latestUrl, latestBytes);
  if (manifest.apk !== paths.apkRelative || latest.url !== paths.apkUrl || manifest.latestSha256 !== digest(latestBytes) || manifest.signerSha256 !== expected) fail('Prepared release manifest mismatch');
  const apkFile = join(directory, paths.apkRelative);
  const hash = await hashFile(apkFile);
  if (hash.sha256 !== latest.sha256 || hash.size !== latest.size) fail('Frozen APK hash or size mismatch');
  const actual = await inspectApk(apkFile, options, deps);
  if (['applicationId', 'versionCode', 'versionName', 'minSdk'].some(key => latest[key] !== actual[key])) fail('Frozen APK metadata mismatch');
  return { directory, latest, manifest, latestBytes, apkFile, ...paths };
}

async function getPublic(url, fetchImpl) {
  try {
    return await fetchImpl(url, { method: 'GET', redirect: 'error', cache: 'no-store', headers: { 'Cache-Control': 'no-cache', 'Accept-Encoding': 'identity' }, signal: AbortSignal.timeout(120_000) });
  } catch { fail('Public GET failed; release stopped'); }
}

async function readResponse(response, max, collect = false) {
  const length = response.headers.get('content-length');
  if (length !== null && (!/^\d+$/.test(length) || Number(length) > max)) {
    await response.body?.cancel();
    fail('Public response exceeds size limit');
  }
  const hash = createHash('sha256');
  const chunks = [];
  let size = 0;
  if (!response.body) fail('Public response has no body');
  try {
    for await (const bytes of response.body) {
      size += bytes.length;
      if (size > max) fail('Public response exceeds size limit');
      hash.update(bytes);
      if (collect) chunks.push(Buffer.from(bytes));
    }
  } catch { fail('Public response incomplete or exceeds size limit'); }
  if (length !== null && Number(length) !== size) fail('Public response Content-Length mismatch');
  return { size, sha256: hash.digest('hex'), bytes: collect ? Buffer.concat(chunks) : undefined };
}

async function remoteLatest(url, fetchImpl) {
  const response = await getPublic(url, fetchImpl);
  if (response.status === 404) { await response.body?.cancel(); return null; }
  if (response.status !== 200) { await response.body?.cancel(); fail(`Remote latest GET HTTP ${response.status}; release stopped`); }
  const body = await readResponse(response, MAX_LATEST_BYTES, true);
  let latest;
  try { latest = JSON.parse(utf8(body.bytes)); } catch { fail('Remote latest is not valid UTF8 JSON'); }
  validateLatest(latest, url, body.bytes);
  return latest;
}

function assertNewer(local, remote) {
  if (remote && local.versionCode <= remote.versionCode) fail('versionCode must be higher than remote latest');
}

export function uploadArguments(bucket, key, file, latest = false) {
  return ['r2', 'object', 'put', `${bucket}/${key}`, '--remote', '--file', file, '--content-type', latest ? 'application/json; charset=utf-8' : 'application/vnd.android.package-archive', '--cache-control', latest ? 'no-store' : 'public, max-age=31536000, immutable'];
}

export function runWrangler(args) {
  const wranglerBin = process.env.WRANGLER_BIN || resolve(ROOT, '../stock/tools/alma-relay/node_modules/.bin/wrangler');
  runCommand(wranglerBin, args);
}

export async function publishRelease(directory, options = {}, deps = {}) {
  const release = await loadPrepared(directory, options, deps);
  if (options.apply !== true) return { status: 'prepared', ...release };
  if (!/^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/.test(options.bucket || '')) fail('--apply requires an explicit valid --bucket');
  const fetchImpl = deps.fetch || globalThis.fetch;
  const wrangler = deps.wrangler || runWrangler;
  assertNewer(release.latest, await remoteLatest(release.latestUrl, fetchImpl));
  const existing = await getPublic(release.apkUrl, fetchImpl);
  if (existing.status === 404) {
    await existing.body?.cancel();
    const key = decodeURIComponent(new URL(release.apkUrl).pathname.slice(1));
    await wrangler(uploadArguments(options.bucket, key, release.apkFile));
  } else if (existing.status === 200) {
    const hash = await readResponse(existing, MAX_APK_BYTES);
    if (hash.sha256 !== release.latest.sha256 || hash.size !== release.latest.size) fail('Immutable APK URL already exists with different content');
  } else {
    await existing.body?.cancel();
    fail(`APK existence GET HTTP ${existing.status}; release stopped`);
  }
  const apkResponse = await getPublic(release.apkUrl, fetchImpl);
  if (apkResponse.status !== 200) { await apkResponse.body?.cancel(); fail(`Public APK GET HTTP ${apkResponse.status}; latest not uploaded`); }
  const publicApk = await readResponse(apkResponse, MAX_APK_BYTES);
  if (publicApk.sha256 !== release.latest.sha256 || publicApk.size !== release.latest.size) fail('Public APK hash or size mismatch; latest not uploaded');
  assertNewer(release.latest, await remoteLatest(release.latestUrl, fetchImpl));
  const checked = await loadPrepared(directory, options, deps);
  if (!checked.latestBytes.equals(release.latestBytes)) fail('Prepared release changed during publication');
  const latestKey = decodeURIComponent(new URL(release.latestUrl).pathname.slice(1));
  await wrangler(uploadArguments(options.bucket, latestKey, join(release.directory, 'latest.json'), true));
  const response = await getPublic(release.latestUrl, fetchImpl);
  if (response.status !== 200) { await response.body?.cancel(); fail(`Published latest GET HTTP ${response.status}; verification failed`); }
  const result = await readResponse(response, MAX_LATEST_BYTES, true);
  if (!result.bytes.equals(release.latestBytes)) fail('Published latest differs from frozen latest.json');
  return { status: 'published', ...release };
}

export function parseArgs(argv) {
  const values = { '--apk': 'apk', '--base-url': 'baseUrl', '--notes-file': 'notesFile', '--expected-signer': 'expectedSigner', '--output': 'output', '--aapt': 'aapt', '--apksigner': 'apksigner', '--bucket': 'bucket', '--prepared': 'prepared' };
  const flags = { '--apply': 'apply', '--dry-run': 'dryRun', '--help': 'help' };
  const options = {};
  for (let i = 0; i < argv.length; i++) {
    const [name, ...rest] = argv[i].split('=');
    const key = values[name] || flags[name];
    if (!key) fail('Unknown release option (see --help)');
    if (Object.hasOwn(options, key)) fail(`Duplicate ${name}`);
    if (flags[name]) {
      if (rest.length) fail(`Unexpected value for ${name}`);
      options[key] = true;
    } else {
      const value = rest.length ? rest.join('=') : argv[++i];
      if (!value || value.startsWith('--')) fail(`Missing value for ${name}`);
      options[key] = value;
    }
  }
  if (options.apply && options.dryRun) fail('--dry-run cannot be combined with --apply');
  if (options.apply && !options.bucket) fail('--apply requires --bucket');
  if (options.prepared && ['apk', 'baseUrl', 'notesFile', 'output'].some(key => options[key])) fail('--prepared cannot be combined with preparation inputs');
  return options;
}

const HELP = `Usage (no upload by default):
  node scripts/android-release.mjs --apk candidate.apk --base-url https://yanjiu-android-updates.bojin1229.workers.dev/skip/ --notes-file notes.txt --expected-signer SHA256 [--output NEW_DIRECTORY] [--dry-run]
  node scripts/android-release.mjs --prepared RELEASE_DIRECTORY --expected-signer SHA256 --apply --bucket BUCKET
`;

const quote = value => `'${String(value).replaceAll("'", "'\\''")}'`;
export async function main(argv = process.argv.slice(2)) {
  const options = parseArgs(argv);
  if (options.help) { console.log(HELP); return; }
  const prepared = options.prepared ? { directory: resolve(options.prepared) } : await prepareRelease(options);
  const result = await publishRelease(prepared.directory, options);
  console.log(`${result.status}: ${result.directory}`);
  console.log(`APK: ${result.latest.applicationId} ${result.latest.versionName} (${result.latest.versionCode}), ${result.latest.size} bytes`);
  console.log(`SHA-256: ${result.latest.sha256}\nSigner SHA-256: ${result.manifest.signerSha256}`);
  if (!options.apply) {
    const next = [process.execPath, join(ROOT, 'scripts/android-release.mjs'), '--prepared', result.directory, '--expected-signer', result.manifest.signerSha256];
    for (const key of ['aapt', 'apksigner']) if (options[key]) next.push(`--${key}`, options[key]);
    console.log(`Next (uploads only when explicitly executed):\n${next.map(quote).join(' ')} --apply --bucket ${quote(options.bucket || 'YOUR_BUCKET')}`);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => { console.error(`android-release: ${error.message}`); process.exitCode = 1; });
}
