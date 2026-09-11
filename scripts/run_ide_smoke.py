#!/usr/bin/env python3
"""Run the built ZIP in a separate IDE process/configuration, with the test-only harness.
Usage: run_ide_smoke.py IDE_HOME FIXTURE REPORT [--plugins EXTRA_PLUGIN_DIRECTORY]
SDK paths are supplied as MCP_GO_SDK, MCP_DART_SDK, MCP_RUST_BIN and MCP_PYTHON.
"""
import argparse,json,os,pathlib,shutil,subprocess,tempfile,zipfile,sys
p=argparse.ArgumentParser(); p.add_argument('ide'); p.add_argument('fixture'); p.add_argument('report'); p.add_argument('--plugins'); p.add_argument('--license-file'); p.add_argument('--ui', action='store_true'); p.add_argument('--rust-fallback', action='store_true'); a=p.parse_args()
repo=pathlib.Path(__file__).resolve().parent.parent
ide=pathlib.Path(a.ide).resolve()
info_path=next(x for x in [ide/'Resources/product-info.json',ide/'product-info.json'] if x.exists())
info=json.loads(info_path.read_text()); launch=info['launch'][0]
sandbox=pathlib.Path(tempfile.mkdtemp(prefix='intellij-mcp-smoke-'))
if a.license_file:
 license_file=pathlib.Path(a.license_file).resolve()
 (sandbox/'config').mkdir()
 (sandbox/'config'/license_file.name).symlink_to(license_file)
plugins=sandbox/'plugins'; plugins.mkdir()
with zipfile.ZipFile(repo/'core/build/distributions/intellij-mcp-1.11.0.zip') as z: z.extractall(plugins)
harness=plugins/'smoke/lib'; harness.mkdir(parents=True); shutil.copy(repo/'core/build/smoke/mcp-smoke-harness.jar',harness)
if a.plugins:
 for source in pathlib.Path(a.plugins).iterdir():
  if source.is_dir() and source.name not in ['core','intellij-mcp']:
   # Gradle can refresh the source sandbox during another build. A private snapshot
   # prevents mmap'd JARs changing under a running IDE (APFS clones cost little).
   if sys.platform=='darwin': subprocess.run(['cp','-cR',str(source.resolve()),str(plugins/source.name)],check=True)
   else: shutil.copytree(source,plugins/source.name)
fixture=(sandbox/'project').resolve(); shutil.copytree(pathlib.Path(a.fixture).resolve(),fixture,ignore=shutil.ignore_patterns('.idea','.build','target','bin','obj'))
plan=json.loads((fixture/'smoke.json').read_text())
lang=plan['language']
if a.rust_fallback:
 for check in plan['checks']:
  if check['tool']=='get_call_hierarchy':
   check['status']='partial'; check.setdefault('contains',[]).append('Rust PSI direct calls')
 (fixture/'smoke.json').write_text(json.dumps(plan))
if lang=='swift':
 subprocess.run(['swift','build','--package-path',str(fixture),'--enable-index-store'],check=True,stdout=subprocess.DEVNULL)
module_type='RUST_MODULE' if lang=='rust' else 'JAVA_MODULE'
(fixture/'.idea').mkdir(exist_ok=True)
(fixture/'.idea/modules.xml').write_text('<project version="4"><component name="ProjectModuleManager"><modules><module fileurl="file://$PROJECT_DIR$/fixture.iml" filepath="$PROJECT_DIR$/fixture.iml" /></modules></component></project>')
(fixture/'fixture.iml').write_text(f'<module type="{module_type}" version="4"><component name="NewModuleRootManager"><content url="file://$MODULE_DIR$"><sourceFolder url="file://$MODULE_DIR$" isTestSource="false" /></content><orderEntry type="inheritedJdk"/><orderEntry type="sourceFolder" forTests="false"/></component></module>')
base=info_path.parent
java=(base/launch['javaExecutablePath']).resolve()
# Cached cross-platform IDE archives may record a Linux launcher layout.
if not java.exists():
 java=next(x for x in [ide/'jbr/Contents/Home/bin/java',ide/'jbr/bin/java'] if x.exists())
args=[str(java),'-Xmx2500m']
for arg in launch.get('additionalJvmArguments',[]):
 args.append(arg.replace('$APP_PACKAGE/Contents',str(ide)).replace('$APP_PACKAGE',str(ide.parent) if ide.name=='Contents' else str(ide)).replace('$IDE_HOME',str(ide)))
args += [f'-Didea.home.path={ide}',f'-Didea.config.path={sandbox}/config',f'-Didea.system.path={sandbox}/system',f'-Didea.log.path={sandbox}/log',f'-Didea.plugins.path={plugins}','-Djava.awt.headless=true','-Didea.is.internal=true','-Didea.trust.all.projects=true','-Dide.show.tips.on.startup.default.value=false','-Didea.initially.ask.config=never','-Didea.fatal.error.notification=disabled']
if a.ui: args += ['-Djava.awt.headless=false','-Dmcp.smoke.ui=true']
args += ['-cp',os.pathsep.join(str(ide/'lib'/x) for x in launch['bootClassPathJarNames']),launch.get('mainClass','com.intellij.idea.Main'),'mcp-smoke',str(fixture),str(pathlib.Path(a.report).resolve())]
print(f'Sandbox: {sandbox}',flush=True)
report=pathlib.Path(a.report).resolve(); report.parent.mkdir(parents=True,exist_ok=True)
report.unlink(missing_ok=True)
try:
 with report.with_suffix('.log').open('w') as log: proc=subprocess.run(args,stdout=log,stderr=subprocess.STDOUT,timeout=300)
 if report.exists():
  report.write_text(report.read_text().replace(str(fixture.resolve()),'<fixture>').replace(str(fixture),'<fixture>').replace(str(repo),'<workspace>'))
  print(report.read_text())
 raise SystemExit(proc.returncode)
except subprocess.TimeoutExpired:
 print('IDE smoke timed out; see',report.with_suffix('.log')); raise SystemExit(124)
