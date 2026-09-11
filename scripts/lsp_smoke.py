#!/usr/bin/env python3
"""Read-only LSP protocol smoke against an installed server; writes report to stdout.
Usage: python3 scripts/lsp_smoke.py sourcekit-lsp docs/fixtures/swift
Build the SwiftPM fixture first with `swift build --enable-index-store`.
"""
import json
import pathlib
import queue
import subprocess
import sys
import threading
import time

language = sys.argv[3] if len(sys.argv) > 3 else "swift"
root = pathlib.Path(sys.argv[2]).resolve()
server_args = [sys.argv[1]] + (["--languageserver"] if pathlib.Path(sys.argv[1]).name == "OmniSharp" else [])
server = subprocess.Popen(server_args, cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
messages = queue.Queue()

def read():
    while True:
        headers = {}
        while True:
            line = server.stdout.readline()
            if not line:
                return
            if line == b"\r\n":
                break
            key, value = line.decode().split(":", 1)
            headers[key.lower()] = value.strip()
        messages.put(json.loads(server.stdout.read(int(headers["content-length"]))))

threading.Thread(target=read, daemon=True).start()
next_id = 0
notifications = []
registrations = []

def send(message):
    data = json.dumps(dict(jsonrpc="2.0", **message)).encode()
    server.stdin.write(f"Content-Length: {len(data)}\r\n\r\n".encode() + data)
    server.stdin.flush()

def request(method, params, timeout=30):
    global next_id
    next_id += 1
    ident = next_id
    send(dict(id=ident, method=method, params=params))
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        msg = messages.get(timeout=max(.01, deadline-time.monotonic()))
        if msg.get("id") == ident and "method" not in msg:
            return msg
        if "method" in msg:
            notifications.append(msg)
        if "id" in msg and "method" in msg:
            if msg["method"] == "client/registerCapability":
                registrations.extend(msg.get("params", {}).get("registrations", []))
            if msg["method"] == "workspace/configuration":
                result = [{} for _ in msg.get("params", {}).get("items", [])]
            else:
                result = None
            send(dict(id=msg["id"], result=result))
    raise TimeoutError(method)

report = {}
try:
    report["initialize"] = request("initialize", dict(processId=None, rootUri=root.as_uri(), capabilities={
        "textDocument": {"callHierarchy": {}, "implementation": {}, "diagnostic": {"dynamicRegistration": True}, "publishDiagnostics": {"versionSupport": True}}
    }))
    send(dict(method="initialized", params={}))
    files = sorted(root.glob("Sources/**/*.swift")) if language == "swift" else sorted(root.glob("*.cs"))
    for file in files:
        send(dict(method="textDocument/didOpen", params={"textDocument": dict(uri=file.as_uri(), languageId=language, version=1, text=file.read_text())}))
    file = root / ("Sources/IntelligenceFixture/Worker.swift" if language == "swift" else "Worker.cs")
    def position(file, needle):
        text = file.read_text(); offset = text.index(needle)
        return dict(line=text[:offset].count("\n"), character=offset-text.rfind("\n", 0, offset)-1)
    target = dict(textDocument=dict(uri=file.as_uri()), position=position(file, "callee()" if language == "swift" else "Callee() {"))
    deadline = time.monotonic() + 30
    while True:
        report["prepare"] = request("textDocument/prepareCallHierarchy", target)
        if report["prepare"].get("result") or time.monotonic() >= deadline:
            break
        time.sleep(.5)
    items = report["prepare"].get("result") or []
    if items:
        report["incoming"] = request("callHierarchy/incomingCalls", dict(item=items[0]))
        caller_file = root / ("Sources/IntelligenceFixture/Caller.swift" if language == "swift" else "Caller.cs")
        caller_items = request("textDocument/prepareCallHierarchy", dict(textDocument=dict(uri=caller_file.as_uri()), position=position(caller_file, "caller()" if language == "swift" else "Run()"))).get("result") or []
        if caller_items:
            report["outgoing"] = request("callHierarchy/outgoingCalls", dict(item=caller_items[0]))
    report["implementation"] = request("textDocument/implementation", dict(textDocument=dict(uri=file.as_uri()), position=position(file, "work()" if language == "swift" else "Work();")))
    pull = report["initialize"].get("result", {}).get("capabilities", {}).get("diagnosticProvider") or any(r["method"] == "textDocument/diagnostic" for r in registrations)
    if pull:
        report["diagnostics"] = request("textDocument/diagnostic", dict(textDocument=dict(uri=file.as_uri())))
        broken = file.read_text().replace("return 1", "return missingSymbol")
        send(dict(method="textDocument/didChange", params=dict(textDocument=dict(uri=file.as_uri(), version=2), contentChanges=[dict(text=broken)])))
        report["unsaved_error"] = request("textDocument/diagnostic", dict(textDocument=dict(uri=file.as_uri())))
        send(dict(method="textDocument/didChange", params=dict(textDocument=dict(uri=file.as_uri(), version=3), contentChanges=[dict(text=file.read_text())])))
        report["unsaved_fix"] = request("textDocument/diagnostic", dict(textDocument=dict(uri=file.as_uri())))
    assert report.get("incoming", {}).get("result"), "No incoming calls"
    assert report.get("outgoing", {}).get("result"), "No outgoing calls"
    assert report.get("implementation", {}).get("result"), "No implementations"
    if pull:
        assert report["unsaved_error"].get("result", {}).get("items"), "No error after unsaved edit"
        assert not report["unsaved_fix"].get("result", {}).get("items"), "Stale diagnostics after fix"
    report["registrations"] = registrations
    report["push_diagnostics"] = [m["params"] for m in notifications if m["method"] == "textDocument/publishDiagnostics"]
    for file in files:
        send(dict(method="textDocument/didClose", params={"textDocument": dict(uri=file.as_uri())}))
    request("shutdown", {}, timeout=5)
    send(dict(method="exit", params={}))
except Exception as exc:
    report["error"] = str(exc)
finally:
    if server.poll() is None:
        server.terminate()
        try:
            server.wait(timeout=5)
        except subprocess.TimeoutExpired:
            server.kill()
    print(json.dumps(report, indent=2))

if "error" in report:
    raise SystemExit(1)
