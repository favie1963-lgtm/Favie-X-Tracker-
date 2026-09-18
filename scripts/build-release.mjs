/**
 * Produces the publishable release APK.
 *
 * The release build only gets a signing config when android/keystore.properties
 * exists, so this script checks for it up front and explains how to create one
 * rather than letting gradle emit an unsigned artifact that a store will
 * reject. It then runs the tests, builds, and verifies the signature.
 *
 * Run with: npm run apk:publish
 */
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const androidDir = join(root, 'android');
const outputDir = join(androidDir, 'app/build/outputs/apk/release');

function run(command, args, cwd = root) {
  console.log(`\n$ ${command} ${args.join(' ')}`);
  execFileSync(command, args, { cwd, stdio: 'inherit' });
}

function fail(message) {
  console.error(`\nERROR: ${message}`);
  process.exit(1);
}

if (!existsSync(join(androidDir, 'keystore.properties'))) {
  fail(
    'android/keystore.properties is missing, so the release APK would be unsigned.\n' +
      '  1. cp android/keystore.properties.example android/keystore.properties\n' +
      '  2. keytool -genkeypair -v -keystore android/favie-release.jks \\\n' +
      '       -alias favie -keyalg RSA -keysize 2048 -validity 10000 \\\n' +
      '       -dname "CN=Favie X Tracker, O=Favie, C=US"\n' +
      '  3. Fill in the passwords in android/keystore.properties\n' +
      'Neither the keystore nor that file should be committed.'
  );
}

// Tests first: a signed artifact that fails parity is worse than no artifact.
run('npm', ['run', 'test:config']);
run('npm', ['run', 'test:vision']);
run('npm', ['run', 'build:android']);
run('./gradlew', ['assembleRelease'], androidDir);

const apk = readdirSync(outputDir)
  .filter((name) => name.endsWith('.apk'))
  .map((name) => join(outputDir, name))
  .find((path) => statSync(path).isFile());

if (!apk) fail(`no APK found in ${outputDir}`);
if (apk.endsWith('-unsigned.apk')) fail(`gradle produced an unsigned APK: ${apk}`);

const sdkRoot = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT;
const buildTools = sdkRoot && join(sdkRoot, 'build-tools');
if (buildTools && existsSync(buildTools)) {
  const version = readdirSync(buildTools).sort().pop();
  const apksigner = join(buildTools, version, 'apksigner');
  if (existsSync(apksigner)) {
    run(apksigner, ['verify', '--print-certs', apk], root);
  } else {
    console.warn(`\nSkipping signature check: ${apksigner} not found.`);
  }
} else {
  console.warn('\nSkipping signature check: ANDROID_HOME is not set.');
}

console.log(`\nPublishable APK: ${apk}`);
console.log('Upload this file to a GitHub release or the Play Console.');
