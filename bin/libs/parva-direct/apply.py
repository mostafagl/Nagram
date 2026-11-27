"""Apply a minimal, pinned per-test socket hook without altering the Go module cache."""
import json, pathlib, shutil, subprocess, sys
source = pathlib.Path(sys.argv[1]).resolve()
module = json.loads(subprocess.check_output(['go', 'mod', 'download', '-json', 'github.com/xtls/xray-core'], text=True))
core = pathlib.Path('parva-xray-core').resolve()
shutil.copytree(module['Dir'], core)
for p in [core, *core.rglob('*')]:
    p.chmod(p.stat().st_mode | 0o200)
subprocess.check_call(['go', 'mod', 'edit', '-replace=github.com/xtls/xray-core=' + str(core)])

def replace(path, old, new):
    text = path.read_text()
    if text.count(old) != 1: raise RuntimeError('Pinned Xray source changed: ' + str(path))
    path.write_text(text.replace(old, new))
replace(core/'transport/internet/system_dialer.go',
        '\terrors.LogDebug(ctx, "dialing to "+dest.String())',
        '\tvar directErr error\n\tdest, directErr = prepareParvaDirectDestination(sockopt, dest)\n\tif directErr != nil { return nil, directErr }\n\terrors.LogDebug(ctx, "dialing to "+dest.String())')
replace(core/'transport/internet/sockopt_linux.go',
        '\tif config.Interface != "" {\n\t\tif err := syscall.BindToDevice',
        '\tif strings.HasPrefix(config.Interface, directBindingPrefix) {\n\t\tif err := bindParvaDirectSocket(config.Interface, fd); err != nil { return NewBindError(err) }\n\t} else if config.Interface != "" {\n\t\tif err := syscall.BindToDevice')
shutil.copyfile(source/'parva_direct_internet.go', core/'transport/internet/parva_direct.go')
shutil.copyfile(source/'parva_direct.go', 'parva_direct.go')
shutil.copyfile(source/'parva_direct_internet_test.go', core/'transport/internet/parva_direct_test.go')
subprocess.check_call(['gofmt', '-w', 'parva_direct.go', str(core/'transport/internet/parva_direct.go'), str(core/'transport/internet/system_dialer.go'), str(core/'transport/internet/sockopt_linux.go')])

subprocess.check_call(['go', 'test', './transport/internet', '-run', '^TestParvaDirect', '-count=1'], cwd=core)
