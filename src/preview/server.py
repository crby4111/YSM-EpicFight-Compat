"""Local motion-preview desk. Stdlib only; all animation math runs in the Java worker."""
from __future__ import annotations

import argparse
import atexit
import base64
import json
import os
from pathlib import Path
import queue
import secrets
import struct
import subprocess
import threading
import urllib.parse
import webbrowser
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
STATE = REPO / "build/motion-preview"
WEB = HERE / "web"


def prepare_runtime() -> dict:
    metadata = STATE / "runtime.json"
    if not metadata.exists():
        STATE.mkdir(parents=True, exist_ok=True)
        env = os.environ.copy()
        local_java = list((REPO.parent / ".build-cache/java21-runtime").glob("*/bin/java.exe"))
        if local_java:
            env["JAVA_HOME"] = str(local_java[0].parents[1])
            env["GRADLE_USER_HOME"] = str(REPO.parent / ".build-cache/gradle-user-home")
        print("首次准备预览组件，日志保存在 build/motion-preview/prepare.log。", flush=True)
        with (STATE / "prepare.log").open("w", encoding="utf-8") as log:
            result = subprocess.run([str(REPO / "gradlew.bat"), "prepareMotionPreview", "--no-daemon"],
                                    cwd=REPO, env=env, stdout=log, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError("预览组件准备失败，请查看 prepare.log；构建需要 Java 21，编译工具链为 Java 17。")
    result = json.loads(metadata.read_text(encoding="utf-8"))
    if not Path(result["java"]).is_file():
        raise RuntimeError("Java 路径失效，请重新运行 prepareMotionPreview。")
    return result


class Worker:
    def __init__(self, runtime: dict):
        STATE.mkdir(parents=True, exist_ok=True)
        args = STATE / "worker.args"
        # Java argument files handle long Windows classpaths without shell interpretation.
        quote = lambda value: '"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"'
        args.write_text("\n".join(["-Xmx2G", "-Dfile.encoding=UTF-8", "-cp",
                                   quote(runtime["classpath"]), runtime["main"]]), encoding="utf-8")
        options = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}
        self.process = subprocess.Popen([runtime["java"], "@" + str(args)], cwd=STATE,
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, text=True, encoding="utf-8", **options)
        self.lines = queue.Queue()
        self.lock = threading.Lock()
        self.log = (STATE / "worker.log").open("a", encoding="utf-8")
        threading.Thread(target=self._read, daemon=True).start()
        atexit.register(self.close)

    def _read(self):
        for line in self.process.stdout:
            if line.startswith("PREVIEW "):
                self.lines.put(line[8:])
            elif not self.log.closed:
                self.log.write(line)
                self.log.flush()
        self.lines.put(None)

    def call(self, request: dict):
        with self.lock:
            if self.process.poll() is not None:
                raise RuntimeError("预览计算进程已退出，请查看 worker.log 后重新启动。")
            self.process.stdin.write(json.dumps(request, ensure_ascii=False) + "\n")
            self.process.stdin.flush()
            try:
                line = self.lines.get(timeout=180)
            except queue.Empty:
                self.close()
                raise RuntimeError("模型计算超时，已停止本次计算进程，请重新启动预览器。") from None
            if line is None:
                raise RuntimeError("计算进程意外结束，请查看 build/motion-preview/worker.log。")
            response = json.loads(line)
            if not response["ok"]:
                raise ValueError(response["error"])
            return response["result"]

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
        self.log.close()


