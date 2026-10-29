#!/usr/bin/env python3
"""Build GhostLock HONOR with the Android SDK, NDK and Java toolchain."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main'
PACKAGE = 'dev.ghostlock.honor'
VERSION = '1.0.0'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def run(args, timeout=180, cwd=None):
    result = subprocess.run([str(x) for x in args], cwd=cwd, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout + result.stderr


def supported(build, package, values, arrays, extra):
    folder = build / 'generated' / package.replace('.', '/')
    folder.mkdir(parents=True, exist_ok=True)
    lines = ['package ' + package + ';', 'final class Supported {', *extra]
    lines += [f'static final String {k} = {json.dumps(v)};' for k, v in values.items()]
    lines += ['static final String[] ' + k + ' = {' + ','.join(json.dumps(x) for x in v) + '};' for k, v in arrays.items()]
    (folder / 'Supported.java').write_text('\n'.join(lines + ['}']) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME'))
    parser.add_argument('--ndk', type=Path, default=os.environ.get('ANDROID_NDK_HOME') or os.environ.get('ANDROID_NDK_ROOT'))
    parser.add_argument('--build-dir', type=Path, default=ROOT / 'build')
    parser.add_argument('--version-code', type=int, default=1)
    parser.add_argument('--enable-recovery', action='store_true')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--keystore', type=Path)
    parser.add_argument('--key-alias', default='androiddebugkey')
    parser.add_argument('--password-file', type=Path)
    args = parser.parse_args()
    if not args.sdk or not args.ndk:
        parser.error('Set Android SDK and NDK paths with --sdk/--ndk or their environment variables')
    if not 0 < args.version_code < 2100000000:
        parser.error('Invalid version code')
    if bool(args.keystore) != bool(args.password_file):
        parser.error('--keystore and --password-file must be supplied together')
    os.umask(0o077)
    build = args.build_dir.resolve()
    build.mkdir(parents=True, exist_ok=True)
    sdk, ndk = args.sdk.resolve(), args.ndk.resolve()
    versions = sorted(p for p in (sdk / 'build-tools').glob('*') if p.is_dir())
    tools = versions[-1] if versions else sdk / 'android-15'
    android = sdk / 'platforms/android-35/android.jar'
    if not android.exists():
        android = sdk / 'android-35/android.jar'
    if not android.is_file():
        parser.error('SDK Platform 35 is required')
    for name in ['aidl', 'aapt2', 'd8', 'zipalign']:
        if not (tools / name).is_file():
            parser.error('Missing SDK tool: ' + name)
    run(['make', '-C', ROOT / 'native', '-j4', f'NDK_ROOT={ndk}', f'BUILD_ROOT={build}', 'ghostlock'], 300)
    native = build / 'native/ghostlock'
    for name in ['classes', 'generated', 'dex', 'assets', 'host-tests']:
        directory = build / name
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir()
    deps = build / 'deps'
    deps.mkdir(exist_ok=True)
    jars = []
    for item in json.loads((ROOT / 'dependencies.lock.json').read_text()):
        target = deps / item['filename']
        if not target.exists():
            if args.offline:
                raise RuntimeError('Missing cached dependency: ' + target.name)
            run(['curl', '-fLsS', '--max-time', '60', '-o', target, item['url']], 65)
        if sha(target) != item['sha256']:
            raise RuntimeError('Dependency hash mismatch: ' + target.name)
        if target.suffix == '.aar':
            jar = deps / (item['name'] + '.jar')
            with zipfile.ZipFile(target) as archive:
                jar.write_bytes(archive.read('classes.jar'))
            jars.append(jar)
        else:
            jars.append(target)
    config = json.loads((ROOT / 'config/device.json').read_text())
    profile = APP / 'assets/profile.bin'
    if sha(profile) != config['assets']['profile.bin']['sha256']:
        raise RuntimeError('Profile hash mismatch')
    assets = build / 'assets'
    shutil.copytree(APP / 'assets', assets, dirs_exist_ok=True)
    shell = assets / 'honor400'
    shutil.copy2(native, shell / 'ghostlock')
    shutil.copy2(profile, shell / 'profile.bin')
    config['assets']['ghostlock'] = {'sha256': sha(native), 'bytes': native.stat().st_size}
    (shell / 'manifest.json').write_text(json.dumps(config, indent=2) + '\n')
    licenses = assets / 'licenses'
    licenses.mkdir()
    shutil.copy2(ROOT / 'LICENSE', licenses / 'Apache-2.0.txt')
    shutil.copy2(ROOT / 'licenses/Shizuku-API-MIT.txt', licenses / 'Shizuku-API-MIT.txt')
    for script in [assets / 'handoff.sh', shell / 'handoff.sh']:
        run(['sh', '-n', script])
    common = {'KERNEL': config['kernel_release'], 'BUILD': config['build'], 'MANAGER': config['manager_package'],
              'MANAGER_SHA': config['manager_apk_sha256'], 'LOADER_SHA': config['manager_loader_sha256']}
    boot = {'BOOT_NAMES': list(config['stock_boot_sha256']), 'BOOT_SHA': list(config['stock_boot_sha256'].values())}
    supported(build, PACKAGE, dict(common, VERSION_NAME=VERSION, NATIVE_SHA=sha(native), PROFILE_SHA=sha(profile)),
              dict(boot, ASSETS=['profile.bin', 'handoff.sh'], ASSET_SHA=[sha(profile), sha(assets / 'handoff.sh')]),
              [f'static final boolean EXPERIMENTAL = {str(args.enable_recovery).lower()};', f'static final int VERSION_CODE = {args.version_code};'])
    supported(build, PACKAGE + '.shizuku', dict(common, LOADER_ENTRY=config['manager_loader_entry']),
              dict(boot, ASSETS=['ghostlock', 'profile.bin', 'handoff.sh'], ASSET_SHA=[sha(shell / n) for n in ['ghostlock', 'profile.bin', 'handoff.sh']]),
              [f'static final int VERSION_CODE = {args.version_code};'])
    java = APP / 'java'
    main = java / PACKAGE.replace('.', '/')
    bridge = main / 'shizuku'
    run([tools / 'aidl', '-I' + str(java), '-o' + str(build / 'generated'), bridge / 'IRestoreService.aidl'])
    suites = [('DirectPolicy', main, PACKAGE), ('RecoveryPolicy', bridge, PACKAGE + '.shizuku'),
              *[(name, main, PACKAGE) for name in ['SessionPolicy', 'PreparationPolicy', 'HistoryFiles', 'MarkerFiles']]]
    checks = []
    for name, folder, package in suites:
        run(['javac', '-d', build / 'host-tests', folder / (name + '.java'), ROOT / 'tests' / (name + 'Test.java')])
        checks.append(run(['java', '-ea', '-cp', build / 'host-tests', package + '.' + name + 'Test']).strip())
    run(['javac', '-source', '8', '-target', '8', '-Xlint:unchecked,-options',
         '-bootclasspath', str(android) + os.pathsep + str(tools / 'core-lambda-stubs.jar'),
         '-classpath', os.pathsep.join(str(j) for j in jars), '-d', build / 'classes',
         *sorted(java.rglob('*.java')), *sorted((build / 'generated').rglob('*.java'))])
    run([tools / 'aapt2', 'compile', '--dir', APP / 'res', '-o', build / 'resources.zip'])
    run([tools / 'aapt2', 'link', '-I', android, '--manifest', APP / 'AndroidManifest.xml', '--min-sdk-version', '34',
         '--target-sdk-version', '35', '--version-code', str(args.version_code), '--version-name', VERSION,
         build / 'resources.zip', '-A', assets, '-o', build / 'unsigned.apk'])
    run([tools / 'd8', '--lib', android, '--min-api', '34', '--output', build / 'dex',
         *sorted((build / 'classes').rglob('*.class')), *jars])
    with zipfile.ZipFile(build / 'unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as archive:
        for item in (build / 'dex').glob('*.dex'):
            archive.write(item, item.name)
        archive.write(native, 'lib/arm64-v8a/libghostlock.so')
    run([tools / 'zipalign', '-f', '4', build / 'unsigned.apk', build / 'aligned.apk'])
    key = args.keystore
    if not key:
        key = build / 'debug.keystore'
        if not key.exists():
            run(['keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android',
                 '-alias', args.key_alias, '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
                 '-dname', 'CN=Android Debug,O=Android,C=US'])
    password = 'file:' + str(args.password_file.resolve()) if args.password_file else 'pass:android'
    apk = build / ('GhostLock-HONOR-' + VERSION + ('' if args.enable_recovery else '-check') + '.apk')
    signer = tools / 'lib/apksigner.jar'
    run(['java', '-jar', signer, 'sign', '--ks', key, '--ks-key-alias', args.key_alias,
         '--ks-pass', password, '--out', apk, build / 'aligned.apk'])
    (build / 'signature.txt').write_text(run(['java', '-jar', signer, 'verify', '--verbose', '--print-certs', apk]))
    ns = '{http://schemas.android.com/apk/res/android}'
    manifest = ET.parse(APP / 'AndroidManifest.xml').getroot()
    expected = ['android.permission.INTERNET', 'android.permission.FOREGROUND_SERVICE',
                'android.permission.FOREGROUND_SERVICE_SPECIAL_USE', 'moe.shizuku.manager.permission.API_V23']
    if manifest.get('package') != PACKAGE or [p.get(ns + 'name') for p in manifest.findall('uses-permission')] != expected:
        raise RuntimeError('Unexpected package or permissions')
    if manifest.findall('.//receiver') or any(s.get(ns + 'exported') != 'false' for s in manifest.findall('.//service')):
        raise RuntimeError('Unexpected exported component')
    providers = manifest.findall('.//provider')
    if len(providers) != 1 or providers[0].get(ns + 'permission') != 'android.permission.INTERACT_ACROSS_USERS_FULL':
        raise RuntimeError('Shizuku provider must remain protected')
    with zipfile.ZipFile(apk) as archive:
        for name, path in {'lib/arm64-v8a/libghostlock.so': native, 'assets/profile.bin': profile,
                           'assets/handoff.sh': assets / 'handoff.sh', **{'assets/honor400/' + n: shell / n for n in ['ghostlock', 'profile.bin', 'handoff.sh']}}.items():
            if hashlib.sha256(archive.read(name)).hexdigest() != sha(path):
                raise RuntimeError('Packaged asset mismatch: ' + name)
        if any('libksud.so' in name for name in archive.namelist()):
            raise RuntimeError('KernelSU loader must not be redistributed')
    result = {'package': PACKAGE, 'version': VERSION, 'version_code': args.version_code,
              'apk': apk.name, 'apk_sha256': sha(apk), 'recovery_enabled': args.enable_recovery,
              'native_sha256': sha(native), 'host_checks': checks, 'device_tested': False}
    (build / 'build-result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
