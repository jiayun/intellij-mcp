#!/usr/bin/env python3
"""External MCP HTTP client used by the isolated IDE harness; JSON in/out over stdio."""
import json
import sys
import urllib.request

endpoint = sys.argv[1]
request = json.load(sys.stdin)
exchanges = []


def rpc(method, params=None, ident=0, notification=False):
    payload = dict(jsonrpc="2.0", method=method)
    if params is not None:
        payload["params"] = params
    if not notification:
        payload["id"] = ident
    req = urllib.request.Request(endpoint, json.dumps(payload).encode(), headers={
        "Content-Type": "application/json", "Accept": "application/json, text/event-stream"})
    with urllib.request.urlopen(req, timeout=75) as response:
        body = response.read().decode()
        status = response.status
    result = json.loads(body) if body else None
    exchanges.append(dict(request=payload, httpStatus=status, response=result))
    if notification:
        assert status == 204 and not body, "Notification received a response body"
        return None
    assert status == 200 and result["jsonrpc"] == "2.0", result
    assert type(result["id"]) is type(ident) and result["id"] == ident, "JSON-RPC ID changed"
    return result


def value(response):
    assert "error" not in response, response
    result = response["result"]
    assert not result.get("isError", False), result
    content = result["content"]
    assert len(content) == 1 and content[0]["type"] == "text", content
    return json.loads(content[0]["text"])


try:
    init = rpc("initialize", dict(protocolVersion="2024-11-05", capabilities={},
        clientInfo=dict(name="intellij-mcp-external-smoke", version="1.0")))
    assert "error" not in init and init["result"]["protocolVersion"] == "2024-11-05", init
    assert "tools" in init["result"]["capabilities"], init
    if "expectedVersion" in request:
        assert init["result"]["serverInfo"]["version"] == request["expectedVersion"], init
    rpc("notifications/initialized", notification=True)
    if request.get("probe"):
        for path in ["/health", "/info"]:
            with urllib.request.urlopen(endpoint.removesuffix("/mcp")+path, timeout=10) as response:
                body = response.read().decode()
                assert response.status == 200
            if path == "/health":
                assert body == "OK", body
            else:
                assert json.loads(body)["version"] == request["expectedVersion"], body
        listing = rpc("tools/list", ident="tools-list")
        definitions = {tool["name"]: tool for tool in listing["result"]["tools"]}
        for name in ["get_diagnostics", "get_call_hierarchy", "find_implementations"]:
            assert name in definitions and definitions[name]["inputSchema"]["type"] == "object", name
        projects = value(rpc("tools/call", dict(name="list_projects", arguments={}), ident=9223372036854775808123))
        assert request["projectPath"] in json.dumps(projects), projects
        supported = value(rpc("tools/call", dict(name="get_supported_languages",
            arguments=dict(projectPath=request["projectPath"])), ident=-1))
        for adapter in supported:
            assert all(key in adapter for key in ["id", "name", "extensions", "capabilities"]), adapter
            if adapter["id"] in ["swift", "csharp"]:
                assert all(cap["status"] == "unknown" for cap in adapter["capabilities"].values()), adapter
        invalid = [
            ("get_diagnostics", dict(filePaths=[])),
            ("find_implementations", dict(filePath=request["filePath"], line=0, column=1)),
            ("get_call_hierarchy", dict(filePath=request["filePath"], line=1, column=1, direction="sideways")),
        ]
        for index, (name, args) in enumerate(invalid):
            args["projectPath"] = request["projectPath"]
            result = rpc("tools/call", dict(name=name, arguments=args), ident="invalid-"+str(index))
            assert result.get("error", {}).get("code") == -32602, result
        result = dict(tools=list(definitions), projects=projects, supportedLanguages=supported)
    elif request.get("postProbe"):
        args = dict(request["arguments"], maxNodes=1, depth=2)
        limited = value(rpc("tools/call", dict(name="get_call_hierarchy", arguments=args), ident="limited"))
        assert limited["status"] == "partial" and limited["truncated"] is True and len(limited["nodes"]) == 1, limited
        mixed = value(rpc("tools/call", dict(name="get_diagnostics", arguments=dict(
            projectPath=args["projectPath"], filePaths=[args["filePath"], args["filePath"]+".missing.java"])), ident="mixed"))
        assert len(mixed) == 2 and mixed[0]["status"] == "complete" and mixed[1]["status"] == "error", mixed
        result = dict(limited=limited, mixedDiagnostics=mixed)
    else:
        result = value(rpc("tools/call", dict(name=request["tool"], arguments=request["arguments"]), ident="query"))
    print(json.dumps(dict(value=result, exchanges=exchanges)))
except Exception as exc:
    print(json.dumps(dict(error=str(exc), exchanges=exchanges)))
    raise SystemExit(1)