def make_demo() -> Path:
    """An original, redistributable test figure; no user or YSM assets in the source tree."""
    root = STATE / "demo/yes_steve_model"
    model = root / "custom/preview_demo"
    model.mkdir(parents=True, exist_ok=True)
    bones = []
    definitions = [
        ("Root", "", [0, 0, 0], None),
        ("Body", "Root", [0, 12, 0], ([-4, 12, -2], [8, 12, 4])),
        ("Head", "Body", [0, 24, 0], ([-4, 24, -4], [8, 8, 8])),
        ("LeftEar", "Head", [3, 31, 0], ([2, 31, -2], [2, 4, 2])),
        ("RightEar", "Head", [-3, 31, 0], ([-4, 31, -2], [2, 4, 2])),
        ("RightArm", "Body", [-5, 22, 0], ([-8, 12, -2], [4, 12, 4])),
        ("LeftArm", "Body", [5, 22, 0], ([4, 12, -2], [4, 12, 4])),
        ("RightLeg", "Root", [-2, 12, 0], ([-4, 0, -2], [4, 12, 4])),
        ("LeftLeg", "Root", [2, 12, 0], ([0, 0, -2], [4, 12, 4])),
        ("Cape", "Body", [0, 23, 2], ([-4, 12, 2], [8, 11, 1])),
        ("Tail", "Body", [0, 13, 2], ([-1, 10, 2], [2, 3, 8])),
    ]
    for name, parent, pivot, cube in definitions:
        bone = {"name": name, "pivot": pivot}
        if parent:
            bone["parent"] = parent
        if cube:
            bone["cubes"] = [{"origin": cube[0], "size": cube[1], "uv": [0, 0]}]
        bones.append(bone)
    geometry = {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.preview_demo", "texture_width": 64, "texture_height": 64},
        "bones": bones}]}
    # Production legacy-folder format is intentionally used here.
    (model / "main.json").write_text(json.dumps(geometry), encoding="utf-8")
    (model / "arm.json").write_text(json.dumps(geometry), encoding="utf-8")
    animations = {"format_version": "1.8.0", "animations": {
        "idle": {"loop": True, "animation_length": 2, "bones": {
            "RightArm": {"rotation": [0, 0, "math.sin(q.anim_time * 180) * 8"]},
            "LeftArm": {"rotation": [0, 0, "-math.sin(q.anim_time * 180) * 8"]}}},
        "swim": {"loop": True, "animation_length": 1, "bones": {
            "Body": {"rotation": [-75, 0, 0]}, "RightArm": {"rotation": [-140, 0, -15]},
            "LeftArm": {"rotation": [-140, 0, 15]}}},
        "cape_swing": {"loop": True, "animation_length": 1, "bones": {
            "Cape": {"rotation": {"0": [0, 0, 0], "0.5": [-50, 0, 0], "1": [0, 0, 0]}}}},
        "parallel_tail": {"loop": True, "animation_length": 2, "bones": {
            "Tail": {"rotation": [0, "math.sin(q.anim_time * 180) * 35", 0]}}}
    }}
    (model / "main.animation.json").write_text(json.dumps(animations), encoding="utf-8")
    controllers={"animation_controllers":{
        "player.preview":{"states":{
            "default":{"animations":["idle"],"transitions":[{"swim":"ctrl.swim"}]},
            "swim":{"animations":["swim"],"blend_transition":.15,"transitions":[{"default":"!ctrl.swim"}]}}},
        "player.preview_cape":{"states":{"default":{"animations":["cape_swing"]}}}
    }}
    (model / "controllers.json").write_text(json.dumps(controllers), encoding="utf-8")
    manifest={"spec":2,"properties":{"default_texture":"main"},"files":{"player":{
        "model":{"main":"main.json","arm":"arm.json"},"animation":{"main":"main.animation.json"},
        "animation_controllers":["controllers.json"],"texture":["main.png"]}}}
    (model / "ysm.json").write_text(json.dumps(manifest), encoding="utf-8")
    width = height = 64
    raw = b"".join(b"\0" + b"".join(bytes((82 + (x // 8 % 2) * 32, 154 + (y // 8 % 2) * 35, 164, 255))
                                      for x in range(width)) for y in range(height))
    def chunk(kind, payload):
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
    (model / "main.png").write_bytes(png)
    return root


class App:
    def __init__(self, worker: Worker, root: Path):
        self.worker, self.root = worker, str(root.resolve())
        self.token = secrets.token_urlsafe(24)

    def browse(self):
        import tkinter as tk
        from tkinter import filedialog
        desktop = tk.Tk()
        desktop.withdraw()
        desktop.attributes("-topmost", True)
        try:
            selected = filedialog.askdirectory(title="选择 yes_steve_model 目录（包含 builtin / custom / auth）",
                                               initialdir=self.root)
            return {"path": selected or self.root, "cancelled": not bool(selected)}
        finally:
            desktop.destroy()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def respond(self, code, body, content_type="application/json; charset=utf-8"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urllib.parse.urlsplit(self.path).path
        files = {"/": ("index.html", "text/html; charset=utf-8"),
                 "/app.js": ("app.js", "text/javascript; charset=utf-8"),
                 "/style.css": ("style.css", "text/css; charset=utf-8")}
        if path not in files:
            return self.respond(404, {"error": "页面不存在"})
        filename, mime = files[path]
        self.respond(200, (WEB / filename).read_bytes(), mime)

    def do_POST(self):
        app = self.server.app
        if self.headers.get("X-Preview-Token") != app.token:
            return self.respond(403, {"error": "预览窗口已失效，请从启动器重新打开。"})
        origin = self.headers.get("Origin")
        if origin and origin != f"http://127.0.0.1:{self.server.server_port}":
            return self.respond(403, {"error": "请求来源不匹配"})
        try:
            command = self.path.removeprefix("/api/")
            size = int(self.headers.get("Content-Length", 0))
            limit = 17 * 1024 * 1024 if command == "recorded" else 256 * 1024
            if not 0 <= size <= limit:
                raise ValueError("配置或请求过大")
            body = json.loads(self.rfile.read(size) or b"{}")
            command = self.path.removeprefix("/api/")
            if command == "initial":
                return self.respond(200, {"root": app.root})
            if command == "browse":
                return self.respond(200, app.browse())
            if command not in {"catalogue", "inspect", "sample", "reset", "recorded"}:
                raise ValueError("未知操作")
            selected_root = app.root
            if command == "catalogue":
                root = Path(body["root"]).expanduser().resolve(strict=True)
                if not root.is_dir():
                    raise ValueError("请选择模型目录")
                selected_root = str(root)
            body["root"] = selected_root
            body["command"] = command
            result = app.worker.call(body)
            if command == "catalogue":
                app.root = selected_root
            self.respond(200, result)
        except (ValueError, OSError, RuntimeError, KeyError, TypeError) as error:
            self.respond(400, {"error": str(error)})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-root", type=Path)
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--no-browser", action="store_true")
    parser.add_argument("--connection-file", type=Path)
    args = parser.parse_args()
    runtime = prepare_runtime()
    app = App(Worker(runtime), args.model_root or make_demo())
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    server.app = app
    url = f"http://127.0.0.1:{server.server_port}/?session={app.token}"
    if args.connection_file:
        args.connection_file.write_text(json.dumps({"url": url, "pid": os.getpid()}), encoding="utf-8")
    print("外部动作预览器已启动：" + url, flush=True)
    print("关闭此窗口可停止预览器。不会启动游戏或写入模型文件。", flush=True)
    if not args.no_browser:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        app.worker.close()


if __name__ == "__main__":
    main()
